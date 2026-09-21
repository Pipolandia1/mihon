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
}
