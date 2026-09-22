package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * A balloon's silhouette, as a flag per cell over its own [BubbleDetection.box].
 *
 * Amendment E4. Until then the only silhouette this feature had was traced out of the page's
 * pixels, which works when the balloon's paper is a different tone from everything touching it
 * and cannot work when it is not — a caption over a light wall, two balloons that touch. A
 * segmentation model answers the question directly, and this is what it answers with.
 *
 * Deliberately not a bitmap and deliberately not pixels. It is resolution-independent in the
 * same way [BubbleBox] is: whatever grid the model produced, the flags describe the box and the
 * card stretches them onto itself, so nothing here has to know what a screen is. That also
 * keeps it storable — phase P13's disk cache can write flags, and could not write a bitmap.
 *
 * Small by construction: a balloon's box is a fraction of a page and the model's grid is a
 * quarter of its input, so a shape is typically well under a thousand cells.
 */
class BubbleShape(
    val width: Int,
    val height: Int,
    val cells: BooleanArray,
) {

    init {
        require(width > 0 && height > 0) { "a shape must have a size: ${width}x$height" }
        require(cells.size == width * height) {
            "a shape must have one cell per position: ${cells.size} for ${width}x$height"
        }
    }

    /** Fraction of the box the silhouette covers. Zero means the model marked nothing. */
    val coverage: Float get() = cells.count { it }.toFloat() / cells.size

    /** Whether the cell at ([x], [y]) is inside the balloon. Out of range is outside. */
    fun at(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return cells[y * width + x]
    }
}
