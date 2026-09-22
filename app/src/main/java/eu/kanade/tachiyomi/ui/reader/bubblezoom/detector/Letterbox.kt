package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import kotlin.math.roundToInt

/**
 * The geometry of fitting a page into the model's square input, and of undoing it.
 *
 * Pure Kotlin, and used by BOTH sides on purpose: the Android code that paints the page into
 * the input bitmap asks this for the scale and the padding, and the decoder asks it to map
 * the model's pixels back. Two implementations of the same arithmetic would be two places to
 * disagree, and a disagreement here is a box that is valid, plausible, and on the wrong spot.
 *
 * Matches the ultralytics letterbox the model was trained and verified with: scale to fit the
 * long side, pad the short side equally on both ends, integer padding. The Python verifier
 * in tools/ uses exactly this and lands its boxes on the synthetic pages' known bubbles with
 * centre error under 0.004, so this arithmetic is confirmed against the model, not assumed.
 */
class Letterbox(val sourceWidth: Int, val sourceHeight: Int, val inputSize: Int) {

    /** Source pixels to input pixels. */
    val scale: Float = inputSize.toFloat() / maxOf(sourceWidth, sourceHeight)

    /** The page's size once scaled, before padding. */
    val scaledWidth: Int = (sourceWidth * scale).roundToInt()
    val scaledHeight: Int = (sourceHeight * scale).roundToInt()

    /** Where the page's top-left corner lands inside the input. */
    val padX: Int = (inputSize - scaledWidth) / 2
    val padY: Int = (inputSize - scaledHeight) / 2

    /** Input-pixel x back to a normalized 0..1 fraction of the source width. */
    fun normalizeX(inputX: Float): Float = (inputX - padX) / scale / sourceWidth

    /** Input-pixel y back to a normalized 0..1 fraction of the source height. */
    fun normalizeY(inputY: Float): Float = (inputY - padY) / scale / sourceHeight
}
