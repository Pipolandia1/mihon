package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * The page's panels, read out of its own pixels. Amendment E8.
 *
 * Comics are read panel by panel, and until now nothing here knew where the panels were: the
 * order was inferred from the balloons alone, which reads a row of panels as one horizontal
 * band and therefore reads across it instead of down each panel in turn. Measured against
 * Google Play Books on the same page, that is the one ordering difference left.
 *
 * Phase P12 wrote down why panels were not attempted then: amendment E1 had just measured that
 * tracing a balloon's outline out of the artwork fails when the tone it is drawn on matches the
 * tone beside it. That was about **balloons**, which are drawn over the art. A gutter is not
 * drawn over anything: it is a straight band of one flat tone crossing the whole page, and that
 * tone is the page's own margin — black in the comic this was built against, white in most
 * manga. That is a far easier thing to find, and the difference is why this is worth trying now
 * where the earlier one was not.
 *
 * So: take the margin's tone from the page's outermost ring, find runs of rows that are that
 * tone nearly all the way across, split there, then do the same with columns, and recurse. The
 * leaves are the panels.
 *
 * Both ways of being wrong were measured on real pages, and they are not symmetric. **Finding
 * too few** is what happens on a busy page whose panels are separated by a line rather than a
 * gutter; the balloons of the panels that were not separated then fall in one group and are
 * ordered exactly as they are today, which is where this started. **Finding too many** happens
 * on a page of flat white, where the recursion runs past the panels and down to the lettering
 * itself; each balloon then becomes its own group, which is also what happens today. Neither
 * failure can put a balloon somewhere a reader would not look, which is what makes this safe to
 * apply to every page.
 *
 * Pure Kotlin over one grey plane, so every rule above is a JVM test.
 */
object PanelFinder {

    /**
     * The panels of a page given as [luma], one byte of brightness per pixel, row by row.
     *
     * Empty when the page could not be divided at all — which is the honest answer for a splash
     * page, and tells the caller to order the balloons the way it did before this existed.
     */
    fun find(luma: IntArray, width: Int, height: Int): List<BubbleBox> {
        if (width <= 0 || height <= 0 || luma.size < width * height) return emptyList()
        val longSide = maxOf(width, height)
        val minGutter = maxOf(2, (BubbleDetectionConfig.PANEL_MIN_GUTTER * longSide).toInt())
        val minPanel = maxOf(4, (BubbleDetectionConfig.PANEL_MIN_SIZE * longSide).toInt())
        if (width < 2 * minPanel && height < 2 * minPanel) return emptyList()

        val tone = marginTone(luma, width, height)
        val leaves = ArrayList<BubbleBox>()
        cut(Page(luma, width, height, tone, minGutter, minPanel), 0, 0, width, height, 0, leaves)
        // One leaf is the page itself: nothing was found, and saying so is not the same as
        // saying "the whole page is one panel", which would make every caller special-case it.
        return if (leaves.size < 2) emptyList() else leaves
    }

    private class Page(
        val luma: IntArray,
        val width: Int,
        val height: Int,
        val tone: Int,
        val minGutter: Int,
        val minPanel: Int,
    ) {
        /** Whether the pixel at ([x], [y]) is the margin's tone, within tolerance. */
        fun isGutter(x: Int, y: Int): Boolean {
            val difference = luma[y * width + x] - tone
            return difference <= BubbleDetectionConfig.PANEL_GUTTER_TOLERANCE &&
                difference >= -BubbleDetectionConfig.PANEL_GUTTER_TOLERANCE
        }
    }

