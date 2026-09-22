package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleShape
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads an ultralytics YOLOv8 **segmentation** export: two tensors, and neither of them is
 * ready to use.
 *
 * `output0` is `[1, 37, anchors]` — channel-major, so an anchor's seven-and-thirty numbers are
 * `anchors` apart rather than next to each other, and reading it as rows silently gives
 * nonsense that still looks like boxes. The four box numbers are a CENTRE and a size, not two
 * corners. There is no NMS in the graph: a page of eight balloons comes back as forty-three
 * anchors above the floor, so suppressing overlaps is this file's job rather than the model's.
 *
 * `output1` is `[1, 32, mh, mw]` — mask prototypes over the letterboxed input. An anchor's last
 * thirty-two numbers are the weights of a linear combination of those planes; push that through
 * a sigmoid, keep what is above a half inside the anchor's own box, and the result is the
 * balloon's silhouette.
 *
 * Pure Kotlin, so all of it is a JVM test. The box half is pinned to real rows the model
 * produced on a real page, with the answer Python decodes from the same rows; the mask half is
 * arithmetic and is tested on prototypes small enough to write down.
 */
object YoloSegDecoder {

    /** 4 box + 1 score + 32 mask coefficients. */
    const val COLUMNS = 37
    const val MASK_COEFFICIENTS = 32
    private const val SCORE_ROW = 4
    private const val FIRST_COEFFICIENT = 5

