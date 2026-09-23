package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Two bubbles that are one speech, joined into one stop. Amendments E12 and E13.
 *
 * Comics letter a long line as two or three lobes sharing an outline — "SEE, THERE'S A TRUE
 * FRIEND." and "BROKEN HEARTS AND..." drawn as one balloon with a waist. The model returns a
 * box per lobe, so the walk stopped twice and the card cut the speech in half.
 *
 * **Crossing boxes are not the test.** A balloon is round and its box is not, so two neighbours
 * standing diagonally cross each other's boxes without touching at all: measured over 103 pages,
 * 246 pairs of balloons have crossing boxes and most of those are separate speeches.
 *
 * The test is the paper. Lobes of one balloon share their surface, so a fill started in one
 * spreads into the other; two balloons that merely stand close are divided by two outlines and
 * the art between them. Measured on those 246 pairs, by how much of the smaller box a fill from
 * the larger one covers:
 *
 * | reach | what they were |
 * |---|---|
 * | 0.00 – 0.13 | separate speeches, every one — "I ATE THE ZOO." beside "I KNOW YOU DID, BUDDY" |
 * | 0.14 – 0.36 | nothing at all: the gap between the two populations |
 * | 0.37 – 0.75 | lobes of one balloon, every one but the leaks below |
 *
 * So the threshold sits in an empty gap rather than on a judgement call, and it is the same
 * fill, tone and tolerance [BubbleMask] traces the card with — this is that question asked of
 * the page once, instead of after the reader has already been shown the wrong half.
 *
 * **The pixels come per pair, from the page's own bytes.** The first build of this read the
 * page plane the panel finder uses, reduced to 600 on its long side, and on the device it
 * joined two speeches the calibration said were apart by a wide margin — 0.60 against 0.13.
 * The calibration had measured crops of each pair taken from the full page, so the caller now
 * hands over such a crop, which is what the card's own decoder does and what the numbers above
 * were measured on. Its size matters more than it first seemed: at 192 on the long side an
 * outline thins to under a pixel and the fill's answer flips with a one-pixel shift of the
 * crop; at 384 and at 768 every pair reads the same.
 *
 * **Leaks are refused.** On a panel whose own paper is the balloon's tone — a yellow sky, a
 * magenta caption page — the fill walks out of the balloon, floods the panel, and from there
 * reaches any box at all. What tells a leak from a balloon is where the fill ends up: inside
 * its balloon, or over the margin around the pair too. By the share of that margin filled,
 * every genuine pair on those pages is at or under 0.32 and every leak at or over 0.54, so a
 * join is refused above [BubbleZoomConfig.JOIN_MAX_RING_FILL] — the guard the card's own trace
 * has, for the same reason.
 *
 * **A fill never runs through another balloon.** Every other balloon's box is closed to it, so
 * a pair is joined by its own paper and not by a third speech standing between them; the
 * middle lobe of a three-lobe balloon still joins its neighbours on its own, and union-find
 * takes it from there.
 *
 * **Boxes that do not cross are asked too, and asked more.** One speaker's balloons are also
 * drawn butted edge to edge, and the model's boxes then leave a hair between them; those pairs
 * went untested. Now a pair within [BubbleZoomConfig.JOIN_NEAR_GAP] on both axes is a candidate
 * as well — 143 more pairs on those pages — but the fill alone is not enough for them: two
 * balloons of two speakers touch at a point just as often as one speaker's do, and lettering
 * runs a tail from one balloon into another, and a fill crosses either. What a real double
 * has that a touch does not is a wide seam: measured across the band between the two boxes,
 * the paper of a waisted or butted balloon fills 0.53 to 0.98 of it, a touch or a tail 0.00 to
 * 0.15 — "THANKS, LOIS." against "OF COURSE." is 0.12. So a non-crossing pair also needs
 * [BubbleZoomConfig.JOIN_MIN_SEAM] of that band filled, and the pairs that touch at a point
 * stay two stops, which is what they were.
 *
 * Pure Kotlin: the crop arrives through a function, so a test can hand over a drawing.
 */
object JoinedBalloons {

