package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import java.io.InputStream

/**
 * The only thing the reader knows about where bubbles come from.
 *
 * No implementation type crosses this line and no runtime is named on it: the reader holds a
 * `BubbleDetector` and never an ONNX session, a LiteRT interpreter or a hardcoded list. That is
 * the whole point of the phase, and it is what lets the fake below and a real model in P9 be
 * swapped without a single call site changing.
 */
interface BubbleDetector : AutoCloseable {

    /**
     * Identifies the detector **and the settings it ran with**, not the model alone.
     *
     * Phase P13 keys its disk cache on this, so anything that changes the boxes a page produces
     * — a different model, a different input size, a different confidence threshold — has to
     * change this number too, or the cache will serve results the current detector would never
     * have returned.
     */
    val modelVersion: Int

    /**
     * Whether asking is worth anything. False once the detector has discovered it cannot work
     * at all - no model file, a native library that will not load - so the session stops
     * copying page bytes and launching jobs for it, and a reader with a broken detector costs
     * exactly what one with the feature off costs. True for a detector that has nothing to
     * discover, which is why it has a body here.
     */
    val available: Boolean get() = true

    /**
     * Finds the dialogue regions in [image], in coordinates normalized to that image.
     *
     * The implementation owns its own transform and therefore owns the division: only it knows
     * whether it letterboxed the page into a square, at what sample size it decoded, and what
     * padding it added. Shared code cannot undo a transform it never saw.
     *
     * Every box must leave through [eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox.clampedOrNull].
     * Undoing a letterbox produces values a fraction of an ulp past 1f, and `BubbleBox`'s
     * constructor rejects those — from inside a background coroutine, where the throw is a
     * silent dead page rather than a crash anyone would notice.
     *
     * Suspending, cancellable, and called off the main thread. Cancellation must propagate:
     * a detector that swallows [kotlinx.coroutines.CancellationException] keeps working for a
     * page the reader has already left.
     */
    suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection>

    /**
     * Releases whatever the detector holds - a native session, reusable buffers. A no-op for
     * a detector that holds nothing, which is why it has a body here.
     */
    override fun close() {}
}

/**
 * One page, as a detector sees it: how large it is, and how to read its bytes.
 *
 * Deliberately not a `Bitmap`. Two reasons, both binding.
 *
 * The caller must not decode. A decoded page is a second full-resolution copy, which the
 * project rules forbid, and the caller cannot know what resolution the detector wants — a model
 * with a 640x640 input wants a sample size the drawing code has no opinion about. Handing over
 * a way to read the bytes lets the detector decode once, at its own size, and recycle it
 * immediately. It also keeps `Bitmap` out of this type, so everything above the detector stays
 * testable on the JVM.
 *
 * A future implementation that would rather have a file path than a stream gets one by adding a
 * field here; no caller changes, because callers never construct the pixels, only point at them.
 *
 * [width] and [height] are not for scaling — a uniform downscale cancels out of a fraction.
 * They are a declared frame of reference: the detector's promise that the fractions it returns
 * are fractions of *this* rectangle. They must be the dimensions of the image **as displayed**,
 * which in this reader is not the same as the file on disk: a double page may have been split
 * in half and a page may have been rotated before it ever reached the screen.
 */
class BubbleDetectionImage(
    val width: Int,
    val height: Int,
    /** Opens a fresh stream over the page's encoded bytes. May be called more than once. */
    val open: () -> InputStream,
)
