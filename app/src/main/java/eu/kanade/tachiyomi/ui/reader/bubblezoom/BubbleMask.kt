package eu.kanade.tachiyomi.ui.reader.bubblezoom

import kotlin.math.abs

/**
 * Traces the bubble's own silhouette in the pixels the card already captured.
 *
 * No model and no licence: a speech bubble is a closed outline around a surface of one tone, so
 * the shape can be read straight off the page. Filling outwards from the middle spreads across
 * that surface and stops at the outline, whichever way round the two tones are — white paper
 * behind black ink, or a black balloon behind white ink.
 *
 * What comes back is a silhouette, not a contour: a flag per pixel, at the reduced resolution
 * the caller passed in. That is deliberate. Reducing first is a low-pass filter, which is what
 * makes screentone read as grey rather than as thousands of tiny holes, and the mask is drawn
 * back up filtered, so the edge arrives antialiased for free.
 *
 * It refuses rather than guesses. A fill that floods the border escaped through a gap in the
 * outline and has taken the page with it; one that misses the text it was found for is not that
 * balloon's. Both hand the bubble back to the rectangular card, which is always correct if less
 * pretty. Captions on a light background and balloons whose paper matches the wall behind them
 * land here, measured and expected.
 *
 * Amendment E3 changed what it is handed, not how it works. The caller now traces in a region
 * far larger than the card will be, because the detector boxes the TEXT and the balloon does not
 * fit in that box — at the card's own padding the outline is cut off by the capture and the fill
 * runs out through the open end, which on a real comic refused nine balloons of nine. A tail may
 * still run out of the larger region, and clipping a tail costs nothing anyone can see, so the
 * border test is a fraction of the perimeter rather than a flag.
 *
 * Pure Kotlin on purpose — no Android type crosses this file, so every branch above is a JVM
 * unit test in `BubbleMaskTest`.
 */
object BubbleMask {

    /** A rectangle in mask coordinates: left and top inclusive, right and bottom exclusive. */
    data class MaskRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * The bubble's silhouette over [width] x [height] ARGB [pixels], or null if it has none to
     * offer. Row-major, one flag per pixel, parallel to [pixels].
     *
     * [growPasses] is how far the silhouette is grown to take in the bubble's own outline,
     * measured by the caller from the real geometry — see `BubbleCardGeometry.outlineGrowPasses`.
     * Nothing here can work it out: at this point the only sizes in hand are the mask's, and
     * how many mask pixels an outline occupies depends on how large the bubble was on the page.
     */
    fun extract(
        pixels: IntArray,
        width: Int,
        height: Int,
        text: MaskRect,
        growPasses: Int,
    ): BooleanArray? {
        if (width < MIN_SIDE || height < MIN_SIDE) return null
        val size = width * height
        if (pixels.size < size) return null

        val luma = FloatArray(size) { lumaOf(pixels[it]) }
        val surface = centreTone(luma, width, height)

        val filled = fillFromCentre(luma, width, height, surface) ?: return null
        fillEnclosedHoles(filled, width, height)

        // Asked before the silhouette is grown: growing would drag it over the text from the
        // outside, and a shape that merely surrounds the words would pass for one that holds
        // them. [text] is where the detection sits inside the search region.
        if (!covers(filled, width, height, text)) return null

        return grow(filled, width, height, growPasses)
    }

