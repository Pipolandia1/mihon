package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleCardSizeTest {

    @Test
    fun `the default size is exactly the calibrated card`() {
        val size = BubbleCardSize.DEFAULT
        assertEquals(BubbleZoomConfig.CARD_MAGNIFICATION, size.magnification)
        assertEquals(BubbleZoomConfig.TARGET_WIDTH_FRACTION, size.widthFraction)
        assertEquals(BubbleZoomConfig.TARGET_HEIGHT_FRACTION, size.heightFraction)
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(BubbleCardSize.DEFAULT, BubbleCardSize.of(0))
        assertEquals(BubbleCardSize.DEFAULT, BubbleCardSize.of(110))
        BubbleCardSize.entries.forEach { assertEquals(it, BubbleCardSize.of(it.percent)) }
    }

    @Test
    fun `no size lets a card run to the screen edge`() {
        BubbleCardSize.entries.forEach {
            assertTrue(it.widthFraction <= BubbleZoomConfig.CARD_MAX_SCREEN_FRACTION, it.name)
            assertTrue(it.heightFraction <= BubbleZoomConfig.CARD_MAX_SCREEN_FRACTION, it.name)
        }
    }

    @Test
    fun `each size lifts a small balloon larger than the one before`() {
        // A small balloon, bound by magnification: the card grows with the factor.
        val scales = BubbleCardSize.entries.map { cardScaleOf(it, paddedW = 200f, paddedH = 120f) }
        assertEquals(scales.sorted(), scales)
        assertEquals(scales.size, scales.toSet().size)
    }

    @Test
    fun `each size also lifts a wide balloon larger, until the screen limit`() {
        // A balloon as wide as half the page, bound by the width ceiling rather than the
        // magnification: scaling only the magnification would leave every size alike here.
        val scales = BubbleCardSize.entries.map { cardScaleOf(it, paddedW = 800f, paddedH = 300f) }
        assertEquals(scales.sorted(), scales)
        assertTrue(scales.first() < scales.last())
        val widest = scales.last() * 800f
        assertTrue(widest <= BubbleZoomConfig.CARD_MAX_SCREEN_FRACTION * VIEWPORT_W + 0.5f)
    }

    private fun cardScaleOf(size: BubbleCardSize, paddedW: Float, paddedH: Float): Float =
        BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = paddedW,
            paddedHeightSourcePx = paddedH,
            viewportWidthPx = VIEWPORT_W,
            viewportHeightPx = VIEWPORT_H,
            currentViewScale = FIT_SCALE,
            magnification = size.magnification,
            widthFraction = size.widthFraction,
            heightFraction = size.heightFraction,
        )

    private companion object {
        // The emulator's figures: a 1600x2400 page fitted into a 1080x2400 viewport.
        const val VIEWPORT_W = 1080f
        const val VIEWPORT_H = 2400f
        const val FIT_SCALE = 1080f / 1600f
    }
}
