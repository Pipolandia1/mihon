package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
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

/**
 * Reads the output of an ultralytics end-to-end export: `[1, N, 6]`, each row
 * `[x1, y1, x2, y2, score, class]` in letterboxed-input pixels, NMS already applied inside
 * the graph, unused rows zero-filled.
 *
 * That layout was verified rather than read off a model card — the card does not document
 * it. The synthetic test pages, whose bubble boxes are known exactly, were run through the
 * real model in Python and decoded this way; every detection landed inside its known bubble,
 * centred to within half a percent. A wrong reading (xywh, or normalized coordinates) lands
 * nowhere.
 *
 * Rows below [BubbleDetectionConfig.CONFIDENCE_THRESHOLD] are not detections. An end-to-end
 * export always returns 300 rows and none of them scores zero: the slots it has nothing for
 * carry candidates at 0.0004 to 0.07, and a decoder that kept "everything above zero" handed
 * the reader 211 bubbles on a page with eight. The first cut of this decoder did exactly that,
 * on the assumption that unused rows were zeros - an assumption its own test could not catch,
 * because the fixture had been filtered at 0.25 before the assumption was written. The test
 * now carries all 300 rows the model actually produced.
 *
 * Every box leaves through [BubbleBox.clampedOrNull]. Undoing the letterbox lands values a
 * fraction of an ulp past the edge, and `BubbleBox`'s constructor throws on those — inside a
 * background coroutine, where the throw is a silently dead page.
 */
object YoloEndToEndDecoder {

    const val COLUMNS = 6

    fun decode(
        rows: FloatArray,
        letterbox: Letterbox,
        threshold: Float = BubbleDetectionConfig.CONFIDENCE_THRESHOLD,
    ): List<BubbleDetection> {
        val found = ArrayList<BubbleDetection>()
        var offset = 0
        while (offset + COLUMNS <= rows.size) {
            val score = rows[offset + 4]
            if (score >= threshold) {
                val x1 = letterbox.normalizeX(rows[offset])
                val y1 = letterbox.normalizeY(rows[offset + 1])
                val x2 = letterbox.normalizeX(rows[offset + 2])
                val y2 = letterbox.normalizeY(rows[offset + 3])
                BubbleBox.clampedOrNull(x1, y1, x2 - x1, y2 - y1)?.let { box ->
                    found += BubbleDetection(
                        box = box,
                        confidence = score.coerceIn(0f, 1f),
                        type = BubbleType.TEXT,
                    )
                }
            }
            offset += COLUMNS
        }
        return found
    }
}
