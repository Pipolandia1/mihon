package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Every tunable number detection depends on, kept apart from [BubbleZoomConfig] because these
 * are about finding bubbles and those are about showing them.
 */
object BubbleDetectionConfig {

    /**
     * How long [eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.FakeBubbleDetector] pretends
     * to think, in milliseconds.
     *
     * It exists to make the phase's own promise falsifiable: the page must render immediately
     * and the bubbles arrive later. With no delay the two are indistinguishable and "the page
     * did not wait" cannot be observed. 300ms is long enough to see on a device and short
     * enough not to be annoying — and it is a parameter rather than a constant at the call
     * site because a test needs to set it to something a virtual clock can step over, and
     * because adb's own latency is the same order of magnitude as this window.
     */
    const val FAKE_LATENCY_MS = 300L

    /**
     * The version number reserved for the fake, kept far from anything a real model will use.
     *
     * Phase P13 caches results on disk keyed by the detector's version. If the fake shared a
     * number with a real detector, that cache would happily serve three hardcoded debug boxes
     * on a real manga page, with nothing in the stored result to say where they came from.
     */
    const val FAKE_MODEL_VERSION = -1

    /** Where the model lives. Gitignored; supplied per build, never committed. */
    const val MODEL_ASSET = "comic_detector.onnx"

    /**
     * How sure the detector has to be before a region becomes a stop on the walk. Amendment E9.
     *
     * Measured over 92 pages of a real comic. The `bubble` class barely moves with it — 768
     * detections at 0.3 against 709 at 0.7 — so the threshold is really about `text_free`,
     * which falls from 210 to 51 over the same range. Looking at what those are: below 0.5 they
     * are covers, credits, advertisements and a shop sign; at 0.5 the three story pages checked
     * by eye had every box on lettering and none on artwork. So 0.5, and the noise that is left
     * is front matter rather than art.
     */
    const val DETECTION_CONFIDENCE = 0.5f

    /**
     * The square side the page is resized into before inference.
     *
     * Not a choice since amendment E9: the graph's image input is fixed at 640 by 640, and the
     * model's own preprocessing config resizes both axes to it. The page is therefore squashed
     * rather than letterboxed, which is also how it was trained.
     */
    const val INPUT_SIZE = 640

    /**
     * The page is looked at this small when finding its panels. Amendment E8.
     *
     * A gutter is never a thin detail — it is a band wide enough to see a page's structure from
     * across a room — so the scan is done on a reduction, where it costs a few milliseconds
     * next to seconds of inference. Measured on a real page: at 400 the thinnest gutters of a
     * dense page were missed and it found five panels of eleven; at 600 it finds the row and
     * column structure the reading order needs, and 800 finds nothing more.
     */
    const val PANEL_SCAN_LONG_SIDE = 600

    /**
     * How far a pixel may be from the page's margin tone and still count as gutter, out of 255.
     *
     * Wide enough for the JPEG noise and the vignetting a scan leaves in a black gutter, narrow
     * enough that a dark panel is not mistaken for one.
     */
    const val PANEL_GUTTER_TOLERANCE = 40

    /** How much of a line has to be that tone before the line counts as gutter. */
    const val PANEL_LINE_PURITY = 0.97f

    /** The thinnest gutter, as a fraction of the page's long side. */
    const val PANEL_MIN_GUTTER = 0.005f

    /** The smallest region worth calling a panel, as a fraction of the page's long side. */
    const val PANEL_MIN_SIZE = 0.06f

    /**
     * How deep the division may go. Six is well past any page's structure — rows, panels,
     * insets — and stops a page of flat paper being subdivided down to its lettering.
     */
    const val PANEL_MAX_DEPTH = 6

    /**
     * How much of a region has to be drawn on before it counts as a panel. Amendment E9.
     *
     * A panel is a drawn area, and this is what separates one from a strip of empty paper. On a
     * page that is mostly white the paper itself reads as gutter, and without this the division
     * comes back as the columns the lettering happens to sit in — which then has the page read
     * down its columns instead of across its rows. Measured: a real comic's panels are 54 to 86
     * percent drawn on; the strips a sparse page produces are 3 to 9 percent, and the white
     * panels of the synthetic test pages 19 to 32, which fall back to ordering by the balloons
     * and were always read correctly that way.
     */
    const val PANEL_MIN_INK = 0.35f

