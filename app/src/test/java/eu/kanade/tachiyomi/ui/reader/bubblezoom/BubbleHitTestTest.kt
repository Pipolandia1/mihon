package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The rule a double tap is resolved by, on the JVM, because the emulator cannot double tap on
 * its own and the finger that will is the reader's.
 */
class BubbleHitTestTest {

    private fun text(x: Float, y: Float, w: Float, h: Float, type: BubbleType = BubbleType.TEXT) =
        BubbleDetection(BubbleBox(x, y, w, h), 0.9f, type)

    private val tolerance = BubbleZoomConfig.DOUBLE_TAP_HIT_TOLERANCE

    @Test
    fun `a point inside exactly one box means that box`() {
        val bubbles = listOf(text(0.1f, 0.1f, 0.2f, 0.1f), text(0.6f, 0.6f, 0.2f, 0.1f))

        assertEquals(1, BubbleHitTest.find(bubbles, 0.7f, 0.65f, tolerance))
        assertEquals(0, BubbleHitTest.find(bubbles, 0.15f, 0.15f, tolerance))
    }

    @Test
    fun `a point inside two nested boxes means the smaller`() {
        val bubbles = listOf(text(0.1f, 0.1f, 0.6f, 0.6f), text(0.3f, 0.3f, 0.1f, 0.1f))

        assertEquals(1, BubbleHitTest.find(bubbles, 0.35f, 0.35f, tolerance))
        // Inside the large one only.
        assertEquals(0, BubbleHitTest.find(bubbles, 0.15f, 0.15f, tolerance))
    }

    @Test
    fun `equal areas fall back to reading order`() {
        val bubbles = listOf(text(0.2f, 0.2f, 0.2f, 0.2f), text(0.3f, 0.3f, 0.2f, 0.2f))

        assertEquals(0, BubbleHitTest.find(bubbles, 0.35f, 0.35f, tolerance))
    }

    @Test
    fun `a point just outside the text is still that balloon, within the tolerance`() {
        // A 0.2 x 0.1 box grows by half its smaller side, 0.05, on every side.
        val bubbles = listOf(text(0.4f, 0.4f, 0.2f, 0.1f))

        assertEquals(0, BubbleHitTest.find(bubbles, 0.36f, 0.45f, tolerance))
        assertEquals(0, BubbleHitTest.find(bubbles, 0.5f, 0.54f, tolerance))
        assertEquals(-1, BubbleHitTest.find(bubbles, 0.34f, 0.45f, tolerance))
        assertEquals(-1, BubbleHitTest.find(bubbles, 0.5f, 0.56f, tolerance))
    }

    @Test
    fun `a tolerance of zero means exact boxes only`() {
        val bubbles = listOf(text(0.4f, 0.4f, 0.2f, 0.1f))

        assertEquals(-1, BubbleHitTest.find(bubbles, 0.39f, 0.45f, 0f))
        assertEquals(0, BubbleHitTest.find(bubbles, 0.4f, 0.45f, 0f))
    }

    @Test
    fun `an exact hit on a small box beats a tolerant hit on a large neighbour`() {
        // The large box, grown, covers the small one entirely; the small one still wins its
        // own interior, and outside both the large one wins its margin.
        val bubbles = listOf(text(0.1f, 0.1f, 0.5f, 0.5f), text(0.62f, 0.3f, 0.05f, 0.05f))

        assertEquals(1, BubbleHitTest.find(bubbles, 0.64f, 0.32f, tolerance))
        assertEquals(0, BubbleHitTest.find(bubbles, 0.61f, 0.5f, tolerance))
    }

    @Test
    fun `two tolerant hits rank by the raw box, so growing cannot reorder them`() {
        // Both boxes miss the point exactly and both reach it grown; the smaller text wins.
        val bubbles = listOf(text(0.1f, 0.1f, 0.4f, 0.4f), text(0.52f, 0.1f, 0.1f, 0.1f))

        assertEquals(1, BubbleHitTest.find(bubbles, 0.51f, 0.15f, tolerance))
    }

    @Test
    fun `edges count as inside`() {
        val bubbles = listOf(text(0.2f, 0.2f, 0.2f, 0.2f))

        assertEquals(0, BubbleHitTest.find(bubbles, 0.2f, 0.2f, 0f))
        assertEquals(0, BubbleHitTest.find(bubbles, 0.4f, 0.4f, 0f))
    }

    @Test
    fun `a box flush with the page edge tolerates without leaving the page`() {
        // padded() truncates at the edge rather than shifting inward, so the grown box is still
        // a valid BubbleBox and the tap on the edge itself lands.
        val bubbles = listOf(text(0f, 0f, 0.1f, 0.1f))

        assertEquals(0, BubbleHitTest.find(bubbles, 0f, 0.14f, tolerance))
        assertEquals(-1, BubbleHitTest.find(bubbles, 0f, 0.16f, tolerance))
    }

    @Test
    fun `no bubbles means no hit`() {
        assertEquals(-1, BubbleHitTest.find(emptyList(), 0.5f, 0.5f, tolerance))
    }

    @Test
    fun `a panel never answers, even when it is the only thing under the finger`() {
        val panel = text(0f, 0f, 1f, 1f, BubbleType.PANEL)
        val bubble = text(0.3f, 0.3f, 0.1f, 0.1f)

        assertEquals(-1, BubbleHitTest.find(listOf(panel), 0.5f, 0.5f, tolerance))
        assertEquals(1, BubbleHitTest.find(listOf(panel, bubble), 0.35f, 0.35f, tolerance))
    }

    @Test
    fun `a point off the page hits nothing`() {
        val bubbles = listOf(text(0.9f, 0.9f, 0.1f, 0.1f))

        assertEquals(-1, BubbleHitTest.find(bubbles, 1.2f, 0.95f, tolerance))
        assertEquals(-1, BubbleHitTest.find(bubbles, Float.NaN, 0.95f, tolerance))
    }
}