    /**
     * The tone of the page's outermost ring, by median.
     *
     * The median and not the mean: a page whose art bleeds off one edge would drag a mean
     * halfway to the art, while it takes more than half the ring to move a median at all.
     */
    private fun marginTone(luma: IntArray, width: Int, height: Int): Int {
        val histogram = IntArray(256)
        var count = 0
        for (x in 0 until width) {
            histogram[luma[x].coerceIn(0, 255)]++
            histogram[luma[(height - 1) * width + x].coerceIn(0, 255)]++
            count += 2
        }
        for (y in 1 until height - 1) {
            histogram[luma[y * width].coerceIn(0, 255)]++
            histogram[luma[y * width + width - 1].coerceIn(0, 255)]++
            count += 2
        }
        var seen = 0
        for (tone in 0..255) {
            seen += histogram[tone]
            if (seen * 2 >= count) return tone
        }
        return 0
    }

    private fun cut(page: Page, x0: Int, y0: Int, x1: Int, y1: Int, depth: Int, out: MutableList<BubbleBox>) {
        val regionWidth = x1 - x0
        val regionHeight = y1 - y0
        if (depth >= BubbleDetectionConfig.PANEL_MAX_DEPTH ||
            regionWidth < page.minPanel || regionHeight < page.minPanel
        ) {
            emit(page, x0, y0, x1, y1, out)
            return
        }
        // Rows first and columns second, which is the order a page is built in: a comic is rows
        // of panels, and a row is panels side by side.
        for (horizontal in booleanArrayOf(true, false)) {
            val pieces = pieces(page, x0, y0, x1, y1, horizontal) ?: continue
            for ((from, to) in pieces) {
                if (horizontal) {
                    cut(page, x0, from, x1, to, depth + 1, out)
                } else {
                    cut(page, from, y0, to, y1, depth + 1, out)
                }
            }
            return
        }
        emit(page, x0, y0, x1, y1, out)
    }

    /**
     * Where the region divides along one axis, or null if it does not.
     *
     * A gutter has to cross the whole region, be at least [BubbleDetectionConfig.PANEL_MIN_GUTTER]
     * thick, and leave something worth calling a panel on both sides. A band that touches the
     * region's own edge is its margin rather than a gutter between two panels, and dividing on
     * it would only trim the region.
     */
    private fun pieces(
        page: Page,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        horizontal: Boolean,
    ): List<Pair<Int, Int>>? {
        val from = if (horizontal) y0 else x0
        val to = if (horizontal) y1 else x1
        val across = if (horizontal) x1 - x0 else y1 - y0
        val needed = (across * BubbleDetectionConfig.PANEL_LINE_PURITY).toInt()

        val bands = ArrayList<Pair<Int, Int>>()
        var runStart = -1
        for (line in from until to) {
            var gutterPixels = 0
            for (offset in 0 until across) {
                val isGutter = if (horizontal) page.isGutter(x0 + offset, line) else page.isGutter(line, y0 + offset)
                if (isGutter) gutterPixels++
            }
            if (gutterPixels >= needed) {
                if (runStart < 0) runStart = line
            } else {
                if (runStart >= 0 && line - runStart >= page.minGutter) bands += runStart to line
                runStart = -1
            }
        }
        if (runStart >= 0 && to - runStart >= page.minGutter) bands += runStart to to

        val inner = bands.filter { (start, end) -> start > from && end < to }
        if (inner.isEmpty()) return null

        val pieces = ArrayList<Pair<Int, Int>>()
        var cursor = from
        for ((start, end) in inner) {
            if (start - cursor >= page.minPanel) pieces += cursor to start
            cursor = end
        }
        if (to - cursor >= page.minPanel) pieces += cursor to to
        return if (pieces.size >= 2) pieces else null
    }

    /** Adds a leaf, as a fraction of the page. Silently drops one too thin to be a rectangle. */
    private fun emit(page: Page, x0: Int, y0: Int, x1: Int, y1: Int, out: MutableList<BubbleBox>) {
        BubbleBox.clampedOrNull(
            x = x0.toFloat() / page.width,
            y = y0.toFloat() / page.height,
            width = (x1 - x0).toFloat() / page.width,
            height = (y1 - y0).toFloat() / page.height,
        )?.let { out += it }
    }
}
