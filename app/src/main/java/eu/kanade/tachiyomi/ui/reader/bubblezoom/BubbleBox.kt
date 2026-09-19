package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * An axis-aligned rectangle in normalized image space.
 *
 * Every value is a fraction of the original page image, never of the view or of the screen.
 * Pixels exist only at the moment of drawing, where the reader multiplies by the image's
 * source dimensions and lets the image view do its own source-to-view conversion.
 *
 * Every instance satisfies, exactly:
 * - all four values are finite
 * - [x] and [y] are in `0f..1f`
 * - [width] and [height] are strictly positive
 * - [right] and [bottom] are at most `1f`
 *
 * The constructor is strict on purpose: it is the contract, not a sanitizer. Floats that
 * came from a detector, a cache or any other outside source must go through
 * [clampedOrNull] instead, which is the single door that absorbs their noise.
 *
 * Note that `-0.0f` passes validation but breaks equality, since the generated `equals`
 * compares bit patterns and `-0.0f != 0.0f` there. [clampedOrNull] normalizes it away;
 * code building boxes from arithmetic of its own should do the same.
 */
data class BubbleBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {

    init {
        require(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()) {
            "BubbleBox values must be finite: x=$x y=$y width=$width height=$height"
        }
        require(x in 0f..1f && y in 0f..1f) {
            "BubbleBox origin must be in 0..1: x=$x y=$y"
        }
        require(width > 0f && height > 0f) {
            "BubbleBox must have positive size: width=$width height=$height"
        }
        require(x + width <= 1f && y + height <= 1f) {
            "BubbleBox must fit in 0..1: right=${x + width} bottom=${y + height}"
        }
        // A width can be positive and still vanish when added to x, if it is smaller than one
        // ulp there. Such a box has right == x, which would make padded() produce a zero-width
        // rectangle and throw, and would make area and the overlap maths disagree.
        require(x + width > x && y + height > y) {
            "BubbleBox is too small to be distinguishable at this position: " +
                "x=$x y=$y width=$width height=$height"
        }
    }

    /** Right edge, `x + width`. Never greater than `1f`. */
    val right: Float get() = x + width

    /** Bottom edge, `y + height`. Never greater than `1f`. */
    val bottom: Float get() = y + height

    val centerX: Float get() = x + width / 2f

    val centerY: Float get() = y + height / 2f

    /** Fraction of the page this box covers. */
    val area: Float get() = width * height

    /**
     * This box grown on all four sides by [fraction] of its own smallest dimension, truncated
     * at the page edges.
     *
     * The basis is the smallest dimension so that a long flat caption grows by the same
     * absolute amount on every side instead of gaining a huge horizontal margin and almost no
     * vertical one. This matches `boxPadding` in docs/BUBBLE_ZOOM_SPEC.md section 7.
     *
     * At a page edge the box is truncated, never shifted inward, so the result always contains
     * the original box and its centre stays on the page. The consequence is that the result can
     * be **smaller than requested**: callers computing a zoom scale must read the returned
     * [width] and [height] and must not assume `width * (1 + 2 * fraction)`.
     *
     * A box already spanning the whole page is a fixed point.
     *
     * @param fraction how much to grow, as a fraction of `min(width, height)`. Must be finite
     *   and non-negative; shrinking is not this method's job and would invert the rectangle.
     */
    fun padded(fraction: Float): BubbleBox {
        require(fraction.isFinite() && fraction >= 0f) {
            "padding fraction must be finite and non-negative: $fraction"
        }
        if (fraction == 0f) return this

        val padding = fraction * minOf(width, height)
        val left = (x - padding).coerceAtLeast(0f)
        val top = (y - padding).coerceAtLeast(0f)
        val newRight = (right + padding).coerceAtMost(1f)
        val newBottom = (bottom + padding).coerceAtMost(1f)
        return BubbleBox(left, top, newRight - left, newBottom - top)
    }

    /**
     * Whether the normalized point ([nx], [ny]) falls inside this box.
     *
     * Edges count as inside, so a bubble flush against the page border still answers true for a
     * tap on that border.
     */
    fun contains(nx: Float, ny: Float): Boolean {
        return nx >= x && nx <= right && ny >= y && ny <= bottom
    }