    /**
     * A crop of the page around one pair: one byte of brightness per pixel, row by row, and
     * [region], the fraction of the page it covers, so a box can be placed in it.
     */
    class Crop(val luma: IntArray, val width: Int, val height: Int, val region: BubbleBox)

    /**
     * What the fill found for one pair. [reach] is how much of the smaller box it covered,
     * [ring] how much of the margin around the pair, [seam] how much of the band between the
     * two boxes — one, for boxes that cross and have no band between them.
     */
    class Spread(val reach: Float, val ring: Float, val seam: Float)

    /**
     * [balloons] with the lobes of each balloon merged into one region, in the order they came.
     *
     * [crop] is asked for the pixels of the pair's rectangle — the smallest box holding both —
     * and answers with that rectangle plus the margin the ring guard reads,
     * [BubbleZoomConfig.JOIN_CROP_PADDING] of its smallest side on every edge. It may answer
     * null, in which case the pair is left as two. It is called once per candidate pair, which
     * on a real page is none to a handful.
     */
    fun merge(
        balloons: List<BubbleDetection>,
        crop: (BubbleBox) -> Crop?,
        /**
         * Told about every candidate pair: the larger box, the smaller, what the fill found,
         * and whether that joined them. The instrument, for the caller that has a log; this
         * file has none and must not grow one.
         */
        report: ((BubbleBox, BubbleBox, Spread, Boolean) -> Unit)? = null,
    ): List<BubbleDetection> {
        if (balloons.size < 2) return balloons

        // Union-find: three lobes in a row are one balloon, and they are only ever compared in
        // pairs, so the groups have to be able to grow.
        val group = IntArray(balloons.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (group[root] != root) root = group[root]
            var walk = i
            while (group[walk] != walk) {
                val next = group[walk]
                group[walk] = root
                walk = next
            }
            return root
        }

        for (i in balloons.indices) {
            for (j in i + 1 until balloons.size) {
                if (find(i) == find(j)) continue
                val a = balloons[i].box
                val b = balloons[j].box
                if (!near(a, b)) continue
                val larger = if (a.area >= b.area) a else b
                val smaller = if (a.area >= b.area) b else a
                val around = union(a, b)
                val others = balloons.indices.filter { it != i && it != j }.map { balloons[it].box }
                val pixels = crop(around)
                val spread = if (pixels == null) NOTHING else spread(pixels, around, larger, smaller, others)
                val joined = spread.reach >= BubbleZoomConfig.MERGE_REACH &&
                    spread.ring <= BubbleZoomConfig.JOIN_MAX_RING_FILL &&
                    spread.seam >= BubbleZoomConfig.JOIN_MIN_SEAM
                report?.invoke(larger, smaller, spread, joined)
                if (!joined) continue
                group[find(i)] = find(j)
            }
        }

        val merged = LinkedHashMap<Int, MutableList<BubbleDetection>>()
        for (i in balloons.indices) merged.getOrPut(find(i)) { ArrayList() } += balloons[i]
        return merged.values.map { lobes ->
            if (lobes.size == 1) lobes[0] else joined(lobes)
        }
    }

    /** One detection covering every lobe, keeping the surest lobe's confidence. */
    private fun joined(lobes: List<BubbleDetection>): BubbleDetection {
        val left = lobes.minOf { it.box.x }
        val top = lobes.minOf { it.box.y }
        val right = lobes.maxOf { it.box.right }
        val bottom = lobes.maxOf { it.box.bottom }
        val box = BubbleBox.clampedOrNull(left, top, right - left, bottom - top) ?: return lobes[0]
        val surest = lobes.maxBy { it.confidence }
        return surest.copy(box = box, shape = null)
    }

    /**
     * Boxes that cross, or stand within [BubbleZoomConfig.JOIN_NEAR_GAP] of each other on both
     * axes. A crossing pair has a negative gap on both, so it passes as it always did.
     */
    private fun near(a: BubbleBox, b: BubbleBox): Boolean {
        return gapX(a, b) <= BubbleZoomConfig.JOIN_NEAR_GAP && gapY(a, b) <= BubbleZoomConfig.JOIN_NEAR_GAP
    }

