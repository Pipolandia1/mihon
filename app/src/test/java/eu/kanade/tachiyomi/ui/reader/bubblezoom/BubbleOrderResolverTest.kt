package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading order, built out of the balloons' own layout.
 *
 * Each fixture is a page drawn in numbers: the name of every balloon says where it is, and the
 * expected order is the order a person reads them in. The page is a unit square, so a "row gap"
 * of 0.02 is two percent of the page's height.
 */
class BubbleOrderResolverTest {

    private val named = mutableMapOf<String, BubbleDetection>()

    /** A balloon at ([x], [y]) of the given size, remembered by name so results read as words. */
    private fun at(name: String, x: Float, y: Float, w: Float = 0.2f, h: Float = 0.08f): BubbleDetection {
        val bubble = BubbleDetection(BubbleBox(x, y, w, h), 0.9f, BubbleType.TEXT)
        named[name] = bubble
        return bubble
    }

    private fun List<BubbleDetection>.names() = map { bubble -> named.entries.first { it.value === bubble }.key }

    /** A panel the page's gutters produced: structure, never a stop. */
    private fun panel(x: Float, y: Float, w: Float, h: Float) =
        BubbleDetection(BubbleBox(x, y, w, h), 1f, BubbleType.PANEL)

    /** An inset drawn as a frame over a splash: structure too, read differently. */
    private fun frame(x: Float, y: Float, w: Float, h: Float) =
        BubbleDetection(BubbleBox(x, y, w, h), 1f, BubbleType.FRAME)

    @Test
    fun `balloons stacked in one panel are read down the page`() {
        val page = listOf(at("bottom", 0.2f, 0.60f), at("top", 0.2f, 0.20f), at("middle", 0.2f, 0.40f))

        val order = BubbleOrderResolver.order(page, rightToLeft = false)

        assertEquals(listOf("top", "middle", "bottom"), order.names())
    }

    @Test
    fun `two panels side by side are read in the reader's direction`() {
        // One row, a wide gutter down the middle, two balloons each side. They are close
        // together the way balloons in one panel are: far enough apart and this becomes a
        // picture the boxes cannot tell from a two-by-two grid of panels, which is read the
        // other way round and is the documented limit of ordering without panels.
        val page = listOf(
            at("right-top", 0.60f, 0.10f),
            at("left-top", 0.10f, 0.10f),
            at("left-bottom", 0.10f, 0.16f),
            at("right-bottom", 0.60f, 0.16f),
        )

        assertEquals(
            listOf("left-top", "left-bottom", "right-top", "right-bottom"),
            BubbleOrderResolver.order(page, rightToLeft = false).names(),
        )
        assertEquals(
            listOf("right-top", "right-bottom", "left-top", "left-bottom"),
            BubbleOrderResolver.order(page, rightToLeft = true).names(),
        )
    }

    @Test
    fun `rows of panels come before what is inside them`() {
        // The layout that breaks a naive left-to-right sort: a page of two rows, two panels
        // each. Sorting by x alone would read the two left panels first, top and bottom.
        val page = listOf(
            at("row2-right", 0.60f, 0.60f),
            at("row1-left", 0.10f, 0.10f),
            at("row2-left", 0.10f, 0.60f),
            at("row1-right", 0.60f, 0.10f),
        )

        val order = BubbleOrderResolver.order(page, rightToLeft = false)

        assertEquals(listOf("row1-left", "row1-right", "row2-left", "row2-right"), order.names())
    }

    @Test
    fun `a row is found even when its panels are not aligned`() {
        // Real pages are not grids: a tall panel beside two short ones still reads as one row,
        // because what separates rows is a strip of page no balloon crosses.
        val page = listOf(
            at("tall-right", 0.60f, 0.12f, h = 0.30f),
            at("short-left-top", 0.10f, 0.10f),
            at("short-left-bottom", 0.10f, 0.30f),
            at("next-row", 0.10f, 0.70f),
        )

        val order = BubbleOrderResolver.order(page, rightToLeft = false)

        assertEquals(
            listOf("short-left-top", "short-left-bottom", "tall-right", "next-row"),
            order.names(),
        )
    }

    @Test
    fun `balloons that overlap in both axes are read down the page, then across`() {
        // No cut is possible: one cluster inside one panel. Down first, and the direction only
        // decides between two at the same height.
        val page = listOf(
            at("lower", 0.30f, 0.30f, w = 0.4f, h = 0.2f),
            at("upper-right", 0.35f, 0.10f, w = 0.3f, h = 0.25f),
            at("upper-left", 0.10f, 0.10f, w = 0.3f, h = 0.25f),
        )

        assertEquals(
            listOf("upper-left", "upper-right", "lower"),
            BubbleOrderResolver.order(page, rightToLeft = false).names(),
        )
        assertEquals(
            listOf("upper-right", "upper-left", "lower"),
            BubbleOrderResolver.order(page, rightToLeft = true).names(),
        )
    }

