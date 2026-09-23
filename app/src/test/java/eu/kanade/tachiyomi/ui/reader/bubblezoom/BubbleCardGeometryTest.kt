package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The canonical figures throughout are the emulator's: a 1600x2400 page in a 1080x2400
 * viewport, and debug box 0, `BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)`.
 */
class BubbleCardGeometryTest {

    private val box0 = BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)

    /**
     * The padding the figures below were worked out at, written down rather than read from
     * [BubbleZoomConfig].
     *
     * These tests pin the arithmetic — which axis wins, how magnification is capped, how many
     * passes an outline needs — and not the padding of the day. Reading the constant made every
     * one of them fail when amendment E10 changed it, which said nothing about the arithmetic.
     */
    private val cardPadding = 0.20f
    private val padded = box0.padded(cardPadding)

    /** The magnification these figures were worked out at, for the same reason as the padding. */
    private val magnification = 2.8f

    /** And the viewport ceiling they were worked out at. */
    private val fraction = 0.60f

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
            magnification = magnification,
            widthFraction = fraction,
            heightFraction = fraction,
        )

        assertEquals(1.4161f, scale, 1e-3f)
    }

    @Test
    fun `a card is never smaller than the page it was lifted from`() {
        // A spread read zoomed: 2000x1537 filling a 1080x2400 viewport's height is a scale of
        // 1.56, and a credits block a quarter of the spread wide is already drawn larger than
        // the viewport ceiling allows. The ceiling asked for 0.75 - half the size the reader
        // was already seeing - and the card came out as a shrunken copy of what was under it.
        val block = BubbleBox(0.30f, 0.05f, 0.26f, 0.60f).padded(cardPadding)
        val pageScale = 2400f / 1537f

        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = block.width * 2000f,
            paddedHeightSourcePx = block.height * 1537f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = pageScale,
            magnification = magnification,
            widthFraction = 0.42f,
            heightFraction = 0.42f,
        )

        assertEquals(pageScale, scale, 1e-4f, "a card that shrinks its balloon is not a zoom")
    }

    @Test
    fun `a wide balloon is magnified as far as its ceiling allows, not held to a target under its own size`() {
        // Amendment E14. A joined balloon 44 % of a 1988-wide page, read fit to a 1080 viewport
        // (scale 0.54): held to 42 % of the viewport it asks for 0.52, the floor answers with
        // the page, and the widest balloon on the page is the one that gets no zoom. Held to
        // two thirds of the screen it lifts to the zoom that fits there - short of the 2x a
        // small balloon gets, but a zoom; held to the whole screen it would get the full 2x,
        // which is what the user saw and found too much.
        val wide = BubbleBox(0.33f, 0.05f, 0.40f, 0.14f).padded(cardPadding)
        val pageScale = 1080f / 1988f
        fun scaleHeldTo(fraction: Float) = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = wide.width * 1988f,
            paddedHeightSourcePx = wide.height * 3057f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = pageScale,
            magnification = 2.0f,
            widthFraction = fraction,
            heightFraction = fraction,
        )

        assertEquals(pageScale, scaleHeldTo(0.42f), 1e-4f, "held to a target under its own size: no zoom at all")
        val held = scaleHeldTo(0.65f)
        assertEquals(0.65f * 1080f / (wide.width * 1988f), held, 1e-4f, "held to two thirds: the zoom that fits there")
        assertTrue(held > 1.3f * pageScale, "which is a real zoom: ${held / pageScale}x")
        assertEquals(2.0f * pageScale, scaleHeldTo(0.92f), 1e-4f, "held to the screen: the full magnification")
    }

    @Test
    fun `a small balloon is shown at exactly CARD_MAGNIFICATION, not framed to fill the screen`() {
        // A text box 5 % wide: framing it to 60 % of the viewport would be 6.5x, which is what
        // the P9 verification saw asked of every real balloon. Play Books shows about 2.8x.
        val small = BubbleBox(0.4f, 0.4f, 0.05f, 0.03f).padded(cardPadding)
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = small.width * 1600f,
            paddedHeightSourcePx = small.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = 0.675f,
            magnification = magnification,
            widthFraction = fraction,
            heightFraction = fraction,
        )

        assertEquals(magnification * 0.675f, scale, 1e-4f)
    }

    @Test
    fun `an unknown view scale leaves only the viewport ceiling`() {
        val small = BubbleBox(0.4f, 0.4f, 0.05f, 0.03f).padded(cardPadding)
        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = small.width * 1600f,
            paddedHeightSourcePx = small.height * 2400f,
            viewportWidthPx = 1080f,
            viewportHeightPx = 2400f,
            currentViewScale = 0f,
            magnification = magnification,
            widthFraction = fraction,
            heightFraction = fraction,
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
            magnification = magnification,
            widthFraction = fraction,
            heightFraction = fraction,
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
    fun `the balloon's place in the search region is where the silhouette is asked to hold it`() {
        // A detection a quarter of the way in, in a search region twice its size: the mask is
        // 200x100, so the box must land at a quarter of that and be half of it.
        val text = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)
        val search = BubbleBox(0f, 0f, 1f, 1f)

        val rect = BubbleCardGeometry.boxWithin(text, search, 200, 100)

        assertEquals(BubbleMask.MaskRect(50, 25, 150, 75), rect)
    }

    @Test
    fun `the balloon's place is clamped into the grid and never empty`() {
        // A detection flush against the page edge: padded() truncates there, so the search
        // region starts at the same place and the text's left edge lands on column zero.
        val flush = BubbleCardGeometry.boxWithin(
            BubbleBox(0f, 0f, 0.2f, 0.2f),
            BubbleBox(0f, 0f, 0.4f, 0.4f),
            100,
            100,
        )
        assertEquals(BubbleMask.MaskRect(0, 0, 50, 50), flush)

        // Thinner than one mask pixel: a rectangle a guard would divide by must still have room.
        val hairline = BubbleCardGeometry.boxWithin(
            BubbleBox(0.5f, 0.5f, 1e-4f, 1e-4f),
            BubbleBox(0f, 0f, 1f, 1f),
            20,
            20,
        )
        assertEquals(1, hairline.width)
        assertEquals(1, hairline.height)
    }

    @Test
    fun `a silhouette's bounds become the page-space box the card frames`() {
        // The whole point of amendment E3: the card is framed on what the trace found, not on
        // the detection. A silhouette filling the middle half of a search region that itself
        // covers the middle half of the page is the middle quarter of the page.
        val search = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        val box = BubbleCardGeometry.boxOfSilhouette(BubbleMask.MaskRect(25, 25, 75, 75), search, 100, 100)

        assertEquals(0.375f, box!!.x, 1e-5f)
        assertEquals(0.375f, box.y, 1e-5f)
        assertEquals(0.25f, box.width, 1e-5f)
        assertEquals(0.25f, box.height, 1e-5f)
    }

    @Test
    fun `a silhouette that fills its search region gives the search region back`() {
        val search = BubbleBox(0.1f, 0.2f, 0.3f, 0.4f)

        val box = BubbleCardGeometry.boxOfSilhouette(BubbleMask.MaskRect(0, 0, 64, 48), search, 64, 48)

        assertEquals(search.x, box!!.x, 1e-5f)
        assertEquals(search.width, box.width, 1e-5f)
        assertEquals(search.bottom, box.bottom, 1e-5f)
    }

    @Test
    fun `a degenerate mask gives no box rather than an invalid one`() {
        val search = BubbleBox(0.1f, 0.1f, 0.5f, 0.5f)

        assertNull(BubbleCardGeometry.boxOfSilhouette(BubbleMask.MaskRect(0, 0, 10, 10), search, 0, 10))
        assertNull(BubbleCardGeometry.boxOfSilhouette(BubbleMask.MaskRect(5, 5, 5, 9), search, 10, 10))
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
            val p = box.padded(cardPadding)
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
