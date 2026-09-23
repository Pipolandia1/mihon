package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Two bubbles that are one speech, joined into one stop. Amendment E12.
 *
 * Comics letter a long line as two or three lobes sharing an outline — "SEE, THERE'S A TRUE
 * FRIEND." and "BROKEN HEARTS AND..." drawn as one balloon with a waist. The model returns a box
 * per lobe, so the walk stopped twice and the card cut the speech in half.
 *
 * **Crossing boxes are not the test.** A balloon is round and its box is not, so two neighbours
 * standing diagonally cross each other's boxes without touching at all: measured over 103 pages,
 * 246 pairs of balloons have crossing boxes and most of those are separate speeches.
 *
 * The test is the paper. Lobes of one balloon share their surface, so a fill started in one
 * spreads into the other; two balloons that merely stand close are divided by two outlines and
 * the art between them. Measured on those 246 pairs, by how much of the smaller box a fill from
 * the larger one covers:
 *
 * | reach | what they were |
 * |---|---|
 * | 0.00 – 0.13 | separate speeches, every one — "I ATE THE ZOO." beside "I KNOW YOU DID, BUDDY" |
 * | 0.14 – 0.36 | nothing at all: the gap between the two populations |
 * | 0.37 – 0.69 | lobes of one balloon, every one |
 *
 * So the threshold sits in an empty gap rather than on a judgement call, and it is the same
 * fill, tone and tolerance [BubbleMask] traces the card with — this is that question asked of
 * the page once, instead of after the reader has already been shown the wrong half.
 *
 * Pure Kotlin over one grey plane.
 */
object JoinedBalloons {

    /**
     * [balloons] with the lobes of each balloon merged into one region, in the order they came.
     *
     * [luma] is the page as one byte of brightness per pixel, at whatever reduction the caller
     * looks at it in; the boxes are fractions of the page, so the two agree without either
     * knowing the other's scale.
     */
    fun merge(balloons: List<BubbleDetection>, luma: IntArray, width: Int, height: Int): List<BubbleDetection> {
        if (balloons.size < 2 || width <= 0 || height <= 0 || luma.size < width * height) return balloons
        val page = Page(luma, width, height)

        // Union-find: three lobes in a row are one balloon, and they are only ever compared in
        // pairs, so the groups have to be able to grow.
        val group = IntArray(balloons.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (group[root] != root) root = group[root]
            var walk = i
            while (group[walk] != walk) {
                val next = group[walk]
                group[walk] = root
                walk = next
            }
            return root
        }

        for (i in balloons.indices) {
            for (j in i + 1 until balloons.size) {
                if (find(i) == find(j)) continue
                val a = balloons[i].box
                val b = balloons[j].box
                if (!crosses(a, b)) continue
                val larger = if (a.area >= b.area) a else b
                val smaller = if (a.area >= b.area) b else a
                if (!reaches(page, larger, smaller)) continue
                group[find(i)] = find(j)
            }
        }

        val merged = LinkedHashMap<Int, MutableList<BubbleDetection>>()
        for (i in balloons.indices) merged.getOrPut(find(i)) { ArrayList() } += balloons[i]
        return merged.values.map { lobes ->
            if (lobes.size == 1) lobes[0] else joined(lobes)
        }
    }

    /** One detection covering every lobe, keeping the surest lobe's confidence. */
    private fun joined(lobes: List<BubbleDetection>): BubbleDetection {
        val left = lobes.minOf { it.box.x }
        val top = lobes.minOf { it.box.y }
        val right = lobes.maxOf { it.box.right }
        val bottom = lobes.maxOf { it.box.bottom }
        val box = BubbleBox.clampedOrNull(left, top, right - left, bottom - top) ?: return lobes[0]
        val surest = lobes.maxBy { it.confidence }
        return surest.copy(box = box, shape = null)
    }

    private fun crosses(a: BubbleBox, b: BubbleBox): Boolean =
        minOf(a.right, b.right) > maxOf(a.x, b.x) && minOf(a.bottom, b.bottom) > maxOf(a.y, b.y)

    private class Page(val luma: IntArray, val width: Int, val height: Int) {
        fun rect(box: BubbleBox): IntArray {
            val left = (box.x * width).toInt().coerceIn(0, width - 1)
            val top = (box.y * height).toInt().coerceIn(0, height - 1)
            val right = (box.right * width).toInt().coerceIn(left + 1, width)
            val bottom = (box.bottom * height).toInt().coerceIn(top + 1, height)
            return intArrayOf(left, top, right, bottom)
        }
    }

