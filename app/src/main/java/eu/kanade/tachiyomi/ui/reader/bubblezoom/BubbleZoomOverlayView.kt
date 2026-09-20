package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withSave
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

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
 * Those pixels are then cut to the bubble's own shape. [BubbleMask] traces the silhouette out
 * of the capture itself and it is multiplied into the capture's alpha, so what rises off the
 * page is the balloon and not a rectangle of page around it. When the outline cannot be traced
 * — an open bubble, a caption with no border, a balloon whose tone matches the art around it —
 * the card falls back to the rounded rectangle, which is never wrong, only plainer.
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

    /**
     * One lifted bubble: its pixels, the shape they were cut to, and where it sits.
     *
     * Held together rather than as parallel fields because the fades compare by identity to
     * decide what they may recycle, and three fields that must agree about which bitmap is
     * which is exactly the bookkeeping that went wrong before.
     */
    private class Card(val bitmap: Bitmap, val mask: Bitmap?, val rect: RectF) {
        fun recycle() {
            bitmap.recycle()
            mask?.recycle()
        }
    }

    private val cornerRadius = dp(BubbleZoomConfig.CARD_CORNER_RADIUS_DP)
    private val screenMargin = dp(BubbleZoomConfig.CARD_SCREEN_MARGIN_DP)
    private val haloWidth = dp(BubbleZoomConfig.CARD_HALO_WIDTH_DP)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** Multiplies the traced silhouette into the capture's alpha, once, at capture time. */
    private val cutoutPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // Two tones, light over dark, so the edge survives both white paper and black ink without a
    // scrim. The same trick ReaderNavigationOverlayView uses to keep its labels readable.
    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BubbleZoomConfig.CARD_BORDER_WIDTH_DP) * 2f
        color = OUTER_TONE
    }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BubbleZoomConfig.CARD_BORDER_WIDTH_DP)
        color = INNER_TONE
    }

    // The same two tones for a cut-out, where there is no rectangle to stroke. The silhouette
    // is drawn twice behind the card, each time a little larger, and a colour filter turns it
    // into flat ink: SRC_IN keeps the mask's alpha and takes the tone's, so the paint's own
    // alpha stays free to carry the fade.
    private val outerHaloPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(OUTER_TONE, PorterDuff.Mode.SRC_IN)
    }
    private val innerHaloPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(INNER_TONE, PorterDuff.Mode.SRC_IN)
    }

    private var current: Card? = null
    private var outgoing: Card? = null

    /**
     * The card an exit fade is already running for.
     *
     * Dismissing twice in a row is the normal case, not a corner: a pan while a bubble is
     * focused makes the holder call [onPageMoved] and then reset the controller, whose new
     * state reaches the presenter as a second dismiss. Without this the second call would
     * cancel the first fade, and cancelling runs the first callback at once — recycling the
     * bitmaps a frame into a 180ms fade, while they are still fully opaque on screen.
     */
    private var exiting: Card? = null

    /** 0 while hidden, 1 while fully shown; drives both the entrance and the cross-fade. */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val drawRect = RectF()
    private val haloRect = RectF()

    init {
        // No touch handling at all, so every tap still reaches the pager and navigates.
        isClickable = false
        isFocusable = false
        elevation = dp(BubbleZoomConfig.CARD_ELEVATION_DP)
        // The view stays opaque and draws nothing when there is no card. Fading the view as
        // well as the card would square the opacity, and during a cross-fade it would take
        // both cards down together and show the bare page through the swap.
    }

    /** Whether a card is currently lifted. */
    val isShowing: Boolean get() = current != null

    /**
     * Lifts [box], or returns false when this bubble is better served by the P4 camera.
     *
     * Refusals are all cheap checks made before anything is allocated: the image must be ready
     * and sized, the padded bubble must be wholly on screen, and the magnification must fall in
     * the band where a frozen capture still adds detail. Failing to trace the bubble's shape is
     * not one of them — that only costs the card its silhouette, not its existence.
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
        val pixels = captureCard(onScreen, cardWidth, cardHeight) ?: return false
        val mask = traceShape(
            capture = pixels,
            paddedLongSourcePx = maxOf(padded.width * sourceWidth, padded.height * sourceHeight),
            pageWidthSourcePx = sourceWidth,
        )
        if (mask != null) cutOut(pixels, mask)

        outgoing?.recycle()
        outgoing = current
        current = Card(pixels, mask, RectF(target.left, target.top, target.right, target.bottom))
        exiting = null

        // Rewound rather than continued. A card that replaces another arrives with progress
        // already at 1, and animating 1 to 1 is a hard cut: no lift on the new card and, worse,
        // the outgoing one drawn at an opacity of zero, so the walk jumps between bubbles.
        progress = 0f
        animateTo(1f, BubbleZoomConfig.CARD_ENTER_DURATION_MS)
        return true
    }

    /** Fades the card away, if there is one and one is not already on its way out. */
    fun dismiss() {
        val dismissing = current ?: return
        if (exiting === dismissing) return
        exiting = dismissing
        animateTo(0f, BubbleZoomConfig.CARD_EXIT_DURATION_MS) {
            if (exiting === dismissing) exiting = null
            // Identity, not the field: an exit animation is cancelled by the next [show], whose
            // own end callback runs synchronously from that cancel. Reading the field here
            // would recycle the card show() had just installed, leaving the walk with a
            // consumed tap and nothing on screen.
            if (current === dismissing) {
                dismissing.recycle()
                current = null
            }
        }
    }

    /** Drops the card at once, with no animation. For recycling and teardown. */
    fun clear() {
        animator?.cancel()
        animator = null
        progress = 0f
        current?.recycle()
        current = null
        outgoing?.recycle()
        outgoing = null
        exiting = null
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

    /**
     * The bubble's silhouette as a white stencil, or null when it could not be traced.
     *
     * Reduced first, both to keep the trace off the frame budget and because reducing is what
     * turns screentone into a tone. The stencil is kept after it has been applied: it is also
     * what the halo is drawn from, since a cut-out has no rectangle left to stroke.
     *
     * The page's own measurements come down with it, because how far the silhouette has to grow
     * to pick up the bubble's outline is a fact about the page and not about the mask.
     */
    private fun traceShape(
        capture: Bitmap,
        paddedLongSourcePx: Float,
        pageWidthSourcePx: Float,
    ): Bitmap? {
        val longSide = maxOf(capture.width, capture.height).toFloat()
        val factor = (longSide / BubbleZoomConfig.MASK_LONG_SIDE_PX).coerceAtLeast(1f)
        val width = (capture.width / factor).toInt().coerceAtLeast(MIN_MASK_SIDE)
        val height = (capture.height / factor).toInt().coerceAtLeast(MIN_MASK_SIDE)

        val reduced = runCatching { Bitmap.createScaledBitmap(capture, width, height, true) }
            .getOrNull() ?: return null
        val pixels = IntArray(width * height)
        reduced.getPixels(pixels, 0, width, 0, 0, width, height)
        // createScaledBitmap hands back the original when nothing had to change.
        if (reduced !== capture) reduced.recycle()

        val shape = BubbleMask.extract(
            pixels = pixels,
            width = width,
            height = height,
            growPasses = BubbleCardGeometry.outlineGrowPasses(
                maskLongSidePx = maxOf(width, height),
                paddedLongSourcePx = paddedLongSourcePx,
                pageWidthSourcePx = pageWidthSourcePx,
            ),
        ) ?: return null
        for (index in pixels.indices) {
            pixels[index] = if (shape[index]) Color.WHITE else Color.TRANSPARENT
        }
        return runCatching { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull()
    }

    /** Multiplies the stencil into the capture's alpha, so the page around the bubble goes. */
    private fun cutOut(capture: Bitmap, mask: Bitmap) {
        val full = RectF(0f, 0f, capture.width.toFloat(), capture.height.toFloat())
        Canvas(capture).drawBitmap(mask, null, full, cutoutPaint)
    }

    private fun createBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun animateTo(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        // Cancelling runs the outgoing animation's own end callback synchronously, right here,
        // before the new one is built. Everything those callbacks touch is therefore compared
        // by identity, so a cancel can only ever clean up what it actually owned.
        val fading = outgoing
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            // Not scaled by animatorDurationScale here: ValueAnimator already multiplies every
            // duration by that same setting internally, and doing it twice made the card's
            // timing quadratic in a setting the reader can change.
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
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
        val card = current ?: return

        // The outgoing bubble fades out underneath while the card morphs, so consecutive
        // bubbles never expose the bare page between them.
        outgoing?.let { previous ->
            drawRect.set(previous.rect)
            drawCard(canvas, previous, drawRect, 1f - progress)
        }

        // Scales in from slightly small, which reads as a lift rather than a pop.
        val grow = 0.92f + 0.08f * progress
        val halfW = card.rect.width() / 2f * grow
        val halfH = card.rect.height() / 2f * grow
        drawRect.set(
            card.rect.centerX() - halfW,
            card.rect.centerY() - halfH,
            card.rect.centerX() + halfW,
            card.rect.centerY() + halfH,
        )
        drawCard(canvas, card, drawRect, progress)
    }

    private fun drawCard(canvas: Canvas, card: Card, rect: RectF, opacity: Float) {
        if (card.bitmap.isRecycled || opacity <= 0f) return
        val alpha8 = (opacity.coerceIn(0f, 1f) * 255).toInt()
        val mask = card.mask
        if (mask != null && !mask.isRecycled) {
            drawCutOut(canvas, card.bitmap, mask, rect, alpha8)
        } else {
            drawRectangle(canvas, card.bitmap, rect, alpha8)
        }
    }

    /**
     * The bubble itself, over a halo of its own shape.
     *
     * Each tone is the stencil again, stretched into a slightly larger rectangle: for a shape
     * that all but fills its box the ring that shows past the bubble is even enough to read as
     * a cut edge, and it costs nothing beyond two more draws of a bitmap already in hand.
     */
    private fun drawCutOut(canvas: Canvas, bitmap: Bitmap, mask: Bitmap, rect: RectF, alpha8: Int) {
        outerHaloPaint.alpha = alpha8
        haloRect.set(rect)
        haloRect.inset(-haloWidth * 2f, -haloWidth * 2f)
        canvas.drawBitmap(mask, null, haloRect, outerHaloPaint)

        innerHaloPaint.alpha = alpha8
        haloRect.set(rect)
        haloRect.inset(-haloWidth, -haloWidth)
        canvas.drawBitmap(mask, null, haloRect, innerHaloPaint)

        bitmapPaint.alpha = alpha8
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
    }

    private fun drawRectangle(canvas: Canvas, bitmap: Bitmap, rect: RectF, alpha8: Int) {
        bitmapPaint.alpha = alpha8
        backgroundPaint.alpha = alpha8
        canvas.withSave {
            clipRoundRect(rect)
            drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
            drawBitmap(bitmap, null, rect, bitmapPaint)
        }
        outerBorderPaint.alpha = (alpha8 * Color.alpha(OUTER_TONE) / 255f).toInt()
        innerBorderPaint.alpha = (alpha8 * Color.alpha(INNER_TONE) / 255f).toInt()
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

    private companion object {
        val OUTER_TONE = Color.argb(0x55, 0, 0, 0)
        val INNER_TONE = Color.argb(0xBB, 0xFF, 0xFF, 0xFF)
        const val MIN_MASK_SIDE = 3
    }
}
