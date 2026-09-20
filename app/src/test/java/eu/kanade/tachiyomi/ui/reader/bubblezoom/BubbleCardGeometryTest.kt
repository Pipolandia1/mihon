package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The canonical figures throughout are the emulator's: a 1600x2400 page in a 1080x2400
 * viewport, and debug box 0, `BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)`.
 */
class BubbleCardGeometryTest {

    private val box0 = BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)
    private val padded = box0.padded(BubbleZoomConfig.FOCUS_PADDING)

    @Test
    fun `the card is framed at exactly the camera's target scale`() {
        // Why it matters: the tile resolution the image view serves is a function of scale, so
        // framing the card identically to the P4 camera is what makes the fallback invisible
        // and what makes the two equally sharp.
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = padded.width * 1600f,
            paddedHeightSourcePx = padded.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
        )

        assertEquals(1.4161f, scale, 1e-3f)
    }

    @Test
    fun `the narrower axis decides, so a tall bubble cannot overflow`() {
        // Landscape: the usable height collapses to 1080 and the height must win.
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = padded.width * 1600f,
            paddedHeightSourcePx = padded.height * 2400f,
            viewportWidthPx = 2400f,
            viewportHeightPx = 1080f,
        )

        assertEquals(1.378f, scale, 1e-2f)
        assertTrue(scale * padded.height * 2400f <= 1080f, "card is taller than the viewport")
    }

    @Test
    fun `a card centred on an interior bubble is left where it is`() {
        val rect = BubbleCardGeometry.place(
            cardWidthPx = 400f,
            cardHeightPx = 400f,
            bubbleCentreXPx = 540f,
            bubbleCentreYPx = 1200f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            marginPx = 12f,
        )

        assertEquals(340f, rect.left)
        assertEquals(1000f, rect.top)
        assertEquals(400f, rect.width)
    }

    @Test
    fun `a card against an edge shifts inward at full size instead of clipping`() {
        val rect = BubbleCardGeometry.place(
            cardWidthPx = 400f,
            cardHeightPx = 400f,
            bubbleCentreXPx = 30f,
            bubbleCentreYPx = 30f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            marginPx = 12f,
        )

        assertEquals(12f, rect.left)
        assertEquals(12f, rect.top)
        assertEquals(400f, rect.width, "the card shrank instead of shifting")
        assertEquals(400f, rect.height, "the card shrank instead of shifting")
    }

    @Test
    fun `a card against the far edge shifts the other way`() {
        val rect = BubbleCardGeometry.place(
            cardWidthPx = 400f,
            cardHeightPx = 400f,
            bubbleCentreXPx = 1070f,
            bubbleCentreYPx = 2390f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            marginPx = 12f,
        )

        assertEquals(668f, rect.left)
        assertEquals(1988f, rect.top)
        assertEquals(1068f, rect.right)
        assertEquals(2388f, rect.bottom)
    }

    @Test
    fun `a card wider than the viewport is centred rather than pushed off one side`() {
        val rect = BubbleCardGeometry.place(
            cardWidthPx = 1200f,
            cardHeightPx = 400f,
            bubbleCentreXPx = 100f,
            bubbleCentreYPx = 1200f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            marginPx = 12f,
        )

        assertEquals(-60f, rect.left)
        assertEquals(1140f, rect.right)
    }

    @Test
    fun `the magnification band refuses a card that adds nothing and one that adds only size`() {
        assertTrue(BubbleCardGeometry.magnificationAllows(cardScale = 1.4161f, currentViewScale = 0.675f))

        // Barely larger than what is already on screen: a pointless frame.
        assertFalse(BubbleCardGeometry.magnificationAllows(cardScale = 0.70f, currentViewScale = 0.675f))
        // Past the band the camera reaches a finer tile level that a frozen capture cannot.
        assertFalse(BubbleCardGeometry.magnificationAllows(cardScale = 2.0f, currentViewScale = 0.675f))
        // Degenerate input is a refusal, never a division by zero.
        assertFalse(BubbleCardGeometry.magnificationAllows(cardScale = 1.4f, currentViewScale = 0f))
        assertFalse(BubbleCardGeometry.magnificationAllows(cardScale = 0f, currentViewScale = 0.675f))
    }

    @Test
    fun `a bubble only partly on screen refuses a card`() {
        val viewportW = 1080f
        val viewportH = 2400f

        assertTrue(
            BubbleCardGeometry.fullyVisible(CardRect(10f, 10f, 500f, 500f), viewportW, viewportH),
        )
        assertFalse(
            BubbleCardGeometry.fullyVisible(CardRect(-1f, 10f, 500f, 500f), viewportW, viewportH),
            "a bubble scrolled off the left must refuse",
        )
        assertFalse(
            BubbleCardGeometry.fullyVisible(CardRect(10f, 10f, 1081f, 500f), viewportW, viewportH),
            "a bubble scrolled off the right must refuse",
        )
        assertFalse(
            BubbleCardGeometry.fullyVisible(CardRect(10f, 10f, 10f, 500f), viewportW, viewportH),
            "an empty rectangle must refuse",
        )
    }

    @Test
    fun `every debug box produces a card that fits the portrait viewport`() {
        for (box in DEBUG_BOXES) {
            val p = box.padded(BubbleZoomConfig.FOCUS_PADDING)
            val scale = BubbleCardGeometry.cardScale(
                paddedWidthSourcePx = p.width * 1600f,
                paddedHeightSourcePx = p.height * 2400f,
                viewportWidthPx = 1080f,
                viewportHeightPx = 2400f,
            )
            val w = p.width * 1600f * scale
            val h = p.height * 2400f * scale

            assertTrue(w <= 1080f, "card wider than the viewport for $box")
            assertTrue(h <= 2400f, "card taller than the viewport for $box")
            assertTrue(
                BubbleCardGeometry.magnificationAllows(scale, 0.675f),
                "the debug box $box would fall back to the camera at fit scale",
            )
        }
    }

    @Test
    fun `the silhouette grows by what the page says, not by what the mask happens to measure`() {
        // 4.8 source pixels of outline on a 1600 wide page, seen through a mask whose long side
        // is 192 across a padded box 470.4 source pixels tall, is 1.96 mask pixels; grown by
        // half again, three passes. That is the number validated against all eighteen bubbles
        // of the synthetic test pages.
        val passes = BubbleCardGeometry.outlineGrowPasses(
            maskLongSidePx = BubbleZoomConfig.MASK_LONG_SIDE_PX,
            paddedLongSourcePx = padded.height * 2400f,
            pageWidthSourcePx = 1600f,
        )

        assertEquals(3, passes)
    }

    @Test
    fun `a larger bubble needs less growing, because the same pen drew it`() {
        // The card is always framed to fill the same share of the viewport, so a small bubble
        // is magnified harder and its outline lands THICKER in the mask. Sizing the growth off
        // the mask's own dimensions, as the first attempt did, returns the same answer for both
        // of these - which is exactly the bug this replaced.
        val small = BubbleCardGeometry.outlineGrowPasses(192, 235f, 1600f)
        val large = BubbleCardGeometry.outlineGrowPasses(192, 941f, 1600f)

        assertEquals(6, small)
        assertEquals(1, large)
    }

    @Test
    fun `growing is clamped at both ends so a nonsense measurement cannot square off the shape`() {
        assertEquals(1, BubbleCardGeometry.outlineGrowPasses(192, 0f, 1600f))
        assertEquals(1, BubbleCardGeometry.outlineGrowPasses(192, 470f, 0f))
        assertEquals(1, BubbleCardGeometry.outlineGrowPasses(0, 470f, 1600f))
        assertEquals(
            BubbleZoomConfig.MASK_MAX_GROW_PASSES,
            BubbleCardGeometry.outlineGrowPasses(192, 40f, 1600f),
        )
    }
}
