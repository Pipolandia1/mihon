package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import eu.kanade.tachiyomi.ui.reader.bubblezoom.DEBUG_BOXES
import kotlinx.coroutines.delay

/**
 * Answers with the three known boxes, after pretending to think.
 *
 * It exists so the whole pipeline can be built and judged before any model is chosen: with it
 * in place, everything from the page bytes to a lifted card is real, and P9 replaces one object.
 *
 * The delay is the instrument, not decoration. Two of the phase's own promises — that the page
 * renders before the bubbles arrive, and that a tap during detection navigates normally rather
 * than being queued — are unobservable when detection is instant.
 *
 * [delay] and not `Thread.sleep`: sleeping would block a shared dispatcher thread, would ignore
 * cancellation, and would not advance a test's virtual clock. All three would look identical on
 * a device and would quietly break the tests that make this detector worth having.
 *
 * Note that this proves nothing about threading or about cancelling real work. A `delay` holds
 * no thread, so a missing `withContext(Dispatchers.Default)` stays invisible until P9 lands a
 * model whose inference is a blocking native call; and `delay` is a cancellation point, while
 * that native call will not be. Cancellation here therefore means "the result is discarded",
 * which is the guarantee that survives into P9.
 */
class FakeBubbleDetector(
    private val latencyMs: Long = BubbleDetectionConfig.FAKE_LATENCY_MS,
    private val boxes: List<BubbleBox> = DEBUG_BOXES,
) : BubbleDetector {

    override val modelVersion: Int = BubbleDetectionConfig.FAKE_MODEL_VERSION

    override suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection> {
        if (latencyMs > 0) delay(latencyMs)
        // Through clampedOrNull even though these numbers are already clean, so that the door
        // every real detector has to use is exercised from the day it exists rather than the
        // day it becomes load-bearing.
        return boxes.mapNotNull { box ->
            BubbleBox.clampedOrNull(box.x, box.y, box.width, box.height)
                ?.let { BubbleDetection(box = it, confidence = 1f, type = BubbleType.TEXT) }
        }
    }
}
