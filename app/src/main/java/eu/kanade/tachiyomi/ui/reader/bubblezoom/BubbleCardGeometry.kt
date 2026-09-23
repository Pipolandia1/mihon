package eu.kanade.tachiyomi.ui.reader.bubblezoom

import kotlin.math.roundToInt

/** A rectangle in pixels, in whatever single space the caller is working in. */
data class CardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * All of the lifted card's arithmetic, with no view and no Android type, so it can be unit
 * tested on the JVM the way [BubbleBox] and [BubbleZoomController] are.
 */
object BubbleCardGeometry {

    /**
     * How many view pixels one source pixel becomes inside the card.
     *
     * [BubbleZoomConfig.CARD_MAGNIFICATION] times the scale the page is drawn at, held to the
     * viewport fractions for a balloon too large for that (amendment E2). The number used to be
     * shared with the P4 camera, so that falling back to it was invisible; the camera is gone.
     */
    fun cardScale(
        paddedWidthSourcePx: Float,
        paddedHeightSourcePx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        currentViewScale: Float,
    ): Float {
        if (paddedWidthSourcePx <= 0f || paddedHeightSourcePx <= 0f) return 0f
        val byWidth = BubbleZoomConfig.TARGET_WIDTH_FRACTION * viewportWidthPx / paddedWidthSourcePx
        val byHeight = BubbleZoomConfig.TARGET_HEIGHT_FRACTION * viewportHeightPx / paddedHeightSourcePx
        // Since E2 the fractions are a ceiling, not the target: the card is CARD_MAGNIFICATION
        // times what is on screen, and only a balloon too large for that is held to the
        // viewport. An unknown view scale (zero or less) leaves the ceiling alone.
        val byMagnification =
            if (currentViewScale > 0f) BubbleZoomConfig.CARD_MAGNIFICATION * currentViewScale else Float.MAX_VALUE
        return minOf(byWidth, byHeight, byMagnification)
    }

    /**
     * Where the card sits, centred on the bubble and then pushed back inside the viewport.
     *
     * It shifts rather than clips: a bubble against a page edge produces a card flush with that
     * edge, still at full size. When the card is larger than the viewport on an axis — only
     * reachable with an absurd magnification — it is centred on that axis instead, which is the
     * least wrong thing available.
     */
    fun place(
        cardWidthPx: Float,
        cardHeightPx: Float,
        bubbleCentreXPx: Float,
        bubbleCentreYPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        marginPx: Float,
    ): CardRect {
        val left = axis(cardWidthPx, bubbleCentreXPx, viewportWidthPx, marginPx)
        val top = axis(cardHeightPx, bubbleCentreYPx, viewportHeightPx, marginPx)
        return CardRect(left, top, left + cardWidthPx, top + cardHeightPx)
    }

    private fun axis(cardSize: Float, centre: Float, viewport: Float, margin: Float): Float {
        val ideal = centre - cardSize / 2f
        val room = viewport - cardSize - 2 * margin
        if (room < 0f) return (viewport - cardSize) / 2f
        return ideal.coerceIn(margin, margin + room)
    }

