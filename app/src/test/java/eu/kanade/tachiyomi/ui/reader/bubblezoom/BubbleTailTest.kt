package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Following a balloon's tail out of its capture. Amendment E18.
 *
 * The page is a scene drawn in fractions of itself — white balloons on a grey panel — and every
 * capture is rasterised from it the way the overlay captures the screen: the region asked for,
 * at the first capture's density. So the trace, the widening and the guards all run for real,
 * and only the pixels are made up.
 */
class BubbleTailTest {

    /** A white shape on the page. */
    private fun interface Shape {
        fun covers(x: Float, y: Float): Boolean
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = Shape { x, y ->
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        dx * dx + dy * dy <= 1f
    }

    /** A wedge pointing down from ([cx], [top]), [half] wide on each side, to a tip at [tip]. */
    private fun tailDown(cx: Float, top: Float, half: Float, tip: Float) = Shape { x, y ->
        y in top..tip && abs(x - cx) <= half * (tip - y) / (tip - top)
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) = Shape { x, y ->
        x in left..right && y in top..bottom
    }

    /** White [shapes], and [tinted] ones as bright as paper but of another colour. */
    private class Scene(val shapes: List<Shape>, val tinted: List<Shape> = emptyList()) {
        var captures = 0
    }

    /** The balloon every test starts from: its box is what the detector returned. */
    private val balloon = ellipse(0.5f, 0.3f, 0.1f, 0.06f)
    private val box = BubbleBox(0.4f, 0.24f, 0.2f, 0.12f)

    private fun rasterise(scene: Scene, search: BubbleBox, longest: Int): Pair<IntArray, Pair<Int, Int>> {
        val factor = minOf(
            FIRST_LONG_SIDE / maxOf(firstSearch.width, firstSearch.height),
            longest / maxOf(search.width, search.height),
        )
        val width = (search.width * factor).toInt().coerceAtLeast(3)
        val height = (search.height * factor).toInt().coerceAtLeast(3)
        val pixels = IntArray(width * height) { index ->
            val x = search.x + (index % width + 0.5f) / width * search.width
            val y = search.y + (index / width + 0.5f) / height * search.height
            when {
                scene.shapes.any { it.covers(x, y) } -> PAPER
                scene.tinted.any { it.covers(x, y) } -> SNOW
                else -> PANEL
            }
        }
        return pixels to (width to height)
    }

    private val firstSearch = box.padded(BubbleZoomConfig.MASK_SEARCH_PADDING)

    private fun trace(scene: Scene, search: BubbleBox, aroundBox: Boolean, longest: Int): BubbleTail.Trace? {
        scene.captures++
        val (pixels, size) = rasterise(scene, search, longest)
        val (width, height) = size
        val within = BubbleCardGeometry.boxWithin(box, search, width, height)
        val surface = BubbleMask.surface(pixels, width, height, within, aroundBox) ?: return null
        return BubbleTail.Trace(search, surface, width, height, pixels)
    }

    private fun follow(scene: Scene): Pair<BubbleTail.Trace, BubbleTail.Followed> {
        val first = trace(scene, firstSearch, aroundBox = false, longest = FIRST_LONG_SIDE)!!
        scene.captures = 0
        val followed = BubbleTail.follow(box, first, PAGE, PAGE) { region ->
            trace(scene, region, aroundBox = true, longest = BubbleZoomConfig.TAIL_MASK_MAX_LONG_SIDE_PX)
        }
        return first to followed
    }

    /** The lowest row of page [trace]'s surface reaches, as a fraction of the page. */
    private fun lowest(trace: BubbleTail.Trace): Float {
        var row = -1
        for (y in 0 until trace.height) {
            for (x in 0 until trace.width) if (trace.surface[y * trace.width + x]) row = y
        }
        return trace.search.y + (row + 1f) / trace.height * trace.search.height
    }

    @Test
    fun `a balloon with no tail is left as it was, and nothing more is captured`() {
        val scene = Scene(listOf(balloon))

        val (first, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.CLOSED, followed.outcome)
        assertSame(first, followed.trace)
        assertEquals(0, scene.captures)
    }

    @Test
    fun `a long tail is followed to its tip`() {
        // The tip is at 0.65 of the page; the usual capture stops at 0.39.
        val scene = Scene(listOf(balloon, tailDown(0.5f, 0.35f, 0.012f, 0.65f)))

        val (first, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.FOLLOWED, followed.outcome)
        assertTrue(lowest(first) < 0.4f, "the first capture cut the tail off at ${lowest(first)}")
        assertTrue(lowest(followed.trace) > 0.63f, "the tail now reaches ${lowest(followed.trace)}")
        assertEquals(2, followed.extensions, "two widenings of the balloon's width reach 0.65")
    }

    @Test
    fun `a tail that runs into another balloon is not followed into it`() {
        // The two share a tone, so the fill would take the second balloon as the first one's
        // tail and the card would carry both.
        val below = ellipse(0.5f, 0.75f, 0.2f, 0.12f)
        val scene = Scene(listOf(balloon, tailDown(0.5f, 0.35f, 0.012f, 0.66f), below))

        val (first, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.REFUSED, followed.outcome)
        assertSame(first, followed.trace, "the balloon is shown as it always was")
    }

    @Test
    fun `a balloon that runs on across its side is not a tail`() {
        // A caption continuing under a panel's border: it leaves the capture across all of its
        // height, which no tail does.
        val scene = Scene(listOf(balloon, rect(0.5f, 0.25f, 0.9f, 0.35f)))

        val (first, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.WIDE, followed.outcome)
        assertSame(first, followed.trace)
        assertEquals(0, scene.captures)
    }

    @Test
    fun `a tail is followed even where the balloon runs on across another side`() {
        // A balloon breaking out of its panel into the paper of the gutter: the right side is
        // left as it was, and the tail below is still followed.
        val scene = Scene(listOf(balloon, rect(0.5f, 0.25f, 0.9f, 0.35f), tailDown(0.45f, 0.35f, 0.012f, 0.65f)))

        val (_, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.FOLLOWED, followed.outcome)
        assertTrue(lowest(followed.trace) > 0.63f, "the tail now reaches ${lowest(followed.trace)}")
    }

    @Test
    fun `a tail of another colour is not the balloon's`() {
        // As bright as the balloon, so the fill takes it; bluish, so it is snow and not tail.
        val scene = Scene(listOf(balloon), tinted = listOf(tailDown(0.5f, 0.35f, 0.012f, 0.65f)))

        val (first, followed) = follow(scene)

        assertEquals(BubbleTail.Outcome.REFUSED, followed.outcome)
        assertSame(first, followed.trace)
    }

    @Test
    fun `a trace that fails leaves the first one standing`() {
        val scene = Scene(listOf(balloon, tailDown(0.5f, 0.35f, 0.012f, 0.6f)))
        val first = trace(scene, firstSearch, aroundBox = false, longest = FIRST_LONG_SIDE)!!

        val followed = BubbleTail.follow(box, first, PAGE, PAGE) { null }

        assertEquals(BubbleTail.Outcome.REFUSED, followed.outcome)
        assertSame(first, followed.trace)
    }

    @Test
    fun `the page's own edge is not a tail`() {
        // A balloon cut by the bottom of the page touches its capture there, and there is no
        // more page to look at.
        val atFoot = BubbleBox(0.4f, 0.88f, 0.2f, 0.12f)
        val scene = Scene(listOf(ellipse(0.5f, 0.97f, 0.1f, 0.06f)))
        val search = atFoot.padded(BubbleZoomConfig.MASK_SEARCH_PADDING)
        val (pixels, size) = rasterise(scene, search, FIRST_LONG_SIDE)
        val (width, height) = size
        val surface = BubbleMask.surface(
            pixels,
            width,
            height,
            BubbleCardGeometry.boxWithin(atFoot, search, width, height),
        )!!
        val first = BubbleTail.Trace(search, surface, width, height, pixels)
        var asked = false

        val followed = BubbleTail.follow(atFoot, first, PAGE, PAGE) {
            asked = true
            null
        }

        assertEquals(BubbleTail.Outcome.CLOSED, followed.outcome)
        assertFalse(asked)
    }

    @Test
    fun `a capture is widened only towards the tail, by the balloon's longer side`() {
        val widened = BubbleTail.extended(firstSearch, box, setOf(BubbleTail.Side.BOTTOM), PAGE, PAGE)

        assertEquals(firstSearch.x, widened.x, 1e-5f)
        assertEquals(firstSearch.y, widened.y, 1e-5f)
        assertEquals(firstSearch.width, widened.width, 1e-5f)
        assertEquals(firstSearch.bottom + box.width, widened.bottom, 1e-5f)
    }

    @Test
    fun `a widened capture stops at the page`() {
        val nearTop = BubbleBox(0.37f, 0.1f, 0.26f, 0.18f)

        val widened = BubbleTail.extended(nearTop, box, setOf(BubbleTail.Side.LEFT, BubbleTail.Side.TOP), PAGE, PAGE)

        assertEquals(nearTop.x - box.width, widened.x, 1e-5f)
        assertEquals(0f, widened.y)
        assertEquals(nearTop.bottom, widened.bottom, 1e-5f)
    }

    private companion object {
        const val PAGE = 1000f
        const val FIRST_LONG_SIDE = 192
        const val PAPER = 0xFFFFFFFF.toInt()
        const val PANEL = 0xFF808080.toInt()

        /** Pale blue, near enough to paper in brightness for the fill to cross into it. */
        const val SNOW = 0xFFBFE0FF.toInt()
    }
}
