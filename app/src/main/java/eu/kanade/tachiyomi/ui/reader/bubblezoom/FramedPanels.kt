package eu.kanade.tachiyomi.ui.reader.bubblezoom

import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Panels drawn as frames, on a page that has no gutter to divide it. Amendment E15.
 *
 * [PanelFinder] reads a page's structure off its gutters — bands of the margin's tone crossing
 * the page. A splash page with insets laid over it has none: the insets are rectangles of ink
 * drawn on the art, and a band that crosses the whole page crosses the art as well. The finder
 * rightly says "not divided", the balloons are ordered by themselves, and a row of three insets
 * is read across all three at once — the top balloon of each, then the next of each. That is
 * what a reader saw on a DC × AEW page: "THAT'S NO EXCUSE." in the middle inset, then "IF EACH
 * SHARD ALTERS REALITY..." in the right one, then back to the middle.
 *
 * So on such a page the frames are looked for directly: rectangles whose four sides are dark
 * line. What makes this safe where it would not be on a whole page is what a frame must also
 * be, measured on 137 pages:
 *
 *  - **Its sides are measured between its corners.** A panel's border runs on into the art past
 *    the corner as often as not, and a line judged along its whole length is judged by the art.
 *    And each side is one unbroken line: nine tenths covered would let it step across a gutter
 *    to the next inset's line.
 *  - **Text is neither line nor gap.** A balloon laid across a frame hides the line under it;
 *    its box counts as covered, but some of a side has to be ink of its own.
 *  - **A side is a line, not the edge of a dark area.** It has light on at least one hand.
 *  - **Outside, a panel has a gutter.** Architecture is full of rectangles — windows, screens,
 *    doors — and what they have around them is more art. A frame has flat tone just outside at
 *    least three of its sides, or is a row mate of one that does (below).
 *  - **Panels nest or stand apart; they never cross.** Two rectangles sharing two sides are one
 *    panel found twice, and a rectangle crossing a better one is a line of the art. Of two that
 *    are one panel, the one standing in a row (or column) with others wins.
 *  - **Row mates.** An inset at a splash's corner has the splash on two sides. One that shares
 *    its top and bottom lines — or its left and right — with a frame already found needs only
 *    two flat sides.
 *  - **A caption's own border is not a panel.** A frame most of which one text box covers is
 *    that box.
 *  - **A panel has something drawn in it.** A frame whose inside is mostly ink is a black band
 *    or a dark mass of the art cut into rectangles.
 *
 * And it answers only when at least two frames hold text: one frame changes nothing about the
 * order of what is in it, and a page with one is more likely to have found a window.
 *
 * **Cost.** A page of dark, busy art holds hundreds of candidate lines on each axis, and the
 * first build of this spent more than ten minutes on one such page, holding up every page after
 * it. Sides are now tested only between lines that meet, each test is a few subtractions, and a
 * work budget gives up on a page rather than stall: on the 137 pages measured the slowest search
 * is a fifth of a second on a desktop, the reported page a few milliseconds.
 *
 * Pure Kotlin over the same grey plane [PanelFinder] reads, so every rule above is a JVM test.
 */
object FramedPanels {

    /**
     * The framed panels of a page given as [luma], one byte of brightness per pixel, row by row,
     * with [text] — every balloon and caption the model found, as fractions of the page.
     *
     * Empty unless at least two frames were found holding the centre of a text box.
     */
    fun find(luma: IntArray, width: Int, height: Int, text: List<BubbleBox>): List<BubbleBox> {
        // Two frames holding text need two pieces of text; one balloon has no order to fix.
        if (width <= 0 || height <= 0 || luma.size < width * height || text.size < 2) return emptyList()
        val page = Page(luma, width, height, text)
        val frames = page.frames()
        val holding = frames.filter { frame ->
            text.any { frame.containsPoint(it.centerX * width, it.centerY * height) }
        }
        if (holding.size < 2) return emptyList()
        return frames.mapNotNull { frame ->
            BubbleBox.clampedOrNull(
                x = frame.x0.toFloat() / width,
                y = frame.y0.toFloat() / height,
                width = (frame.x1 - frame.x0).toFloat() / width,
                height = (frame.y1 - frame.y0).toFloat() / height,
            )
        }
    }

