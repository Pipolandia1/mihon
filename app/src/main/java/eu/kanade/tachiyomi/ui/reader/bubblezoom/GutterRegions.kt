package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * A page's panels as the regions its gutters enclose. Amendment E17.
 *
 * [PanelFinder] divides a page along lines of gutter that run straight across it, which is how
 * most pages are built and which it does well. Some artists draw their panels at an angle: the
 * gutters are still there, plain paper a few pixels wide, but each one climbs or falls across
 * the page, and no single line of pixels lies inside it. Measured on a whole issue drawn that
 * way: panels found on 4 pages of 38, and the balloons of a row of three panels read straight
 * across all three, top line first, on 11 of them.
 *
 * So this looks at the shape the other way round: every drawn area the gutter surrounds is a
 * panel, however its sides run. The drawn pixels are grouped into connected regions, and each
 * region big enough and drawn on enough is a panel — its box, which for a panel at an angle is
 * a little larger than the panel and overlaps its neighbour's by the angle's rise.
 *
 * It is only asked where it is safe. On a page whose margin is dark, the art's own shadows are
 * margin-toned and cut panels into pieces, so only paper margins are looked at. And the answer
 * has to look like a page of panels ([plausible]): on a page drawn to its edges, "the regions
 * the margin tone surrounds" is whatever the art happens to leave, and that comes back as one
 * region the size of the page, or as pieces lying inside each other, or as scraps covering a
 * fraction of it. Measured over the same 38 pages: every page with real gutters covers at least
 * 86 percent of the page with boxes that overlap by at most 15 percent, and every other page
 * fails at least one of the rules below.
 *
 * Pure Kotlin over the same grey plane as [PanelFinder], so every rule is a JVM test.
 */
internal object GutterRegions {

    /**
     * The panels of a page given as [luma], or an empty list when the page does not read as a
     * page of panels this way. [tone] is the margin's, [minPanel] the smallest side of a panel,
     * both as [PanelFinder] measured them.
     */
    fun find(luma: IntArray, width: Int, height: Int, tone: Int, minPanel: Int): List<BubbleBox> {
        if (tone < BubbleDetectionConfig.PANEL_REGION_MIN_TONE) return emptyList()
        if (width < 3 || height < 3 || luma.size < width * height) return emptyList()

        val gutter = BooleanArray(width * height) {
            val difference = luma[it] - tone
            difference <= BubbleDetectionConfig.PANEL_GUTTER_TOLERANCE &&
                difference >= -BubbleDetectionConfig.PANEL_GUTTER_TOLERANCE
        }
        val regions = regions(inkLessOnePixel(gutter, width, height), width, height)
        val panels = regions.filter {
            it.width >= minPanel && it.height >= minPanel &&
                ink(gutter, width, it) >= BubbleDetectionConfig.PANEL_MIN_INK
        }
        if (!plausible(panels, width, height)) return emptyList()
        return panels.mapNotNull {
            BubbleBox.clampedOrNull(
                x = it.x0.toFloat() / width,
                y = it.y0.toFloat() / height,
                width = it.width.toFloat() / width,
                height = it.height.toFloat() / height,
            )
        }
    }

    private class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
        val area get() = width.toLong() * height
    }

    /**
     * The drawn pixels, less one pixel all round.
     *
     * A balloon's outline or a stroke of the pen that touches the gutter from both sides is a
     * pixel or two wide at this scale; left alone, it joins two panels into one region.
     */
    private fun inkLessOnePixel(gutter: BooleanArray, width: Int, height: Int): BooleanArray {
        val ink = BooleanArray(gutter.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                ink[i] = !gutter[i] && !gutter[i - 1] && !gutter[i + 1] && !gutter[i - width] && !gutter[i + width]
            }
        }
        return ink
    }

    /** The bounding box of each 4-connected region of [ink], by flood fill. */
    private fun regions(ink: BooleanArray, width: Int, height: Int): List<Rect> {
        val seen = BooleanArray(ink.size)
        // Every pixel is pushed at most once, because it is marked as it is pushed.
        val stack = IntArray(ink.size)
        val out = ArrayList<Rect>()
        for (start in ink.indices) {
            if (!ink[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var x0 = width
            var y0 = height
            var x1 = -1
            var y1 = -1
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (x > 0 && ink[i - 1] && !seen[i - 1]) {
                    seen[i - 1] = true
                    stack[top++] = i - 1
                }
                if (x < width - 1 && ink[i + 1] && !seen[i + 1]) {
                    seen[i + 1] = true
                    stack[top++] = i + 1
                }
                if (y > 0 && ink[i - width] && !seen[i - width]) {
                    seen[i - width] = true
                    stack[top++] = i - width
                }
                if (y < height - 1 && ink[i + width] && !seen[i + width]) {
                    seen[i + width] = true
                    stack[top++] = i + width
                }
            }
            out += Rect(x0, y0, x1 + 1, y1 + 1)
        }
        return out
    }

    /** How much of [rect] is drawn on rather than gutter, as [PanelFinder] measures a panel. */
    private fun ink(gutter: BooleanArray, width: Int, rect: Rect): Float {
        var drawn = 0L
        for (y in rect.y0 until rect.y1) {
            for (x in rect.x0 until rect.x1) {
                if (!gutter[y * width + x]) drawn++
            }
        }
        return if (rect.area == 0L) 0f else drawn.toFloat() / rect.area
    }

    /**
     * Whether [panels] look like a page divided into panels rather than whatever a page drawn
     * to its edges leaves: at least two, none the size of the page, not lying over each other,
     * and between them covering most of it.
     */
    private fun plausible(panels: List<Rect>, width: Int, height: Int): Boolean {
        if (panels.size < 2) return false
        val whole = BubbleDetectionConfig.PANEL_REGION_WHOLE_PAGE
        if (panels.any { it.width >= whole * width && it.height >= whole * height }) return false

        for (i in panels.indices) {
            for (j in i + 1 until panels.size) {
                val a = panels[i]
                val b = panels[j]
                val across = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)
                val down = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
                if (across <= 0 || down <= 0) continue
                val shared = across.toLong() * down
                if (shared > BubbleDetectionConfig.PANEL_REGION_MAX_OVERLAP * minOf(a.area, b.area)) return false
            }
        }

        val covered = BooleanArray(width * height)
        var count = 0L
        for (panel in panels) {
            for (y in panel.y0 until panel.y1) {
                for (x in panel.x0 until panel.x1) {
                    val i = y * width + x
                    if (!covered[i]) {
                        covered[i] = true
                        count++
                    }
                }
            }
        }
        return count >= BubbleDetectionConfig.PANEL_REGION_MIN_COVER * width * height
    }
}
