package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Every tunable number the zoom behaviour depends on.
 *
 * Nothing in the bubblezoom package may hardcode a threshold, a padding or a duration; it
 * belongs here so it can be changed in one place and reasoned about as a set.
 */
object BubbleZoomConfig {

    /**
     * How much breathing room to leave around a bubble, as a fraction of its own smallest
     * dimension. Matches `boxPadding` in docs/BUBBLE_ZOOM_SPEC.md section 7.
     *
     * This is a presentation concern: detections are stored raw and padded only when framing.
     */
    const val FOCUS_PADDING = 0.20f

    /**
     * Fraction of the usable viewport the padded box should occupy.
     *
     * The target scale is the SMALLER of the two, so the box fits on both axes. Driving off
     * the width alone, as a first reading of the phase brief suggests, overflows vertically
     * for a tall narrow bubble: a box twice as tall as it is wide, scaled so its width is 60%
     * of a 1080px viewport, ends up 1290px tall on a 2400px screen — fine here, but on a
     * landscape viewport of 1080px height it would be cut off.
     */
    const val TARGET_WIDTH_FRACTION = 0.60f
    const val TARGET_HEIGHT_FRACTION = 0.60f

    /** Duration of the animation onto a bubble. */
    const val FOCUS_DURATION_MS = 200L

    /** Duration of the animation back to the page's default fit. */
    const val RESET_DURATION_MS = 200L

    /** How long the lifted card takes to appear, and to morph from one bubble to the next. */
    const val CARD_ENTER_DURATION_MS = 200L

    /** How long it takes to fade away. Slightly quicker, so dismissing feels responsive. */
    const val CARD_EXIT_DURATION_MS = 180L

    /** Gap kept between the card and the edge of the viewport, in dp. */
    const val CARD_SCREEN_MARGIN_DP = 12f

    /** Corner radius of the card, in dp. */
    const val CARD_CORNER_RADIUS_DP = 8f

    /** Elevation of the card, in dp. With no scrim this is what separates it from the page. */
    const val CARD_ELEVATION_DP = 8f

    /** Width of the hairline that keeps the card's edge readable over white paper, in dp. */
    const val CARD_BORDER_WIDTH_DP = 1f

    /**
     * The card is only worth lifting inside this magnification band, measured against the scale
     * the page is already at.
     *
     * Below the lower bound it would be barely larger than the bubble already on screen. Above
     * the upper bound it stops adding detail: the capture is frozen at the tile resolution the
     * page is being drawn at, while the P4 camera, by actually changing the view's scale, can
     * cross a power-of-two threshold and have the library decode finer tiles. Past that point
     * the camera genuinely shows more rather than merely bigger, so it takes over.
     */
    const val CARD_MIN_MAGNIFICATION = 1.1f
    const val CARD_MAX_MAGNIFICATION = 2.6f
}
