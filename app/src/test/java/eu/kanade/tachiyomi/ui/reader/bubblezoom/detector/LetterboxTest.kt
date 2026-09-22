package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The letterbox, which both sides of the feature depend on agreeing about: the Android code
 * paints the page into the model's square input with it, and the decoder maps the model's
 * pixels back out with it. Two implementations of this arithmetic would be two places to
 * disagree, and a disagreement is a box that is valid, plausible and on the wrong spot.
 *
 * Its own end-to-end decoder went with amendment E4, when the box model was replaced by one
 * that segments; what the segmentation export needs is in `YoloSegDecodingTest`.
 */
class LetterboxTest {

    /** The synthetic test pages' shape: 1600x2400, the figures throughout. */
    private val letterbox = Letterbox(sourceWidth = 1600, sourceHeight = 2400, inputSize = 640)

    @Test
    fun `the letterbox is the one Python used`() {
        // scale = 640 / 2400, page scaled to 427x640, centred horizontally with 106px each side.
        assertEquals(0.26666668f, letterbox.scale, 1e-6f)
        assertEquals(427, letterbox.scaledWidth)
        assertEquals(640, letterbox.scaledHeight)
        assertEquals(106, letterbox.padX)
        assertEquals(0, letterbox.padY)
    }

    @Test
    fun `a wide page pads the other axis`() {
        val wide = Letterbox(sourceWidth = 2400, sourceHeight = 1600, inputSize = 640)

        assertEquals(640, wide.scaledWidth)
        assertEquals(427, wide.scaledHeight)
        assertEquals(0, wide.padX)
        assertEquals(106, wide.padY)
    }

    @Test
    fun `undoing the letterbox is the exact inverse of applying it`() {
        // The Android side paints the page at (padX + x * scale); the decoder must map that
        // input pixel back to x / sourceWidth or the two halves of the pipeline disagree.
        val sourceX = 1234f
        val sourceY = 987f

        val inputX = letterbox.padX + sourceX * letterbox.scale
        val inputY = letterbox.padY + sourceY * letterbox.scale

        assertEquals(sourceX / 1600f, letterbox.normalizeX(inputX), 1e-5f)
        assertEquals(sourceY / 2400f, letterbox.normalizeY(inputY), 1e-5f)
    }
}
