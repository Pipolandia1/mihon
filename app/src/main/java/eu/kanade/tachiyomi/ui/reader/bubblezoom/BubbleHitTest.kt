package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Which bubble a double tap means.
 *
 * Pure Kotlin, so the rule is pinned on the JVM. The point arrives already normalized to the
 * page: converting it is the only part that needs the image view, and it stays with the caller.
 *
 * The smallest box that contains the point wins. A detection nested inside a larger one — a
 * word balloon inside a panel-sized caption — is the one the finger is closest to having chosen.
 * Among equal areas the lower index wins, which is reading order, so the answer never depends on
 * float noise. A point outside every box is tried again against each box grown by [find]'s
 * `tolerance` of its own smallest side, because the detector boxes the text and not the balloon
 * (P9 measured 31–69 % of the balloon's area) and a tap on the balloon's margin is still a tap
 * on that balloon. Growing does not reorder: the ranking stays by the raw box, so a large box
 * grown over a small neighbour's margin cannot steal the small one's tap.
 */
object BubbleHitTest {

    /** Index into [bubbles] of the bubble at ([nx], [ny]), or -1. Panels never answer. */
    fun find(bubbles: List<BubbleDetection>, nx: Float, ny: Float, tolerance: Float): Int {
        val exact = smallestContaining(bubbles, nx, ny) { it }
        if (exact >= 0 || tolerance <= 0f) return exact
        return smallestContaining(bubbles, nx, ny) { it.padded(tolerance) }
    }

    private inline fun smallestContaining(
        bubbles: List<BubbleDetection>,
        nx: Float,
        ny: Float,
        region: (BubbleBox) -> BubbleBox,
    ): Int {
        var best = -1
        var bestArea = Float.MAX_VALUE
        bubbles.forEachIndexed { index, bubble ->
            if (bubble.type == BubbleType.PANEL) return@forEachIndexed
            if (!region(bubble.box).contains(nx, ny)) return@forEachIndexed
            val area = bubble.box.area
            if (area < bestArea) {
                best = index
                bestArea = area
            }
        }
        return best
    }
}
