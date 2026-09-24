package eu.kanade.tachiyomi.ui.reader.bubblezoom

import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Puts a page's balloons into the order they are meant to be read.
 *
 * What arrives is the model's order, which after suppression is by confidence — so the walk
 * jumps about the page. Measured on a real page: the card went down, back to the top, down
 * again, back up, twice in seven steps.
 *
 * Comics are read panel by panel, so panels are what the order really follows. Where the page
 * says where its panels are — [PanelFinder] reads them off the gutters, amendment E8 — the
 * balloons are grouped by the panel they sit in and each group is read out in turn, which is
 * what Google Play Books does and what this did not.
 *
 * Everything below is what happens **inside** a group, and what happens to a whole page when
 * the panels could not be found: the layout is inferred from the balloons themselves, by
 * **recursive XY-cut**: the oldest
 * document-layout algorithm there is, and one that needs nothing but the boxes. Look for a
 * horizontal band of page that no balloon crosses; that is a row of panels, so split there and
 * take the top first. Failing that, look for a vertical one; that is the gap between panels
 * side by side, so split there and take them in reading order. When a group can be cut neither
 * way — balloons that overlap in both axes, which is what a cluster inside one panel looks
 * like — read it in bands: down the page, but anything at roughly the same height across it
 * first, because lettering is placed by eye and a row of balloons is never aligned exactly.
 *
 * What it therefore gets right is the common case, which is most of a comic: rows of panels,
 * panels side by side, stacks of balloons inside one panel. What it cannot know is a panel
 * whose balloons happen to line up with a neighbour's, because from the boxes alone that is the
 * same picture as one wide panel — which is exactly the case the panels above now settle when
 * the page's gutters can be found, and the honest limit of this when they cannot.
 *
 * Pure Kotlin. Every rule above is a JVM test.
 */
object BubbleOrderResolver {

    /**
     * [bubbles] in reading order. [rightToLeft] is the reader's direction, which only decides
     * how two things side by side are ordered — down the page is down the page either way.
     */
    fun order(bubbles: List<BubbleDetection>, rightToLeft: Boolean): List<BubbleDetection> {
        val panels = bubbles.filter { it.type == BubbleType.PANEL }
        val frames = bubbles.filter { it.type == BubbleType.FRAME }
        // Panels are structure and never a stop on the walk, so they leave here whatever else
        // happens. The controller filters them too; this keeps every count on the way truthful.
        val text = if (panels.isEmpty() && frames.isEmpty()) bubbles else bubbles.filter { !it.type.isStructure }
        if (text.size < 2) return text
        if (panels.isNotEmpty()) return byPanels(text, panels, rightToLeft)
        if (frames.isNotEmpty()) return byFrames(text, frames, rightToLeft)
        return withinGroup(text, rightToLeft)
    }

