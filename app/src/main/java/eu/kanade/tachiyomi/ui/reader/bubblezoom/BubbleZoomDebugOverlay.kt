package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder

/**
 * Flip to false to take the debug overlay out of the reader entirely.
 *
 * Phase P7 replaces this with a real preference; until then this is the only switch.
 */
const val BUBBLE_ZOOM_DEBUG = true

/**
 * Draws [DEBUG_BOXES] on top of the page image, to prove that normalized coordinates survive
 * zoom, pan, rotation and resize.
 *
 * The overlay is a sibling of the [SubsamplingScaleImageView] inside the same parent, laid out
 * to the same bounds, so a point in the image view's coordinate space is the same point here.
 * It never computes the transform itself: every corner goes through the image view's own
 * [SubsamplingScaleImageView.sourceToViewCoord], which is the only thing that knows the current
 * scale, translation and rotation.
 *
 * It exists only on the subsampling path. On the animated path there is no image view able to
 * do the conversion, so there is no overlay at all rather than a broken one.
 */
class BubbleZoomDebugOverlay private constructor(
    context: Context,
    private val imageView: SubsamplingScaleImageView,
) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = STROKE_COLOR
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            STROKE_WIDTH_DP,
            context.resources.displayMetrics,
        )
    }

    // Reused across frames: onDraw runs on every pan and animation frame.
    private val topLeft = PointF()
    private val bottomRight = PointF()

    // TEMP P5: the trigger now walks a real BubbleZoomController instead of indexing DEBUG_BOXES
    // by hand, so the broadcast exercises the state machine rather than a parallel copy of it.
    // The controller, the key and the receiver all go away when P6 wires real navigation.
    private val debugController = BubbleZoomController()
    private val debugPageKey = BubblePageKey(this)
    private var debugActivated = false

    /**
     * TEMP P4: steps through the debug boxes, then back to the page's default fit.
     *
     * Triggered by a broadcast rather than a gesture, because every gesture on a page is
     * already taken: single tap is navigation, double tap is the image view's own zoom, and
     * long press is intercepted by [eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager]'s
     * GestureDetectorWithLongTap for the page-actions menu. Claiming one of those would mean
     * touching navigation, which phase P4 is not allowed to do.
     *
     *     adb shell am broadcast -a eu.kanade.tachiyomi.BUBBLE_ZOOM_DEBUG_FOCUS
     */
    private val debugFocusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Neighbouring pages are attached but not on screen; only the visible one reacts.
            if (!isShown || !getGlobalVisibleRect(visibleRect)) return
            if (!imageView.isReady) return

            if (!debugActivated) {
                debugController.activatePage(debugPageKey, DEBUG_DETECTIONS, PageEntry.FORWARD)
                debugActivated = true
            }
            if (!debugController.tryMoveNext()) {
                // The walk ended. P6 turns the page here; there is no navigation yet, so the
                // trigger simply starts over on the same page.
                debugController.selectBubble(-1)
            }

            // The presentation is a pure projection of the state, which is the contract P16 will
            // reuse to draw a card instead of moving the camera.
            val focus = debugController.state.value.focusFor(debugPageKey)
            if (focus != null) {
                // Captured before the first focus of a walk, so the reset at the end of it puts
                // the page back exactly where the reader left it.
                if (savedScale == null) {
                    savedScale = imageView.scale
                    savedCenter = imageView.center
                }
                imageView.focusOnBubble(focus.box)
            } else {
                val scale = savedScale
                val center = savedCenter
                if (scale != null && center != null) {
                    imageView.resetBubbleZoom(scale, center)
                }
                savedScale = null
                savedCenter = null
            }
        }
    }

    private val visibleRect = Rect()

    // TEMP P4: where the page was before the focus cycle started.
    private var savedScale: Float? = null
    private var savedCenter: PointF? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(
            context,
            debugFocusReceiver,
            IntentFilter(ACTION_DEBUG_FOCUS),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(debugFocusReceiver) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Before onReady the source dimensions and the transform are meaningless.
        if (!imageView.isReady) return
        val sourceWidth = imageView.sWidth.toFloat()
        val sourceHeight = imageView.sHeight.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return

        for (box in DEBUG_BOXES) {
            // Normalized -> original image pixels -> view pixels. The second step is the image
            // view's job; this class must never reimplement it.
            imageView.sourceToViewCoord(box.x * sourceWidth, box.y * sourceHeight, topLeft)
                ?: continue
            imageView.sourceToViewCoord(box.right * sourceWidth, box.bottom * sourceHeight, bottomRight)
                ?: continue

            canvas.drawRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, strokePaint)
        }
    }

    companion object {

        /**
         * The boxes phase P3 draws. They are the exact coordinates of the bubbles on page 1 of
         * the synthetic test manga, so any misalignment on screen is measurable rather than a
         * matter of opinion. See tools/test-pages/ground-truth.json.
         */
        val DEBUG_BOXES = listOf(
            BubbleBox(0.62f, 0.08f, 0.23f, 0.14f),
            BubbleBox(0.15f, 0.31f, 0.28f, 0.16f),
            BubbleBox(0.58f, 0.67f, 0.26f, 0.15f),
        )

        /** TEMP P5: [DEBUG_BOXES] as detections, so the debug walk feeds the real controller. */
        private val DEBUG_DETECTIONS = DEBUG_BOXES.map {
            BubbleDetection(box = it, confidence = 1f, type = BubbleType.TEXT)
        }

        private const val STROKE_WIDTH_DP = 2f
        private val STROKE_COLOR = Color.argb(0xFF, 0xFF, 0x00, 0xAA)

        /** TEMP P4: action that steps the debug focus. Removed together with the receiver. */
        const val ACTION_DEBUG_FOCUS = "eu.kanade.tachiyomi.BUBBLE_ZOOM_DEBUG_FOCUS"

        /**
         * Adds an overlay over [imageView] inside [parent], or returns null when the overlay is
         * switched off. The caller keeps the returned instance only to invalidate it.
         */
        fun attach(parent: ViewGroup, imageView: SubsamplingScaleImageView): BubbleZoomDebugOverlay? {
            if (!BUBBLE_ZOOM_DEBUG) return null
            // ReaderPageImageView is not only used by the reader: MangaCoverDialog builds one to
            // show a cover, and the webtoon viewer has its own holder. Without this check the
            // debug boxes are painted over a manga cover, and the TEMP P4 broadcast animates it.
            if (parent !is PagerPageHolder) return null
            return BubbleZoomDebugOverlay(parent.context, imageView).also { overlay ->
                // Not clickable and with no touch handling, so taps fall through to the image
                // view underneath and normal reader navigation is untouched.
                overlay.isClickable = false
                parent.addView(
                    overlay,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // TEMP P4: the focus trigger is a broadcast, registered by the overlay itself
                // in onAttachedToWindow. See debugFocusReceiver.
            }
        }

        /** Removes an overlay previously added by [attach]. Safe to call with null. */
        fun detach(parent: ViewGroup, overlay: BubbleZoomDebugOverlay?) {
            overlay?.let(parent::removeView)
        }
    }
}