    /**
     * The rectangle [shape] occupies, or null when it occupies nothing.
     *
     * This is what amendment E3 frames the card with. The silhouette was traced in a region
     * chosen to be far too big for the balloon, and its own bounds are the only honest answer
     * to which part of that region the balloon is.
     */
    fun bounds(shape: BooleanArray, width: Int, height: Int): MaskRect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!shape[row + x]) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return MaskRect(left, top, right + 1, bottom + 1)
    }

    /** Whether enough of the text this balloon was found for lies inside the silhouette. */
    private fun covers(filled: BooleanArray, width: Int, height: Int, text: MaskRect): Boolean {
        val left = text.left.coerceIn(0, width - 1)
        val top = text.top.coerceIn(0, height - 1)
        val right = text.right.coerceIn(left + 1, width)
        val bottom = text.bottom.coerceIn(top + 1, height)

        var inside = 0
        var total = 0
        for (y in top until bottom) {
            val row = y * width
            for (x in left until right) {
                total++
                if (filled[row + x]) inside++
            }
        }
        if (total == 0) return false
        return inside.toFloat() / total >= BubbleZoomConfig.MASK_MIN_TEXT_COVERAGE
    }

    /**
     * The tone of whatever the bubble is made of, taken as the median of a patch at the middle.
     *
     * A median rather than the centre pixel alone: the exact middle of a bubble is as likely to
     * land on a letter as on the surface around it, and a letter would send the fill off to
     * trace a glyph. Half of a patch cannot be text, so the median is the surface.
     */
    private fun centreTone(luma: FloatArray, width: Int, height: Int): Float {
        val patch = ArrayList<Float>()
        forEachSeed(width, height) { index -> patch.add(luma[index]) }
        patch.sort()
        return patch[patch.size / 2]
    }

    /**
     * Spreads across the surface from every seed that shares its tone, four-connected.
     *
     * Returns null when the fill found no seed at all, when it escaped to the border, or when
     * what it covered is too small or too nearly everything to be a bubble.
     */
    private fun fillFromCentre(luma: FloatArray, width: Int, height: Int, surface: Float): BooleanArray? {
        val size = width * height
        val filled = BooleanArray(size)
        // Each pixel is flagged as it is pushed, so it is pushed at most once and the stack can
        // never need more room than the image has pixels.
        val stack = IntArray(size)
        var top = 0
        var area = 0

        forEachSeed(width, height) { index ->
            if (!filled[index] && sameSurface(luma[index], surface)) {
                filled[index] = true
                stack[top++] = index
                area++
            }
        }
        if (top == 0) return null

        var border = 0
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++

            if (x > 0) {
                val next = index - 1
                if (!filled[next] && sameSurface(luma[next], surface)) {
                    filled[next] = true
                    stack[top++] = next
                    area++
                }
            }
            if (x < width - 1) {
                val next = index + 1
                if (!filled[next] && sameSurface(luma[next], surface)) {
                    filled[next] = true
                    stack[top++] = next
                    area++
                }
            }
            if (y > 0) {
                val next = index - width
                if (!filled[next] && sameSurface(luma[next], surface)) {
                    filled[next] = true
                    stack[top++] = next
                    area++
                }
            }
            if (y < height - 1) {
                val next = index + width
                if (!filled[next] && sameSurface(luma[next], surface)) {
                    filled[next] = true
                    stack[top++] = next
                    area++
                }
            }
        }

        // A thin touch on the border is a tail leaving the region, which costs the silhouette
        // nothing visible. A wide one is the fill escaping through a gap in the outline and
        // taking the page with it, and a wrong silhouette is worse than none. Measured on a
        // real comic: right silhouettes touched 0 to 0.6 percent of the perimeter, leaked ones
        // more than 20.
        val perimeter = 2 * (width + height)
        if (border.toFloat() / perimeter > BubbleZoomConfig.MASK_MAX_LEAK_FRACTION) return null

        // The region is deliberately far larger than the balloon, so covering a third of it is
        // the fill holding the page even when it never reached a border. Same measurement: 10
        // to 15 percent for a balloon, 33 to 48 for a fill that had got out.
        if (area.toFloat() / size > BubbleZoomConfig.MASK_MAX_SEARCH_AREA_FRACTION) return null
        return filled
    }

    /**
     * Takes back everything the fill had to go around: lettering, screentone, a tail's shading.
     *
     * Anything unfilled that cannot be reached from the border is inside the bubble by
     * definition, whatever it looks like. Only the outline itself stays out, because it touches
     * the page, and [grow] is what puts that back.
     */
    private fun fillEnclosedHoles(filled: BooleanArray, width: Int, height: Int) {
        val size = width * height
        val outside = BooleanArray(size)
        val stack = IntArray(size)
        var top = 0

        fun seed(index: Int) {
            if (!filled[index] && !outside[index]) {
                outside[index] = true
                stack[top++] = index
            }
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }

        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x > 0) seed(index - 1)
            if (x < width - 1) seed(index + 1)
            if (y > 0) seed(index - width)
            if (y < height - 1) seed(index + width)
        }

        for (index in 0 until size) {
            if (!filled[index] && !outside[index]) filled[index] = true
        }
    }

    /**
     * Grows the silhouette outwards to take in the bubble's own outline.
     *
     * The fill stops at the inside edge of the outline, so a silhouette used as it stands would
     * cut the bubble's line off and leave a bare blob of paper floating. The line has to come
     * with it.
     *
     * How far is the caller's to say, and is clamped here only against a nonsense value.
     */
    private fun grow(filled: BooleanArray, width: Int, height: Int, passes: Int): BooleanArray {
        var current = filled
        repeat(passes.coerceIn(1, BubbleZoomConfig.MASK_MAX_GROW_PASSES)) {
            current = dilate(current, width, height)
        }
        return current
    }

    private fun dilate(source: BooleanArray, width: Int, height: Int): BooleanArray {
        val grown = BooleanArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                grown[index] = source[index] ||
                    (x > 0 && source[index - 1]) ||
                    (x < width - 1 && source[index + 1]) ||
                    (y > 0 && source[index - width]) ||
                    (y < height - 1 && source[index + width])
            }
        }
        return grown
    }

    private fun sameSurface(tone: Float, surface: Float): Boolean =
        abs(tone - surface) <= BubbleZoomConfig.MASK_TOLERANCE

    /** Visits the patch at the middle of the mask, the only place a bubble is certain to be. */
    private inline fun forEachSeed(width: Int, height: Int, visit: (Int) -> Unit) {
        val half = (minOf(width, height) * BubbleZoomConfig.MASK_SEED_PATCH_FRACTION)
            .toInt()
            .coerceAtLeast(1)
        val centreX = width / 2
        val centreY = height / 2
        for (y in (centreY - half).coerceAtLeast(0)..(centreY + half).coerceAtMost(height - 1)) {
            for (x in (centreX - half).coerceAtLeast(0)..(centreX + half).coerceAtMost(width - 1)) {
                visit(y * width + x)
            }
        }
    }

    private fun lumaOf(argb: Int): Float {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / 255f
    }

    private const val MIN_SIDE = 3
    private const val RED_WEIGHT = 0.299f
    private const val GREEN_WEIGHT = 0.587f
    private const val BLUE_WEIGHT = 0.114f
}
