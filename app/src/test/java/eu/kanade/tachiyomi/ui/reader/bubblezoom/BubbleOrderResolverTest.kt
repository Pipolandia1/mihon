package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
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
