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
     * Deliberately the same number `focusOnBubble` uses as its target scale. The card and the
     * P4 camera therefore frame a bubble identically, which is what makes falling back to the
     * camera invisible, and what makes the two equally sharp: the tile resolution the image
     * view serves is a function of scale and nothing else.
     */
    fun cardScale(
        paddedWidthSourcePx: Float,
        paddedHeightSourcePx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Float {
        if (paddedWidthSourcePx <= 0f || paddedHeightSourcePx <= 0f) return 0f
        val byWidth = BubbleZoomConfig.TARGET_WIDTH_FRACTION * viewportWidthPx / paddedWidthSourcePx
        val byHeight = BubbleZoomConfig.TARGET_HEIGHT_FRACTION * viewportHeightPx / paddedHeightSourcePx
        return minOf(byWidth, byHeight)
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
     * Whether a card is worth lifting for this bubble, or the P4 camera should take it instead.
     *
     * Below the lower bound the card would be the same size as the bubble already on screen and
     * would read as a pointless frame. Above the upper bound it becomes empty magnification:
     * past that the camera can reach a finer tile level that a frozen capture never asked for,
     * so the camera genuinely shows more, not just bigger.
     */
    fun magnificationAllows(cardScale: Float, currentViewScale: Float): Boolean {
        if (currentViewScale <= 0f || cardScale <= 0f) return false
        val magnification = cardScale / currentViewScale
        return magnification >= BubbleZoomConfig.CARD_MIN_MAGNIFICATION &&
            magnification <= BubbleZoomConfig.CARD_MAX_MAGNIFICATION
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

    /**
     * Whether the padded bubble is wholly on screen right now.
     *
     * The card is captured by replaying the page view through a magnifying matrix, so anything
     * the view is not currently drawing comes out blank. A partly scrolled-off bubble would
     * produce a card with an empty band, which is worse than no card at all.
     */
    fun fullyVisible(bubbleOnScreen: CardRect, viewportWidthPx: Float, viewportHeightPx: Float): Boolean {
        return bubbleOnScreen.left >= 0f &&
            bubbleOnScreen.top >= 0f &&
            bubbleOnScreen.right <= viewportWidthPx &&
            bubbleOnScreen.bottom <= viewportHeightPx &&
            bubbleOnScreen.width > 0f &&
            bubbleOnScreen.height > 0f
    }
}