    /**
     * A candidate, in the plane's own pixels: [x0] to [y1] is the inside of its lines — what is
     * in the panel — and [outerX0] to [outerY1] the outside, where the gutter starts.
     */
    private class Frame(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val outerX0: Int,
        val outerY0: Int,
        val outerX1: Int,
        val outerY1: Int,
    ) {
        val area: Int get() = (x1 - x0) * (y1 - y0)

        fun containsPoint(x: Float, y: Float): Boolean = x >= x0 && x <= x1 && y >= y0 && y <= y1
    }

    /** A drawn line: the rows (or columns) [first] to [last] it came out as. */
    private class Line(val first: Int, val last: Int) {
        /** Where along the axis the runs it was found in start and end. */
        val spans = ArrayList<IntArray>()

        /**
         * Whether one of its runs could hold a side from [from] to [to]. A side is one hole-free
         * stretch of a single run, and every row of a line has its run within [tolerance] of the
         * first one's, so a line none of whose runs reach that far cannot hold the side: this is
         * the cheap test that keeps a dark page from testing every pair of lines pixel by pixel.
         */
        fun reaches(from: Int, to: Int, tolerance: Int): Boolean =
            spans.any { it[0] <= from + tolerance && it[1] >= to - tolerance }
    }

    private class Scored(val frame: Frame, val score: Float)

    /** How far past a line's own rows the light beside it is read, in pixels of the scan. */
    private const val BESIDE = 2

    private class Page(val luma: IntArray, val width: Int, val height: Int, text: List<BubbleBox>) {
        private val longSide = maxOf(width, height)
        private val minLine = (BubbleDetectionConfig.FRAME_MIN_LINE * longSide).toInt()
        private val minPanel = (BubbleDetectionConfig.PANEL_MIN_SIZE * longSide).toInt()
        private val cornerTolerance = maxOf(2, (BubbleDetectionConfig.FRAME_CORNER_TOLERANCE * longSide).toInt())
        private val maxThickness = maxOf(2, (BubbleDetectionConfig.FRAME_MAX_THICKNESS * longSide).toInt())
        private val maxHole = maxOf(1, (BubbleDetectionConfig.FRAME_MAX_HOLE * longSide).toInt())

        /** Text boxes, in the plane's pixels. */
        private val boxes = text.map {
            floatArrayOf(it.x * width, it.y * height, it.right * width, it.bottom * height)
        }

        /** Pixels under a text box: neither line nor gap. */
        private val wild = BooleanArray(width * height).also { wild ->
            for (box in text) {
                val x0 = (box.x * width).toInt().coerceIn(0, width)
                val y0 = (box.y * height).toInt().coerceIn(0, height)
                val x1 = ceil(box.right * width).toInt().coerceIn(0, width)
                val y1 = ceil(box.bottom * height).toInt().coerceIn(0, height)
                for (y in y0 until y1) {
                    for (x in x0 until x1) wild[y * width + x] = true
                }
            }
        }

        /** Dark pixels outside every text box: the ink a line is drawn in. */
        private val ink = BooleanArray(width * height) { i ->
            !wild[i] && luma[i] <= BubbleDetectionConfig.FRAME_LINE_LUMA
        }

        /** Prefix sums along each row, [okRow] of ink-or-text and [inkRow] of ink. */
        private val okRow = IntArray(height * (width + 1))
        private val inkRow = IntArray(height * (width + 1))

        /** The same down each column. */
        private val okColumn = IntArray(width * (height + 1))
        private val inkColumn = IntArray(width * (height + 1))

        /**
         * Prefix counts of where a hole too long for a side starts - [maxHole] + 1 pixels of
         * neither ink nor text in a row - along each row and down each column, so whether a side
         * is one unbroken line is a subtraction rather than a walk along it.
         */
        private val holeRow = IntArray(height * (width + 1))
        private val holeColumn = IntArray(width * (height + 1))

