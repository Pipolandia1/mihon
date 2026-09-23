package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading a page's panels off its gutters. Amendment E8.
 *
 * Each fixture is a page drawn in brightness: a background of the margin's tone with rectangles
 * of "art" painted on it, exactly as a comic page is a dark or light field with panels on it.
 */
class PanelFinderTest {

    private val width = 100
    private val height = 150

    private class Page(val width: Int, val height: Int, background: Int) {
        val luma = IntArray(width * height) { background }

        fun paint(x0: Int, y0: Int, x1: Int, y1: Int, tone: Int = ART) {
            for (y in y0 until y1) {
                for (x in x0 until x1) luma[y * width + x] = tone
            }
        }
    }

    private fun Page.panels(): List<BubbleBox> = PanelFinder.find(luma, width, height)

    /** The panel containing that point, as a fraction of the page, for readable assertions. */
    private fun List<BubbleBox>.at(x: Float, y: Float): Int = indexOfFirst { it.contains(x, y) }

    @Test
    fun `three panels down a page with black gutters`() {
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 96, 46)
        page.paint(4, 54, 96, 96)
        page.paint(4, 104, 96, 146)

        val panels = page.panels()

        assertEquals(3, panels.size)
        assertTrue(panels[0].y < panels[1].y && panels[1].y < panels[2].y, "found down the page")
        assertTrue(panels[0].contains(0.5f, 0.15f), "the first panel is where it was painted")
        assertTrue(panels.all { it.width > 0.9f }, "each row spans the page")
    }

    @Test
    fun `a white page is read the same way as a black one`() {
        // Manga is white paper with white gutters; the tone is whatever the page's margin is.
        val page = Page(width, height, background = WHITE)
        page.paint(4, 4, 96, 70, tone = 60)
        page.paint(4, 80, 96, 146, tone = 60)

        assertEquals(2, page.panels().size)
    }

    @Test
    fun `a row of panels side by side is divided across`() {
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 46, 70)
        page.paint(54, 4, 96, 70)
        page.paint(4, 80, 96, 146)

        val panels = page.panels()

        assertEquals(3, panels.size)
        val left = panels.at(0.25f, 0.25f)
        val right = panels.at(0.75f, 0.25f)
        val bottom = panels.at(0.5f, 0.75f)
        assertTrue(left >= 0 && right >= 0 && bottom >= 0, "every painted panel was found")
        assertTrue(left != right, "the two in the top row are two panels, not one")
        assertTrue(panels[left].width < 0.6f && panels[right].width < 0.6f)
    }

    @Test
    fun `rows are found before columns, so a page is rows of panels`() {
        // The layout that tells the two orders apart: two rows of two. Cutting columns first
        // would make it two columns of two, which is read down the left half and then the
        // right, and that is not how a comic is read.
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 46, 70)
        page.paint(54, 4, 96, 70)
        page.paint(4, 80, 46, 146)
        page.paint(54, 80, 96, 146)

        val panels = page.panels()

        assertEquals(4, panels.size)
        assertTrue(panels[0].bottom < 0.5f && panels[1].bottom < 0.5f, "the top row comes first")
        assertTrue(panels[2].y > 0.4f && panels[3].y > 0.4f)
    }

    @Test
    fun `a splash page is not divided at all`() {
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 96, 146)

        assertEquals(emptyList<BubbleBox>(), page.panels(), "one panel is not a division")
    }

    @Test
    fun `a page with nothing on it is not divided either`() {
        assertEquals(emptyList<BubbleBox>(), Page(width, height, background = WHITE).panels())
    }

    @Test
    fun `a gutter that does not cross the page is not a gutter`() {
        // Two panels side by side in the top half, but the gap between them stops halfway down
        // because the bottom half is one wide panel. Dividing the page there would cut it.
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 46, 70)
        page.paint(54, 4, 96, 70)
        page.paint(4, 80, 96, 146)

        val panels = page.panels()

        assertTrue(panels.none { it.height > 0.8f }, "no panel spans both halves of the page")
        assertTrue(panels.any { it.width > 0.8f }, "the bottom panel is whole")
    }

    @Test
    fun `a few stray pixels do not close a gutter`() {
        // Lettering that overhangs its panel, or a scan's dust. Below the purity threshold the
        // line is still gutter.
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 96, 70)
        page.paint(4, 80, 96, 146)
        page.paint(40, 74, 42, 76)

        assertEquals(2, page.panels().size)
    }

    @Test
    fun `art that reaches across the gutter closes it`() {
        // A figure drawn over the gutter joins the two panels, and they are read as one. That
        // is the safe way to be wrong: their balloons then order the way they did before.
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 96, 70)
        page.paint(4, 80, 96, 146)
        page.paint(4, 70, 96, 80)

        assertEquals(emptyList<BubbleBox>(), page.panels())
    }

    @Test
    fun `empty paper is not a panel, however straight the space between the words`() {
        // The regression this rule exists for, drawn the way a real sparse page is: lettering
        // in lines, which breaks every horizontal band into fragments too short to divide on,
        // so the only division left is down the page. Those columns are paper with a few words
        // in them, and grouping by them would have the page read down its columns.
        val page = Page(width, height, background = WHITE)
        for (corner in listOf(6, 120)) {
            for (line in 0 until 5) {
                val y = corner + line * 4
                page.paint(10, y, 34, y + 2, tone = 20)
                page.paint(66, y, 90, y + 2, tone = 20)
            }
        }

        assertEquals(emptyList<BubbleBox>(), page.panels(), "those columns are paper, not panels")
    }

    @Test
    fun `a division with only one drawn side is not a division`() {
        // Half a page of art and half a page of nothing: one panel, and one panel is not a
        // division, so the balloons decide the order by themselves.
        val page = Page(width, height, background = BLACK)
        page.paint(4, 4, 96, 70)

        assertEquals(emptyList<BubbleBox>(), page.panels())
    }

    @Test
    fun `a page it cannot read is no page at all`() {
        assertEquals(emptyList<BubbleBox>(), PanelFinder.find(IntArray(0), 0, 0))
        assertEquals(emptyList<BubbleBox>(), PanelFinder.find(IntArray(4), 10, 10), "array too short")
        assertEquals(emptyList<BubbleBox>(), PanelFinder.find(IntArray(9), 3, 3), "smaller than a panel")
    }

    private companion object {
        const val BLACK = 8
        const val WHITE = 250
        const val ART = 150
    }
}
