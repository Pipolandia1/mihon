package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The canvas throughout is 120x120 with a balloon at the middle. Since amendment E3 that canvas
 * is the SEARCH REGION rather than the card: the caller looks for the balloon in a region far
 * larger than it will show, so a legitimate silhouette covers a modest share of it and there is
 * no floor on how little it may cover — a caption whose border hugs its words is small and real.
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

    /** Where the detection sits: a line of text across the middle of the balloon. */
    private val text = BubbleMask.MaskRect(centre - 20, centre - 6, centre + 20, centre + 6)

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
        assertNull(BubbleMask.extract(IntArray(side * side) { PAPER }, side, side, text, passes))
    }

    @Test
    fun `an outline with a gap in it is refused`() {
        // An open bubble, a caption with no border, a balloon whose line is broken by its own
        // tail: the fill leaks out through the gap and comes back holding the page.
        val pixels = bubblePage()
        fillRect(pixels, centre - 4, centre - 40, centre + 4, centre - 30, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, text, passes))
    }

    @Test
    fun `a tail running out of the search region is kept, because a tail is thin`() {
        // The region is chosen to be bigger than the balloon, but a long tail can still leave
        // it, and clipping a tail costs the silhouette nothing anyone can see. Ink page, paper
        // balloon, and an eleven pixel paper tail to the left edge: eleven border pixels of a
        // 480 pixel perimeter, about 2%.
        val pixels = IntArray(side * side) { INK }
        ellipse(pixels, 41, 33, PAPER)
        fillRect(pixels, 0, centre - 5, centre, centre + 5, PAPER)

        val mask = traced(pixels)

        assertTrue(mask[at(2, centre)], "the tail is part of the shape")
    }

    @Test
    fun `a fill that floods a border is refused even when what it covered looks like a bubble`() {
        // The same fixture with the corridor opened to fifty pixels: more than a tenth of the
        // perimeter, which is not a tail but the fill escaping into the page. What it holds is
        // still about a third of the canvas, so only the leak test can refuse it - delete that
        // test and this one alone goes red.
        val pixels = IntArray(side * side) { INK }
        ellipse(pixels, 41, 33, PAPER)
        fillRect(pixels, 0, centre - 25, centre, centre + 25, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, text, passes))
    }

    @Test
    fun `a fill that covers almost the whole card is refused even when it never escaped`() {
        // A one pixel frame of ink: the fill never touches the border, so the escape test has
        // nothing to say, and it still comes back holding 97% of the card. That is not a
        // bubble the card can lift, it is most of a page.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, 1, 1, side - 2, side - 2, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, text, passes))
    }

    @Test
    fun `a silhouette that does not hold the text it was found for is refused`() {
        // The guard that says "this shape belongs to that detection" rather than merely "this
        // shape is bubble-shaped": a balloon at the middle, and a detection off in a corner.
        // The trace succeeds, and the shape it found is not the one the reader asked for.
        val corner = BubbleMask.MaskRect(4, 4, 30, 18)

        assertNull(BubbleMask.extract(bubblePage(), side, side, corner, passes))
    }

    @Test
    fun `a silhouette barely larger than its text is kept, because a tight caption is one`() {
        // There is no floor on coverage any more. In a region chosen to be several times the
        // detection, a caption whose border hugs its words is small and perfectly real, and
        // the floor that used to live here would have refused it.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, centre - 24, centre - 10, centre + 24, centre + 10, PAPER)

        val mask = traced(pixels)

        val covered = mask.count { it }.toFloat() / mask.size
        assertTrue(covered < 0.12f, "covered $covered, which a floor would have refused")
    }

    @Test
    fun `bounds are the rectangle the silhouette occupies`() {
        val shape = BooleanArray(side * side)
        for (y in 20..40) {
            for (x in 10..50) shape[at(x, y)] = true
        }

        val bounds = BubbleMask.bounds(shape, side, side)

        assertEquals(BubbleMask.MaskRect(10, 20, 51, 41), bounds)
        assertEquals(41, bounds!!.width)
        assertEquals(21, bounds.height)
    }

    @Test
    fun `bounds of a shape with nothing in it are null`() {
        assertNull(BubbleMask.bounds(BooleanArray(side * side), side, side))
    }

    @Test
    fun `bounds of a silhouette that fills its region are the region`() {
        assertEquals(
            BubbleMask.MaskRect(0, 0, side, side),
            BubbleMask.bounds(BooleanArray(side * side) { true }, side, side),
        )
    }

    @Test
    fun `input too small or too short to be a card is refused`() {
        assertNull(BubbleMask.extract(IntArray(4) { PAPER }, 2, 2, text, passes))
        assertNull(BubbleMask.extract(IntArray(side * side - 1) { PAPER }, side, side, text, passes))
    }

    // --- fixtures ------------------------------------------------------------------------

    /** Extracts and insists it worked, so each test reads as what it is actually checking. */
    private fun traced(pixels: IntArray): BooleanArray {
        val mask = BubbleMask.extract(pixels, side, side, text, passes)
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
