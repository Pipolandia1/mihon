package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Joining the lobes of a balloon lettered as two bubbles. Amendment E12.
 *
 * The page is drawn in brightness, 200 by 200, dark art with pale balloons on it — the
 * arrangement that makes the question real: two lobes sharing their paper, against two balloons
 * whose boxes cross but which are divided by their own outlines.
 */
class JoinedBalloonsTest {

    private val side = 200

    private class Page(val side: Int, background: Int = ART) {
        val luma = IntArray(side * side) { background }

        fun disc(cx: Int, cy: Int, rx: Int, ry: Int, tone: Int) {
            for (y in 0 until side) {
                for (x in 0 until side) {
                    val dx = (x - cx).toFloat() / rx
                    val dy = (y - cy).toFloat() / ry
                    if (dx * dx + dy * dy <= 1f) luma[y * side + x] = tone
                }
            }
        }

        /** A lobe: pale paper inside a dark outline, like every balloon ever drawn. */
        fun lobe(cx: Int, cy: Int, rx: Int, ry: Int) {
            disc(cx, cy, rx + 3, ry + 3, INK)
            disc(cx, cy, rx, ry, PAPER)
        }

        fun rect(left: Int, top: Int, right: Int, bottom: Int, tone: Int) {
            for (y in top until bottom) {
                for (x in left until right) luma[y * side + x] = tone
            }
        }

        /** A neck of outlined paper between two lobes, the way a chain is inked. */
        fun neck(left: Int, right: Int, cy: Int, half: Int) {
            rect(left, cy - half - 3, right, cy + half + 3, INK)
            rect(left, cy - half, right, cy + half, PAPER)
        }

        /**
         * Crops the drawing at its own resolution, as the detector crops the page's bytes: the
         * rectangle asked for plus the margin the ring guard reads, a fixed share of its
         * smallest side. The figure is the detector's and pinned here on purpose, so a retuning
         * there is a deliberate change here rather than a silent one.
         */
        fun crop(around: BubbleBox): JoinedBalloons.Crop {
            val margin = (MARGIN * minOf(around.width, around.height) * side).toInt()
            val left = ((around.x * side).toInt() - margin).coerceIn(0, side - 1)
            val top = ((around.y * side).toInt() - margin).coerceIn(0, side - 1)
            val right = ((around.right * side).toInt() + margin).coerceIn(left + 1, side)
            val bottom = ((around.bottom * side).toInt() + margin).coerceIn(top + 1, side)
            val width = right - left
            val height = bottom - top
            val pixels = IntArray(width * height) { i -> luma[(top + i / width) * side + left + i % width] }
            val region = BubbleBox(
                left.toFloat() / side,
                top.toFloat() / side,
                width.toFloat() / side,
                height.toFloat() / side,
            )
            return JoinedBalloons.Crop(pixels, width, height, region)
        }
    }

    private fun box(cx: Int, cy: Int, rx: Int, ry: Int) = BubbleBox(
        (cx - rx).toFloat() / side,
        (cy - ry).toFloat() / side,
        (2f * rx) / side,
        (2f * ry) / side,
    )

    private fun detection(box: BubbleBox, confidence: Float = 0.9f) =
        BubbleDetection(box, confidence, BubbleType.TEXT)

    private fun merge(page: Page, vararg boxes: BubbleDetection) =
        JoinedBalloons.merge(boxes.toList(), page::crop)

    @Test
    fun `two lobes sharing their paper are one balloon`() {
        // A waisted balloon: two discs overlapping, drawn once, so the paper runs from one into
        // the other. Their boxes cross, and so does the surface.
        val page = Page(side)
        page.disc(90, 80, 55, 38, INK)
        page.disc(110, 130, 48, 34, INK)
        page.disc(90, 80, 52, 35, PAPER)
        page.disc(110, 130, 45, 31, PAPER)

        val merged = merge(page, detection(box(90, 80, 52, 35)), detection(box(110, 130, 45, 31), 0.7f))

        assertEquals(1, merged.size)
        val box = merged.single().box
        assertTrue(box.y <= (80 - 35).toFloat() / side + 1e-3f, "the join reaches the top lobe")
        assertTrue(box.bottom >= (130 + 31).toFloat() / side - 1e-3f, "and the bottom one")
        assertEquals(0.9f, merged.single().confidence, "the surest lobe's confidence carries")
    }

