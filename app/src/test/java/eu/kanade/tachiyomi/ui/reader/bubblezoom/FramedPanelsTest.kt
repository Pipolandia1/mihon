package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Framed panels on a page no gutter divides. Amendment E15.
 *
 * The fixture is the page a reader reported, drawn in brightness at the scan's scale: a splash
 * of busy art across the whole page, and along its foot a row of three insets, each a rectangle
 * of black line with a flat grey gutter between them and white paper below. The splash is a
 * fine checker of two mid tones — nothing flat about it, and nothing dark enough to be ink.
 */
class FramedPanelsTest {

    private val width = 200
    private val height = 300

    private class Page(val width: Int, val height: Int) {
        val luma = IntArray(width * height) { i -> if (((i % width) / 2 + (i / width) / 2) % 2 == 0) ART_A else ART_B }

        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, tone: Int) {
            for (y in y0 until y1) {
                for (x in x0 until x1) luma[y * width + x] = tone
            }
        }

        /** A panel: an outline of black line [LINE] pixels thick, art inside. */
        fun frame(x0: Int, y0: Int, x1: Int, y1: Int) {
            fill(x0, y0, x1, y1, INK)
            fill(x0 + LINE, y0 + LINE, x1 - LINE, y1 - LINE, INSET_ART)
        }

        /**
         * The splash's foot: three insets with a flat gutter between them and white paper under
         * them. Above them and to the left of the first is the splash itself, as on the page.
         */
        fun insets() {
            fill(0, 270, 200, 300, PAPER)
            fill(20, 180, 196, 270, GUTTER)
            frame(20, 180, 75, 260)
            frame(80, 180, 135, 260)
            frame(140, 180, 195, 260)
        }
    }

    private fun box(x0: Int, y0: Int, x1: Int, y1: Int) = BubbleBox(
        x0.toFloat() / width,
        y0.toFloat() / height,
        (x1 - x0).toFloat() / width,
        (y1 - y0).toFloat() / height,
    )

    /** A balloon in each inset, and two over the splash above them. */
    private val lettering = listOf(
        box(30, 30, 90, 50),
        box(100, 90, 160, 110),
        box(30, 200, 65, 220),
        box(90, 190, 125, 210),
        box(150, 230, 185, 250),
    )

    private fun Page.panels(text: List<BubbleBox> = lettering) = FramedPanels.find(luma, width, height, text)

    private fun List<BubbleBox>.holding(x: Int, y: Int) = count {
        it.contains(x.toFloat() / width, y.toFloat() / height)
    }

    @Test
    fun `three insets laid over a splash are three panels`() {
        val page = Page(width, height)
        page.insets()

        val panels = page.panels()

        assertEquals(3, panels.size, "one per inset: $panels")
        // Each inset's own lines, not its neighbour's across the gutter: a side is one line.
        assertTrue(panels.none { it.contains(77f / width, 220f / height) }, "no frame bridges a gutter")
        assertEquals(1, panels.holding(47, 220), "the left inset")
        assertEquals(1, panels.holding(107, 220), "the middle one")
        assertEquals(1, panels.holding(167, 220), "the right one")
        assertEquals(0, panels.holding(60, 40), "and nothing up in the splash")
    }

    @Test
    fun `a balloon laid across a frame's line does not hide the frame`() {
        // The case on the reported page: a balloon sits over the top of an inset and hides two
        // thirds of that side under paper. Its box counts as covered.
        val page = Page(width, height)
        page.insets()
        page.fill(88, 170, 120, 196, PAPER)
        val text = lettering + box(88, 170, 120, 196)

        assertEquals(3, page.panels(text).size)
    }

    @Test
    fun `one frame alone is not enough`() {
        // One frame changes nothing about the order of what is inside it, and a page with one
        // is more likely to have found a window than a panel.
        val page = Page(width, height)
        page.fill(0, 270, 200, 300, PAPER)
        page.fill(20, 180, 196, 270, GUTTER)
        page.frame(80, 180, 135, 260)

        assertEquals(emptyList<BubbleBox>(), page.panels())
    }

    @Test
    fun `rectangles inside the art are not panels`() {
        // Windows, screens, doors: straight dark lines on four sides, and more art all around.
        val page = Page(width, height)
        page.frame(30, 60, 90, 140)
        page.frame(110, 60, 170, 140)
        val text = listOf(box(40, 80, 80, 100), box(120, 80, 160, 100))

        assertEquals(emptyList<BubbleBox>(), page.panels(text))
    }

    @Test
    fun `a caption's own border is not a panel`() {
        // Boxed captions on white paper are rectangles of line with flat tone all round; what
        // gives them away is that each is almost entirely its own text box.
        val page = Page(width, height)
        page.fill(0, 150, 200, 300, PAPER)
        page.frame(20, 180, 90, 230)
        page.frame(110, 180, 180, 230)
        val text = listOf(box(22, 182, 88, 228), box(112, 182, 178, 228))

        assertEquals(emptyList<BubbleBox>(), page.panels(text))
    }

    @Test
    fun `rectangles filled with ink are not panels`() {
        // A black band, or a dark mass of the art, cut into rectangles: straight lines, flat
        // paper all round, a balloon in each - and nothing drawn inside but ink.
        val page = Page(width, height)
        page.fill(0, 150, 200, 300, PAPER)
        for ((x0, x1) in listOf(20 to 90, 110 to 180)) {
            page.frame(x0, 180, x1, 260)
            for (y in 182 until 258) {
                for (x in x0 + 2 until x1 - 2) {
                    // Three pixels in four are ink, the fourth breaks every run so the inside
                    // holds no line of its own.
                    if ((x + y) % 4 != 0) page.luma[y * width + x] = INK
                }
            }
        }
        page.fill(40, 200, 70, 215, PAPER)
        page.fill(130, 200, 160, 215, PAPER)
        val text = listOf(box(40, 200, 70, 215), box(130, 200, 160, 215))

        assertEquals(emptyList<BubbleBox>(), page.panels(text))
    }

    @Test
    fun `without lettering there is nothing to look for`() {
        val page = Page(width, height)
        page.insets()

        assertEquals(emptyList<BubbleBox>(), page.panels(emptyList()))
    }

    @Test
    fun `a page it cannot read is no page at all`() {
        assertEquals(emptyList<BubbleBox>(), FramedPanels.find(IntArray(0), 0, 0, lettering))
        assertEquals(emptyList<BubbleBox>(), FramedPanels.find(IntArray(9), width, height, lettering))
    }

    private companion object {
        const val ART_A = 100
        const val ART_B = 200
        const val INSET_ART = 150
        const val INK = 5
        const val GUTTER = 90
        const val PAPER = 240
        const val LINE = 2
    }
}
