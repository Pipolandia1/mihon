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

    /**
     * How much larger than on screen the card shows the balloon, against the scale the page is
     * drawn at right now.
     *
     * Google Play Books, measured on four cards: 2.55–3.32×, with the width fraction above as
     * the ceiling that stops a wide caption growing past the screen. Without this term a small
     * balloon is framed to fill 60 % of the viewport — 4× and more on a real page — which the
     * P9 verification saw refused for every real balloon there was.
     */
    const val CARD_MAGNIFICATION = 2.8f

    /**
     * How far outside a detected box a double tap may land and still mean that balloon, as a
     * fraction of the box's smallest side.
     *
     * The detector boxes the text, not the balloon (P9: 31–69 % of the balloon's area), so a
     * reader who double taps the balloon's margin is still aiming at it. Half the text's
     * smallest side reaches the outline of every balloon measured and no neighbour's.
     */
    const val DOUBLE_TAP_HIT_TOLERANCE = 0.5f

    /** Shortest vertical drag, in dp, that counts as a swipe between bubbles. */
    const val SWIPE_MIN_DISTANCE_DP = 48f

    /** Slowest vertical fling, in dp per second, that counts as a swipe between bubbles. */
    const val SWIPE_MIN_VELOCITY_DP_PER_S = 400f

    /**
     * Swipe up is next and swipe down is previous: the scroll convention, and what Google Play
     * Books does (measured six times). One constant to flip for the other reading.
     */
    const val SWIPE_UP_IS_NEXT = true

    /**
     * Most pixels a card's region decode may produce; past it the sample size doubles. A card
     * is a few hundred pixels on a side, so anything above this is detail no screen can show,
     * at a memory cost the reader would feel.
     */
    const val CARD_MAX_DECODE_PIXELS = 2_000_000

    /** How long the lifted card takes to appear, and to morph from one bubble to the next. */
    const val CARD_ENTER_DURATION_MS = 200L

    /** How long it takes to fade away. Slightly quicker, so dismissing feels responsive. */
    const val CARD_EXIT_DURATION_MS = 180L

    /**
     * How many pages past the next one are looked at before the reader gets there.
     *
     * The pager already gives a lead of one page, and that lead is only as long as the reader
     * lingers; with a model that takes seconds, briskly turned pages arrive first. Three more
     * is enough that an ordinary reading pace never catches up, and few enough that the bytes
     * they hold stay small next to what the reader already holds decoded.
     */
    const val PREFETCH_PAGES = 3

    /** Gap kept between the card and the edge of the viewport, in dp. */
    const val CARD_SCREEN_MARGIN_DP = 12f

    /** Corner radius of the card, in dp. */
    const val CARD_CORNER_RADIUS_DP = 8f

    /** Elevation of the card, in dp. With no scrim this is what separates it from the page. */
    const val CARD_ELEVATION_DP = 8f

    /** Width of the hairline that keeps the card's edge readable over white paper, in dp. */
    const val CARD_BORDER_WIDTH_DP = 1f

    /**
     * Long side, in pixels, the capture is reduced to before [BubbleMask] traces its outline.
     *
     * Small enough that the whole trace is a fraction of a millisecond, and small enough that
     * reducing to it averages screentone into flat grey instead of leaving a field of holes.
     * The mask is drawn back up filtered, so the roughly threefold upscale is what gives the
     * cut-out its antialiased edge rather than a staircase.
     */
    const val MASK_LONG_SIDE_PX = 192

    /**
     * Half-size of the seed patch at the middle of the mask, as a fraction of its smaller side.
     *
     * Large enough that its median cannot be a letter, small enough that it stays inside the
     * bubble even when the padding was truncated against a page edge and the bubble is
     * therefore off-centre in the card.
     */
    const val MASK_SEED_PATCH_FRACTION = 0.10f

    /**
     * Luminance distance, 0..1, within which a neighbouring pixel is still the same surface.
     *
     * This is what the bubble's outline has to beat to stop the fill. At the mask's resolution
     * a five pixel outline on a 1600px page survives as about two pixels of near-black against
     * white paper, a gap of roughly 0.9 — so there is a wide margin before a thin or a
     * grey outline starts leaking, and leaking is refused rather than guessed at.
     */
    const val MASK_TOLERANCE = 0.18f

    /**
     * How thick a bubble's outline is, as a fraction of the page's width.
     *
     * Line weight belongs to the drawing, not to the balloon: the same pen draws a big bubble
     * and a small one, so an outline is a constant fraction of the PAGE and not of the bubble.
     * The synthetic test pages use five pixels on a page 1600 wide, which is where this comes
     * from, and printed manga scanned at that width sits in the same place.
     */
    const val MASK_OUTLINE_PAGE_FRACTION = 0.003f

    /**
     * How far past the measured outline the silhouette grows anyway.
     *
     * The two errors are not symmetric. Growing too far shows a sliver of page around the
     * bubble, which reads as a cut edge; growing too little saws through the bubble's own line
     * and leaves a bare blob of paper floating. So it errs outwards.
     */
    const val MASK_OUTLINE_GENEROSITY = 1.5f

    /** Ceiling on that growth, so a degenerate measurement cannot dilate the shape into a box. */
    const val MASK_MAX_GROW_PASSES = 8

    /**
     * How far past the detected box to look for the balloon, as a fraction of the box's own
     * smallest side. Amendment E3.
     *
     * The detector boxes the TEXT, so the balloon around it is not in the detection at all: at
     * the padding the card is framed with, the balloon's outline is cut off by the capture and
     * the fill runs out through the open end — measured on a real comic, nine balloons of nine
     * refused. The silhouette is therefore traced in a region far larger than the card will be,
     * and then the silhouette decides what the card shows. 1.2 was measured: the balloon then
     * covers 10 to 15 percent of the search region, comfortably inside it, and going further
     * buys nothing because what still fails fails for another reason.
     */
    const val MASK_SEARCH_PADDING = 1.2f

    /**
     * How much of the search region's border the fill may touch before it counts as a leak.
     *
     * A tail is allowed to run out of the region — it is a few pixels wide and cutting it off
     * costs the silhouette nothing anyone can see. A fill that has escaped into the page takes
     * a whole side with it. Measured on a real comic: the silhouettes that were right touched
     * 0 to 0.6 percent of the perimeter, and every one that had leaked touched more than 20.
     */
    const val MASK_MAX_LEAK_FRACTION = 0.05f

    /**
     * Most of the search region the silhouette may cover before it is the page rather than the
     * balloon. With [MASK_SEARCH_PADDING] a real balloon measured 10 to 15 percent, and a fill
     * that had escaped measured 33 to 48.
     */
    const val MASK_MAX_SEARCH_AREA_FRACTION = 0.35f

    /**
     * How much of the detected text the silhouette must cover to be that balloon's silhouette.
     *
     * The one guard that says "this shape belongs to the text we found" rather than merely
     * "this shape is bubble-sized". Measured between 68 and 81 percent for every silhouette
     * that was right — the gap to 100 is the lettering itself, where the fill stops.
     */
    const val MASK_MIN_TEXT_COVERAGE = 0.5f

    /** How far the cut-out's two-tone halo reaches past its edge, in dp. */
    const val CARD_HALO_WIDTH_DP = 1f
}
