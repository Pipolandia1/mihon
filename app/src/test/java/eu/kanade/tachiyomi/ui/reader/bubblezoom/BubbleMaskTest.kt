package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The canvas throughout is 120x120 with a balloon at the middle, which is the shape of a real
 * card: `MASK_LONG_SIDE_PX` is 192 and a padded box leaves the bubble covering roughly half.
 *
 * The page is white and the outline is black on purpose. That is the hardest arrangement, and
 * the one the synthetic test pages use: bubble and page are the same tone, and only a three
 * pixel line keeps the fill from swallowing the whole page.
 */
class BubbleMaskTest {

    private val side = 120
    private val centre = side / 2

    /** Enough to cross the three pixel ring these fixtures draw, which is the real ratio. */
    private val passes = 2

    @Test
    fun `a bubble on a white page is traced to its own shape and not to its box`() {
        val mask = traced(bubblePage())

        assertTrue(mask[at(centre, centre)], "the middle of the bubble is inside the shape")
        assertFalse(mask[at(2, 2)], "the card's corner is page, not bubble")
        assertFalse(mask[at(side - 3, 2)], "the card's corner is page, not bubble")
        assertFalse(mask[at(2, side - 3)], "the card's corner is page, not bubble")
        assertFalse(mask[at(side - 3, side - 3)], "the card's corner is page, not bubble")

        // An ellipse of 41x33 in a 120x120 card is about 0.30 before growing, and the whole
        // card would be 1.0. Anything near either end means the trace lost the shape.
        val covered = mask.count { it }.toFloat() / mask.size
        assertTrue(covered > 0.25f && covered < 0.45f, "covered $covered of the card")
    }

    @Test
    fun `the bubble's own outline comes with it`() {
        // Without the growing pass the fill stops at the inside edge of the line, and the card
        // shows a bare blob of paper with its balloon sawn off.
        val mask = traced(bubblePage())

        assertTrue(mask[at(centre + 42, centre)], "a pixel on the outline itself is inside")
        assertTrue(mask[at(centre, centre + 34)], "a pixel on the outline itself is inside")
    }

    @Test
    fun `lettering inside the bubble is not punched out of it`() {
        // The fill has to go around every letter, so the raw fill is the paper with the text
        // knocked out of it. Anything the border cannot reach is inside by definition.
        val pixels = bubblePage()
        fillRect(pixels, centre - 20, centre - 4, centre + 20, centre + 4, INK)

        val mask = traced(pixels)

        assertTrue(mask[at(centre, centre)], "the middle of a letter is still inside the bubble")
        assertTrue(mask[at(centre - 18, centre)], "and so is the end of it")
    }

    @Test
    fun `a bubble whose exact middle lands on a letter is still traced`() {
        // The seed is the median of a patch rather than the one pixel at the centre, so a
        // glyph under the middle cannot send the fill off to trace the glyph instead.
        val pixels = bubblePage()
        fillRect(pixels, centre - 4, centre - 4, centre + 4, centre + 4, INK)

        val mask = traced(pixels)

        val covered = mask.count { it }.toFloat() / mask.size
        assertTrue(covered > 0.25f && covered < 0.45f, "covered $covered, so it traced the bubble")
        assertFalse(mask[at(2, 2)], "and not the page")
    }

    @Test
    fun `an inverted bubble is traced the same way`() {
        // Nothing in the trace knows which tone is paper: a black balloon outlined in white on
        // a black page is the same problem with the tones exchanged.
        val pixels = IntArray(side * side) { INK }
        ellipse(pixels, 44, 36, PAPER)
        ellipse(pixels, 41, 33, INK)

        val mask = traced(pixels)

        assertTrue(mask[at(centre, centre)])
        assertFalse(mask[at(2, 2)])
    }

    @Test
    fun `a page with no bubble on it is refused`() {
        // Nothing stops the fill, so it holds the whole page. The card falls back to its
        // rectangle, which is plainer but never wrong.
        assertNull(BubbleMask.extract(IntArray(side * side) { PAPER }, side, side, passes))
    }

    @Test
    fun `an outline with a gap in it is refused`() {
        // An open bubble, a caption with no border, a balloon whose line is broken by its own
        // tail: the fill leaks out through the gap and comes back holding the page.
        val pixels = bubblePage()
        fillRect(pixels, centre - 4, centre - 40, centre + 4, centre - 30, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, passes))
    }

    @Test
    fun `a fill that escaped is refused even when what it covered looks like a bubble`() {
        // The two tests above are both ALSO caught by the area ceiling, so neither of them can
        // tell whether the escape guard still exists. This one can: ink page, paper balloon,
        // and a paper corridor leaking out to the left edge. The fill runs out through the
        // corridor and reaches the border, yet ends up holding about a third of the card -
        // squarely inside the legal band. Delete `if (escaped) return null` and only this test
        // goes red.
        val pixels = IntArray(side * side) { INK }
        ellipse(pixels, 41, 33, PAPER)
        fillRect(pixels, 0, centre - 5, centre, centre + 5, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, passes))
    }

    @Test
    fun `a fill that covers almost the whole card is refused even when it never escaped`() {
        // A one pixel frame of ink: the fill never touches the border, so the escape test has
        // nothing to say, and it still comes back holding 97% of the card. That is not a
        // bubble the card can lift, it is most of a page.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, 1, 1, side - 2, side - 2, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, passes))
    }

    @Test
    fun `a fill that found only a letter is refused`() {
        // A surface too small to be a balloon is a glyph, a screentone cell or a tail tip,
        // and tracing one of those would lift a card with a letter in it.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, centre - 17, centre - 17, centre + 17, centre + 17, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, passes))
    }

    @Test
    fun `input too small or too short to be a card is refused`() {
        assertNull(BubbleMask.extract(IntArray(4) { PAPER }, 2, 2, passes))
        assertNull(BubbleMask.extract(IntArray(side * side - 1) { PAPER }, side, side, passes))
    }

    // --- fixtures ------------------------------------------------------------------------

    /** Extracts and insists it worked, so each test reads as what it is actually checking. */
    private fun traced(pixels: IntArray): BooleanArray {
        val mask = BubbleMask.extract(pixels, side, side, passes)
        assertNotNull(mask, "expected the bubble to be traced")
        return mask!!
    }

    /** White page, white balloon, three pixels of black line between the two. */
    private fun bubblePage(): IntArray {
        val pixels = IntArray(side * side) { PAPER }
        ellipse(pixels, 44, 36, INK)
        ellipse(pixels, 41, 33, PAPER)
        return pixels
    }

    private fun ellipse(pixels: IntArray, radiusX: Int, radiusY: Int, color: Int) {
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dx = (x - centre).toFloat() / radiusX
                val dy = (y - centre).toFloat() / radiusY
                if (dx * dx + dy * dy <= 1f) pixels[at(x, y)] = color
            }
        }
    }

    private fun fillRect(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        for (y in top..bottom) {
            for (x in left..right) {
                pixels[at(x, y)] = color
            }
        }
    }

    private fun at(x: Int, y: Int) = y * side + x

    private companion object {
        const val PAPER = 0xFFFFFFFF.toInt()
        const val INK = 0xFF000000.toInt()
    }
}
