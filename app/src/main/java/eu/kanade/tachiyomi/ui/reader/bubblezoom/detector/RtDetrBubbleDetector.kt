package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleCardDecoder
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleCardGeometry
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleZoomConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.JoinedBalloons
import eu.kanade.tachiyomi.ui.reader.bubblezoom.PanelFinder
import eu.kanade.tachiyomi.ui.reader.bubblezoom.SourceRegion
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The real detector: an RT-DETR-v2 trained on comics, run by ONNX Runtime, on the CPU, off the
 * main thread, one page at a time. Amendment E9.
 *
 * It replaces a YOLOv8 segmentation model trained on manga, and the reason is measured. On 92
 * pages of the comic this feature is built against, the model that left offered 589 regions and
 * this one offers 842 — more on 70 pages, fewer on four, and by one or two where fewer. On the
 * page that has been the measuring stick all along it finds nineteen balloons where the other
 * found twelve, including every one Google Play Books stops on. It is 11 MB against 109, and
 * 221 ms against 375 on the same desktop and page.
 *
 * What it does not do is segment: there is no silhouette in the answer, so the card traces the
 * balloon out of the page's pixels (amendment E3) and falls back to a rectangle. That is a
 * smaller question than it was, because this model boxes the BALLOON where the other boxed the
 * lettering inside it.
 *
 * Nothing about it leaks upward. The reader holds a [BubbleDetector]; this class and its
 * decoder are the only place in the app that import `ai.onnxruntime`.
 *
 * The pipeline, in this order and with these guarantees:
 *
 *  1. Decode the page's encoded bytes at a power-of-two sample size that keeps its long side at
 *     or above the model's input. Never at full size for a page large enough to matter.
 *  2. Average that down to the input square — squashed, not letterboxed, which is how this
 *     model was trained and what its own preprocessing config says. Averaged and not sampled:
 *     see [AreaResample], where the measurement is.
 *  3. Run, asking for boxes against a page of one by one, so what comes back is already the
 *     fraction of the page every other part of this feature speaks in.
 *  4. Keep the balloons and the free-floating captions through [RtDetrDecoder].
 *  5. Read the page's panels off its gutters with [PanelFinder], while the decoded page is
 *     still here to read, so the reading order can follow them.
 *  6. Recycle the decoded page immediately. Only the boxes survive.
 *
 * A missing model, a native library that will not load, or an allocation that fails is not a
 * crash: the first [detect] throws an ordinary exception, the session records the page as
 * failed, navigation stays exactly as upstream Mihon's, and the failure is remembered so no
 * later page pays for the attempt again.
 *
 * Cancellation is honoured between the steps but never inside `run`, which is a blocking native
 * call. That is the guarantee phase P8 wrote down: a cancelled detection's answer is discarded,
 * not that its work is interrupted.
 */