    /**
     * Whether a fill started in [larger] covers enough of [smaller] for the two to be one
     * balloon, by the same tone and tolerance the card's own trace uses.
     *
     * The fill runs inside the two boxes' own rectangle and nothing wider: what it does out on
     * the page is not the question, and a page-sized fill per pair would be megabytes of
     * churn for every balloon the reader walks past.
     */
    private fun reaches(page: Page, larger: BubbleBox, smaller: BubbleBox): Boolean {
        val from = page.rect(larger)
        val to = page.rect(smaller)
        val left = minOf(from[0], to[0])
        val top = minOf(from[1], to[1])
        val right = maxOf(from[2], to[2])
        val bottom = maxOf(from[3], to[3])
        val windowWidth = right - left
        val windowHeight = bottom - top
        if (windowWidth < 2 || windowHeight < 2) return false

        val surface = surfaceTone(page, from[0], from[1], from[2], from[3])
        val tolerance = (BubbleZoomConfig.MASK_TOLERANCE * 255f).toInt()
        val size = windowWidth * windowHeight
        val filled = BooleanArray(size)
        val stack = IntArray(size)
        var count = 0

        // Seeded from a patch at the middle of the larger lobe, because its exact centre is as
        // likely to be a letter as paper.
        val half = maxOf(1, (minOf(from[2] - from[0], from[3] - from[1]) * SEED_FRACTION).toInt())
        val seedX = (from[0] + from[2]) / 2
        val seedY = (from[1] + from[3]) / 2
        for (y in (seedY - half).coerceAtLeast(top) until (seedY + half + 1).coerceAtMost(bottom)) {
            for (x in (seedX - half).coerceAtLeast(left) until (seedX + half + 1).coerceAtMost(right)) {
                val index = (y - top) * windowWidth + (x - left)
                if (!filled[index] && sameSurface(page.luma[y * page.width + x], surface, tolerance)) {
                    filled[index] = true
                    stack[count++] = index
                }
            }
        }
        if (count == 0) return false

        while (count > 0) {
            val index = stack[--count]
            val x = index % windowWidth
            val y = index / windowWidth
            if (x > 0) {
                val next = index - 1
                if (!filled[next] &&
                    sameSurface(page.luma[(y + top) * page.width + x - 1 + left], surface, tolerance)
                ) {
                    filled[next] = true
                    stack[count++] = next
                }
            }
            if (x < windowWidth - 1) {
                val next = index + 1
                if (!filled[next] &&
                    sameSurface(page.luma[(y + top) * page.width + x + 1 + left], surface, tolerance)
                ) {
                    filled[next] = true
                    stack[count++] = next
                }
            }
            if (y > 0) {
                val next = index - windowWidth
                if (!filled[next] &&
                    sameSurface(page.luma[(y - 1 + top) * page.width + x + left], surface, tolerance)
                ) {
                    filled[next] = true
                    stack[count++] = next
                }
            }
            if (y < windowHeight - 1) {
                val next = index + windowWidth
                if (!filled[next] &&
                    sameSurface(page.luma[(y + 1 + top) * page.width + x + left], surface, tolerance)
                ) {
                    filled[next] = true
                    stack[count++] = next
                }
            }
        }

        var inside = 0
        var total = 0
        for (y in to[1] until to[3]) {
            for (x in to[0] until to[2]) {
                total++
                if (filled[(y - top) * windowWidth + (x - left)]) inside++
            }
        }
        if (total == 0) return false
        return inside.toFloat() / total >= BubbleZoomConfig.MERGE_REACH
    }

    private fun sameSurface(tone: Int, surface: Int, tolerance: Int): Boolean {
        val difference = tone - surface
        return difference <= tolerance && difference >= -tolerance
    }

    /** The commonest tone in the rectangle, which is what the balloon is made of. */
    private fun surfaceTone(page: Page, left: Int, top: Int, right: Int, bottom: Int): Int {
        val bins = IntArray(BubbleZoomConfig.MASK_TONE_BINS)
        val width = 256 / BubbleZoomConfig.MASK_TONE_BINS
        for (y in top until bottom) {
            val row = y * page.width
            for (x in left until right) {
                bins[(page.luma[row + x] / width).coerceIn(0, bins.lastIndex)]++
            }
        }
        var commonest = 0
        for (bin in bins.indices) {
            if (bins[bin] > bins[commonest]) commonest = bin
        }
        return commonest * width + width / 2
    }

    /** Half-width of the seed patch, as a fraction of the lobe's smallest side. */
    private const val SEED_FRACTION = 0.10f
}
