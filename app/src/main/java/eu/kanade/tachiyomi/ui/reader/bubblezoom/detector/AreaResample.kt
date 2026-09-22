package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Shrinking a page to the model's input by averaging, not by sampling. Amendment E6.
 *
 * The page was reaching the model through `Canvas.drawBitmap` with `FILTER_BITMAP`, which is
 * bilinear: for each output pixel it reads the four source pixels nearest the point it maps to
 * and ignores every other one. Reducing a 983-pixel-tall page to 640 that way throws away more
 * than a third of it at random, and lettering is exactly the high-frequency detail that gets
 * thrown. What the model is then asked about is not the page.
 *
 * Measured on two real pages, same weights, the only difference being this step:
 *
 * | page | bilinear, as it was | averaged |
 * |---|---|---|
 * | a dense page, 19 text regions | 11 balloons | **14** |
 * | a page with a masked face and a gloved hand | 6, of which the face and the hand | **4, neither** |
 *
 * Both halves of that table are the same defect. Aliasing invents structure that is not on the
 * page — enough of it, on a face, to score 0.72 — and destroys structure that is, so the fix
 * finds three more balloons AND stops stopping at a face.
 *
 * Each output pixel here is the average of every source pixel its footprint covers, weighted by
 * how much of each it covers. Pure Kotlin over ARGB ints, so it is checkable on the JVM, which
 * matters for something whose failure looks like "the model got a bit worse".
 *
 * **Enlarging is not what this is for.** When the footprint is under one source pixel — a page
 * smaller than the model's input — it degenerates to nearest-neighbour. Those pages are already
 * being shown detail they do not have, and which pixel is repeated is not what decides a
 * balloon there.
 */
object AreaResample {

    /**
     * Writes [sourceWidth] x [sourceHeight] pixels of [source] into the first
     * [targetWidth] * [targetHeight] of [target], averaged.
     *
     * Alpha is dropped: a page is opaque, and the tensor only ever reads the three colours.
     */
    fun resample(
        source: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        target: IntArray,
        targetWidth: Int,
        targetHeight: Int,
    ) {
        require(sourceWidth > 0 && sourceHeight > 0) { "empty source: ${sourceWidth}x$sourceHeight" }
        require(targetWidth > 0 && targetHeight > 0) { "empty target: ${targetWidth}x$targetHeight" }
        require(source.size >= sourceWidth * sourceHeight) { "source is short of its own size" }
        require(target.size >= targetWidth * targetHeight) { "target is short of its own size" }

        val xRatio = sourceWidth.toDouble() / targetWidth
        val yRatio = sourceHeight.toDouble() / targetHeight

        for (ty in 0 until targetHeight) {
            val top = ty * yRatio
            val bottom = min(top + yRatio, sourceHeight.toDouble())
            val firstRow = floor(top).toInt()
            val lastRow = min(ceil(bottom).toInt(), sourceHeight)
            for (tx in 0 until targetWidth) {
                val left = tx * xRatio
                val right = min(left + xRatio, sourceWidth.toDouble())
                val firstColumn = floor(left).toInt()
                val lastColumn = min(ceil(right).toInt(), sourceWidth)

                var red = 0.0
                var green = 0.0
                var blue = 0.0
                var total = 0.0
                for (sy in firstRow until lastRow) {
                    // How much of this source row the output pixel covers. Whole in the middle,
                    // a fraction at each end, which is what makes a non-integer ratio smooth.
                    val weightY = min(bottom, (sy + 1).toDouble()) - maxOf(top, sy.toDouble())
                    if (weightY <= 0.0) continue
                    val row = sy * sourceWidth
                    for (sx in firstColumn until lastColumn) {
                        val weightX = min(right, (sx + 1).toDouble()) - maxOf(left, sx.toDouble())
                        if (weightX <= 0.0) continue
                        val weight = weightX * weightY
                        val pixel = source[row + sx]
                        red += ((pixel shr 16) and 0xFF) * weight
                        green += ((pixel shr 8) and 0xFF) * weight
                        blue += (pixel and 0xFF) * weight
                        total += weight
                    }
                }
                target[ty * targetWidth + tx] = if (total <= 0.0) {
                    // Only reachable for a footprint that fell entirely outside the source,
                    // which the clamping above prevents; kept so a rounding surprise is black
                    // rather than an exception in a detection job.
                    0xFF000000.toInt()
                } else {
                    val r = (red / total + 0.5).toInt().coerceIn(0, 255)
                    val g = (green / total + 0.5).toInt().coerceIn(0, 255)
                    val b = (blue / total + 0.5).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
    }
}