    /**
     * Where the detected text sits inside the search region, in mask pixels.
     *
     * The silhouette has to be asked whether it holds the text it was found for; the text's box
     * and the search region are both fractions of the page, so this is arithmetic rather than
     * geometry. Clamped into the grid, and never empty.
     */
    fun boxWithin(
        box: BubbleBox,
        search: BubbleBox,
        maskWidth: Int,
        maskHeight: Int,
    ): BubbleMask.MaskRect {
        val left = ((box.x - search.x) / search.width * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val top = ((box.y - search.y) / search.height * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        val right = ((box.right - search.x) / search.width * maskWidth)
            .roundToInt()
            .coerceIn(left + 1, maskWidth)
        val bottom = ((box.bottom - search.y) / search.height * maskHeight)
            .roundToInt()
            .coerceIn(top + 1, maskHeight)
        return BubbleMask.MaskRect(left, top, right, bottom)
    }

    /**
     * The page-space box a silhouette occupies: what amendment E3 frames the card with.
     *
     * [bounds] are mask pixels inside [search], which is itself a fraction of the page, so this
     * maps one into the other. The result is the balloon as the trace found it — not the text's
     * box, and not an arbitrary padding of it.
     */
    fun boxOfSilhouette(
        bounds: BubbleMask.MaskRect,
        search: BubbleBox,
        maskWidth: Int,
        maskHeight: Int,
    ): BubbleBox? {
        if (maskWidth <= 0 || maskHeight <= 0) return null
        val x = search.x + search.width * bounds.left / maskWidth
        val y = search.y + search.height * bounds.top / maskHeight
        val width = search.width * bounds.width / maskWidth
        val height = search.height * bounds.height / maskHeight
        return BubbleBox.clampedOrNull(x, y, width, height)
    }

    /**
     * The padded balloon as integer source pixels, for a region decoder.
     *
     * Clamped to the image and never empty: a region decoder throws on an empty rectangle, and
     * a padded box truncated at the page edge can round to one.
     */
    fun regionOf(padded: BubbleBox, sourceWidth: Int, sourceHeight: Int): SourceRegion? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val left = (padded.x * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
        val top = (padded.y * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
        val right = (padded.right * sourceWidth).roundToInt().coerceIn(left + 1, sourceWidth)
        val bottom = (padded.bottom * sourceHeight).roundToInt().coerceIn(top + 1, sourceHeight)
        return SourceRegion(left, top, right, bottom)
    }

    /**
     * The power-of-two sample size for decoding [region] into a card [cardWidthPx] by
     * [cardHeightPx]: the largest that still leaves a source pixel per card pixel, and never so
     * small that the decode exceeds [BubbleZoomConfig.CARD_MAX_DECODE_PIXELS].
     */
    fun sampleSize(region: SourceRegion, cardWidthPx: Float, cardHeightPx: Float): Int {
        var sample = 1
        while (region.width / (sample * 2f) >= cardWidthPx && region.height / (sample * 2f) >= cardHeightPx) {
            sample *= 2
        }
        while ((region.width.toLong() / sample) * (region.height.toLong() / sample) >
            BubbleZoomConfig.CARD_MAX_DECODE_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * How far [BubbleMask] must grow its silhouette to take the bubble's own outline with it.
     *
     * The fill stops at the inside edge of the line, so the line has to be added back, and how
     * many mask pixels it occupies is not something the mask can see. Only three numbers decide
     * it: how thick the line is on the page, how much of the page the card covers, and that the
     * mask is always [BubbleZoomConfig.MASK_LONG_SIDE_PX] on its long side.
     *
     * Sizing it off the mask's own dimensions instead, as a first attempt did, is wrong for a
     * reason worth stating: the card is always framed to fill the same share of the viewport, so
     * a small bubble is magnified harder and its line lands THICKER in the mask than a large
     * bubble's, from the very same pen. The amount has to fall as the bubble grows, and the
     * mask's dimensions barely move.
     */
    fun outlineGrowPasses(
        maskLongSidePx: Int,
        paddedLongSourcePx: Float,
        pageWidthSourcePx: Float,
    ): Int {
        if (paddedLongSourcePx <= 0f || pageWidthSourcePx <= 0f || maskLongSidePx <= 0) return 1
        val outlineSourcePx = BubbleZoomConfig.MASK_OUTLINE_PAGE_FRACTION * pageWidthSourcePx
        val outlineMaskPx = outlineSourcePx * maskLongSidePx / paddedLongSourcePx
        return (outlineMaskPx * BubbleZoomConfig.MASK_OUTLINE_GENEROSITY)
            .roundToInt()
            .coerceIn(1, BubbleZoomConfig.MASK_MAX_GROW_PASSES)
    }
}

/**
 * A rectangle of source pixels as a region decoder wants it: left and top inclusive, right and
 * bottom exclusive.
 */
data class SourceRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