        init {
            for (y in 0 until height) {
                var ok = 0
                var dark = 0
                for (x in 0 until width) {
                    val i = y * width + x
                    if (ink[i] || wild[i]) ok++
                    if (ink[i]) dark++
                    okRow[y * (width + 1) + x + 1] = ok
                    inkRow[y * (width + 1) + x + 1] = dark
                }
            }
            for (x in 0 until width) {
                var ok = 0
                var dark = 0
                for (y in 0 until height) {
                    val i = y * width + x
                    if (ink[i] || wild[i]) ok++
                    if (ink[i]) dark++
                    okColumn[x * (height + 1) + y + 1] = ok
                    inkColumn[x * (height + 1) + y + 1] = dark
                }
            }
            val longHole = maxHole + 1
            val rowStarts = BooleanArray(width)
            for (y in 0 until height) {
                // Backwards, so each pixel knows how long the gap starting at it runs.
                var gap = 0
                for (x in width - 1 downTo 0) {
                    gap = if (covered(horizontal = true, y, x)) 0 else gap + 1
                    rowStarts[x] = gap >= longHole
                }
                var count = 0
                for (x in 0 until width) {
                    if (rowStarts[x]) count++
                    holeRow[y * (width + 1) + x + 1] = count
                }
            }
            val columnStarts = BooleanArray(height)
            for (x in 0 until width) {
                var gap = 0
                for (y in height - 1 downTo 0) {
                    gap = if (covered(horizontal = false, x, y)) 0 else gap + 1
                    columnStarts[y] = gap >= longHole
                }
                var count = 0
                for (y in 0 until height) {
                    if (columnStarts[y]) count++
                    holeColumn[x * (height + 1) + y + 1] = count
                }
            }
        }

        fun frames(): List<Frame> {
            val rows = lines(horizontal = true)
            val columns = lines(horizontal = false)
            val found = ArrayList<Frame>()
            // A frame's corners are where its row lines and column lines meet, so which column
            // lines cross each row line is worked out once, and a pair of rows is only ever
            // tested against the columns that cross both. A page of dark, busy art holds hundreds
            // of candidate lines on each axis, and testing every column against every pair of
            // rows cost it seconds; the budget is the backstop, and a page that exhausts it is
            // simply not searched, which is the order it had before frames.
            val meets = Array(rows.size) { i ->
                columns.indices.filter { j -> crosses(rows[i], columns[j]) }.toIntArray()
            }
            // The columns that are sides between the current pair of rows, by index. Reused for
            // every pair: a dark page has tens of thousands of pairs, and a list per pair was
            // most of what the search allocated.
            val sides = IntArray(columns.size)
            var work = 0L
            for ((index, top) in rows.withIndex()) {
                for (next in index + 1 until rows.size) {
                    val bottom = rows[next]
                    if (bottom.first - top.last < minPanel) continue
                    val y0 = top.last
                    val y1 = bottom.first
                    // The columns crossing both rows, in order, that reach from one to the other
                    // and are drawn all the way.
                    val a = meets[index]
                    val b = meets[next]
                    var count = 0
                    var i = 0
                    var j = 0
                    while (i < a.size && j < b.size) {
                        when {
                            a[i] < b[j] -> i++
                            a[i] > b[j] -> j++
                            else -> {
                                val column = columns[a[i]]
                                if (column.reaches(y0, y1, cornerTolerance) &&
                                    ++work <= BubbleDetectionConfig.FRAME_WORK_BUDGET &&
                                    verticalSide(column, y0, y1)
                                ) {
                                    sides[count++] = a[i]
                                }
                                i++
                                j++
                            }
                        }
                    }
                    if (work > BubbleDetectionConfig.FRAME_WORK_BUDGET) return emptyList()
                    for (at in 0 until count) {
                        val left = columns[sides[at]]
                        for (other in at + 1 until count) {
                            val right = columns[sides[other]]
                            if (right.first - left.last < minPanel) continue
                            val x0 = left.last
                            val x1 = right.first
                            if (top.reaches(x0, x1, cornerTolerance) &&
                                bottom.reaches(x0, x1, cornerTolerance) &&
                                horizontalSide(top, x0, x1) &&
                                horizontalSide(bottom, x0, x1)
                            ) {
                                found += Frame(x0, y0, x1, y1, left.first, top.first, right.last + 1, bottom.last + 1)
                                if (found.size > BubbleDetectionConfig.FRAME_MAX_CANDIDATES) return emptyList()
                            }
                        }
                    }
                }
            }
            val panels = found.filterNot { isTextBox(it) || isFilled(it) }
            return select(panels)
        }

