package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.graphics.PointF
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView

/**
 * Moves the reader's camera onto [box], padded by [BubbleZoomConfig.FOCUS_PADDING].
 *
 * The target scale is the smaller of what each axis allows, so the padded box fits both
 * ways rather than overflowing vertically when it is tall and narrow.
 *
 * Degenerate cases are all absorbed, mostly by the image view itself:
 * - a box larger than the viewport asks for a scale below `minScale` and is clamped to the
 *   page's fit, which is the most that can be shown;
 * - a box against a page edge cannot be centred without panning off the page, so
 *   `SubsamplingScaleImageView` pulls the centre back in — the bubble ends up framed but
 *   off-centre, which is correct, not a bug;
 * - a request made before the image is ready, or on a view with no size yet, does nothing.
 *
 * An animation already in flight does not need cancelling: `AnimationBuilder.start()`
 * notifies the outgoing animation's listener and replaces it.
 */
fun SubsamplingScaleImageView.focusOnBubble(box: BubbleBox) {
    if (!isReady) return

    val viewWidth = width - paddingLeft - paddingRight
    val viewHeight = height - paddingTop - paddingBottom
    if (viewWidth <= 0 || viewHeight <= 0) return

    val sourceWidth = sWidth.toFloat()
    val sourceHeight = sHeight.toFloat()
    if (sourceWidth <= 0f || sourceHeight <= 0f) return

    // Padding is applied here and never persisted. Note that padded() truncates at the page
    // edges, so the result can be smaller than requested — which is why the scale below is
    // computed from the returned box and not from box.width * (1 + 2 * FOCUS_PADDING).
    val padded = box.padded(BubbleZoomConfig.FOCUS_PADDING)
    val boxWidthPx = padded.width * sourceWidth
    val boxHeightPx = padded.height * sourceHeight
    if (boxWidthPx <= 0f || boxHeightPx <= 0f) return

    val byWidth = BubbleZoomConfig.TARGET_WIDTH_FRACTION * viewWidth / boxWidthPx
    val byHeight = BubbleZoomConfig.TARGET_HEIGHT_FRACTION * viewHeight / boxHeightPx
    // The image view clamps this again in limitedScale(); doing it here too keeps the value
    // honest for anything that wants to reason about what was actually asked for.
    val targetScale = minOf(byWidth, byHeight).coerceIn(minScale, maxScale)

    val target = PointF(padded.centerX * sourceWidth, padded.centerY * sourceHeight)
    animateScaleAndCenter(targetScale, target)
        ?.withDuration(BubbleZoomConfig.FOCUS_DURATION_MS)
        ?.withEasing(SubsamplingScaleImageView.EASE_OUT_QUAD)
        ?.withInterruptible(true)
        ?.start()
}

/**
 * Animates back to [toScale] and [toCenter], which the caller captured before the first
 * [focusOnBubble] of a sequence.
 *
 * The target is remembered rather than recomputed on purpose. "The page's default fit" is not
 * `minScale` centred on the image: Mihon frames a fresh page with `setScaleAndCenter(scale,
 * corner)` in `ReaderPageImageView.setupZoom`, where the corner comes from `zoomStartPosition`
 * — and the shipped default resolves to RIGHT, because the default reading mode is
 * right-to-left and `zoomStart` defaults to Auto. Animating to the geometric centre would
 * leave the reader somewhere the page never was, whenever the page still overflows the
 * viewport at `minScale` (any "fit width" page taller than the screen, a double-page spread,
 * or a page that `landscapeZoom` already zoomed into).
 *
 * Restoring the captured state also gives the later phases what they actually need: after the
 * last bubble, the page goes back to exactly how the reader left it, and normal navigation
 * resumes without a visible jump.
 */
fun SubsamplingScaleImageView.resetBubbleZoom(toScale: Float, toCenter: PointF) {
    if (!isReady) return

    animateScaleAndCenter(toScale, toCenter)
        ?.withDuration(BubbleZoomConfig.RESET_DURATION_MS)
        ?.withEasing(SubsamplingScaleImageView.EASE_OUT_QUAD)
        ?.withInterruptible(true)
        ?.start()
}
