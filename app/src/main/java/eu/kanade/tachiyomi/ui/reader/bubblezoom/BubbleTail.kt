package eu.kanade.tachiyomi.ui.reader.bubblezoom

import kotlin.math.abs

/**
 * Follows a balloon's tail out of the capture it was traced in. Amendment E18.
 *
 * The silhouette is traced in the balloon's box padded by a quarter of its shorter side, which
 * holds the balloon and its outline and was never meant to hold more: a tail that reaches
 * further runs out of the capture and the card shows it sawn off. Measured over the 263
 * balloons of a whole issue: 94 silhouettes ran into the capture's edge, and most of those were
 * tails cut short.
 *
 * A tail is told apart by where it leaves: through a gap narrow next to the balloon. The
 * capture is then widened on that side by the balloon's own longer side and the surface traced
 * again, from the middle of the balloon, until it no longer touches the edge — up to
 * [BubbleZoomConfig.TAIL_MAX_EXTENSIONS] times. What comes back is either the same balloon with
 * the rest of its tail, or the first trace untouched:
 *
 *  - A silhouette that leaves through a wide gap is not a tail there: a caption running on
 *    under a panel's border, or a balloon breaking out of its panel into the gutter. That side
 *    is left as it was, and only a narrow one is followed.
 *  - A tail that grows the balloon by more than [BubbleZoomConfig.TAIL_MAX_GROWTH] of its area
 *    has run into something of the same tone: another balloon it touches, or paper. Measured:
 *    every real tail added at most 0.20; every one that ran into a neighbour, 0.42 or more.
 *  - A tail of another hue is not this balloon's: a pale caption running into snow, a white
 *    balloon into a beige wall. The fill compares brightness only, so the colour is asked
 *    afterwards. Measured: every real tail within [BubbleZoomConfig.TAIL_MAX_HUE_SHIFT] of its
 *    balloon's hue at 0.061; the three that were snow, a wall and fire, at 0.133 and more.
 *  - A tail still open after the last extension, or a trace that fails, gives the first back.
 *
 * Only filled tails, the white wedge a balloon points with. A tail drawn as a line has no
 * surface to follow, and the trace never sees it as part of the balloon.
 *
 * Pure Kotlin: the captures arrive through a function, so every rule is a JVM test.
 */
object BubbleTail {

