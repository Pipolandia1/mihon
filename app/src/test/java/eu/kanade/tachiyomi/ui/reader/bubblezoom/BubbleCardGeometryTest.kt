package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `a balloon too wide for the magnification is held to the viewport fraction`() {
        // Debug box 0 at 2.8x of the fit scale would be 1.89; 60 % of the width is less, and
        // the fraction is the ceiling.
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = padded.width * 1600f,
            paddedHeightSourcePx = padded.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = 0.675f,
        )

        assertEquals(1.4161f, scale, 1e-3f)
    }

    @Test
    fun `a small balloon is shown at exactly CARD_MAGNIFICATION, not framed to fill the screen`() {
        // A text box 5 % wide: framing it to 60 % of the viewport would be 6.5x, which is what
        // the P9 verification saw asked of every real balloon. Play Books shows about 2.8x.
        val small = BubbleBox(0.4f, 0.4f, 0.05f, 0.03f).padded(BubbleZoomConfig.FOCUS_PADDING)
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = small.width * 1600f,
            paddedHeightSourcePx = small.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = 0.675f,
        )

        assertEquals(BubbleZoomConfig.CARD_MAGNIFICATION * 0.675f, scale, 1e-4f)
    }

    @Test
    fun `an unknown view scale leaves only the viewport ceiling`() {
        val small = BubbleBox(0.4f, 0.4f, 0.05f, 0.03f).padded(BubbleZoomConfig.FOCUS_PADDING)
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = small.width * 1600f,
            paddedHeightSourcePx = small.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = 0f,
        )

        assertEquals(0.6f * 1080f / (small.width * 1600f), scale, 1e-3f)
    }

    @Test
    fun `the narrower axis decides, so a tall bubble cannot overflow`() {
        // Landscape: the usable height collapses to 1080 and the height must win.
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = padded.width * 1600f,
            paddedHeightSourcePx = padded.height * 2400f,
            viewportWidthPx = 2400f,
            viewportHeightPx = 1080f,
            // Above the magnification, so only the axes decide here.
            currentViewScale = 1f,
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
    fun `a region is the padded box in whole source pixels`() {
        val region = BubbleCardGeometry.regionOf(BubbleBox(0.1f, 0.2f, 0.3f, 0.4f), 1000, 2000)

        assertEquals(SourceRegion(100, 400, 400, 1200), region)
    }

    @Test
    fun `a region never leaves the image and is never empty`() {
        assertEquals(
            SourceRegion(900, 1800, 1000, 2000),
            BubbleCardGeometry.regionOf(BubbleBox(0.9f, 0.9f, 0.1f, 0.1f), 1000, 2000),
        )
        // A box thinner than a pixel still decodes one column, because a decoder throws on none.
        val hairline = BubbleCardGeometry.regionOf(BubbleBox(0.5f, 0.5f, 1e-4f, 0.1f), 100, 100)
        assertEquals(1, hairline?.width)
        assertEquals(null, BubbleCardGeometry.regionOf(BubbleBox(0.1f, 0.1f, 0.1f, 0.1f), 0, 100))
    }

    @Test
    fun `the sample size is the largest that keeps a source pixel per card pixel`() {
        assertEquals(2, BubbleCardGeometry.sampleSize(SourceRegion(0, 0, 800, 600), 300f, 200f))
        assertEquals(1, BubbleCardGeometry.sampleSize(SourceRegion(0, 0, 300, 200), 600f, 400f))
        assertEquals(32, BubbleCardGeometry.sampleSize(SourceRegion(0, 0, 5000, 5000), 100f, 100f))
    }

    @Test
    fun `the sample size doubles until the decode fits the pixel budget`() {
        // 3000 x 3000 for a 2000 x 2000 card would be 9 megapixels at sample 1.
        assertEquals(4, BubbleCardGeometry.sampleSize(SourceRegion(0, 0, 3000, 3000), 2000f, 2000f))
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
                currentViewScale = 0.675f,
            )
            val w = p.width * 1600f * scale
            val h = p.height * 2400f * scale

            assertTrue(w <= 1080f, "card wider than the viewport for $box")
            assertTrue(h <= 2400f, "card taller than the viewport for $box")
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