        /** Whether a row line and a column line meet: each one's runs reach the other. */
        private fun crosses(row: Line, column: Line): Boolean =
            column.reaches(row.first, row.last, cornerTolerance) &&
                row.reaches(column.first, column.last, cornerTolerance)

        /**
         * The lines along one axis: runs of adjacent rows (or columns) holding a long run of
         * ink-or-text whose ends agree, one drawn line however many rows thick it came out. A
         * dark area gives runs of every length and extent, does not cluster, and is dropped.
         */
        private fun lines(horizontal: Boolean): List<Line> {
            val count = if (horizontal) height else width
            val along = if (horizontal) width else height
            // first, last, start, end of each cluster; only those that reached the previous
            // position can still grow, so only those are searched.
            val clusters = ArrayList<IntArray>()
            val active = ArrayList<IntArray>()
            for (position in 0 until count) {
                active.removeAll { it[1] < position - 1 }
                var runStart = -1
                var runEnd = -1
                var at = 0
                while (at <= along) {
                    val covered = at < along && covered(horizontal, position, at)
                    if (covered) {
                        if (runStart < 0) {
                            runStart = at
                        } else if (at - runEnd > maxHole) {
                            keep(clusters, active, horizontal, position, runStart, runEnd)
                            runStart = at
                        }
                        runEnd = at + 1
                    }
                    at++
                }
                if (runStart >= 0) keep(clusters, active, horizontal, position, runStart, runEnd)
            }
            val out = LinkedHashMap<Long, Line>()
            for (cluster in clusters) {
                if (cluster[1] - cluster[0] + 1 > maxThickness) continue
                val line = out.getOrPut((cluster[0].toLong() shl 32) or cluster[1].toLong()) {
                    Line(cluster[0], cluster[1])
                }
                line.spans += intArrayOf(cluster[2], cluster[3])
            }
            return out.values.sortedWith(compareBy<Line> { it.first }.thenBy { it.last })
        }

        private fun covered(horizontal: Boolean, position: Int, at: Int): Boolean {
            val i = if (horizontal) position * width + at else at * width + position
            return ink[i] || wild[i]
        }

        /** Adds a run to the cluster it continues, or starts one, if it is long and inked enough. */
        private fun keep(
            clusters: MutableList<IntArray>,
            active: MutableList<IntArray>,
            horizontal: Boolean,
            position: Int,
            start: Int,
            end: Int,
        ) {
            if (end - start < minLine) return
            val dark = if (horizontal) {
                inkRow[position * (width + 1) + end] - inkRow[position * (width + 1) + start]
            } else {
                inkColumn[position * (height + 1) + end] - inkColumn[position * (height + 1) + start]
            }
            if (dark < BubbleDetectionConfig.FRAME_MIN_INK_SIDE * (end - start)) return
            for (cluster in active) {
                if (position - cluster[1] <= 1 &&
                    abs(cluster[2] - start) <= cornerTolerance &&
                    abs(cluster[3] - end) <= cornerTolerance
                ) {
                    cluster[1] = position
                    return
                }
            }
            val cluster = intArrayOf(position, position, start, end)
            clusters += cluster
            active += cluster
        }

