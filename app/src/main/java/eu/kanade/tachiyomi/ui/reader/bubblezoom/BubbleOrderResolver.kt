package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Puts a page's balloons into the order they are meant to be read.
 *
 * What arrives is the model's order, which after suppression is by confidence — so the walk
 * jumps about the page. Measured on a real page: the card went down, back to the top, down
 * again, back up, twice in seven steps.
 *
 * Phase P12 of the spec asks for panel detection first, and this does not do that. Comics are
 * read panel by panel, so panels are what the order really follows, but finding them means
 * reading gutters out of the artwork — and the two amendments before this one measured exactly
 * how that goes on a real comic: tone-based tracing found six balloons of nine and no amount of
 * tuning moved it, because a gutter that is the same tone as the art beside it is not there to
 * be found. A second model is the other way, and one of 109 MB has just been added.
 *
 * So the layout is inferred from the balloons themselves, by **recursive XY-cut**: the oldest
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
 * same picture as one wide panel. That is the honest limit of ordering without panels, and it
 * is a far smaller error than ordering by confidence.
 *
 * Pure Kotlin. Every rule above is a JVM test.
 */
object BubbleOrderResolver {

    /**
     * [bubbles] in reading order. [rightToLeft] is the reader's direction, which only decides
     * how two things side by side are ordered — down the page is down the page either way.
     */
    fun order(bubbles: List<BubbleDetection>, rightToLeft: Boolean): List<BubbleDetection> {
        if (bubbles.size < 2) return bubbles
        val out = ArrayList<BubbleDetection>(bubbles.size)
        cut(bubbles, rightToLeft, out, depth = 0)
        return out
    }

    private fun cut(
        group: List<BubbleDetection>,
        rightToLeft: Boolean,
        out: MutableList<BubbleDetection>,
        depth: Int,
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

        val rows = split(group, rightToLeft, horizontal = true)
        if (rows != null) {
            rows.forEach { cut(it, rightToLeft, out, depth + 1) }
            return
        }
        val columns = split(group, rightToLeft, horizontal = false)
        if (columns != null) {
            columns.forEach { cut(it, rightToLeft, out, depth + 1) }
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
    ): List<List<BubbleDetection>>? {
        val start = { b: BubbleDetection -> if (horizontal) b.box.y else b.box.x }
        val end = { b: BubbleDetection -> if (horizontal) b.box.bottom else b.box.right }
        val minimum = if (horizontal) BubbleZoomConfig.ORDER_ROW_GAP else BubbleZoomConfig.ORDER_COLUMN_GAP

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
