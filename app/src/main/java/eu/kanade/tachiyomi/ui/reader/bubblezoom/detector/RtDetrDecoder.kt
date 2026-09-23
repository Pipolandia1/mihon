package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import eu.kanade.tachiyomi.ui.reader.bubblezoom.JoinedBalloons

/**
 * Turning one RT-DETR answer into the regions the reader walks. Amendment E9.
 *
 * The model has three classes and the walk wants two of them:
 *
 *  - `bubble` is the balloon itself, outline and all. This is the one to stop on, and it is
 *    also what the card should crop — the previous model boxed the lettering, which is why
 *    amendment E3 had to search far outside the box to find the balloon around it.
 *  - `text_free` is lettering with no balloon around it: the caption boxes of a modern comic,
 *    which on one page measured here carry eleven of its twenty-three pieces of dialogue.
 *  - `text_bubble` is the lettering INSIDE a balloon, which is the same stop seen twice, so it
 *    is dropped. Over 92 pages only six of them fell outside every balloon the model found,
 *    which is not worth a containment test to rescue.
 *
 * There is no suppression here. RT-DETR is end-to-end: its three hundred queries are already
 * one per object, and the graph's own post-processing has run before this sees anything.
 *
 * Pure Kotlin over the flat arrays the runtime hands back, so the mapping and the threshold are
 * JVM tests rather than something only the device can show.
 */
object RtDetrDecoder {

    const val CLASS_BUBBLE = 0L
    const val CLASS_TEXT_IN_BUBBLE = 1L
    const val CLASS_TEXT_FREE = 2L

    /**
     * The regions worth stopping on, in the model's own order.
     *
     * [boxes] is four floats per query — left, top, right, bottom — already fractions of the
     * page, which is what the graph returns when it is asked for a target size of one by one.
     */
    fun decode(
        labels: LongArray,
        boxes: FloatArray,
        scores: FloatArray,
        minimumConfidence: Float = BubbleDetectionConfig.DETECTION_CONFIDENCE,
        /**
         * The page as one grey plane, when the caller has one. With it, the lobes of a balloon
         * lettered as two bubbles are joined into one stop — see [JoinedBalloons], which needs
         * the pixels to tell a waisted balloon from two that merely stand close. Without it
         * every box is its own region, which is what this did before amendment E12.
         */
        page: IntArray? = null,
        pageWidth: Int = 0,
        pageHeight: Int = 0,
    ): List<BubbleDetection> {
        val queries = minOf(labels.size, scores.size, boxes.size / 4)
        val balloons = ArrayList<BubbleDetection>(queries)
        val found = ArrayList<BubbleDetection>(queries)
        for (i in 0 until queries) {
            val label = labels[i]
            if (label != CLASS_BUBBLE && label != CLASS_TEXT_FREE) continue
            val score = scores[i]
            if (!score.isFinite() || score < minimumConfidence) continue
            val left = boxes[i * 4]
            val top = boxes[i * 4 + 1]
            val right = boxes[i * 4 + 2]
            val bottom = boxes[i * 4 + 3]
            val box = BubbleBox.clampedOrNull(left, top, right - left, bottom - top) ?: continue
            val region = BubbleDetection(
                box = box,
                confidence = score.coerceIn(0f, 1f),
                type = BubbleType.TEXT,
                // No silhouette: this model draws rectangles. The card traces the balloon out of
                // the page's own pixels instead, which the box being the BALLOON now makes a
                // far smaller question than it was when the box was the lettering.
                shape = null,
            )
            // Kept apart only long enough to join the lobes: a caption standing over a balloon
            // is not a lobe of it, and the classes are the only place that is known.
            if (label == CLASS_BUBBLE) balloons += region else found += region
        }
        val whole = if (page != null) {
            JoinedBalloons.merge(balloons, page, pageWidth, pageHeight)
        } else {
            balloons
        }
        return whole + found
    }
}