        /** Whether some row of [line] is drawn from [x0] to [x1]. */
        private fun horizontalSide(line: Line, x0: Int, x1: Int): Boolean {
            val length = x1 - x0
            if (length <= 0) return false
            for (y in line.first..line.last) {
                val ok = okRow[y * (width + 1) + x1] - okRow[y * (width + 1) + x0]
                val dark = inkRow[y * (width + 1) + x1] - inkRow[y * (width + 1) + x0]
                if (ok >= BubbleDetectionConfig.FRAME_COVER * length &&
                    dark >= BubbleDetectionConfig.FRAME_MIN_INK_SIDE * length &&
                    unbroken(holeRow, y * (width + 1), x0, x1) &&
                    (lightRow(line.first - BESIDE, x0, x1) || lightRow(line.last + BESIDE, x0, x1))
                ) {
                    return true
                }
            }
            return false
        }

        /** Whether some column of [line] is drawn from [y0] to [y1]. */
        private fun verticalSide(line: Line, y0: Int, y1: Int): Boolean {
            val length = y1 - y0
            if (length <= 0) return false
            for (x in line.first..line.last) {
                val ok = okColumn[x * (height + 1) + y1] - okColumn[x * (height + 1) + y0]
                val dark = inkColumn[x * (height + 1) + y1] - inkColumn[x * (height + 1) + y0]
                if (ok >= BubbleDetectionConfig.FRAME_COVER * length &&
                    dark >= BubbleDetectionConfig.FRAME_MIN_INK_SIDE * length &&
                    unbroken(holeColumn, x * (height + 1), y0, y1) &&
                    (lightColumn(line.first - BESIDE, y0, y1) || lightColumn(line.last + BESIDE, y0, y1))
                ) {
                    return true
                }
            }
            return false
        }

        /**
         * Whether row [y], from [x0] to [x1], is mostly not ink: the light beside a line. A frame
         * is drawn in line, so it has light on at least one hand - the panel's art or the gutter.
         * The edge of a solid dark area has none, and without this every rectangle inside one
         * passed as a frame: measured on a dark page, six hundred thousand of them.
         */
        private fun lightRow(y: Int, x0: Int, x1: Int): Boolean {
            if (y < 0 || y >= height) return true
            val dark = inkRow[y * (width + 1) + x1] - inkRow[y * (width + 1) + x0]
            return dark < BubbleDetectionConfig.FRAME_BESIDE_INK * (x1 - x0)
        }

        /** The same for column [x], from [y0] to [y1]. */
        private fun lightColumn(x: Int, y0: Int, y1: Int): Boolean {
            if (x < 0 || x >= width) return true
            val dark = inkColumn[x * (height + 1) + y1] - inkColumn[x * (height + 1) + y0]
            return dark < BubbleDetectionConfig.FRAME_BESIDE_INK * (y1 - y0)
        }

        /**
         * Whether the stretch [from] to [to] of one row (or column), whose hole counts start at
         * [base] in [holes], has no hole longer than [maxHole] inside it. A side is one drawn
         * line: coverage alone would let it step across the gutter to the next inset's line and
         * come back as a frame round both.
         */
        private fun unbroken(holes: IntArray, base: Int, from: Int, to: Int): Boolean {
            val lastStart = to - maxHole - 1
            if (lastStart < from) return true
            return holes[base + lastStart + 1] - holes[base + from] == 0
        }

        /**
         * A frame whose inside, lettering aside, is mostly ink: a black band or a dark mass of
         * the art cut into rectangles, not a panel with something drawn in it. Measured: the
         * reported page's insets are 0.10 to 0.26 ink inside, the rectangles found in the black
         * band over another page's panels 0.73 to 0.92 - and read as panels they reordered that
         * page's first three balloons.
         */
        private fun isFilled(frame: Frame): Boolean {
            val margin = maxOf(2, (BubbleDetectionConfig.FRAME_FLAT_INSET * longSide).toInt())
            val x0 = frame.x0 + margin
            val x1 = frame.x1 - margin
            if (x1 <= x0 || frame.y1 - frame.y0 <= 2 * margin) return false
            var dark = 0
            var seen = 0
            for (y in frame.y0 + margin until frame.y1 - margin) {
                val row = y * (width + 1)
                val ink = inkRow[row + x1] - inkRow[row + x0]
                val text = (okRow[row + x1] - okRow[row + x0]) - ink
                dark += ink
                seen += (x1 - x0) - text
            }
            return seen > 0 && dark > BubbleDetectionConfig.FRAME_MAX_INSIDE_INK * seen
        }

