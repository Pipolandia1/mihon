package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * The detection result for one page.
 *
 * [bubbles] is the navigable list, already in reading order for the direction the reader was in
 * when it was resolved. It holds [BubbleType.TEXT] and [BubbleType.UNKNOWN] regions; panels are
 * structure used to derive that order, not navigation targets, so they do not appear here.
 *
 * Nothing here is validated beyond what the types already guarantee. [modelVersion] and
 * [detectionTimeMs] only feed caching and metrics, and a wrong value in either cannot corrupt
 * what is drawn on screen — throwing from inside the reader over a metric would be a worse bug
 * than the bad metric.
 *
 * @param modelVersion identifies the detector and the settings that produced these coordinates.
 *   Cache entries written by a different version are ignored rather than trusted.
 * @param detectionTimeMs wall-clock duration of the inference that produced this, or `0L` when
 *   no inference ran.
 */
data class BubblePageResult(
    val bubbles: List<BubbleDetection>,
    val modelVersion: Int,
    val detectionTimeMs: Long,
)