    /**
     * The balloons' own order, with each row of framed insets read one inset at a time.
     * Amendment E15.
     *
     * Frames are insets drawn over a splash, and unlike the gutters' panels they do not tile the
     * page: most of its balloons sit on the splash around them, in no frame at all. Put through
     * [byPanels] with the rest, a splash balloon that reaches down level with the insets lands
     * in their row and is read after them — measured on the reported page, "LAST I CHECKED" went
     * from fourth to eighth on a one-pixel change in where a frame's side was found.
     *
     * So the splash keeps the order the balloons alone give it, which is the order it had before
     * frames were found, and the insets are read where the walk first reaches any of them. Insets
     * that share their top and bottom lines are a row and are read as one block, one inset at a
     * time in the reading direction; so are insets that share their left and right, a column.
     */
    private fun byFrames(
        text: List<BubbleDetection>,
        frames: List<BubbleDetection>,
        rightToLeft: Boolean,
    ): List<BubbleDetection> {
        val home = IdentityHashMap<BubbleDetection, BubbleDetection>()
        val members = IdentityHashMap<BubbleDetection, MutableList<BubbleDetection>>()
        for (bubble in text) {
            val frame = frames
                .filter { it.box.contains(bubble.box.centerX, bubble.box.centerY) }
                .minByOrNull { it.box.area } ?: continue
            home[bubble] = frame
            members.getOrPut(frame) { ArrayList() } += bubble
        }
        val base = withinGroup(text, rightToLeft)
        if (members.isEmpty()) return base

        // Blocks: frames that are row mates or column mates, joined through each other.
        val used = frames.filter { members.containsKey(it) }
        val block = IntArray(used.size) { it }
        fun root(i: Int): Int {
            var at = i
            while (block[at] != at) at = block[at]
            return at
        }
        for (i in used.indices) {
            for (j in i + 1 until used.size) {
                if (mates(used[i].box, used[j].box)) block[root(i)] = root(j)
            }
        }
        val blockOf = IdentityHashMap<BubbleDetection, Int>()
        used.forEachIndexed { i, frame -> blockOf[frame] = root(i) }

        val out = ArrayList<BubbleDetection>(text.size)
        val read = HashSet<Int>()
        for (bubble in base) {
            val frame = home[bubble]
            if (frame == null) {
                out += bubble
                continue
            }
            val id = blockOf.getValue(frame)
            if (!read.add(id)) continue
            val row = used.filter { blockOf[it] == id }
            val inOrder = ArrayList<BubbleDetection>(row.size)
            // Insets meet at a gutter as panels do, so no gap is required between them.
            cut(row, rightToLeft, inOrder, depth = 0, rowGap = 0f, columnGap = 0f)
            for (inset in inOrder) {
                val group = members.getValue(inset)
                out += if (group.size == 1) group else withinGroup(group, rightToLeft)
            }
        }
        return out
    }

    /** Two frames drawn on the same lines: a row (top and bottom) or a column (left and right). */
    private fun mates(a: BubbleBox, b: BubbleBox): Boolean {
        val tolerance = BubbleZoomConfig.ORDER_FRAME_MATE
        val row = abs(a.y - b.y) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
        val column = abs(a.x - b.x) <= tolerance && abs(a.right - b.right) <= tolerance
        return row || column
    }

    /**
     * Each balloon read with the panel it sits in, and the panels read in turn.
     *
     * A balloon belongs to the smallest panel that contains its centre — smallest because a
     * page divided into rows and then into panels leaves both rectangles containing it, and the
     * panel is the one that says where to read it. A balloon in no panel at all, which is what
     * a caption laid over a gutter looks like, is a group of its own and is read where it lies.
     */
    private fun byPanels(
        text: List<BubbleDetection>,
        panels: List<BubbleDetection>,
        rightToLeft: Boolean,
    ): List<BubbleDetection> {
        val grouped = LinkedHashMap<BubbleDetection, MutableList<BubbleDetection>>()
        val loose = ArrayList<BubbleDetection>()
        for (bubble in text) {
            val panel = panels
                .filter { it.box.contains(bubble.box.centerX, bubble.box.centerY) }
                .minByOrNull { it.box.area }
            if (panel == null) loose += bubble else grouped.getOrPut(panel) { ArrayList() } += bubble
        }
        if (grouped.isEmpty()) return withinGroup(text, rightToLeft)

        // One box stands for each group: the panel's own, which is exact, or the balloon's when
        // it has no panel. Identity and not equality, because two panels of the same size and
        // position would be the same value and must still be two groups.
        val members = IdentityHashMap<BubbleDetection, List<BubbleDetection>>()
        val representatives = ArrayList<BubbleDetection>(grouped.size + loose.size)
        grouped.forEach { (panel, bubbles) ->
            representatives += panel
            members[panel] = bubbles
        }
        loose.forEach {
            representatives += it
            members[it] = listOf(it)
        }

        val ordered = ArrayList<BubbleDetection>(representatives.size)
        // No gap is required between panels: they meet at the gutter, and the gutter is what
        // divided them. The gaps below exist to tell balloons apart, which is a different job.
        cut(representatives, rightToLeft, ordered, depth = 0, rowGap = 0f, columnGap = 0f)

        val out = ArrayList<BubbleDetection>(text.size)
        for (representative in ordered) {
            val group = members[representative] ?: continue
            if (group.size == 1) out += group[0] else out += withinGroup(group, rightToLeft)
        }
        return out
    }