        /** A frame most of which one text box covers: that box's own border. */
        private fun isTextBox(frame: Frame): Boolean {
            val area = frame.area.toFloat()
            return boxes.any { box ->
                val across = minOf(frame.x1.toFloat(), box[2]) - maxOf(frame.x0.toFloat(), box[0])
                val down = minOf(frame.y1.toFloat(), box[3]) - maxOf(frame.y0.toFloat(), box[1])
                across > 0f && down > 0f && across * down >= BubbleDetectionConfig.FRAME_TEXT_COVER * area
            }
        }

        /**
         * The panels among [candidates]: those with flat tone outside at least three sides, best
         * first, dropping any that is the same as or crosses one kept; then their row mates.
         *
         * "Best" is first the frame with the most mates among the candidates — others standing
         * apart from it on the same top and bottom lines, or the same left and right — and only
         * then the flattest. Two candidates that are one panel found twice differ by one side,
         * and flatness cannot tell the panel's own line from a line of its art: on the reported
         * page a rectangle inside the middle inset, its top on a line of the art, was as flat as
         * the inset and won. What the insets have and it has not is each other.
         */
        private fun select(candidates: List<Frame>): List<Frame> {
            val strong = ArrayList<Scored>()
            val weak = ArrayList<Scored>()
            for (frame in candidates) {
                val flat = flatness(frame).sorted()
                when {
                    flat[1] >= BubbleDetectionConfig.FRAME_MIN_FLAT -> strong += Scored(frame, flat[1])
                    flat[2] >= BubbleDetectionConfig.FRAME_MIN_FLAT -> weak += Scored(frame, flat[2])
                }
            }
            val all = strong.map { it.frame } + weak.map { it.frame }
            val mateCount = IdentityHashMap<Frame, Int>()
            for (frame in all) mateCount[frame] = all.count { it !== frame && isMate(frame, it) }
            val kept = ArrayList<Frame>()
            val ranked = strong.sortedWith(
                compareByDescending<Scored> { mateCount.getValue(it.frame) }.thenByDescending { it.score },
            )
            for (candidate in ranked) {
                val frame = candidate.frame
                if (kept.none { relation(frame, it) == Relation.SAME || relation(frame, it) == Relation.CROSSING }) {
                    kept += frame
                }
            }
            // Row mates: an inset laid over a splash has the splash on two of its sides, but it
            // shares its top and bottom lines (or its left and right) with the insets beside it.
            val mates = weak.sortedWith(
                compareByDescending<Scored> { mateCount.getValue(it.frame) }.thenByDescending { it.score },
            )
            var grew = true
            while (grew) {
                grew = false
                for (candidate in mates) {
                    val frame = candidate.frame
                    if (kept.any { it === frame }) continue
                    if (kept.any { relation(frame, it) != Relation.APART }) continue
                    if (kept.none { isMate(frame, it) }) continue
                    kept += frame
                    grew = true
                }
            }
            return kept
        }

        /** Two frames standing apart on the same top and bottom lines, or the same left and right. */
        private fun isMate(a: Frame, b: Frame): Boolean {
            val row = near(a.y0, b.y0) && near(a.y1, b.y1)
            val column = near(a.x0, b.x0) && near(a.x1, b.x1)
            return (row || column) && relation(a, b) == Relation.APART
        }

        private fun near(a: Int, b: Int): Boolean = abs(a - b) <= cornerTolerance

        private enum class Relation { SAME, NESTED, APART, CROSSING }

