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
 * It refuses rather than guesses. A fill that reaches the border escaped through a gap in the
 * outline and has taken the page with it; one that covers almost nothing found a glyph or a
 * texture. Both hand the bubble back to the rectangular card, which is always correct if less
 * pretty. Open bubbles, captions with no outline and ragged shout balloons all land here.
 *
 * Pure Kotlin on purpose — no Android type crosses this file, so every branch above is a JVM
 * unit test in `BubbleMaskTest`.
 */
object BubbleMask {

    /**
     * The bubble's silhouette over [width] x [height] ARGB [pixels], or null if it has none to
     * offer. Row-major, one flag per pixel, parallel to [pixels].
     *
     * [growPasses] is how far the silhouette is grown to take in the bubble's own outline,
     * measured by the caller from the real geometry — see `BubbleCardGeometry.outlineGrowPasses`.
     * Nothing here can work it out: at this point the only sizes in hand are the mask's, and
     * how many mask pixels an outline occupies depends on how large the bubble was on the page.
     */
    fun extract(pixels: IntArray, width: Int, height: Int, growPasses: Int): BooleanArray? {
        if (width < MIN_SIDE || height < MIN_SIDE) return null
        val size = width * height
        if (pixels.size < size) return null

        val luma = FloatArray(size) { lumaOf(pixels[it]) }
        val surface = centreTone(luma, width, height)

        val filled = fillFromCentre(luma, width, height, surface) ?: return null
        fillEnclosedHoles(filled, width, height)

        return grow(filled, width, height, growPasses)
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

        var escaped = false
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) escaped = true

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

        // Escaping means the outline had a gap and the fill is now holding the page rather than
        // the bubble. There is no salvaging that, and a wrong silhouette is worse than none.
        if (escaped) return null

        val covered = area.toFloat() / size
        if (covered < BubbleZoomConfig.MASK_MIN_AREA_FRACTION) return null
        if (covered > BubbleZoomConfig.MASK_MAX_AREA_FRACTION) return null
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
