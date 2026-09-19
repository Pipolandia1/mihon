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
}