    /** Page between the two on the x axis; negative where they overlap. */
    private fun gapX(a: BubbleBox, b: BubbleBox): Float = maxOf(a.x, b.x) - minOf(a.right, b.right)

    private fun gapY(a: BubbleBox, b: BubbleBox): Float = maxOf(a.y, b.y) - minOf(a.bottom, b.bottom)

    /** The smallest box holding both. Both are valid boxes, so their union is one too. */
    private fun union(a: BubbleBox, b: BubbleBox): BubbleBox {
        val left = minOf(a.x, b.x)
        val top = minOf(a.y, b.y)
        return BubbleBox(left, top, maxOf(a.right, b.right) - left, maxOf(a.bottom, b.bottom) - top)
    }

    /** A page box as a rectangle of the crop's pixels: left and top inclusive, right and bottom exclusive. */
    private fun Crop.rect(box: BubbleBox): IntArray {
        val left = ((box.x - region.x) / region.width * width).toInt().coerceIn(0, width - 1)
        val top = ((box.y - region.y) / region.height * height).toInt().coerceIn(0, height - 1)
        val right = ((box.right - region.x) / region.width * width).toInt().coerceIn(left + 1, width)
        val bottom = ((box.bottom - region.y) / region.height * height).toInt().coerceIn(top + 1, height)
        return intArrayOf(left, top, right, bottom)
    }

    /** A pair whose pixels could not be read: nowhere, and everywhere it must not be. */
    private val NOTHING = Spread(reach = 0f, ring = 1f, seam = 0f)

    /**
     * A fill started in [larger], by the same tone and tolerance the card's own trace uses,
     * kept out of every box in [others], and run over the whole crop — the pair's rectangle and
     * its margin and nothing wider, because what it does out on the page is not the question.
     */
    private fun spread(
        page: Crop,
        around: BubbleBox,
        larger: BubbleBox,
        smaller: BubbleBox,
        others: List<BubbleBox>,
    ): Spread {
        val width = page.width
        val height = page.height
        if (width <= 0 || height <= 0 || page.luma.size < width * height) return NOTHING
        val from = page.rect(larger)
        val to = page.rect(smaller)
        val pair = page.rect(around)

        // Every pixel of the balloon's tone is open to the fill, less every other balloon's box.
        val surface = surfaceTone(page, from[0], from[1], from[2], from[3])
        val tolerance = (BubbleZoomConfig.MASK_TOLERANCE * 255f).toInt()
        val size = width * height
        val open = BooleanArray(size) { i ->
            val difference = page.luma[i] - surface
            difference <= tolerance && difference >= -tolerance
        }
        for (other in others) {
            if (!crosses(other, page.region)) continue
            val shut = page.rect(other)
            for (y in shut[1] until shut[3]) {
                val row = y * width
                for (x in shut[0] until shut[2]) open[row + x] = false
            }
        }

        val filled = BooleanArray(size)
        val stack = IntArray(size)
        var count = 0

        // Seeded from a patch at the middle of the larger lobe, because its exact centre is as
        // likely to be a letter as paper.
        val half = maxOf(1, (minOf(from[2] - from[0], from[3] - from[1]) * SEED_FRACTION).toInt())
        val seedX = (from[0] + from[2]) / 2
        val seedY = (from[1] + from[3]) / 2
        for (y in (seedY - half).coerceAtLeast(0) until (seedY + half + 1).coerceAtMost(height)) {
            for (x in (seedX - half).coerceAtLeast(0) until (seedX + half + 1).coerceAtMost(width)) {
                val index = y * width + x
                if (!filled[index] && open[index]) {
                    filled[index] = true
                    stack[count++] = index
                }
            }
        }
        if (count == 0) return NOTHING

        while (count > 0) {
            val index = stack[--count]
            val x = index % width
            if (x > 0 && !filled[index - 1] && open[index - 1]) {
                filled[index - 1] = true
                stack[count++] = index - 1
            }
            if (x < width - 1 && !filled[index + 1] && open[index + 1]) {
                filled[index + 1] = true
                stack[count++] = index + 1
            }
            if (index >= width && !filled[index - width] && open[index - width]) {
                filled[index - width] = true
                stack[count++] = index - width
            }
            if (index < size - width && !filled[index + width] && open[index + width]) {
                filled[index + width] = true
                stack[count++] = index + width
            }
        }

        return Spread(
            reach = share(filled, width, to[0], to[1], to[2], to[3]),
            ring = ring(filled, width, height, pair),
            seam = seam(filled, width, height, from, to),
        )
    }

