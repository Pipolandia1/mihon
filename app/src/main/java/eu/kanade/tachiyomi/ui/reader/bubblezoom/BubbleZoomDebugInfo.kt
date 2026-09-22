package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * What the debug overlay says about one page: the instrument panel, phase P17.
 *
 * It exists so that a bad result can be told apart at a glance. A balloon missed by the model,
 * a balloon found but ordered wrongly, a page that is still thinking, and a page whose detector
 * never ran look identical on screen without this, and telling them apart by reading logcat
 * against screenshots is how most of a day went once.
 *
 * Two of the numbers the phase brief asked for are not here, because what they counted no
 * longer exists. There are no panels to count: the model returns speech bubbles only, and
 * amendment P12 decided against detecting panels at all. And there is no cache to report a hit
 * against, since that phase was dropped once the prefetch made it pointless. In their place is
 * the fact that actually matters now — whether this page was looked at before the reader
 * reached it, which is the difference between a page that is instant and a page that waits.
 *
 * Pure data, so the overlay is the only thing that has to know how to draw it.
 */
data class BubbleZoomDebugInfo(
    /** How many balloons the model returned for this page. */
    val bubbles: Int,
    /** How many of them came with a silhouette. With the segmentation model, all of them. */
    val shaped: Int,
    /**
     * How long the page waited for its answer, or null while it is still running.
     *
     * Wall clock from the page being handed to detection to its bubbles arriving, so it
     * INCLUDES time queued behind other pages — detection is serialised on one thread, and a
     * page offered while three others are in front of it reads several times the model's own
     * time. That is the number worth showing: it is what the reader waits. The model's own
     * time per page is in logcat, next to the page's size.
     */
    val detectionMs: Long?,
    /** Whether this page was detected before the reader reached it. */
    val prefetched: Boolean,
    /** Which detector answered, so a stale cache or a fake can never be mistaken for the model. */
    val modelVersion: Int,
    /** The balloon the walk is on, or -1 for the page at rest. */
    val focusedIndex: Int,
    /** The model's confidence in that balloon, or null when none is focused. */
    val focusedConfidence: Float?,
    /** Set when the detector failed for this page: the reason, short enough to read. */
    val failure: String? = null,
) {

    /** The top line: what happened to this page. */
    val summary: String
        get() = when {
            failure != null -> "failed: $failure"
            detectionMs == null -> "detecting…"
            else -> buildString {
                append("$bubbles bubble")
                if (bubbles != 1) append("s")
                if (shaped != bubbles) append(", $shaped shaped")
                append(" · ${detectionMs}ms")
                if (prefetched) append(" · ahead")
                append(" · v$modelVersion")
            }
        }

    /** The second line: where the walk is. Null when there is nothing to say. */
    val focus: String?
        get() {
            if (focusedIndex < 0 || bubbles == 0) return null
            val confidence = focusedConfidence?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
            return "${focusedIndex + 1}/$bubbles$confidence"
        }
}
