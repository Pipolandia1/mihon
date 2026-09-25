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
 * It refuses rather than guesses. A fill that runs out of the balloon's own box has escaped
 * through a gap in the outline and taken the page with it; one that does not fill that box is
 * not that balloon's. Both hand the bubble back to the rectangular card, which is always correct
 * if less pretty. Captions whose paper is the same tone as the panel behind them land here —
 * there is no edge to stop at, and no amount of tuning invents one.
 *
 * **Amendment E10 retuned it for a box that is the balloon.** Until E9 the detector boxed the
 * TEXT, so the balloon was not in the box at all: the search region had to be more than twice
 * the box to contain it, the surface tone was read from a patch at the middle, and escapes were
 * caught by how much of the region's border the fill touched. With the box being the balloon,
 * all three were measurably wrong, and the two that mattered are replaced:
 *
 *  - **The tone comes from the whole box**, as the commonest tone in it, rather than from the
 *    median of a small patch at the centre. On a balloon packed with lettering that patch is
 *    ink, so the fill spread across one letter and stopped: measured on a real page, ten of
 *    nineteen balloons died that way with a fill covering half a percent of the region.
 *  - **An escape is measured against the box**, not against the region's border. A silhouette
 *    that is the balloon barely leaves its box; one that has flooded the panel is mostly
 *    outside it. Measured over 106 balloons: right ones sat at 0.00 to 0.22 of their area
 *    outside the box, and the first flooded one at 0.30.
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
        box: MaskRect,
        growPasses: Int,
    ): BooleanArray? {
        val filled = surface(pixels, width, height, box) ?: return null
        return outline(filled, pixels, width, height, growPasses)
    }

    /**
     * The balloon's surface: [extract] before the outline is added, which is the shape
     * [BubbleTail] reads a tail's way out of the capture from. Amendment E18.
     *
     * [aroundBox] is for a capture that is not centred on the balloon, which is what following a
     * tail makes: the fill starts from the middle of [box] rather than of the capture, and the
     * escape guard is not asked, because a tail is exactly the part of a balloon that lies
     * outside its box. [BubbleTail] guards that case by growth instead.
     */
    fun surface(
        pixels: IntArray,
        width: Int,
        height: Int,
        box: MaskRect,
        aroundBox: Boolean = false,
    ): BooleanArray? {
        if (width < MIN_SIDE || height < MIN_SIDE) return null
        val size = width * height
        if (pixels.size < size) return null

        val luma = FloatArray(size) { lumaOf(pixels[it]) }
        val surface = surfaceTone(luma, width, box)

        val filled = fillFromCentre(luma, width, height, surface, box, aroundBox) ?: return null
        fillEnclosedHoles(filled, width, height)

        // Asked before the silhouette is grown: growing would drag it over the box from the
        // outside, and a shape that merely surrounds the balloon would pass for one that is it.
        if (!covers(filled, width, height, box)) return null
        return filled
    }

    /**
     * [surface] grown to take in the balloon's own line, and no further. Amendment E19.
     *
     * [growPasses] is how thick the caller expects the line to be, from the page's size. That
     * is an estimate, made generous on purpose, and on a comic whose pen is finer than it
     * assumes the growth went on past the line into the art around the balloon: a rim of red,
     * beige or olive page the reader saw around every card. Measured on a whole issue, the
     * rings grown out of a balloon read its paper, then its black line for one to three rings,
     * then whatever is drawn beside it.
     *
     * So the line is read off the balloon itself, as the ring among the first few that differs
     * most from its surface, and the silhouette grows while the rings are still that tone — and,
     * past the first, only into pixels of that tone, so where art touches the line on one side
     * only that side stops. A balloon whose edge does not stand out from its surface, a caption
     * with no border, grows the whole way as it always did: there is no line to stop at.
     */
    fun outline(surface: BooleanArray, pixels: IntArray, width: Int, height: Int, growPasses: Int): BooleanArray {
        val passes = growPasses.coerceIn(1, BubbleZoomConfig.MASK_MAX_GROW_PASSES)
        val size = width * height
        if (pixels.size < size || surface.size < size) return grow(surface, width, height, passes)

        val luma = FloatArray(size) { lumaOf(pixels[it]) }
        val tone = medianTone(luma, surface) { true } ?: return grow(surface, width, height, passes)

        // The tone of each ring the plain growth would add, one pass at a time.
        val rings = FloatArray(passes)
        var reached = surface
        for (ring in 0 until passes) {
            val next = dilate(reached, width, height)
            val inside = reached
            rings[ring] = medianTone(luma, next) { !inside[it] } ?: tone
            reached = next
        }

        var line = rings[0]
        for (ring in 1 until minOf(passes, BubbleZoomConfig.MASK_LINE_PROBE_RINGS)) {
            if (abs(rings[ring] - tone) > abs(line - tone)) line = rings[ring]
        }
        // Nothing around it stands out from its surface: no line to stop at, so as before.
        if (abs(line - tone) < BubbleZoomConfig.MASK_LINE_CONTRAST) return reached

        var thickness = 1
        while (thickness < passes && abs(rings[thickness] - line) <= BubbleZoomConfig.MASK_LINE_TOLERANCE) {
            thickness++
        }
        // The first ring always: it is where the fill stopped, half surface and half line.
        var grown = dilate(surface, width, height)
        repeat(thickness - 1) {
            grown = dilate(grown, width, height) { abs(luma[it] - line) <= BubbleZoomConfig.MASK_LINE_TOLERANCE }
        }
        return grown
    }

    /** The median of [luma] over the pixels of [cells] that [counted] admits, or null if none. */
    private inline fun medianTone(luma: FloatArray, cells: BooleanArray, counted: (Int) -> Boolean): Float? {
        val bins = IntArray(TONE_BINS)
        var total = 0
        for (index in luma.indices) {
            if (!cells[index] || !counted(index)) continue
            bins[(luma[index] * (TONE_BINS - 1)).toInt().coerceIn(0, TONE_BINS - 1)]++
            total++
        }
        if (total == 0) return null
        val half = (total + 1) / 2
        var seen = 0
        for (bin in bins.indices) {
            seen += bins[bin]
            if (seen >= half) return bin / (TONE_BINS - 1f)
        }
        return 1f
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

    /** Whether enough of the balloon this was found for lies inside the silhouette. */
    private fun covers(filled: BooleanArray, width: Int, height: Int, box: MaskRect): Boolean {
        val left = box.left.coerceIn(0, width - 1)
        val top = box.top.coerceIn(0, height - 1)
        val right = box.right.coerceIn(left + 1, width)
        val bottom = box.bottom.coerceIn(top + 1, height)

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
        return inside.toFloat() / total >= BubbleZoomConfig.MASK_MIN_BOX_COVERAGE
    }

    /**
     * The tone the balloon is made of: the commonest tone across the whole of its box.
     *
     * Not the median of a patch at the middle, which is what this was until amendment E10. A
     * balloon is lettering on a surface, and the middle of one is as likely to be lettering as
     * not — on a balloon whose words fill it, a patch there is ALL lettering and its median is
     * ink. The fill then spread across one letter and stopped, which measured on a real page
     * killed ten balloons of nineteen.
     *
     * Across the box the surface wins on area, whatever the lettering does, and a histogram's
     * commonest bin finds it whether the balloon is white paper or black. A median across the
     * box would do for most, but tips to ink on a balloon more than half full of it.
     */
    private fun surfaceTone(luma: FloatArray, width: Int, box: MaskRect): Float {
        val bins = IntArray(BubbleZoomConfig.MASK_TONE_BINS)
        for (y in box.top until box.bottom) {
            val row = y * width
            for (x in box.left until box.right) {
                val bin = (luma[row + x] * BubbleZoomConfig.MASK_TONE_BINS)
                    .toInt()
                    .coerceIn(0, BubbleZoomConfig.MASK_TONE_BINS - 1)
                bins[bin]++
            }
        }
        var commonest = 0
        for (bin in bins.indices) {
            if (bins[bin] > bins[commonest]) commonest = bin
        }
        return (commonest + 0.5f) / BubbleZoomConfig.MASK_TONE_BINS
    }

    /**
     * Spreads across the surface from every seed that shares its tone, four-connected.
     *
     * Returns null when the fill found no seed at all, or when what it covered is mostly outside
     * the balloon's own box — which is a fill that has got out through a gap in the outline and
     * is tracing the panel behind it.
     */
    private fun fillFromCentre(
        luma: FloatArray,
        width: Int,
        height: Int,
        surface: Float,
        box: MaskRect,
        aroundBox: Boolean,
    ): BooleanArray? {
        val size = width * height
        val filled = BooleanArray(size)
        // Each pixel is flagged as it is pushed, so it is pushed at most once and the stack can
        // never need more room than the image has pixels.
        val stack = IntArray(size)
        var top = 0
        var area = 0

        // Outwards from the middle until the surface is found. One patch is enough for almost
        // every balloon, but on one whose words fill it the patch is all lettering, and the
        // nearest surface is a few pixels further out — still inside the balloon, which is what
        // starting at the middle and growing guarantees and scanning the box would not.
        var half = if (aroundBox) seedHalf(box.width, box.height) else seedHalf(width, height)
        val centreX = if (aroundBox) (box.left + box.right) / 2 else width / 2
        val centreY = if (aroundBox) (box.top + box.bottom) / 2 else height / 2
        val limit = maxOf(box.width, box.height)
        while (top == 0 && half <= limit) {
            forEachSeed(width, height, centreX, centreY, half) { index ->
                if (!filled[index] && sameSurface(luma[index], surface)) {
                    filled[index] = true
                    stack[top++] = index
                    area++
                }
            }
            half *= 2
        }
        if (top == 0) return null

        var outside = 0
        while (top > 0) {
            val index = stack[--top]
            val x = index % width
            val y = index / width
            if (x < box.left || x >= box.right || y < box.top || y >= box.bottom) outside++

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

        // A balloon's silhouette is its box, give or take an outline and a tail; a fill that
        // got out is the panel behind it, which is mostly elsewhere. Measured over 106 balloons
        // of a real comic: every right one left 0 to 22 percent of itself outside the box, and
        // the first flooded one 30.
        if (!aroundBox && outside.toFloat() / area > BubbleZoomConfig.MASK_MAX_ESCAPE_FRACTION) return null
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

    /** [source] grown by a pixel, four-connected, into the pixels [admits] lets it. */
    private inline fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        admits: (Int) -> Boolean = { true },
    ): BooleanArray {
        val grown = BooleanArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                grown[index] = source[index] || (
                    (
                        (x > 0 && source[index - 1]) ||
                            (x < width - 1 && source[index + 1]) ||
                            (y > 0 && source[index - width]) ||
                            (y < height - 1 && source[index + width])
                        ) && admits(index)
                    )
            }
        }
        return grown
    }

    private fun sameSurface(tone: Float, surface: Float): Boolean =
        abs(tone - surface) <= BubbleZoomConfig.MASK_TOLERANCE

    /** The half-width of the first seed patch, a fraction of the mask's smaller side. */
    private fun seedHalf(width: Int, height: Int): Int =
        (minOf(width, height) * BubbleZoomConfig.MASK_SEED_PATCH_FRACTION).toInt().coerceAtLeast(1)

    /**
     * Visits a patch of [half] around ([centreX], [centreY]) — the middle of the mask, where the
     * balloon certainly is, or the middle of the balloon's box when the capture is not centred
     * on it.
     *
     * Only the fill starts here. Where the SURFACE's tone comes from is another question, and
     * since amendment E10 the answer is the whole box — see [surfaceTone].
     */
    private inline fun forEachSeed(
        width: Int,
        height: Int,
        centreX: Int,
        centreY: Int,
        half: Int,
        visit: (Int) -> Unit,
    ) {
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
    private const val TONE_BINS = 256
    private const val RED_WEIGHT = 0.299f
    private const val GREEN_WEIGHT = 0.587f
    private const val BLUE_WEIGHT = 0.114f
}