    /** The share of the rectangle that is filled. */
    private fun share(filled: BooleanArray, width: Int, left: Int, top: Int, right: Int, bottom: Int): Float {
        if (right <= left || bottom <= top) return 0f
        var inside = 0
        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) if (filled[row + x]) inside++
        }
        return inside.toFloat() / ((right - left) * (bottom - top))
    }

    /** The share of the crop outside [pair] — the margin — that is filled. */
    private fun ring(filled: BooleanArray, width: Int, height: Int, pair: IntArray): Float {
        var outside = 0
        var area = 0
        for (y in 0 until height) {
            val inPairRows = y >= pair[1] && y < pair[3]
            val row = y * width
            for (x in 0 until width) {
                if (inPairRows && x >= pair[0] && x < pair[2]) continue
                area++
                if (filled[row + x]) outside++
            }
        }
        return if (area == 0) 0f else outside.toFloat() / area
    }

    /**
     * The share of the band between the two rectangles that is filled: across the gap where
     * one stands above the other, or beside it, and over the stretch where they overlap on the
     * other axis. Rectangles that cross have no band and read as one; rectangles that overlap
     * on neither axis — diagonal neighbours — have no stretch and read as none.
     */
    private fun seam(filled: BooleanArray, width: Int, height: Int, a: IntArray, b: IntArray): Float {
        val gapX = maxOf(a[0], b[0]) - minOf(a[2], b[2])
        val gapY = maxOf(a[1], b[1]) - minOf(a[3], b[3])
        if (gapX < 0 && gapY < 0) return 1f
        if (gapX >= 0 && gapY >= 0) return 0f
        val smallest = minOf(b[2] - b[0], b[3] - b[1])
        val thickness = maxOf(maxOf(gapX, gapY), 1, (smallest * BubbleZoomConfig.JOIN_SEAM_BAND).toInt())
        return if (gapY >= 0) {
            // One above the other: a horizontal band across the gap, over their overlap in x.
            val middle = (minOf(a[3], b[3]) + maxOf(a[1], b[1])) / 2
            val top = (middle - thickness / 2).coerceAtLeast(0)
            share(filled, width, maxOf(a[0], b[0]), top, minOf(a[2], b[2]), (top + thickness).coerceAtMost(height))
        } else {
            val middle = (minOf(a[2], b[2]) + maxOf(a[0], b[0])) / 2
            val left = (middle - thickness / 2).coerceAtLeast(0)
            share(filled, width, left, maxOf(a[1], b[1]), (left + thickness).coerceAtMost(width), minOf(a[3], b[3]))
        }
    }

    private fun crosses(a: BubbleBox, b: BubbleBox): Boolean =
        minOf(a.right, b.right) > maxOf(a.x, b.x) && minOf(a.bottom, b.bottom) > maxOf(a.y, b.y)

    /** The commonest tone in the rectangle, which is what the balloon is made of. */
    private fun surfaceTone(page: Crop, left: Int, top: Int, right: Int, bottom: Int): Int {
        val bins = IntArray(BubbleZoomConfig.MASK_TONE_BINS)
        val width = 256 / BubbleZoomConfig.MASK_TONE_BINS
        for (y in top until bottom) {
            val row = y * page.width
            for (x in left until right) {
                bins[(page.luma[row + x] / width).coerceIn(0, bins.lastIndex)]++
            }
        }
        var commonest = 0
        for (bin in bins.indices) {
            if (bins[bin] > bins[commonest]) commonest = bin
        }
        return commonest * width + width / 2
    }

    /** Half-width of the seed patch, as a fraction of the lobe's smallest side. */
    private const val SEED_FRACTION = 0.10f
}
