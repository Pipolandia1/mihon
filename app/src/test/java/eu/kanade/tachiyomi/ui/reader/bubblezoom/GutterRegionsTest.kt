package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Panels as the regions their gutters enclose, for gutters drawn at an angle. Amendment E17.
 *
 * The fixtures are drawn the way the issue that needed this was: paper, with the art filling
 * everything but the margin and gutters that climb or fall across the page by more than their
 * own width, so that no straight line of pixels lies inside any of them.
 */
class GutterRegionsTest {

    private val width = 100
    private val height = 150
    private val minPanel = (BubbleDetectionConfig.PANEL_MIN_SIZE * height).toInt()

    private class Page(val width: Int, val height: Int, background: Int) {
        val luma = IntArray(width * height) { background }

        fun paint(tone: Int = ART, where: (x: Int, y: Int) -> Boolean) {
            for (y in 0 until height) {
                for (x in 0 until width) if (where(x, y)) luma[y * width + x] = tone
            }
        }
    }

    /**
     * Three rows under a 4-pixel margin, the gutters between them 4 pixels thick and rising 8
     * across the page, the bottom row divided in two by a gutter leaning 8 across its height.
     */
    private fun slantedPage(background: Int = WHITE): Page {
        val page = Page(width, height, background)
        page.paint { x, y ->
            val inside = x in MARGIN until width - MARGIN && y in MARGIN until height - MARGIN
            val first = y - (48 + x * 8 / width)
            val second = y - (100 - x * 8 / width)
            val column = x - (46 + (y - 100) * 8 / 50)
            inside && first !in 0 until GUTTER && second !in 0 until GUTTER &&
                !(y > 100 && column in 0 until GUTTER)
        }
        return page
    }

    private fun Page.regions(): List<BubbleBox> =
        GutterRegions.find(luma, width, height, tone = luma[0], minPanel = minPanel)

    @Test
    fun `panels at an angle are found as the regions between their gutters`() {
        val panels = slantedPage().regions()

        assertEquals(4, panels.size)
        assertTrue(panels.any { it.contains(0.5f, 0.15f) }, "the top row")
        assertTrue(panels.any { it.contains(0.5f, 0.5f) }, "the middle row")
        assertTrue(panels.any { it.contains(0.2f, 0.85f) } && panels.any { it.contains(0.8f, 0.85f) }, "the bottom two")
    }

    @Test
    fun `the page's panels are those regions when no straight line crosses a gutter`() {
        // The straight cut finds nothing here - every gutter rises twice its own thickness - so
        // four panels can only have come from the regions.
        val page = slantedPage()

        assertEquals(4, PanelFinder.find(page.luma, page.width, page.height).size)
    }

    @Test
    fun `a dark margin is not looked at this way`() {
        // On a black margin the art's own shadows are the margin's tone and would cut a panel
        // into pieces, so the regions are only trusted on paper.
        val page = slantedPage(background = BLACK)

        assertEquals(emptyList<BubbleBox>(), page.regions())
    }

    @Test
    fun `a page drawn to its edges is not divided`() {
        // Art everywhere inside a hairline of paper, but for a few paper-toned patches: one
        // region, the size of the page.
        val page = Page(width, height, WHITE)
        page.paint { x, y ->
            val patch = (x in 20 until 30 && y in 40 until 50) || (x in 60 until 75 && y in 90 until 95)
            x in 2 until width - 2 && y in 2 until height - 2 && !patch
        }

        assertEquals(emptyList<BubbleBox>(), page.regions())
    }

    @Test
    fun `regions lying inside each other are not panels side by side`() {
        // Two panels side by side, the left with a ring of paper drawn inside it: a division.
        val page = Page(width, height, WHITE)
        page.paint { x, y -> x in MARGIN until 48 && y in MARGIN until height - MARGIN }
        page.paint { x, y -> x in 52 until width - MARGIN && y in MARGIN until height - MARGIN }
        page.paint(WHITE) { x, y -> x in 12 until 40 && y in 40 until 110 }
        assertEquals(2, page.regions().size)

        // Art inside the ring makes a region inside the left one's box, which is what the scraps
        // of a page drawn to its edges look like, and the page no longer reads as panels.
        page.paint { x, y -> x in 16 until 36 && y in 44 until 106 }

        assertEquals(emptyList<BubbleBox>(), page.regions())
    }

    @Test
    fun `scraps covering a fraction of the page are not a division of it`() {
        // Two drawn areas on a page of paper: each big enough to be a panel, together a third
        // of the page, which is a title page or an advert rather than a page of panels.
        val page = Page(width, height, WHITE)
        page.paint { x, y -> x in 10 until 45 && y in 10 until 80 }
        page.paint { x, y -> x in 55 until 90 && y in 10 until 80 }

        assertEquals(emptyList<BubbleBox>(), page.regions())
    }

    @Test
    fun `an outline crossing a gutter does not join the panels on either side`() {
        // A stroke a pixel thick drawn down across the first gutter, touching both rows.
        val page = slantedPage()
        page.paint(tone = 20) { x, y -> x == 50 && y in 45 until 60 }

        assertEquals(4, page.regions().size)
    }

    private companion object {
        const val BLACK = 8
        const val WHITE = 250
        const val ART = 60
        const val MARGIN = 4
        const val GUTTER = 4
    }
}