    @Test
    fun `two balloons that merely stand close are left alone`() {
        // The case crossing boxes cannot tell apart: two balloons side by side, each with its
        // own outline, whose rectangles overlap because a balloon is round and a box is not.
        val page = Page(side)
        page.lobe(70, 100, 45, 30)
        page.lobe(130, 100, 45, 30)

        val merged = merge(page, detection(box(70, 100, 45, 30)), detection(box(130, 100, 45, 30)))

        assertEquals(2, merged.size, "two speeches, two stops")
    }

    @Test
    fun `boxes that do not cross at all are never compared`() {
        val page = Page(side)
        page.lobe(50, 50, 30, 20)
        page.lobe(150, 150, 30, 20)

        assertEquals(2, merge(page, detection(box(50, 50, 30, 20)), detection(box(150, 150, 30, 20))).size)
    }

    @Test
    fun `three lobes in a row become one, not two`() {
        // Union-find earns its place here: the first and the last never touch, and are one
        // balloon only through the middle.
        val page = Page(side)
        page.disc(60, 100, 40, 26, INK)
        page.disc(100, 100, 40, 26, INK)
        page.disc(140, 100, 40, 26, INK)
        page.disc(60, 100, 37, 23, PAPER)
        page.disc(100, 100, 37, 23, PAPER)
        page.disc(140, 100, 37, 23, PAPER)

        val merged = merge(
            page,
            detection(box(60, 100, 37, 23)),
            detection(box(100, 100, 37, 23)),
            detection(box(140, 100, 37, 23)),
        )

        assertEquals(1, merged.size)
        assertTrue(merged.single().box.width > 0.6f, "the joined box spans all three")
    }

    @Test
    fun `a joined balloon carries no silhouette from its lobes`() {
        // Whatever shape a lobe came with describes that lobe, not the balloon around it.
        val page = Page(side)
        page.disc(90, 80, 55, 38, INK)
        page.disc(110, 130, 48, 34, INK)
        page.disc(90, 80, 52, 35, PAPER)
        page.disc(110, 130, 45, 31, PAPER)
        val shaped = BubbleDetection(
            box(90, 80, 52, 35),
            0.9f,
            BubbleType.TEXT,
            BubbleShape(2, 2, booleanArrayOf(true, true, true, true)),
        )

        val merged = merge(page, shaped, detection(box(110, 130, 45, 31)))

        assertEquals(1, merged.size)
        assertEquals(null, merged.single().shape)
    }

    @Test
    fun `one balloon, or none, comes back as it went in`() {
        val page = Page(side)
        val single = listOf(detection(box(100, 100, 30, 20)))

        assertEquals(single, JoinedBalloons.merge(single, page::crop))
        assertEquals(emptyList<BubbleDetection>(), JoinedBalloons.merge(emptyList(), page::crop))
    }

    @Test
    fun `a pair whose pixels cannot be read is left as two`() {
        // The region decoder can refuse - a progressive JPEG it cannot address, bytes gone
        // stale - and a pair it cannot see is two stops, never a guess.
        val two = listOf(detection(box(70, 100, 45, 30)), detection(box(130, 100, 45, 30)))

        assertEquals(two, JoinedBalloons.merge(two, { null }))
        val short = { _: BubbleBox -> JoinedBalloons.Crop(IntArray(9), side, side, BubbleBox(0f, 0f, 1f, 1f)) }
        assertEquals(two, JoinedBalloons.merge(two, short), "a crop too short for its own size")
    }

    @Test
    fun `two lobes standing edge to edge are one balloon`() {
        // A butted balloon: two lobes inked as one, joined by a wide neck, whose boxes do not
        // cross at all - a hair of page lies between them. Amendment E13.
        val page = Page(side)
        page.lobe(60, 100, 38, 25)
        page.lobe(140, 100, 38, 25)
        page.neck(90, 110, 100, 16)

        val merged = merge(page, detection(box(60, 100, 38, 25)), detection(box(140, 100, 38, 25)))

        assertEquals(1, merged.size)
        val box = merged.single().box
        assertEquals((60 - 38).toFloat() / side, box.x, 1e-3f, "the join spans the left lobe")
        assertEquals((140 + 38).toFloat() / side, box.right, 1e-3f, "and the right one")
    }