    /*
     * Framed panels, on a page no gutter divides. Amendment E15. Fractions are of the scan's
     * long side ([PANEL_SCAN_LONG_SIDE]); the figures were measured at 600 over 137 pages. See
     * [FramedPanels] for what each rule is for.
     */

    /** A pixel this dark, out of 255, is ink a frame could be drawn in. */
    const val FRAME_LINE_LUMA = 56

    /** The shortest run of ink that makes a row or column a candidate frame line. */
    const val FRAME_MIN_LINE = 0.08f

    /** How far two runs' ends, or two frames' sides, may differ and still be the same. */
    const val FRAME_CORNER_TOLERANCE = 0.012f

    /** Thicker than this, a dark band is an area of the art, not a line. */
    const val FRAME_MAX_THICKNESS = 0.02f

    /** How much of a side, between its corners, must be ink or under a text box. */
    const val FRAME_COVER = 0.90f

    /**
     * The longest break a line may have, text aside: JPEG noise and a hair of art, not a gap.
     * A side is one drawn line; coverage alone let a side step across a 5-pixel gutter to the
     * next inset's line.
     */
    const val FRAME_MAX_HOLE = 0.005f

    /**
     * The most side tests one page may cost before the search gives up and the page is read as
     * it was before frames. A page of dark, busy art has hundreds of candidate lines on each
     * axis; with the reach test the reported page needs a few thousand.
     */
    const val FRAME_WORK_BUDGET = 400_000L

    /**
     * The most candidate frames one page may produce before the search gives up, for the same
     * reason: a page that has this many rectangles of line is art, not insets.
     */
    const val FRAME_MAX_CANDIDATES = 2_000

    /** How much of the row or column beside a side may be ink for the side to be a line. */
    const val FRAME_BESIDE_INK = 0.5f

    /**
     * How much of a side must be ink of its own; the rest may lie under text. A balloon laid
     * across the top of an inset left 0.23 of that side visible on the device's scan of the
     * reported page (0.34 on a desktop reduction of the same page), all of it line. A side that
     * is all text is none.
     */
    const val FRAME_MIN_INK_SIDE = 0.15f

    /** How far outside a side the band read for flatness starts, and how thick it is. */
    const val FRAME_FLAT_OFFSET = 0.004f
    const val FRAME_FLAT_BAND = 0.006f

    /** How far in from each corner that band stops, so the corner's own ink is not read. */
    const val FRAME_FLAT_INSET = 0.01f

    /** A side this close to the page's edge is on the margin, and flat by definition. */
    const val FRAME_PAGE_EDGE = 0.03f

    /** How far from the band's median tone a pixel may be and still be the same flat tone. */
    const val FRAME_FLAT_TOLERANCE = 40

    /**
     * How much of the band outside a side must be flat for the side to border a gutter. Measured
     * on the page this was built for: the insets' gutter sides 0.85 to 1.00, the splash beside
     * them 0.62 to 0.68.
     */
    const val FRAME_MIN_FLAT = 0.85f

    /** Frames this close still stand apart: the two lines of one gutter. */
    const val FRAME_APART_SLACK = 0.005f

    /**
     * The most of a frame's inside, lettering aside, that may be ink. The reported page's insets
     * are 0.10 to 0.26; rectangles cut out of a black band over another page's panels 0.73 to
     * 0.92. A panel of dark night art above this is missed, and read as before frames.
     */
    const val FRAME_MAX_INSIDE_INK = 0.6f

    /** A frame this much covered by one text box is that box's own border. */
    const val FRAME_TEXT_COVER = 0.5f

    /**
     * A page whose long side is at least this many input sizes is never decoded at full
     * resolution, even when the next power-of-two sample size lands a little under the input:
     * the model sees it slightly upscaled instead of the app holding a second full-size copy.
     * 1.5 puts the common 1100-1300px scanlation page on the reduced side and leaves genuinely
     * small pages, where a full decode is cheap, alone.
     */
    const val FULL_DECODE_BELOW = 1.5f

    /**
     * Identifies the real detector AND its settings, for the P13 cache. Bump it when the model
     * file, [INPUT_SIZE], or anything else that changes the boxes a page produces changes.
     *
     * Version 1 was Kiuyha/Manga-Bubble-YOLO yolo26n at 640, which boxed the text. Version 2 is
     * kitsumed/yolov8m_seg-speech-bubble at 640, which segments the balloon: different boxes
     * and, for the first time, a shape — so anything cached under 1 describes something else.
     */
    const val ONNX_MODEL_VERSION = 3
}
