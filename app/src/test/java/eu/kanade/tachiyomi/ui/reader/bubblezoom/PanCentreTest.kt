package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Bringing a balloon on screen when the page is zoomed past it. Amendment E11.
 *
 * The figures are a double-page spread as the reader sees one: 2000x1537 source pixels in a
 * 1080x2400 viewport at a scale that fills the height, so about a third of the width is off
 * screen at a time and the page has to come sideways to the balloon.
 */
class PanCentreTest {

    private val sourceWidth = 2000f
    private val sourceHeight = 1537f
    private val viewportWidth = 1080f
    private val viewportHeight = 2400f

    /** Fills the viewport's height: 2400 / 1537, which leaves the width overflowing. */
    private val scale = viewportHeight / sourceHeight

    /** Half the page's visible width, in source pixels. */
    private val halfVisible = viewportWidth / scale / 2f

    private fun centre(box: BubbleBox, atX: Float, atY: Float = sourceHeight / 2f) =
        BubbleCardGeometry.panCentre(
            box = box,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
            scale = scale,
            centreXSourcePx = atX,
            centreYSourcePx = atY,
            marginPx = 12f,
        )

    @Test
    fun `a balloon already on screen does not move the page`() {
        val middle = BubbleBox(0.48f, 0.4f, 0.04f, 0.05f)

        assertNull(centre(middle, atX = sourceWidth / 2f))
    }

    @Test
    fun `a balloon off to the right brings the page sideways, and no further than it must`() {
        // Far right of the spread, with the reader looking at the middle.
        val box = BubbleBox(0.80f, 0.4f, 0.12f, 0.06f)

        val moved = centre(box, atX = sourceWidth / 2f)!!

        val right = box.right * sourceWidth
        assertEquals(right + 12f / scale - halfVisible, moved.x, 1f, "the balloon's right edge is just inside")
        assertEquals(sourceHeight / 2f, moved.y, 1f, "and nothing moved up or down")
    }

    @Test
    fun `a balloon off to the left brings it back the other way`() {
        val box = BubbleBox(0.02f, 0.4f, 0.10f, 0.06f)

        val moved = centre(box, atX = sourceWidth * 0.75f)!!

        val left = box.x * sourceWidth
        assertEquals(left - 12f / scale + halfVisible, moved.x, 1f)
    }

    @Test
    fun `a balloon wider than the viewport is centred, since it cannot be shown whole`() {
        // A caption running most of the way across a spread. There is no centre that shows all
        // of it, so the reader gets its middle rather than one of its ends.
        val box = BubbleBox(0.1f, 0.4f, 0.8f, 0.05f)

        val moved = centre(box, atX = sourceWidth * 0.2f)!!

        assertEquals((box.x + box.width / 2f) * sourceWidth, moved.x, 1f)
    }

    @Test
    fun `a page that fits the viewport never moves`() {
        // An ordinary portrait page at fit: everything is already on screen, whatever the
        // balloon's corner of it, and moving would be a bug the reader would see on every step.
        val corner = BubbleBox(0.85f, 0.9f, 0.1f, 0.06f)

        val moved = BubbleCardGeometry.panCentre(
            box = corner,
            sourceWidth = 1280f,
            sourceHeight = 1966f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            scale = 1080f / 1280f,
            centreXSourcePx = 640f,
            centreYSourcePx = 983f,
            marginPx = 12f,
        )

        assertNull(moved)
    }

    @Test
    fun `a balloon below the fold moves the page down, not sideways`() {
        // The same rule on the other axis: a tall page zoomed past its own bottom.
        val box = BubbleBox(0.4f, 0.90f, 0.1f, 0.06f)

        val moved = BubbleCardGeometry.panCentre(
            box = box,
            sourceWidth = 1280f,
            sourceHeight = 1966f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 1200f,
            scale = 1080f / 1280f,
            centreXSourcePx = 640f,
            centreYSourcePx = 600f,
            marginPx = 12f,
        )!!

        assertEquals(640f, moved.x, 1f, "nothing moved sideways")
        assertEquals(box.bottom * 1966f + 12f / (1080f / 1280f) - 1200f / (1080f / 1280f) / 2f, moved.y, 1f)
    }

    @Test
    fun `nonsense is not a move`() {
        // A view not laid out yet, or a page with no size: the reader is mid-rotation or
        // mid-load, and the only safe answer is to leave the page alone.
        val box = BubbleBox(0.1f, 0.1f, 0.1f, 0.1f)

        fun attempt(width: Float, height: Float, viewport: Float, zoom: Float) =
            BubbleCardGeometry.panCentre(
                box = box,
                sourceWidth = width,
                sourceHeight = height,
                viewportWidthPx = viewport,
                viewportHeightPx = viewport,
                scale = zoom,
                centreXSourcePx = 0f,
                centreYSourcePx = 0f,
                marginPx = 12f,
            )

        assertNull(attempt(0f, 0f, viewportWidth, scale), "a page with no size")
        assertNull(attempt(sourceWidth, sourceHeight, 0f, scale), "a view with no size")
        assertNull(attempt(sourceWidth, sourceHeight, viewportWidth, 0f), "no scale yet")
    }
}
