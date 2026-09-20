package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withSave
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale

/**
 * Lifts the focused bubble as a card above the page, leaving the page itself where it is.
 *
 * The card's pixels are a replay of the page view through a magnifying matrix, captured once
 * per focused bubble into a card-sized bitmap. Nothing is re-decoded and no second copy of the
 * page is kept: the replay reads whatever tiles the image view is already holding, which for a
 * page whose fit scale is above about two thirds is the full-resolution image.
 *
 * The capture is taken once rather than replayed every frame on purpose. Drawing the live view
 * from inside [onDraw] would hold references to tile bitmaps that the library recycles as it
 * refreshes them, which is a crash in the reader rather than a soft card.
 *
 * Because the capture is frozen, the card is only true while the page stands still. Anything
 * that moves the page under it — a pan, a fling, a pinch, a double tap — makes it a lie, and
 * [onPageMoved] dismisses it instead of showing stale pixels.
 *
 * The view never handles touch. "Dismiss on tap outside" cannot be built here: the pager feeds
 * its gesture detector unconditionally, so a tap consumed for dismissal would navigate as well.
 */
class BubbleZoomOverlayView(
    context: Context,
    private val imageView: SubsamplingScaleImageView,
) : View(context) {

    private val cornerRadius = dp(BubbleZoomConfig.CARD_CORNER_RADIUS_DP)
    private val screenMargin = dp(BubbleZoomConfig.CARD_SCREEN_MARGIN_DP)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // Two tones, light over dark, so the edge survives both white paper and black ink without a
    // scrim. The same trick ReaderNavigationOverlayView uses to keep its labels readable.
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BubbleZoomConfig.CARD_BORDER_WIDTH_DP) * 2f
        color = Color.argb(0x55, 0, 0, 0)
    }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BubbleZoomConfig.CARD_BORDER_WIDTH_DP)
        color = Color.argb(0xBB, 0xFF, 0xFF, 0xFF)
    }

    private var capture: Bitmap? = null
    private var outgoing: Bitmap? = null

    private var cardRect = RectF()
    private var outgoingRect = RectF()

    /** 0 while hidden, 1 while fully shown; drives both the entrance and the cross-fade. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val drawRect = RectF()

    init {
        // No touch handling at all, so every tap still reaches the pager and navigates.
        isClickable = false
        isFocusable = false
        elevation = dp(BubbleZoomConfig.CARD_ELEVATION_DP)
        alpha = 0f
    }

    /** Whether a card is currently lifted. */
    val isShowing: Boolean get() = capture != null

    /**
     * Lifts [box], or returns false when this bubble is better served by the P4 camera.
     *
     * Refusals are all cheap checks made before anything is allocated: the image must be ready
     * and sized, the padded bubble must be wholly on screen, and the magnification must fall in
     * the band where a frozen capture still adds detail.
     */
    fun show(box: BubbleBox): Boolean {
        if (!imageView.isReady) return false
        val viewportWidth = imageView.width.toFloat()
        val viewportHeight = imageView.height.toFloat()
        if (viewportWidth <= 0f || viewportHeight <= 0f) return false

        val sourceWidth = imageView.sWidth.toFloat()
        val sourceHeight = imageView.sHeight.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return false

        val padded = box.padded(BubbleZoomConfig.FOCUS_PADDING)
        val topLeft = imageView.sourceToViewCoord(padded.x * sourceWidth, padded.y * sourceHeight)
            ?: return false
        val bottomRight = imageView
            .sourceToViewCoord(padded.right * sourceWidth, padded.bottom * sourceHeight)
            ?: return false

        val onScreen = CardRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
        if (!BubbleCardGeometry.fullyVisible(onScreen, viewportWidth, viewportHeight)) return false

        val scale = BubbleCardGeometry.cardScale(
            paddedWidthSourcePx = padded.width * sourceWidth,
            paddedHeightSourcePx = padded.height * sourceHeight,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        if (!BubbleCardGeometry.magnificationAllows(scale, imageView.scale)) return false

        val cardWidth = padded.width * sourceWidth * scale
        val cardHeight = padded.height * sourceHeight * scale
        if (cardWidth < 1f || cardHeight < 1f) return false

        val target = BubbleCardGeometry.place(
            cardWidthPx = cardWidth,
            cardHeightPx = cardHeight,
            bubbleCentreXPx = onScreen.centerX,
            bubbleCentreYPx = onScreen.centerY,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
            marginPx = screenMargin,
        )

        // Captured before anything is torn down, so a refusal leaves the current card untouched
        // and the walk never flashes the bare page between two bubbles.
        val fresh = captureCard(onScreen, cardWidth, cardHeight) ?: return false

        outgoing?.recycle()
        outgoing = capture
        outgoingRect.set(cardRect)
        capture = fresh
        cardRect.set(target.left, target.top, target.right, target.bottom)

        animateTo(1f, BubbleZoomConfig.CARD_ENTER_DURATION_MS)
        return true
    }

    /** Fades the card away, if there is one. */
    fun dismiss() {
        val dismissing = capture ?: return
        animateTo(0f, BubbleZoomConfig.CARD_EXIT_DURATION_MS) {
            // Identity, not the field: an exit animation is cancelled by the next [show], whose
            // own end callback runs synchronously from that cancel. Reading the field here
            // would recycle the card show() had just installed, leaving the walk with a
            // consumed tap and nothing on screen.
            if (capture === dismissing) {
                dismissing.recycle()
                capture = null
            }
        }
    }

    /** Drops the card at once, with no animation. For recycling and teardown. */
    fun clear() {
        animator?.cancel()
        animator = null
        progress = 0f
        alpha = 0f
        capture?.recycle()
        capture = null
        outgoing?.recycle()
        outgoing = null
        invalidate()
    }

    /**
     * Called when the page moved for a reason other than an animation the feature started.
     * A frozen capture no longer matches what is underneath, so it goes rather than lying.
     */
    fun onPageMoved() = dismiss()

    private fun captureCard(onScreen: CardRect, cardWidth: Float, cardHeight: Float): Bitmap? {
        val width = cardWidth.toInt().coerceAtLeast(1)
        val height = cardHeight.toInt().coerceAtLeast(1)
        val bitmap = runCatching { createBitmap(width, height) }.getOrNull() ?: return null

        val canvas = Canvas(bitmap)
        // Map the bubble's on-screen rectangle onto the whole card, then replay the page view
        // through that matrix. Skia folds this into the tile draw, so it stays a single
        // filtered sample from whatever resolution the library already has in memory.
        val matrix = Matrix()
        matrix.setRectToRect(
            RectF(onScreen.left, onScreen.top, onScreen.right, onScreen.bottom),
            RectF(0f, 0f, cardWidth, cardHeight),
            Matrix.ScaleToFit.FILL,
        )
        canvas.withSave {
            concat(matrix)
            runCatching { imageView.draw(this) }.onFailure {
                bitmap.recycle()
                return null
            }
        }
        return bitmap
    }

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun animateTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        // Cancelling runs the outgoing animation's own end callback synchronously, right here,
        // before the new one is built. Everything those callbacks touch is therefore compared
        // by identity, so a cancel can only ever clean up what it actually owned.
        val fading = outgoing
        animator?.cancel()
        val scaled = (durationMs * context.animatorDurationScale).toLong().coerceAtLeast(1L)
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = scaled
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                alpha = progress
                invalidate()
            }
            doOnEnd {
                if (fading != null && outgoing === fading) {
                    fading.recycle()
                    outgoing = null
                }
                onEnd?.invoke()
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = capture ?: return

        // The outgoing bubble fades out underneath while the card morphs, so consecutive
        // bubbles never expose the bare page between them.
        outgoing?.let { previous ->
            drawRect.set(outgoingRect)
            drawCard(canvas, previous, drawRect, 1f - progress)
        }

        // Scales in from slightly small, which reads as a lift rather than a pop.
        val grow = 0.92f + 0.08f * progress
        val halfW = cardRect.width() / 2f * grow
        val halfH = cardRect.height() / 2f * grow
        drawRect.set(
            cardRect.centerX() - halfW,
            cardRect.centerY() - halfH,
            cardRect.centerX() + halfW,
            cardRect.centerY() + halfH,
        )
        drawCard(canvas, current, drawRect, progress)
    }

    private fun drawCard(canvas: Canvas, bitmap: Bitmap, rect: RectF, opacity: Float) {
        if (bitmap.isRecycled || opacity <= 0f) return
        val alpha8 = (opacity.coerceIn(0f, 1f) * 255).toInt()
        bitmapPaint.alpha = alpha8
        backgroundPaint.alpha = alpha8
        canvas.withSave {
            clipRoundRect(rect)
            drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
            drawBitmap(bitmap, null, rect, bitmapPaint)
        }
        outerBorderPaint.alpha = (alpha8 * 0.33f).toInt()
        innerBorderPaint.alpha = (alpha8 * 0.73f).toInt()
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outerBorderPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, innerBorderPaint)
    }

    private fun Canvas.clipRoundRect(rect: RectF) {
        val path = android.graphics.Path().apply {
            addRoundRect(rect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
        }
        clipPath(path)
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    )
}