    /**
     * A surface traced in [search], a region of the page, over a [width] x [height] mask, with
     * the ARGB [pixels] it was traced from.
     */
    class Trace(
        val search: BubbleBox,
        val surface: BooleanArray,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    /** What following found, which the caller logs: the tail is invisible when it works. */
    enum class Outcome {
        /** The silhouette never reached the capture's edge. */
        CLOSED,

        /** It left only through gaps too wide to be a tail. */
        WIDE,

        /** It had a tail, and the trace now holds all of it. */
        FOLLOWED,

        /** It had a tail that could not be followed safely; the first trace stands. */
        REFUSED,
    }

    class Followed(val trace: Trace, val outcome: Outcome, val extensions: Int)

    /** The sides of a capture a silhouette can run out of. */
    enum class Side { LEFT, TOP, RIGHT, BOTTOM }

    /**
     * The balloon in [box] with all of its tail, starting from [first], its trace in the usual
     * capture. [retrace] traces the balloon again in a larger region of the page, or returns
     * null when it cannot; it is only asked when there is a tail to follow.
     */
    fun follow(
        box: BubbleBox,
        first: Trace,
        sourceWidth: Float,
        sourceHeight: Float,
        retrace: (BubbleBox) -> Trace?,
    ): Followed {
        val boxInMask = BubbleCardGeometry.boxWithin(box, first.search, first.width, first.height)
        val neck = BubbleZoomConfig.TAIL_NECK * minOf(boxInMask.width, boxInMask.height)
        val touched = openSides(first)
        // A side it leaves widely is where the balloon runs on, not a tail: it stays as it was
        // traced, and is not asked about again.
        val wide = touched.filterValues { it > neck }.keys
        var open = touched.keys - wide
        if (open.isEmpty()) return Followed(first, if (wide.isEmpty()) Outcome.CLOSED else Outcome.WIDE, 0)

        val start = area(first, sourceWidth, sourceHeight)
        val most = start * (1f + BubbleZoomConfig.TAIL_MAX_GROWTH)
        var search = first.search
        for (extension in 1..BubbleZoomConfig.TAIL_MAX_EXTENSIONS) {
            search = extended(search, box, open, sourceWidth, sourceHeight)
            val next = retrace(search) ?: return Followed(first, Outcome.REFUSED, extension)
            if (area(next, sourceWidth, sourceHeight) > most) return Followed(first, Outcome.REFUSED, extension)
            open = openSides(next).keys - wide
            if (open.isEmpty()) {
                val same = sameHue(next, box, first.search)
                return Followed(if (same) next else first, if (same) Outcome.FOLLOWED else Outcome.REFUSED, extension)
            }
        }
        return Followed(first, Outcome.REFUSED, BubbleZoomConfig.TAIL_MAX_EXTENSIONS)
    }

    /**
     * The sides [trace]'s surface touches, with the longest run of surface along each, leaving
     * out any that is already the page's own edge: a balloon cut by the edge of the page has
     * nothing further to follow.
     */
    private fun openSides(trace: Trace): Map<Side, Int> {
        val open = HashMap<Side, Int>(4)
        for (side in Side.entries) {
            if (atPageEdge(trace.search, side)) continue
            val run = longestRun(trace, side)
            if (run > 0) open[side] = run
        }
        return open
    }

    private fun atPageEdge(search: BubbleBox, side: Side): Boolean = when (side) {
        Side.LEFT -> search.x <= 0f
        Side.TOP -> search.y <= 0f
        Side.RIGHT -> search.right >= 1f
        Side.BOTTOM -> search.bottom >= 1f
    }

    private fun longestRun(trace: Trace, side: Side): Int {
        val along = if (side == Side.LEFT || side == Side.RIGHT) trace.height else trace.width
        var best = 0
        var run = 0
        for (i in 0 until along) {
            val index = when (side) {
                Side.LEFT -> i * trace.width
                Side.RIGHT -> i * trace.width + trace.width - 1
                Side.TOP -> i
                Side.BOTTOM -> (trace.height - 1) * trace.width + i
            }
            if (trace.surface[index]) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /**
     * [search] widened on each [open] side by [BubbleZoomConfig.TAIL_REACH] times the balloon's
     * longer side, in the page's own pixels so a tall balloon reaches as far sideways as down.
     */
    fun extended(
        search: BubbleBox,
        box: BubbleBox,
        open: Set<Side>,
        sourceWidth: Float,
        sourceHeight: Float,
    ): BubbleBox {
        val reach = BubbleZoomConfig.TAIL_REACH * maxOf(box.width * sourceWidth, box.height * sourceHeight)
        val across = reach / sourceWidth
        val down = reach / sourceHeight
        val left = if (Side.LEFT in open) (search.x - across).coerceAtLeast(0f) else search.x
        val top = if (Side.TOP in open) (search.y - down).coerceAtLeast(0f) else search.y
        val right = if (Side.RIGHT in open) (search.right + across).coerceAtMost(1f) else search.right
        val bottom = if (Side.BOTTOM in open) (search.bottom + down).coerceAtMost(1f) else search.bottom
        return BubbleBox.clampedOrNull(left, top, right - left, bottom - top) ?: search
    }

    /**
     * Whether what [trace] added beyond [firstSearch] is the colour of the balloon in [box].
     *
     * Compared by hue, each colour less its own grey: a thin tail's pixels are its surface mixed
     * with the outline around it, darker but not another colour, and a darker tail must pass.
     * Medians, so the lettering in the balloon and the outline along the tail do not count.
     */
    private fun sameHue(trace: Trace, box: BubbleBox, firstSearch: BubbleBox): Boolean {
        val inBox = BubbleCardGeometry.boxWithin(box, trace.search, trace.width, trace.height)
        val body = Array(CHANNELS) { IntArray(TONES) }
        val tail = Array(CHANNELS) { IntArray(TONES) }
        var tailCount = 0
        for (y in 0 until trace.height) {
            val pageY = trace.search.y + (y + HALF) / trace.height * trace.search.height
            for (x in 0 until trace.width) {
                val index = y * trace.width + x
                if (!trace.surface[index]) continue
                val pageX = trace.search.x + (x + HALF) / trace.width * trace.search.width
                val into = when {
                    x >= inBox.left && x < inBox.right && y >= inBox.top && y < inBox.bottom -> body
                    !firstSearch.contains(pageX, pageY) -> tail.also { tailCount++ }
                    else -> continue
                }
                val pixel = trace.pixels[index]
                into[0][(pixel shr 16) and 0xFF]++
                into[1][(pixel shr 8) and 0xFF]++
                into[2][pixel and 0xFF]++
            }
        }
        if (tailCount < MIN_TAIL_PIXELS) return true
        val bodyHue = hueOf(body)
        val tailHue = hueOf(tail)
        val shift = (0 until CHANNELS).maxOf { abs(bodyHue[it] - tailHue[it]) }
        return shift <= BubbleZoomConfig.TAIL_MAX_HUE_SHIFT
    }

    /** The median of each channel, less their mean: the colour with its grey taken out. */
    private fun hueOf(histograms: Array<IntArray>): FloatArray {
        val medians = FloatArray(CHANNELS) { channel -> median(histograms[channel]) / (TONES - 1f) }
        val grey = medians.average().toFloat()
        return FloatArray(CHANNELS) { medians[it] - grey }
    }

    private fun median(histogram: IntArray): Int {
        val half = (histogram.sum() + 1) / 2
        var seen = 0
        for (tone in histogram.indices) {
            seen += histogram[tone]
            if (seen >= half) return tone
        }
        return histogram.size - 1
    }

    private const val CHANNELS = 3
    private const val TONES = 256
    private const val HALF = 0.5f

    /** Fewer than this beyond the first capture is a tail that barely moved: nothing to judge. */
    private const val MIN_TAIL_PIXELS = 3

    /** How much of the page [trace]'s surface covers, in the page's own square pixels. */
    private fun area(trace: Trace, sourceWidth: Float, sourceHeight: Float): Float {
        val pixel =
            (trace.search.width * sourceWidth / trace.width) * (trace.search.height * sourceHeight / trace.height)
        return trace.surface.count { it } * pixel
    }
}