    @Test
    fun `the page measured on the device is read down instead of jumping about`() {
        // The real sequence that started this: the card went 507, 1101, 508, 760, 508, 1382 down
        // a 2026-pixel page — down, back to the top, down, back up. These are those balloons,
        // as fractions of that page, in the order the model returned them.
        val page = listOf(
            at("top-middle", 0.40f, 0.068f),
            at("mid-left", 0.30f, 0.361f),
            at("top-right", 0.50f, 0.069f),
            at("upper-mid", 0.50f, 0.193f),
            at("top-left", 0.18f, 0.069f),
            at("lower", 0.47f, 0.500f),
        )

        val order = BubbleOrderResolver.order(page, rightToLeft = false)
        val tops = order.map { it.box.y }

        assertEquals(tops.sorted(), tops.sortedBy { it }, "sanity")
        assertEquals(
            listOf("top-left", "top-middle", "top-right", "upper-mid", "mid-left", "lower"),
            order.names(),
        )
        // The property the reader actually feels: never backwards up the page.
        for (i in 1 until tops.size) {
            assert(tops[i] >= tops[i - 1] - 0.02f) { "step $i goes back up the page" }
        }
    }

    // --- with the page's own panels, amendment E8 ------------------------------------------

    @Test
    fun `two panels side by side are read one at a time, not across both`() {
        // The measured difference against Play Books, and the case the boxes alone cannot
        // settle: four balloons in two panels, each one level with its neighbour. Without the
        // panels this is the same picture as one wide panel and is read across.
        val page = listOf(
            at("left-top", 0.06f, 0.10f, w = 0.35f),
            at("right-top", 0.55f, 0.10f, w = 0.35f),
            at("left-bottom", 0.06f, 0.30f, w = 0.35f),
            at("right-bottom", 0.55f, 0.30f, w = 0.35f),
        )
        val panels = listOf(panel(0f, 0f, 0.48f, 1f), panel(0.5f, 0f, 0.5f, 1f))

        assertEquals(
            listOf("left-top", "left-bottom", "right-top", "right-bottom"),
            BubbleOrderResolver.order(page + panels, rightToLeft = false).names(),
        )
        assertEquals(
            listOf("right-top", "right-bottom", "left-top", "left-bottom"),
            BubbleOrderResolver.order(page + panels, rightToLeft = true).names(),
        )
    }