    /**
     * Intersection over union with [other], in `0f..1f`.
     *
     * Boxes that merely touch along an edge or at a corner score exactly `0f`; no tolerance is
     * involved anywhere. A box compared with itself is exactly `1f`.
     *
     * The arithmetic runs in [Double] on purpose. Both the sum and the product of two [Float]s
     * are exactly representable in a Double, so `(y + height) - y` gives back `height` instead
     * of drifting by an ulp the way it does in Float. Done in Float this returns more than
     * `1f` for ordinary boxes: `BubbleBox(0.58f, 0.67f, 0.26f, 0.15f)` compared with itself
     * yields 1.0000004, because its `bottom - y` is 0.15000004 while its `height` is 0.15.
     */
    fun intersectionOverUnion(other: BubbleBox): Float {
        val intersection = intersectionArea(other)
        if (intersection <= 0.0) return 0f
        val union = exactArea() + other.exactArea() - intersection
        if (union <= 0.0) return 0f
        return (intersection / union).toFloat()
    }

    /**
     * Overlapping area with [other], or `0.0` when they do not overlap.
     *
     * Each axis is clamped independently before multiplying, otherwise two boxes that miss on
     * both axes would report a positive area from the product of two negative overlaps.
     */
    private fun intersectionArea(other: BubbleBox): Double {
        val overlapX = minOf(exactRight(), other.exactRight()) - maxOf(x.toDouble(), other.x.toDouble())
        if (overlapX <= 0.0) return 0.0
        val overlapY = minOf(exactBottom(), other.exactBottom()) - maxOf(y.toDouble(), other.y.toDouble())
        if (overlapY <= 0.0) return 0.0
        return overlapX * overlapY
    }

    /** [right] without the rounding that storing it back into a Float would introduce. */
    private fun exactRight(): Double = x.toDouble() + width.toDouble()

    /** [bottom] without the rounding that storing it back into a Float would introduce. */
    private fun exactBottom(): Double = y.toDouble() + height.toDouble()

    /** [area] without the rounding that storing it back into a Float would introduce. */
    private fun exactArea(): Double = width.toDouble() * height.toDouble()

    companion object {

        /**
         * Builds a box from untrusted floats, or returns null when nothing usable survives.
         *
         * This is the only sanctioned way to turn outside numbers into a [BubbleBox]. A
         * detector that letterboxes a page into a square input and undoes that transform emits
         * values a fraction of an ulp past `1f` — `1.0000001f` is a real observed output — and
         * the strict constructor would reject those. Clipping happens on the edges rather than
         * by sliding the origin, so a box that overhangs the page loses the overhanging part
         * instead of moving somewhere it was not detected.
         *
         * Returns null for non-finite input and for anything that clips down to zero size.
         */
        fun clampedOrNull(x: Float, y: Float, width: Float, height: Float): BubbleBox? {
            if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) {
                return null
            }
            // Adding 0f collapses -0.0f to 0.0f, which coerceIn leaves untouched and which
            // would otherwise produce boxes that are equal geometrically but not by equals().
            val nx = x + 0f
            val ny = y + 0f

            // Already valid: returned verbatim, so sanitizing is an identity on good input and
            // idempotent on its own output. The clamp below goes through right = x + width and
            // back, and (x + width) - x is not always width.
            if (nx in 0f..1f && ny in 0f..1f && width > 0f && height > 0f &&
                nx + width <= 1f && ny + height <= 1f &&
                nx + width > nx && ny + height > ny
            ) {
                return BubbleBox(nx, ny, width, height)
            }

            val left = nx.coerceIn(0f, 1f)
            val top = ny.coerceIn(0f, 1f)
            val right = (x + width).coerceIn(left, 1f)
            val bottom = (y + height).coerceIn(top, 1f)
            val clampedWidth = right - left
            val clampedHeight = bottom - top
            if (clampedWidth <= 0f || clampedHeight <= 0f) return null
            // A surviving size can still be too small to shift the origin's exponent, which the
            // constructor rejects; there is no usable box left in that case either.
            if (left + clampedWidth <= left || top + clampedHeight <= top) return null
            return BubbleBox(left, top, clampedWidth, clampedHeight)
        }
    }
}