    /** [group] in reading order, by the balloons alone. */
    private fun withinGroup(group: List<BubbleDetection>, rightToLeft: Boolean): List<BubbleDetection> {
        if (group.size < 2) return group
        val out = ArrayList<BubbleDetection>(group.size)
        cut(group, rightToLeft, out, depth = 0)
        return out
    }

    private fun cut(
        group: List<BubbleDetection>,
        rightToLeft: Boolean,
        out: MutableList<BubbleDetection>,
        depth: Int,
        rowGap: Float = BubbleZoomConfig.ORDER_ROW_GAP,
        columnGap: Float = BubbleZoomConfig.ORDER_COLUMN_GAP,
    ) {
        if (group.size == 1) {
            out += group[0]
            return
        }
        // A cut that fails leaves the group whole, so a page whose balloons all overlap would
        // recurse on itself for ever without this. The bound is generous: each level at least
        // halves a group that can be cut at all.
        if (depth >= MAX_DEPTH) {
            out += banded(group, rightToLeft)
            return
        }

        val rows = split(group, rightToLeft, horizontal = true, minimum = rowGap)
        if (rows != null) {
            rows.forEach { cut(it, rightToLeft, out, depth + 1, rowGap, columnGap) }
            return
        }
        val columns = split(group, rightToLeft, horizontal = false, minimum = columnGap)
        if (columns != null) {
            columns.forEach { cut(it, rightToLeft, out, depth + 1, rowGap, columnGap) }
            return
        }
        // Neither axis has a gap: one cluster inside one panel. Read down the page, but by
        // BANDS rather than by the top edge alone — balloons meant to be read across are never
        // aligned to the pixel, and sorting on a difference of a thousandth of a page would
        // order three balloons in a row by which letterer nudged which. Measured: on a real
        // page the middle balloon of a row sat one thousandth higher than the left one and was
        // read first.
        out += banded(group, rightToLeft)
    }

    /** [group] read as rows of roughly-aligned balloons, each row in the reading direction. */
    private fun banded(group: List<BubbleDetection>, rightToLeft: Boolean): List<BubbleDetection> {
        val across = compareBy<BubbleDetection> { if (rightToLeft) -it.box.x else it.box.x }
        val sorted = group.sortedBy { it.box.y }
        val out = ArrayList<BubbleDetection>(group.size)
        var band = mutableListOf(sorted[0])
        var top = sorted[0].box.y
        for (bubble in sorted.drop(1)) {
            if (bubble.box.y - top > BubbleZoomConfig.ORDER_BAND_TOLERANCE) {
                out += band.sortedWith(across)
                band = mutableListOf(bubble)
                top = bubble.box.y
            } else {
                band += bubble
            }
        }
        out += band.sortedWith(across)
        return out
    }

    /**
     * [group] split at every gap along one axis, in reading order, or null if there is no gap.
     *
     * A gap is a strip that no box crosses. The boxes are swept in order of their leading edge
     * and a run ends wherever the next one starts past everything seen so far — the standard
     * interval-merge, which is what makes this linear after the sort rather than quadratic.
     */
    private fun split(
        group: List<BubbleDetection>,
        rightToLeft: Boolean,
        horizontal: Boolean,
        minimum: Float,
    ): List<List<BubbleDetection>>? {
        val start = { b: BubbleDetection -> if (horizontal) b.box.y else b.box.x }
        val end = { b: BubbleDetection -> if (horizontal) b.box.bottom else b.box.right }

        val sorted = group.sortedBy(start)
        val bands = ArrayList<MutableList<BubbleDetection>>()
        var current = mutableListOf(sorted[0])
        var reach = end(sorted[0])
        for (bubble in sorted.drop(1)) {
            if (start(bubble) - reach >= minimum) {
                bands += current
                current = mutableListOf(bubble)
                reach = end(bubble)
            } else {
                current += bubble
                if (end(bubble) > reach) reach = end(bubble)
            }
        }
        bands += current
        if (bands.size < 2) return null
        // Down the page is down the page; across it depends on which way the reader reads.
        return if (horizontal || !rightToLeft) bands else bands.reversed()
    }

    /** Deep enough for any page: each level that cuts at all makes the groups strictly smaller. */
    private const val MAX_DEPTH = 16
}