        private fun relation(a: Frame, b: Frame): Relation {
            val overlap = minOf(a.x1, b.x1) > maxOf(a.x0, b.x0) && minOf(a.y1, b.y1) > maxOf(a.y0, b.y0)
            // Two rectangles sharing two sides are one panel found twice, one of them with a line
            // of the art for its other sides.
            var shared = 0
            if (near(a.x0, b.x0)) shared++
            if (near(a.y0, b.y0)) shared++
            if (near(a.x1, b.x1)) shared++
            if (near(a.y1, b.y1)) shared++
            if (overlap && shared >= 2) return Relation.SAME
            val t = cornerTolerance
            if (a.x0 >= b.x0 - t && a.y0 >= b.y0 - t && a.x1 <= b.x1 + t && a.y1 <= b.y1 + t) return Relation.NESTED
            if (b.x0 >= a.x0 - t && b.y0 >= a.y0 - t && b.x1 <= a.x1 + t && b.y1 <= a.y1 + t) return Relation.NESTED
            val slack = maxOf(1, (BubbleDetectionConfig.FRAME_APART_SLACK * longSide).toInt())
            if (a.x1 <= b.x0 + slack || b.x1 <= a.x0 + slack || a.y1 <= b.y0 + slack || b.y1 <= a.y0 + slack) {
                return Relation.APART
            }
            return Relation.CROSSING
        }

        /**
         * How flat the band just outside each side is — top, bottom, left, right — as the share
         * of its pixels, text aside, within tolerance of the band's median. A side on the page's
         * own edge is on the margin and counts as flat.
         *
         * Read from the line's OUTER edge: from the inner one, a line two pixels thick puts its
         * own second row in the band, and every side of every frame reads as art.
         */
        private fun flatness(frame: Frame): List<Float> {
            val offset = maxOf(1, (BubbleDetectionConfig.FRAME_FLAT_OFFSET * longSide).toInt())
            val band = maxOf(2, (BubbleDetectionConfig.FRAME_FLAT_BAND * longSide).toInt())
            val edge = (BubbleDetectionConfig.FRAME_PAGE_EDGE * longSide).toInt()
            val inset = maxOf(2, (BubbleDetectionConfig.FRAME_FLAT_INSET * longSide).toInt())
            val x0 = frame.outerX0
            val y0 = frame.outerY0
            val x1 = frame.outerX1
            val y1 = frame.outerY1
            return listOf(
                if (y0 <=
                    edge
                ) {
                    1f
                } else {
                    flatIn(x0 + inset, maxOf(0, y0 - offset - band), x1 - inset, maxOf(0, y0 - offset))
                },
                if (height - y1 <= edge) {
                    1f
                } else {
                    flatIn(x0 + inset, minOf(height, y1 + offset), x1 - inset, minOf(height, y1 + offset + band))
                },
                if (x0 <=
                    edge
                ) {
                    1f
                } else {
                    flatIn(maxOf(0, x0 - offset - band), y0 + inset, maxOf(0, x0 - offset), y1 - inset)
                },
                if (width - x1 <= edge) {
                    1f
                } else {
                    flatIn(minOf(width, x1 + offset), y0 + inset, minOf(width, x1 + offset + band), y1 - inset)
                },
            )
        }

        private fun flatIn(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            val histogram = IntArray(256)
            var count = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val i = y * width + x
                    if (wild[i]) continue
                    histogram[luma[i].coerceIn(0, 255)]++
                    count++
                }
            }
            if (count == 0) return 1f
            if (count < 4) return 0f
            var seen = 0
            var median = 0
            for (tone in 0..255) {
                seen += histogram[tone]
                if (seen * 2 >= count) {
                    median = tone
                    break
                }
            }
            var within = 0
            val tolerance = BubbleDetectionConfig.FRAME_FLAT_TOLERANCE
            for (tone in (median - tolerance).coerceAtLeast(0)..(median + tolerance).coerceAtMost(255)) {
                within += histogram[tone]
            }
            return within.toFloat() / count
        }
    }
}
