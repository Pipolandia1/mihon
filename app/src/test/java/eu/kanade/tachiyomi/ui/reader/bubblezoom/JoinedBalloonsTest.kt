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

    private class Page(val side: Int) {
        val luma = IntArray(side * side) { ART }

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
        JoinedBalloons.merge(boxes.toList(), page.luma, side, side)

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

        assertEquals(single, JoinedBalloons.merge(single, page.luma, side, side))
        assertEquals(emptyList<BubbleDetection>(), JoinedBalloons.merge(emptyList(), page.luma, side, side))
    }

    @Test
    fun `a page it cannot read joins nothing`() {
        val two = listOf(detection(box(70, 100, 45, 30)), detection(box(130, 100, 45, 30)))

        assertEquals(two, JoinedBalloons.merge(two, IntArray(0), 0, 0))
        assertEquals(two, JoinedBalloons.merge(two, IntArray(9), side, side), "plane too short")
    }

    private companion object {
        const val ART = 30
        const val INK = 10
        const val PAPER = 240
    }
}
