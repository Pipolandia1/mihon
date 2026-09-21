package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

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

        private const val STROKE_WIDTH_DP = 2f
        private val STROKE_COLOR = Color.argb(0xFF, 0xFF, 0x00, 0xAA)

        /**
         * Adds an overlay over [imageView] inside [parent].
         *
         * Only [BubbleZoomPagePresenter] calls this, and only while the debug preference is on,
         * so there is no switch here any more. That also retires the guard this used to carry
         * against being attached to a manga cover: the presenter belongs to a page holder and
         * to nothing else, where before any ReaderPageImageView could reach it — and
         * MangaCoverDialog builds one of those.
         */
        fun attach(parent: ViewGroup, imageView: SubsamplingScaleImageView): BubbleZoomDebugOverlay {
            return BubbleZoomDebugOverlay(parent.context, imageView).also { overlay ->
                // Not clickable and with no touch handling, so taps fall through to the image
                // view underneath and normal reader navigation is untouched.
                overlay.isClickable = false
                parent.addView(
                    overlay,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }

        /** Removes an overlay previously added by [attach]. Safe to call with null. */
        fun detach(parent: ViewGroup, overlay: BubbleZoomDebugOverlay?) {
            overlay?.let(parent::removeView)
        }
    }
}
