package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The real detector: a YOLO end-to-end model run by ONNX Runtime, on the CPU, off the main
 * thread, one page at a time.
 *
 * Nothing about it leaks upward. The reader holds a [BubbleDetector]; this class is the only
 * place in the app that imports `ai.onnxruntime`, and it is constructed only by the session's
 * factory.
 *
 * The pipeline is the spec's, in this order and with these guarantees:
 *
 *  1. Decode the page's encoded bytes at a power-of-two sample size that lands its long side
 *     near [BubbleDetectionConfig.INPUT_SIZE]. Never at full size for a page large enough to
 *     matter: a 1200px scanlation page decodes at 600 and is upscaled a little, rather than
 *     being held at 1200x1700 in ARGB next to the viewer's own tiles of the same page.
 *  2. Paint that into a reusable square input bitmap through the same [Letterbox] the decoder
 *     will use to undo it, on the grey the model was trained with.
 *  3. Pack it as an NCHW float tensor, RGB, 0..1.
 *  4. Run. The session is created once, lazily, and lives until [close]; a [Mutex] means one
 *     inference at a time, so the reusable buffers are never shared.
 *  5. Decode through [YoloEndToEndDecoder], which normalizes and clamps.
 *  6. Recycle the decoded page immediately. Only the boxes survive.
 *
 * A missing model, a native library that will not load, or an allocation that fails is not a
 * crash: the first [detect] throws an ordinary exception, the session records the page as
 * failed, navigation stays exactly as upstream Mihon's, and the failure is remembered so no
 * later page pays for the attempt again.
 *
 * Cancellation is honoured between the steps and not inside `run`, which is a blocking native
 * call. That is the guarantee phase P8 wrote down: a cancelled detection's answer is discarded,
 * not that its work is interrupted.
 */
class OnnxBubbleDetector(
    private val context: Context,
    private val inputSize: Int = BubbleDetectionConfig.INPUT_SIZE,
) : BubbleDetector {

    override val modelVersion: Int = BubbleDetectionConfig.ONNX_MODEL_VERSION

    override val available: Boolean get() = unavailable == null && !closed

    private val inference = Mutex()

    private var session: OrtSession? = null
    private var inputName: String? = null

    /** Why the session could not be created, kept so the asset is not looked up per page. */
    private var unavailable: Exception? = null

    /**
     * Set by [close]. Whoever finds it set with the mutex free releases the session: closing
     * an ORT session while `run` is executing on it is undefined behaviour, not an exception.
     */
    @Volatile
    private var closed = false

    // One of each, reused under the mutex. The input bitmap is the only bitmap ever held.
    private var inputBitmap: Bitmap? = null
    private var inputPixels: IntArray? = null
    private var inputTensor: FloatBuffer? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

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
        val letterbox = Letterbox(image.width, image.height, inputSize)

        val page = decodeReduced(image)
        val tensor = try {
            paintInput(page, letterbox)
            packTensor()
        } finally {
            page.recycle()
        }

        val started = System.nanoTime()
        val rows = run(session, tensor)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val found = YoloEndToEndDecoder.decode(rows, letterbox)
        logcat(LogPriority.INFO) {
            "BubbleZoom detect ${image.width}x${image.height} -> ${found.size} boxes in ${elapsedMs}ms"
        }
        return found
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
            val name = created.inputNames.firstOrNull()
            if (name == null) {
                created.close()
                throw IOException("model has no inputs")
            }
            inputName = name
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
     * the rules forbid; decoding it at 600 and letting the model see it upscaled a hair is
     * the better trade, and pages small enough that full size is cheap still get it.
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

    private fun paintInput(page: Bitmap, letterbox: Letterbox) {
        val target = inputBitmap ?: Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            .also { inputBitmap = it }
        val canvas = Canvas(target)
        canvas.drawColor(LETTERBOX_FILL)
        // The decoded page is smaller than the source by the sample size, so the scale is
        // taken against ITS size; the letterbox geometry itself came from the source dimensions
        // and only the destination rectangle matters.
        val matrix = Matrix().apply {
            setScale(
                letterbox.scaledWidth.toFloat() / page.width,
                letterbox.scaledHeight.toFloat() / page.height,
            )
            postTranslate(letterbox.padX.toFloat(), letterbox.padY.toFloat())
        }
        canvas.drawBitmap(page, matrix, paint)
    }

    /** ARGB pixels to three float planes, R then G then B, each 0..1. */
    private fun packTensor(): FloatBuffer {
        val target = inputBitmap ?: error("input bitmap missing")
        val count = inputSize * inputSize
        val pixels = inputPixels ?: IntArray(count).also { inputPixels = it }
        val tensor = inputTensor ?: ByteBuffer.allocateDirect(count * 3 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { inputTensor = it }

        target.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        tensor.rewind()
        for (i in 0 until count) tensor.put(i, ((pixels[i] shr 16) and 0xFF) / 255f)
        for (i in 0 until count) tensor.put(count + i, ((pixels[i] shr 8) and 0xFF) / 255f)
        for (i in 0 until count) tensor.put(2 * count + i, (pixels[i] and 0xFF) / 255f)
        tensor.rewind()
        return tensor
    }

    private fun run(session: OrtSession, tensor: FloatBuffer): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        OnnxTensor.createTensor(env, tensor, shape).use { input ->
            session.run(mapOf(inputName!! to input)).use { result ->
                val output = result.get(0) as OnnxTensor
                // floatBuffer is documented as a COPY, so it survives the result being closed.
                val buffer = output.floatBuffer ?: throw IOException("model output is not float")
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    private fun releaseUnderLock() {
        runCatching { session?.close() }
        session = null
        inputName = null
        inputBitmap?.recycle()
        inputBitmap = null
        inputPixels = null
        inputTensor = null
    }

    private companion object {
        val LETTERBOX_FILL = Color.rgb(
            BubbleDetectionConfig.LETTERBOX_PAD_VALUE,
            BubbleDetectionConfig.LETTERBOX_PAD_VALUE,
            BubbleDetectionConfig.LETTERBOX_PAD_VALUE,
        )
    }
}
