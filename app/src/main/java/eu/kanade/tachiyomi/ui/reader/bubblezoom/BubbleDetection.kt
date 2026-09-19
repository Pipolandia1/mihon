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
) {

    init {
        require(confidence in 0f..1f) {
            "confidence must be in 0..1: $confidence"
        }
    }
}
