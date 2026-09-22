package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * One region a detector found on a page.
 *
 * The [box] is stored raw, exactly as detected. Padding is a presentation concern applied when
 * framing a zoom, not something baked into what was persisted.
 *
 * @param confidence how sure the detector is, in `0f..1f`.
 */
data class BubbleDetection(
    val box: BubbleBox,
    val confidence: Float,
    val type: BubbleType,
    /**
     * The balloon's silhouette over [box], when whatever found it knew the shape.
     *
     * Null from a detector that only boxes things, which is every detector this feature had
     * before amendment E4 and is still the fake one. The card then falls back to tracing the
     * shape out of the page's own pixels, and to a rectangle when that cannot be done either.
     */
    val shape: BubbleShape? = null,
) {

    init {
        require(confidence in 0f..1f) {
            "confidence must be in 0..1: $confidence"
        }
    }
}
