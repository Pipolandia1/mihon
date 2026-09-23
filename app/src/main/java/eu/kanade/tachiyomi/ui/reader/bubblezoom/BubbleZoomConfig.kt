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
    const val FOCUS_PADDING = 0.08f

    /**
     * Fraction of the usable viewport the padded box should occupy.
     *
     * The target scale is the SMALLER of the two, so the box fits on both axes. Driving off
     * the width alone, as a first reading of the phase brief suggests, overflows vertically
     * for a tall narrow bubble: a box twice as tall as it is wide, scaled so its width is a
     * given fraction of a 1080px viewport, ends up far taller on a 2400px screen — fine there,
     * but on a landscape viewport of 1080px height it would be cut off.
     *
     * **Amendment E11 divided this by 1.42 as well**, and for the same reason as
     * [CARD_MAGNIFICATION]: 60 % was chosen when the card was framed on the LETTERING, and
     * since E9 it is framed on the balloon, which measures 1.42 times the lettering on its
     * smallest side. For a balloon wider than about a third of the page this fraction is what
     * binds rather than the magnification, so leaving it would have left the biggest cards —
     * the ones that take up the page — exactly as big as they were.
     */
    const val TARGET_WIDTH_FRACTION = 0.42f
    const val TARGET_HEIGHT_FRACTION = 0.42f

    /**
     * How much larger than on screen the card shows the balloon, against the scale the page is
     * drawn at right now.
     *
     * Google Play Books, measured on four cards: 2.55–3.32×, with the width fraction above as
     * the ceiling that stops a wide caption growing past the screen. Without this term a small
     * balloon is framed to fill 60 % of the viewport — 4× and more on a real page — which the
     * P9 verification saw refused for every real balloon there was.
     *
     * **Amendment E11 divided it by 1.42.** That figure was measured over 729 balloons matched
     * to their own lettering: since E9 the card is framed on the BALLOON, and a balloon's box is
     * 1.42 times its lettering's on the smallest side. The 2.8 above was calibrated against Play
     * Books when the frame was the lettering, so keeping it made every card half again as big as
     * the thing it was calibrated against — which is exactly what it looked like.
     */
    const val CARD_MAGNIFICATION = 2.0f

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

    /**
     * How long the page takes to slide a balloon into view on a zoomed page. Amendment E11.
     *
     * A little longer than the card's own entrance, because this one moves the whole page and a
     * move the reader did not ask for has to be legible as a move rather than a cut — and short
     * enough that a walk of ten balloons does not feel like waiting on it.
     */
    const val CARD_PAN_DURATION_MS = 260L

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

    /**
     * How wide a strip of page with no balloon in it counts as the gap between two rows of
     * panels, as a fraction of the page's height. Amendment P12.
     *
     * Small enough that the gutter between two rows of panels is always found — a comic page
     * of three rows leaves far more than a fiftieth of its height between them — and large
     * enough that two balloons stacked inside one panel, which nearly touch, are not read as
     * two separate rows and therefore ordered the same way either way.
     */
    const val ORDER_ROW_GAP = 0.02f

    /**
     * The same across the page, as a fraction of its width, for panels side by side.
     *
     * Wider than the row gap on purpose: balloons within a panel are often set apart
     * horizontally for the lettering to breathe, and a narrow threshold would read that as two
     * panels and order them by column when they should be read down.
     */
    const val ORDER_COLUMN_GAP = 0.04f

    /**
     * How far apart two balloons' top edges may be and still be read as one row, as a fraction
     * of the page's height.
     *
     * Only used where no cut is possible, which is a cluster inside one panel. Lettering is
     * placed by eye, so balloons meant to be read across a panel are never aligned exactly —
     * measured on a real page, one sat a thousandth of a page higher than its neighbour and a
     * strict sort by the top edge read it first. A fortieth of a page is far more than that
     * drift and far less than the step between two balloons genuinely stacked.
     */
    const val ORDER_BAND_TOLERANCE = 0.025f

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
     * smallest side. Amendment E3, retuned by E10.
     *
     * E3 needed 1.2 because the detector boxed the TEXT and the balloon was not in the box at
     * all. Since E9 the box IS the balloon, so the region only has to hold its outline and a
     * tail — and a wide region is actively harmful: on a dense page it takes in the neighbouring
     * balloon, which the fill then joins.
     *
     * A quarter, and not less, because of what the escape guard needs: a fill that has flooded
     * the region must land outside [MASK_MAX_ESCAPE_FRACTION]. At 0.25 a flooded region leaves
     * 42 percent of itself outside the box of a wide caption, comfortably refused; at 0.15 it
     * leaves only 29, too near the threshold to tell the two apart.
     */
    const val MASK_SEARCH_PADDING = 0.25f

    /**
     * How much of itself the fill may leave outside the balloon's own box. Amendment E10.
     *
     * The guard that replaced measuring the region's border. A silhouette that is the balloon
     * is its box give or take an outline and a tail; one that got out through a gap in the
     * outline is the panel behind it, which is mostly elsewhere. Measured over 106 balloons of
     * a real comic: every right one left between 0 and 22 percent of itself outside the box,
     * and the first flooded one left 30. A quarter sits in that gap.
     */
    const val MASK_MAX_ESCAPE_FRACTION = 0.25f

    /**
     * How much of the detected balloon the silhouette must cover to be that balloon's.
     *
     * The one guard that says "this shape is the thing we found" rather than merely "this shape
     * is bubble-sized". An ellipse fills 79 percent of its own box and a rounded caption more,
     * so half is comfortably below every right answer and above a fill that caught one letter.
     */
    const val MASK_MIN_BOX_COVERAGE = 0.5f

    /**
     * How many tones the histogram of a balloon is counted in, to find the one it is made of.
     *
     * Coarse on purpose: a balloon's paper is one tone plus the noise of a scan, and thirty-two
     * bins are eight levels each, which holds that noise in one bin without merging paper and
     * lettering.
     */
    const val MASK_TONE_BINS = 32

    /**
     * How much of the smaller lobe a fill from the larger one must cover for the two to be one
     * balloon. Amendment E12.
     *
     * Measured over 246 pairs of balloons whose boxes cross, on 103 pages: a fill reaching
     * between 0 and 0.13 of the other box was two separate speeches every time, and one
     * reaching 0.37 or more was the two lobes of one balloon every time. Between those there
     * were three pairs in the whole corpus, so a quarter sits in an all but empty gap.
     */
    const val MERGE_REACH = 0.25f

    /** How far the cut-out's two-tone halo reaches past its edge, in dp. */
    const val CARD_HALO_WIDTH_DP = 1f
}
