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
 * The feature's instrument panel: every detection where it is, in the order it will be walked,
 * and a corner readout of what the detector did with this page.
 *
 * It began as three constants drawn over a page to prove that normalized coordinates survive
 * zoom, pan, rotation and resize; phase P17 made it the thing that tells a bad result apart.
 * A balloon the model missed, a balloon found but ordered wrongly, a page still thinking and a
 * page whose detector never ran all look the same on screen without it.
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

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    )

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = STROKE_COLOR
        strokeWidth = dp(STROKE_WIDTH_DP)
    }

    // Readable over white paper and black ink alike, which is the whole job: the panel is a
    // dark slab and the text is light, so neither depends on what the artwork underneath does.
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_COLOR }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(TEXT_SIZE_DP)
    }
    private val orderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(ORDER_SIZE_DP)
    }
    private val orderBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STROKE_COLOR }

    /**
     * The readout, or null while there is nothing to say.
     *
     * Read from the session once per apply rather than pushed through the presentation flow,
     * because it changes for reasons that flow does not carry — a detection finishing for a
     * page that is not the subject.
     */
    var info: BubbleZoomDebugInfo? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * What is drawn: the page's current detections, whatever produced them.
     *
     * Until phase P9 this drew the three constants from P3, which was fine while those WERE
     * the detections and silently useless the moment a real model replaced them - the overlay
     * kept showing three fixed rectangles over pages with eight real boxes. Now the presenter
     * hands over the controller's list for the page, so what is drawn is what will be walked.
     */
    var boxes: List<BubbleBox> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
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

        for ((index, box) in boxes.withIndex()) {
            // Normalized -> original image pixels -> view pixels. The second step is the image
            // view's job; this class must never reimplement it.
            imageView.sourceToViewCoord(box.x * sourceWidth, box.y * sourceHeight, topLeft)
                ?: continue
            imageView.sourceToViewCoord(box.right * sourceWidth, box.bottom * sourceHeight, bottomRight)
                ?: continue

            canvas.drawRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y, strokePaint)
            // The number is the reading order, which is the point: amendment P12 rearranged
            // these and without seeing the order on the page there is no way to tell a wrong
            // order from a wrong detection.
            drawOrder(canvas, index + 1, topLeft.x, topLeft.y)
        }
        drawPanel(canvas)
    }

    /** The walk's position for one box, as a tag on its top-left corner. */
    private fun drawOrder(canvas: Canvas, order: Int, x: Float, y: Float) {
        val label = order.toString()
        val width = orderPaint.measureText(label)
        val padding = dp(ORDER_PADDING_DP)
        val height = orderPaint.textSize + padding
        canvas.drawRect(x, y, x + width + padding * 2, y + height, orderBackPaint)
        canvas.drawText(label, x + padding, y + orderPaint.textSize, orderPaint)
    }

    /** The corner readout: what the detector did with this page, and where the walk is. */
    private fun drawPanel(canvas: Canvas) {
        val readout = info ?: return
        val lines = listOfNotNull(readout.summary, readout.focus)
        val padding = dp(PANEL_PADDING_DP)
        val lineHeight = textPaint.textSize * 1.35f
        val width = lines.maxOf { textPaint.measureText(it) } + padding * 2
        val height = lineHeight * lines.size + padding * 2 - (lineHeight - textPaint.textSize)

        val left = padding
        val top = padding
        canvas.drawRoundRect(left, top, left + width, top + height, padding, padding, panelPaint)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, left + padding, top + padding + textPaint.textSize + lineHeight * index, textPaint)
        }
    }

    companion object {

        private const val STROKE_WIDTH_DP = 2f
        private const val TEXT_SIZE_DP = 12f
        private const val ORDER_SIZE_DP = 11f
        private const val ORDER_PADDING_DP = 3f
        private const val PANEL_PADDING_DP = 8f
        private val STROKE_COLOR = Color.argb(0xFF, 0xFF, 0x00, 0xAA)
        private val PANEL_COLOR = Color.argb(0xCC, 0x11, 0x11, 0x11)

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