    @Test
    fun `two balloons further apart than the gap are never asked`() {
        // The same chain drawn wider: the paper runs between the lobes, but their boxes stand
        // a fourteenth of the page apart, and a pair that far apart is not a candidate.
        val page = Page(side)
        page.lobe(55, 100, 38, 25)
        page.lobe(145, 100, 38, 25)
        page.neck(85, 115, 100, 12)

        assertEquals(2, merge(page, detection(box(55, 100, 38, 25)), detection(box(145, 100, 38, 25))).size)
    }

    @Test
    fun `a fill that floods the panel joins nothing`() {
        // Two balloons on a panel of their own tone, each with a break in its outline where its
        // tail opens: the fill leaves the first, covers the panel, and enters the second. That
        // reach would join them; the flood over the margin says it must not.
        val page = Page(side, background = PAPER)
        page.lobe(70, 100, 45, 30)
        page.lobe(130, 100, 45, 30)
        page.rect(66, 64, 74, 72, PAPER)
        page.rect(126, 64, 134, 72, PAPER)
        var seen: Pair<JoinedBalloons.Spread, Boolean>? = null

        val merged = JoinedBalloons.merge(
            listOf(detection(box(70, 100, 45, 30)), detection(box(130, 100, 45, 30))),
            page::crop,
        ) { _, _, spread, joined -> seen = spread to joined }

        assertEquals(2, merged.size)
        val (spread, joined) = seen!!
        assertTrue(spread.reach > 0.5f, "the fill did reach the other balloon: ${spread.reach}")
        assertTrue(spread.ring > 0.5f, "and flooded the margin: ${spread.ring}")
        assertEquals(false, joined)
    }

    @Test
    fun `two balloons touching at a point stay two`() {
        // The same two lobes, edge to edge, but linked by a thread of paper - the way two
        // speakers' balloons touch, or a tail is lettered into the next balloon. The fill
        // crosses; the seam says how little of the band it crossed.
        val page = Page(side)
        page.lobe(60, 100, 38, 25)
        page.lobe(140, 100, 38, 25)
        page.neck(90, 110, 100, 3)
        var seen: Pair<JoinedBalloons.Spread, Boolean>? = null

        val merged = JoinedBalloons.merge(
            listOf(detection(box(60, 100, 38, 25)), detection(box(140, 100, 38, 25))),
            page::crop,
        ) { _, _, spread, joined -> seen = spread to joined }

        assertEquals(2, merged.size)
        val (spread, joined) = seen!!
        assertTrue(spread.reach > 0.5f, "the fill did reach the other lobe: ${spread.reach}")
        assertTrue(spread.seam < 0.25f, "across a thread of the band: ${spread.seam}")
        assertEquals(false, joined)
    }

    @Test
    fun `a fill does not run through another balloon`() {
        // The wide neck again, with a third box standing over most of it. Whatever is drawn
        // there, the box is closed to the fill, and what is left of the neck is a thread: the
        // same pair reads a wide seam alone and a thin one with the bystander on the page.
        val page = Page(side)
        page.lobe(60, 100, 38, 25)
        page.lobe(140, 100, 38, 25)
        page.neck(90, 110, 100, 16)
        val a = detection(box(60, 100, 38, 25))
        val b = detection(box(140, 100, 38, 25))
        val bystander = detection(BubbleBox(90f / side, 60f / side, 20f / side, 50f / side))
        fun seamOf(vararg balloons: BubbleDetection): Float {
            var seam = -1f
            JoinedBalloons.merge(balloons.toList(), page::crop) { larger, smaller, spread, _ ->
                if (larger == a.box && smaller == b.box) seam = spread.seam
            }
            return seam
        }

        val alone = seamOf(a, b)
        val shut = seamOf(a, b, bystander)

        assertTrue(alone > 0.5f, "the neck alone: $alone")
        assertTrue(shut < 0.25f, "the neck with the bystander's box shut: $shut")
    }

    private companion object {
        const val ART = 30
        const val INK = 10
        const val PAPER = 240

        /** The margin the detector's crop carries, as a share of the rectangle's smallest side. */
        const val MARGIN = 0.15f
    }
}