class RtDetrBubbleDetector(
    private val context: Context,
    private val inputSize: Int = BubbleDetectionConfig.INPUT_SIZE,
) : BubbleDetector {

    override val modelVersion: Int = BubbleDetectionConfig.ONNX_MODEL_VERSION

    override val available: Boolean get() = unavailable == null && !closed

    private val inference = Mutex()

    private var session: OrtSession? = null
    private var imageInput: String? = null
    private var sizeInput: String? = null

    /** Why the session could not be created, kept so the asset is not looked up per page. */
    private var unavailable: Exception? = null

    /**
     * Set by [close]. Whoever finds it set with the mutex free releases the session: closing
     * an ORT session while `run` is executing on it is undefined behaviour, not an exception.
     */
    @Volatile
    private var closed = false

    // Reused under the mutex, and all small: the square the model sees and the tensor packed
    // from it. No bitmap is held between inferences.
    private var inputPixels: IntArray? = null
    private var inputTensor: FloatBuffer? = null

    override suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection> {
        try {
            return inference.withLock {
                if (closed) throw IllegalStateException("detector is closed")
                infer(image)
            }
        } finally {
            // After the lock is released, never inside it. Checked inside, this inference and
            // close() could each find the other's condition unmet - closed still false here,
            // the lock still held there - and both walk away, leaking the session for good.
            releaseIfClosed()
        }
    }

    override fun close() {
        closed = true
        releaseIfClosed()
    }

    /** Releases if [closed] and the mutex is free. Idempotent, so both sides may try. */
    private fun releaseIfClosed() {
        if (!closed || !inference.tryLock()) return
        try {
            releaseUnderLock()
        } finally {
            inference.unlock()
        }
    }

    private fun infer(image: BubbleDetectionImage): List<BubbleDetection> {
        val session = openSession()
        val page = decodeReduced(image)

        val started = System.nanoTime()
        val found: List<BubbleDetection>
        val panels: List<BubbleBox>
        try {
            fillInput(page)
            val answer = run(session)
            found = RtDetrDecoder.decode(
                labels = answer.labels,
                boxes = answer.boxes,
                scores = answer.scores,
                // Each candidate pair of balloons is looked at in a crop decoded from the page's
                // own bytes, at the resolution the join was calibrated on. Not the reduced plane
                // the panels are read from: on the device that plane joined two speeches the
                // calibration put far apart, and no re-measurement of the crops reproduced it.
                crop = { around -> cropOf(image, around) },
                reportJoin = { larger, smaller, spread, joined ->
                    // Said per pair because a wrong join is a speech cut in half or two speeches
                    // read as one, and from the screen those look like the model's fault.
                    logcat(LogPriority.INFO) {
                        val shape = "BubbleZoom join %.3f,%.3f %.3fx%.3f + %.3f,%.3f %.3fx%.3f " +
                            "reach=%.2f ring=%.2f seam=%.2f -> %s"
                        shape.format(
                            larger.x, larger.y, larger.width, larger.height,
                            smaller.x, smaller.y, smaller.width, smaller.height,
                            spread.reach, spread.ring, spread.seam, if (joined) "joined" else "kept apart",
                        )
                    }
                },
            )
            // While the page is still decoded: the gutters are read from the same pixels, and
            // re-decoding a page to look at them would cost more than the look does.
            val grey = greyPlane(page)
            panels = PanelFinder.find(grey.luma, grey.width, grey.height)
        } finally {
            page.recycle()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        logcat(LogPriority.INFO) {
            "BubbleZoom detect ${image.width}x${image.height} -> ${found.size} bubbles " +
                "(${panels.size} panels) in ${elapsedMs}ms"
        }
        return found + panels.map { BubbleDetection(it, confidence = 1f, type = BubbleType.PANEL) }
    }

    private fun openSession(): OrtSession {
        session?.let { return it }
        unavailable?.let { throw it }
        try {
            val bytes = context.assets.open(BubbleDetectionConfig.MODEL_ASSET).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            // SessionOptions owns a native handle and has no finalizer; it is closed as soon as
            // the session has been built from it.
            val created = OrtSession.SessionOptions().use { options -> env.createSession(bytes, options) }
            val names = created.inputNames.toList()
            if (names.size < 2) {
                created.close()
                throw IOException("expected an image and a target size input, got $names")
            }
            // By name, because the order of a graph's inputs is not part of its contract.
            imageInput = names.firstOrNull { it.contains("image") } ?: names[0]
            sizeInput = names.firstOrNull { it != imageInput } ?: names[1]
            session = created
            return created
        } catch (failure: Throwable) {
            // Throwable on purpose. The native library failing to load surfaces as an
            // UnsatisfiedLinkError inside an ExceptionInInitializerError, and reading the asset
            // can be an OutOfMemoryError. Neither is an Exception, and from a background job
            // either would kill the process - with the feature on, and only then. Rewrapped so
            // what is remembered and rethrown is an Exception the session's own net catches.
            val wrapped = failure as? Exception
                ?: IllegalStateException("BubbleZoom detector could not start", failure)
            unavailable = wrapped
            logcat(LogPriority.ERROR, wrapped) { "BubbleZoom detector unavailable" }
            throw wrapped
        }
    }

    /**
     * Decodes the page no larger than it has to be.
     *
     * `inSampleSize` must be a power of two. The largest one that keeps the long side at or
     * above the input size is chosen - except when that would mean decoding a large page at
     * full size, because the next step down is a little under the input. A 1200px page is the
     * common scanlation case, and holding it at full size in ARGB is the second copy of a page
     * the rules forbid; decoding it at 600 and letting the model see it upscaled a hair is the
     * better trade, and pages small enough that full size is cheap still get it.
     */
    private fun decodeReduced(image: BubbleDetectionImage): Bitmap {
        val longSide = maxOf(image.width, image.height)
        var sample = 1
        while (longSide / (sample * 2) >= inputSize) sample *= 2
        if (sample == 1 && longSide >= inputSize * BubbleDetectionConfig.FULL_DECODE_BELOW) sample = 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return image.open().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("page could not be decoded")
    }

    /**
     * Fills the model's square with the whole page, squashed to fit.
     *
     * No letterbox: this model's preprocessing resizes both axes to the input and its training
     * did the same, so padding it would show it a page it has never seen. Averaged rather than
     * sampled, which amendment E6 measured to be worth three balloons a page.
     */
    private fun fillInput(page: Bitmap) {
        val input = inputPixels ?: IntArray(inputSize * inputSize).also { inputPixels = it }
        val source = IntArray(page.width * page.height)
        page.getPixels(source, 0, page.width, 0, 0, page.width, page.height)
        AreaResample.resample(source, page.width, page.height, input, inputSize, inputSize)
    }

    /** ARGB pixels to three float planes, R then G then B, each 0..1. */
    private fun packTensor(): FloatBuffer {
        val count = inputSize * inputSize
        val pixels = inputPixels ?: error("the input has not been filled")
        val tensor = inputTensor ?: ByteBuffer.allocateDirect(count * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { inputTensor = it }

        tensor.rewind()
        for (i in 0 until count) tensor.put(i, ((pixels[i] shr 16) and 0xFF) / 255f)
        for (i in 0 until count) tensor.put(count + i, ((pixels[i] shr 8) and 0xFF) / 255f)
        for (i in 0 until count) tensor.put(2 * count + i, (pixels[i] and 0xFF) / 255f)
        tensor.rewind()
        return tensor
    }

    /** What one inference comes back with, in plain arrays. */
    private class Answer(val labels: LongArray, val boxes: FloatArray, val scores: FloatArray)

    private fun run(session: OrtSession): Answer {
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        // A page of one by one: the graph's own post-processing multiplies its normalized boxes
        // by this, so asking for one gives back the fractions this feature speaks in, with no
        // arithmetic of ours in between to disagree about.
        val unitPage = LongBuffer.wrap(longArrayOf(1L, 1L))
        OnnxTensor.createTensor(env, packTensor(), shape).use { image ->
            OnnxTensor.createTensor(env, unitPage, longArrayOf(1, 2)).use { size ->
                session.run(mapOf(imageInput!! to image, sizeInput!! to size)).use { result ->
                    val labels = result.get("labels").orElse(null) as? OnnxTensor
                    val boxes = result.get("boxes").orElse(null) as? OnnxTensor
                    val scores = result.get("scores").orElse(null) as? OnnxTensor
                    if (labels == null || boxes == null || scores == null) {
                        throw IOException("unexpected outputs: ${session.outputNames}")
                    }
                    // Each buffer is documented as a COPY, so it survives the result closing.
                    val labelBuffer = labels.longBuffer ?: throw IOException("labels are not int64")
                    val boxBuffer = boxes.floatBuffer ?: throw IOException("boxes are not float")
                    val scoreBuffer = scores.floatBuffer ?: throw IOException("scores are not float")
                    return Answer(
                        labels = LongArray(labelBuffer.remaining()).also { labelBuffer.get(it) },
                        boxes = FloatArray(boxBuffer.remaining()).also { boxBuffer.get(it) },
                        scores = FloatArray(scoreBuffer.remaining()).also { scoreBuffer.get(it) },
                    )
                }
            }
        }
    }

    /** The page reduced to one byte of brightness per pixel, and the size that came out. */
    private class GreyPlane(val luma: IntArray, val width: Int, val height: Int)

    /**
     * [around] of the page, plus [BubbleZoomConfig.JOIN_CROP_PADDING] of its smallest side on
     * every edge, as a grey crop: decoded from the page's bytes by the card's own region decoder,
     * sampled so its long side lands at or above [BubbleZoomConfig.JOIN_CROP_LONG_SIDE].
     *
     * The margin is added here, in pixels, because that is how the join was calibrated; a
     * fraction of a box on a page twice as tall as it is wide would be a different margin on
     * each axis.
     *
     * Blocking, like every decode here: this runs on the detection dispatcher, never the main
     * thread. Null when the region cannot be decoded, and the pair is then left as two.
     */
    private fun cropOf(image: BubbleDetectionImage, around: BubbleBox): JoinedBalloons.Crop? {
        val pair = BubbleCardGeometry.regionOf(around, image.width, image.height) ?: return null
        val margin = (BubbleZoomConfig.JOIN_CROP_PADDING * minOf(pair.width, pair.height)).toInt()
        val region = SourceRegion(
            left = (pair.left - margin).coerceAtLeast(0),
            top = (pair.top - margin).coerceAtLeast(0),
            right = (pair.right + margin).coerceAtMost(image.width),
            bottom = (pair.bottom + margin).coerceAtMost(image.height),
        )
        // The card's sample size is driven by both sides; this one by the long side alone, so a
        // wide pair is not decoded at full width for the sake of its height.
        var sample = 1
        while (maxOf(region.width, region.height) / (sample * 2f) >= BubbleZoomConfig.JOIN_CROP_LONG_SIDE) {
            sample *= 2
        }
        val bitmap = BubbleCardDecoder.decode(image, region, sample) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val luma = IntArray(width * height) { i ->
                val pixel = pixels[i]
                (((pixel shr 16 and 0xFF) * 77) + ((pixel shr 8 and 0xFF) * 151) + ((pixel and 0xFF) * 28)) shr 8
            }
            val covered = BubbleBox.clampedOrNull(
                x = region.left.toFloat() / image.width,
                y = region.top.toFloat() / image.height,
                width = region.width.toFloat() / image.width,
                height = region.height.toFloat() / image.height,
            ) ?: return null
            return JoinedBalloons.Crop(luma, width, height, covered)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The decoded page as one grey plane, small enough to sweep several times.
     *
     * Reduced by averaging for the same reason the model's input is: a gutter is a flat band,
     * and a naive reduction that samples every other pixel puts a stripe of artwork through one
     * often enough to matter.
     */
    private fun greyPlane(page: Bitmap): GreyPlane {
        val longSide = maxOf(page.width, page.height)
        val scan = minOf(BubbleDetectionConfig.PANEL_SCAN_LONG_SIDE, longSide)
        val width = (page.width.toLong() * scan / longSide).toInt().coerceAtLeast(1)
        val height = (page.height.toLong() * scan / longSide).toInt().coerceAtLeast(1)

        val source = IntArray(page.width * page.height)
        page.getPixels(source, 0, page.width, 0, 0, page.width, page.height)
        val reduced = IntArray(width * height)
        AreaResample.resample(source, page.width, page.height, reduced, width, height)

        val luma = IntArray(width * height) { i ->
            val pixel = reduced[i]
            // The same integer luma every other part of this feature uses.
            (
                ((pixel shr 16 and 0xFF) * 77) +
                    ((pixel shr 8 and 0xFF) * 151) +
                    ((pixel and 0xFF) * 28)
                ) shr 8
        }
        return GreyPlane(luma, width, height)
    }

    private fun releaseUnderLock() {
        runCatching { session?.close() }
        session = null
        imageInput = null
        sizeInput = null
        inputPixels = null
        inputTensor = null
    }
}
