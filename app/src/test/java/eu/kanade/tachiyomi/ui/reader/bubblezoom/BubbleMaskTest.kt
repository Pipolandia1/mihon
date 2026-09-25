package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The canvas throughout is 120x120 with a balloon at the middle, and it is the SEARCH REGION:
 * the balloon's own box padded, which since amendment E10 is by a quarter of its smallest side.
 * So [box] below is where the detector said the balloon is — the ellipse's own rectangle — and
 * the ring of canvas around it is the margin the trace is given to work in.
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

    /**
     * Where the detection sits: the balloon's own rectangle, which is what the model returns
     * since amendment E9 — the outer ellipse of [bubblePage], 88 by 72 in a 120 canvas.
     */
    private val box = BubbleMask.MaskRect(centre - 44, centre - 36, centre + 44, centre + 36)

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
        val pixels = bubblePage()
        fillRect(pixels, centre - 4, centre - 4, centre + 4, centre + 4, INK)

        val mask = traced(pixels)

        val covered = mask.count { it }.toFloat() / mask.size
        assertTrue(covered > 0.25f && covered < 0.55f, "covered $covered, so it traced the bubble")
        assertFalse(mask[at(2, 2)], "and not the page")
    }

    @Test
    fun `a bubble whose middle is ALL lettering is still traced`() {
        // What amendment E10 is for. The seed patch is a tenth of the mask, and on a balloon
        // whose words fill it that patch is entirely ink — so a tone taken from there is ink,
        // the fill spreads across one letter and stops, and the card falls back to a rectangle.
        // Measured on a real page before the fix: ten balloons of nineteen died exactly here,
        // with a fill holding half a percent of the region. The tone now comes from the whole
        // box, where the surface wins on area whatever the lettering does.
        val pixels = bubblePage()
        fillRect(pixels, centre - 18, centre - 12, centre + 18, centre + 12, INK)

        val mask = traced(pixels)

        assertTrue(mask[at(centre + 30, centre)], "the paper beside the words is inside the shape")
        assertFalse(mask[at(2, 2)], "and the page is not")
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
        assertNull(BubbleMask.extract(IntArray(side * side) { PAPER }, side, side, box, passes))
    }

    @Test
    fun `an outline with a gap in it is refused`() {
        // An open bubble, a caption with no border, a balloon whose line is broken by its own
        // tail: the fill leaks out through the gap and comes back holding the page. This is
        // what the escape guard is for — most of what it holds is outside the balloon's box.
        val pixels = bubblePage()
        fillRect(pixels, centre - 4, centre - 40, centre + 4, centre - 30, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, box, passes))
    }

    @Test
    fun `a tail leaving the balloon's box is kept, because a tail is thin`() {
        // A tail is drawn outside the balloon's rectangle by definition, and clipping one costs
        // the silhouette nothing anyone can see. Ink page, paper balloon, and a ten pixel tail
        // running out to the left: a few percent of the fill, far under the escape threshold.
        val pixels = IntArray(side * side) { INK }
        ellipse(pixels, 41, 33, PAPER)
        fillRect(pixels, 0, centre - 5, centre - 30, centre + 5, PAPER)

        val mask = traced(pixels)

        assertTrue(mask[at(2, centre)], "the tail is part of the shape")
    }

    @Test
    fun `a fill that covers almost the whole region is refused`() {
        // A one pixel frame of ink: the fill never escapes through anything, it simply holds
        // the whole canvas - and a canvas is the box plus its margin, so most of what it holds
        // is outside the box.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, 1, 1, side - 2, side - 2, PAPER)

        assertNull(BubbleMask.extract(pixels, side, side, box, passes))
    }

    @Test
    fun `a silhouette that does not fill the balloon it was found for is refused`() {
        // The guard that says "this shape is the thing we found" rather than merely "this shape
        // is bubble-shaped": a balloon at the middle, and a detection off in a corner. The
        // trace succeeds, and the shape it found is not the one the reader asked for.
        val corner = BubbleMask.MaskRect(4, 4, 30, 18)

        assertNull(BubbleMask.extract(bubblePage(), side, side, corner, passes))
    }

    @Test
    fun `a caption whose border hugs its words is a balloon too`() {
        // Small, rectangular and perfectly real. Its box is its own rectangle, so it fills it
        // and stays inside it, and both guards are happy about something the eye would call
        // obvious.
        val pixels = IntArray(side * side) { INK }
        fillRect(pixels, centre - 24, centre - 10, centre + 24, centre + 10, PAPER)
        val caption = BubbleMask.MaskRect(centre - 24, centre - 10, centre + 25, centre + 11)

        val mask = BubbleMask.extract(pixels, side, side, caption, passes)

        assertNotNull(mask, "expected the caption to be traced")
        assertTrue(mask!![at(centre, centre)])
        assertFalse(mask[at(2, 2)], "and the page around it is not part of it")
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
        assertNull(BubbleMask.extract(IntArray(4) { PAPER }, 2, 2, box, passes))
        assertNull(BubbleMask.extract(IntArray(side * side - 1) { PAPER }, side, side, box, passes))
    }

    // --- fixtures ------------------------------------------------------------------------

    /** Extracts and insists it worked, so each test reads as what it is actually checking. */
    @Test
    fun `the surface and its outline are the silhouette extract gives`() {
        // Amendment E18 split extract in two so a tail can be read off the surface; the two
        // halves together must be exactly what it was.
        val page = bubblePage()
        val surface = BubbleMask.surface(page, side, side, box)
        assertNotNull(surface)

        assertTrue(BubbleMask.outline(surface!!, side, side, passes).contentEquals(traced(page)))
    }

    @Test
    fun `a capture off centre is traced from the middle of the balloon`() {
        // A widened capture has the balloon to one side and page at its middle. Started from
        // there, the fill finds page, not balloon; started from the box it finds the balloon.
        val pixels = IntArray(side * side) { GREY }
        for (y in 10 until 40) {
            for (x in 10 until 50) pixels[at(x, y)] = PAPER
        }
        val offCentre = BubbleMask.MaskRect(10, 10, 50, 40)

        val surface = BubbleMask.surface(pixels, side, side, offCentre, aroundBox = true)

        assertNotNull(surface)
        assertTrue(surface!![at(30, 25)], "the balloon is the surface")
        assertFalse(surface[at(centre, centre)], "the page at the capture's middle is not")
    }

    private fun traced(pixels: IntArray): BooleanArray {
        val mask = BubbleMask.extract(pixels, side, side, box, passes)
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
        const val GREY = 0xFF808080.toInt()
    }
}