    /**
     * One anchor that beat the confidence floor: its box in letterboxed-input pixels, already
     * turned into corners, and the weights that will make its mask.
     */
    class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val coefficients: FloatArray,
    ) {
        val area: Float get() = max(0f, right - left) * max(0f, bottom - top)
    }

    /**
     * Every anchor of [output] scoring at least [threshold], as corners.
     *
     * [output] is the raw `[1, 37, anchors]` tensor, flattened. [anchors] is how many there are,
     * which is the only way to read it: the stride between an anchor's columns IS that number.
     */
    fun candidates(
        output: FloatArray,
        anchors: Int,
        threshold: Float = BubbleDetectionConfig.SEG_CONFIDENCE_THRESHOLD,
    ): List<Candidate> {
        if (anchors <= 0 || output.size < COLUMNS * anchors) return emptyList()
        val found = ArrayList<Candidate>()
        for (anchor in 0 until anchors) {
            val score = output[SCORE_ROW * anchors + anchor]
            if (score < threshold) continue
            val centreX = output[anchor]
            val centreY = output[anchors + anchor]
            val width = output[2 * anchors + anchor]
            val height = output[3 * anchors + anchor]
            val coefficients = FloatArray(MASK_COEFFICIENTS) { i ->
                output[(FIRST_COEFFICIENT + i) * anchors + anchor]
            }
            found += Candidate(
                left = centreX - width / 2f,
                top = centreY - height / 2f,
                right = centreX + width / 2f,
                bottom = centreY + height / 2f,
                score = score,
                coefficients = coefficients,
            )
        }
        return found
    }

    /**
     * Greedy non-maximum suppression: keep the strongest, drop everything that overlaps it by
     * more than [iou], repeat.
     *
     * The model has no opinion about duplicates — forty-three anchors for eight balloons — so
     * without this the reader would walk the same balloon five times over.
     */
    fun suppress(
        candidates: List<Candidate>,
        iou: Float = BubbleDetectionConfig.SEG_NMS_IOU,
    ): List<Candidate> {
        val ordered = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Candidate>()
        for (candidate in ordered) {
            if (kept.none { overlap(it, candidate) > iou }) kept += candidate
        }
        return kept
    }

    private fun overlap(a: Candidate, b: Candidate): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0f || height <= 0f) return 0f
        val intersection = width * height
        val union = a.area + b.area - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }

    /** The candidate's box as a fraction of the page, undoing the letterbox. Null if degenerate. */
    fun boxOf(candidate: Candidate, letterbox: Letterbox): BubbleBox? {
        val x1 = letterbox.normalizeX(candidate.left)
        val y1 = letterbox.normalizeY(candidate.top)
        val x2 = letterbox.normalizeX(candidate.right)
        val y2 = letterbox.normalizeY(candidate.bottom)
        return BubbleBox.clampedOrNull(x1, y1, x2 - x1, y2 - y1)
    }

    /**
     * The candidate's silhouette over its own box, or null when the model marked nothing there.
     *
     * [prototypes] is the raw `[1, 32, height, width]` tensor, flattened; the grid covers the
     * whole letterboxed input, so the candidate's box in input pixels maps onto it by the ratio
     * of the two. Only that sub-rectangle is evaluated — the linear combination is thirty-two
     * multiplies per cell and there is no reason to pay for the rest of the page.
     */
    fun shapeOf(
        candidate: Candidate,
        prototypes: FloatArray,
        gridWidth: Int,
        gridHeight: Int,
        inputSize: Int,
        refine: Int = BubbleDetectionConfig.SEG_MASK_REFINEMENT,
    ): BubbleShape? {
        if (gridWidth <= 0 || gridHeight <= 0 || inputSize <= 0) return null
        if (prototypes.size < MASK_COEFFICIENTS * gridWidth * gridHeight) return null

        val perPlane = gridWidth * gridHeight
        val scaleX = gridWidth.toFloat() / inputSize
        val scaleY = gridHeight.toFloat() / inputSize
        val left = (candidate.left * scaleX).toInt().coerceIn(0, gridWidth - 1)
        val top = (candidate.top * scaleY).toInt().coerceIn(0, gridHeight - 1)
        val right = (candidate.right * scaleX).roundToInt().coerceIn(left + 1, gridWidth)
        val bottom = (candidate.bottom * scaleY).roundToInt().coerceIn(top + 1, gridHeight)

        val coarseWidth = right - left
        val coarseHeight = bottom - top

        // The combination first, on the model's own grid: thirty-two multiplies per cell, and
        // there are only a few hundred cells. Kept as logits rather than as flags, because the
        // refinement below has to interpolate something continuous.
        val logits = FloatArray(coarseWidth * coarseHeight)
        for (y in 0 until coarseHeight) {
            val row = (top + y) * gridWidth
            for (x in 0 until coarseWidth) {
                val cell = row + left + x
                var sum = 0f
                for (plane in 0 until MASK_COEFFICIENTS) {
                    sum += candidate.coefficients[plane] * prototypes[plane * perPlane + cell]
                }
                logits[y * coarseWidth + x] = sum
            }
        }

        val factor = refine.coerceAtLeast(1)
        val width = coarseWidth * factor
        val height = coarseHeight * factor
        val cells = BooleanArray(width * height)
        var marked = 0
        for (y in 0 until height) {
            val sy = (y + 0.5f) / factor - 0.5f
            val y0 = sy.toInt().coerceIn(0, coarseHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(coarseHeight - 1)
            val fy = (sy - y0).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val sx = (x + 0.5f) / factor - 0.5f
                val x0 = sx.toInt().coerceIn(0, coarseWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(coarseWidth - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val top0 = logits[y0 * coarseWidth + x0] * (1 - fx) + logits[y0 * coarseWidth + x1] * fx
                val bottom0 = logits[y1 * coarseWidth + x0] * (1 - fx) + logits[y1 * coarseWidth + x1] * fx
                val value = top0 * (1 - fy) + bottom0 * fy
                // The prototypes are logits; a half is where the model stops being unsure. The
                // logits are what is interpolated rather than the flags, so the edge lands
                // where the model's own confidence crosses a half rather than on a grid line.
                if (1f / (1f + exp(-value)) > 0.5f) {
                    cells[y * width + x] = true
                    marked++
                }
            }
        }
        if (marked == 0) return null
        return BubbleShape(width, height, cells)
    }

    /**
     * The whole thing: raw tensors in, ordered balloons out.
     *
     * A candidate whose box cannot be expressed as a [BubbleBox] is dropped rather than thrown
     * on, because this runs inside a background coroutine where a throw is a silently dead page.
     * A candidate whose mask comes back empty keeps its box and loses its shape: the card can
     * still lift it, as a rectangle.
     */
    fun decode(
        output: FloatArray,
        anchors: Int,
        prototypes: FloatArray,
        gridWidth: Int,
        gridHeight: Int,
        letterbox: Letterbox,
        threshold: Float = BubbleDetectionConfig.SEG_CONFIDENCE_THRESHOLD,
        iou: Float = BubbleDetectionConfig.SEG_NMS_IOU,
    ): List<BubbleDetection> {
        val kept = suppress(candidates(output, anchors, threshold), iou)
        return kept.mapNotNull { candidate ->
            val box = boxOf(candidate, letterbox) ?: return@mapNotNull null
            BubbleDetection(
                box = box,
                confidence = candidate.score.coerceIn(0f, 1f),
                type = BubbleType.TEXT,
                shape = shapeOf(candidate, prototypes, gridWidth, gridHeight, letterbox.inputSize),
            )
        }
    }
}