    @Test
    fun `a row of framed insets under a splash is read one inset at a time`() {
        // Amendment E15, the page a reader reported, as the model and the frame finder saw it:
        // six balloons over a splash, then three insets along its foot whose balloons sit at
        // every height. By the balloons alone the row is read across all three insets.
        val page = listOf(
            at("1", 0.165f, 0.066f, w = 0.179f, h = 0.074f),
            at("2", 0.403f, 0.113f, w = 0.168f, h = 0.059f),
            at("3", 0.552f, 0.395f, w = 0.151f, h = 0.062f),
            at("4", 0.340f, 0.488f, w = 0.142f, h = 0.058f),
            at("5", 0.148f, 0.533f, w = 0.162f, h = 0.058f),
            at("6", 0.119f, 0.590f, w = 0.161f, h = 0.057f),
            at("middle-1", 0.433f, 0.644f, w = 0.099f, h = 0.036f),
            at("right-1", 0.649f, 0.626f, w = 0.129f, h = 0.040f),
            at("middle-2", 0.498f, 0.670f, w = 0.124f, h = 0.068f),
            at("right-2", 0.725f, 0.755f, w = 0.144f, h = 0.060f),
            at("left-1", 0.108f, 0.781f, w = 0.165f, h = 0.052f),
            at("left-2", 0.167f, 0.833f, w = 0.183f, h = 0.045f),
            at("middle-3", 0.375f, 0.817f, w = 0.208f, h = 0.062f),
            at("right-3", 0.657f, 0.838f, w = 0.218f, h = 0.039f),
        )
        val insets = listOf(
            frame(0.374f, 0.623f, 0.247f, 0.255f),
            frame(0.100f, 0.623f, 0.238f, 0.254f),
            frame(0.641f, 0.623f, 0.249f, 0.254f),
        )

        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "left-1", "left-2", "middle-1", "middle-2", "middle-3") +
                listOf("right-1", "right-2", "right-3"),
            BubbleOrderResolver.order(page + insets, rightToLeft = false).names(),
        )
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "middle-1", "right-1", "middle-2", "right-2", "left-1") +
                listOf("left-2", "middle-3", "right-3"),
            BubbleOrderResolver.order(page, rightToLeft = false).names(),
            "without them, across the row: what the reader saw",
        )
    }

    @Test
    fun `framed insets do not pull a splash balloon level with them out of place`() {
        // Why a frame is not read as a gutter's panel: the splash balloons "5" and "6" reach down
        // level with the insets, and read as panels the insets and they fall into one row, split
        // by columns - so "4", above and to the right, is read after the whole left inset.
        val page = listOf(
            at("4", 0.340f, 0.488f, w = 0.142f, h = 0.058f),
            at("5", 0.148f, 0.533f, w = 0.162f, h = 0.058f),
            at("6", 0.119f, 0.590f, w = 0.161f, h = 0.057f),
            at("left-1", 0.108f, 0.781f, w = 0.165f, h = 0.052f),
            at("middle-1", 0.433f, 0.644f, w = 0.099f, h = 0.036f),
        )
        val boxes = listOf(BubbleBox(0.100f, 0.623f, 0.238f, 0.254f), BubbleBox(0.374f, 0.623f, 0.247f, 0.255f))

        assertEquals(
            listOf("4", "5", "6", "left-1", "middle-1"),
            BubbleOrderResolver.order(page + boxes.map { frame(it.x, it.y, it.width, it.height) }, false).names(),
        )
        assertEquals(
            "4",
            BubbleOrderResolver.order(page + boxes.map { panel(it.x, it.y, it.width, it.height) }, false).names()[3],
            "the same rectangles as gutter panels",
        )
    }

    @Test
    fun `without the panels the same page is read across, which is what they are for`() {
        val page = listOf(
            at("left-top", 0.06f, 0.10f, w = 0.35f),
            at("right-top", 0.55f, 0.10f, w = 0.35f),
            at("left-bottom", 0.06f, 0.30f, w = 0.35f),
            at("right-bottom", 0.55f, 0.30f, w = 0.35f),
        )

        assertEquals(
            listOf("left-top", "right-top", "left-bottom", "right-bottom"),
            BubbleOrderResolver.order(page, rightToLeft = false).names(),
        )
    }

    @Test
    fun `a panel is never a stop on the walk`() {
        val page = listOf(at("only", 0.1f, 0.1f))
        val panels = listOf(panel(0f, 0f, 1f, 0.5f), panel(0f, 0.5f, 1f, 0.5f))

        val order = BubbleOrderResolver.order(page + panels, rightToLeft = false)

        assertEquals(1, order.size)
        assertTrue(order.none { it.type == BubbleType.PANEL })
    }

    @Test
    fun `a balloon belongs to the smallest panel that holds it`() {
        // A page divided into rows and then into panels leaves both rectangles containing the
        // balloon; the panel is the one that says where to read it.
        val page = listOf(
            at("in-right-panel", 0.60f, 0.12f, w = 0.3f),
            at("in-left-panel", 0.10f, 0.20f, w = 0.3f),
        )
        val panels = listOf(
            panel(0f, 0f, 1f, 0.5f),
            panel(0f, 0f, 0.48f, 0.5f),
            panel(0.5f, 0f, 0.5f, 0.5f),
        )

        assertEquals(
            listOf("in-left-panel", "in-right-panel"),
            BubbleOrderResolver.order(page + panels, rightToLeft = false).names(),
        )
    }

    @Test
    fun `a caption over the gutter belongs to no panel and is read where it lies`() {
        val page = listOf(
            at("in-lower-panel", 0.10f, 0.70f, w = 0.3f),
            at("over-the-gutter", 0.10f, 0.47f, w = 0.3f, h = 0.06f),
            at("in-upper-panel", 0.10f, 0.10f, w = 0.3f),
        )
        val panels = listOf(panel(0f, 0f, 1f, 0.45f), panel(0f, 0.55f, 1f, 0.45f))

        assertEquals(
            listOf("in-upper-panel", "over-the-gutter", "in-lower-panel"),
            BubbleOrderResolver.order(page + panels, rightToLeft = false).names(),
        )
    }

    @Test
    fun `panels with nothing in them change nothing`() {
        val page = listOf(at("top", 0.1f, 0.1f), at("bottom", 0.1f, 0.6f))
        val empty = listOf(panel(0.8f, 0.8f, 0.15f, 0.15f))

        assertEquals(
            listOf("top", "bottom"),
            BubbleOrderResolver.order(page + empty, rightToLeft = false).names(),
            "no balloon is in that panel, so the balloons decide as they did before",
        )
    }

    @Test
    fun `nothing, one, and already-ordered pages come back unharmed`() {
        assertEquals(emptyList<BubbleDetection>(), BubbleOrderResolver.order(emptyList(), false))

        val single = listOf(at("only", 0.4f, 0.4f))
        assertEquals(single, BubbleOrderResolver.order(single, false))

        val ordered = listOf(at("a", 0.1f, 0.1f), at("b", 0.1f, 0.4f), at("c", 0.1f, 0.7f))
        assertEquals(listOf("a", "b", "c"), BubbleOrderResolver.order(ordered, false).names())
    }

    @Test
    fun `every balloon comes back exactly once, however tangled the page`() {
        // The one property a recursive cut can quietly break: dropping or repeating a group.
        val page = (0 until 24).map { i ->
            at("b$i", x = (i % 5) * 0.19f, y = (i / 5) * 0.16f, w = 0.17f, h = 0.13f)
        }

        for (direction in listOf(false, true)) {
            val order = BubbleOrderResolver.order(page, direction)
            assertEquals(page.size, order.size, "size changed")
            assertEquals(page.toSet(), order.toSet(), "a balloon was lost or duplicated")
        }
    }
}
