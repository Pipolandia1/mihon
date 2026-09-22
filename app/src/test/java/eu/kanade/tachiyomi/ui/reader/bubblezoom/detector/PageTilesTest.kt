package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which pages are looked at twice, where the two looks fall, and what happens to a balloon both
 * of them find. Amendment E5.
 */
class PageTilesTest {

    private fun detection(x: Float, y: Float, w: Float, h: Float, confidence: Float = 0.9f) =
        BubbleDetection(BubbleBox(x, y, w, h), confidence, BubbleType.TEXT)

    @Test
    fun `an ordinary portrait page is looked at once, whole`() {
        // The page that matters most: it must cost exactly what it cost before this amendment.
        val tiles = PageTiles.of(1280, 1966)

        assertEquals(1, tiles.size)
        assertTrue(tiles.single().isWholePage)
    }

    @Test
    fun `a page that happens to be square is not split for nothing`() {
        assertEquals(1, PageTiles.of(1000, 1000).size)
        assertEquals(1, PageTiles.of(1100, 1000).size, "1.10 wide is not a spread")
    }

    @Test
    fun `a double-page spread is looked at in two halves that overlap`() {
        // The measured case: 2000x1537, six captions, none of them found in one look.
        val tiles = PageTiles.of(2000, 1537)

        assertEquals(2, tiles.size)
        val (left, right) = tiles
        assertEquals(0f, left.x)
        assertEquals(1f, right.x + right.width, "the halves have to reach both edges")
        assertTrue(left.width > 0.5f && right.width > 0.5f, "each half must pass the middle")
        assertTrue(right.x < left.width, "and the two must overlap, or the fold is a blind strip")
        assertTrue(tiles.all { it.y == 0f && it.height == 1f }, "split across, not down")
    }

    @Test
    fun `a page with no dimensions is still given something to look at`() {
        assertEquals(1, PageTiles.of(0, 0).size)
        assertEquals(1, PageTiles.of(-5, 900).size)
    }

    @Test
    fun `a box found in the right half is placed in the right half of the page`() {
        val right = PageTiles.of(2000, 1537)[1]

        // Dead centre of that tile, whatever the overlap turns out to be.
        val placed = right.place(BubbleBox(0.5f, 0.25f, 0.1f, 0.2f))!!

        assertEquals(right.x + 0.5f * right.width, placed.x, 1e-6f)
        assertEquals(0.25f, placed.y, 1e-6f, "the halves do not cut the height")
        assertEquals(0.1f * right.width, placed.width, 1e-6f)
        assertEquals(0.2f, placed.height, 1e-6f)
    }

    @Test
    fun `placing never produces a box off the page`() {
        val right = PageTiles.of(2000, 1537)[1]

        val placed = right.place(BubbleBox(0.9f, 0.9f, 0.1f, 0.1f))!!

        assertTrue(placed.right <= 1f && placed.bottom <= 1f)
    }

    @Test
    fun `the whole page places a box unchanged`() {
        val whole = PageTiles.of(1280, 1966).single()
        val box = BubbleBox(0.31f, 0.07f, 0.2f, 0.05f)

        assertEquals(box, whole.place(box))
    }

    @Test
    fun `a balloon both halves found is kept once, as the half that was surer of it saw it`() {
        val fromLeft = detection(0.45f, 0.30f, 0.08f, 0.05f, confidence = 0.62f)
        val fromRight = detection(0.452f, 0.302f, 0.079f, 0.049f, confidence = 0.88f)

        val merged = PageTiles.merge(listOf(fromLeft, fromRight))

        assertEquals(listOf(fromRight), merged)
    }

    @Test
    fun `a balloon the fold cut is not also kept as its clipped self`() {
        // The case an overlap test alone misses: one half saw the whole balloon, the other saw
        // the third of it that reached into its own strip. Their IoU is about 0.3.
        val whole = detection(0.44f, 0.30f, 0.09f, 0.05f, confidence = 0.80f)
        val clipped = detection(0.44f, 0.30f, 0.03f, 0.05f, confidence = 0.41f)

        assertTrue(whole.box.intersectionOverUnion(clipped.box) < 0.4f, "premise")
        assertEquals(listOf(whole), PageTiles.merge(listOf(clipped, whole)))
    }

    @Test
    fun `two different balloons in the overlap both survive`() {
        val one = detection(0.42f, 0.20f, 0.06f, 0.04f)
        val other = detection(0.52f, 0.60f, 0.06f, 0.04f)

        assertEquals(2, PageTiles.merge(listOf(one, other)).size)
    }

    @Test
    fun `merging nothing, or one, is nothing or one`() {
        assertEquals(emptyList<BubbleDetection>(), PageTiles.merge(emptyList()))
        val single = listOf(detection(0.1f, 0.1f, 0.1f, 0.1f))
        assertEquals(single, PageTiles.merge(single))
    }
}
