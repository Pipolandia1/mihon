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
    const val MODEL_ASSET = "bubble_detector.onnx"

    /**
     * The square side the page is letterboxed into before inference.
     *
     * The model card recommends 1280. Measured on the synthetic pages, 640 finds exactly the same
     * bubbles at a fifth of the cost - 1280 added false positives on one page and nothing
     * anywhere else - so the spec's 640 stands until real pages say otherwise.
     */
    const val INPUT_SIZE = 640

    /**
     * A page whose long side is at least this many input sizes is never decoded at full
     * resolution, even when the next power-of-two sample size lands a little under the input:
     * the model sees it slightly upscaled instead of the app holding a second full-size copy.
     * 1.5 puts the common 1100-1300px scanlation page on the reduced side and leaves genuinely
     * small pages, where a full decode is cheap, alone.
     */
    const val FULL_DECODE_BELOW = 1.5f

    /**
     * The score below which a row of the model's output is not a detection.
     *
     * An end-to-end YOLO export always returns 300 rows, and none of them scores exactly zero:
     * the slots it has no detection for carry candidates at 0.0004 to 0.07. Measured on the
     * synthetic pages, every real bubble scores 0.28 or above and everything else 0.07 or
     * below, so 0.25 recovers exactly the ground truth on all five. The spec's 0.35 would
     * already cut a genuine 0.28 bubble on page 03 - phase P10 owns tuning this, and should
     * start from that fact rather than from the spec's number.
     *
     * Learned the hard way: the first cut of the decoder kept every row above zero, on the
     * assumption that padding rows were zeros, and the device walked 211 "bubbles" on a page
     * with eight. The test could not catch it because its fixture had been filtered at 0.25
     * before the assumption was written down.
     */
    const val CONFIDENCE_THRESHOLD = 0.25f

    /** The grey the ultralytics letterbox pads with; the model was trained seeing it. */
    const val LETTERBOX_PAD_VALUE = 114

    /**
     * Identifies the real detector AND its settings, for the P13 cache. Bump it when the model
     * file, [INPUT_SIZE], or anything else that changes the boxes a page produces changes.
     * Version 1 is Kiuyha/Manga-Bubble-YOLO yolo26n at 640.
     */
    const val ONNX_MODEL_VERSION = 1
}
