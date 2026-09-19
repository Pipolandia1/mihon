package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Values here are deliberately dyadic — exactly representable in binary floating point — so
 * that the expected results are exact and the assertions need no tolerance. The one test that
 * cannot avoid a repeating fraction says so and uses a delta.
 */
class BubbleBoxTest {

    // region derived properties

    @Test
    fun `derived edges and centre are computed from origin and size`() {
        val box = BubbleBox(0.25f, 0.5f, 0.5f, 0.25f)

        assertEquals(0.75f, box.right)
        assertEquals(0.75f, box.bottom)
        assertEquals(0.5f, box.centerX)
        assertEquals(0.625f, box.centerY)
        assertEquals(0.125f, box.area)
    }

    // endregion

    // region validation

    @Test
    fun `rejects an origin outside the page`() {
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(-0.1f, 0f, 0.5f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, -0.1f, 0.5f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(1.5f, 0f, 0.5f, 0.5f) }
    }

    @Test
    fun `rejects a box with no area`() {
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, 0f, 0f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, 0f, 0.5f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, 0f, -0.5f, 0.5f) }
    }

    @Test
    fun `rejects a box that overhangs the page even when every field is in range`() {
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0.8f, 0f, 0.5f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, 0.8f, 0.5f, 0.5f) }
    }

    @Test
    fun `rejects non-finite values`() {
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(Float.NaN, 0f, 0.5f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) {
            BubbleBox(0f, 0f, Float.POSITIVE_INFINITY, 0.5f)
        }
    }

    @Test
    fun `accepts a box flush against every page edge`() {
        val fullPage = BubbleBox(0f, 0f, 1f, 1f)

        assertEquals(1f, fullPage.right)
        assertEquals(1f, fullPage.bottom)
        assertEquals(1f, fullPage.area)
    }

    // endregion

    // region padded

    @Test
    fun `padded grows an interior box on all four sides`() {
        // min(0.5, 0.25) = 0.25, so the padding is 0.25 * 0.25 = 0.0625 per side.
        val padded = BubbleBox(0.25f, 0.25f, 0.5f, 0.25f).padded(0.25f)

        assertEquals(BubbleBox(0.1875f, 0.1875f, 0.625f, 0.375f), padded)
    }

    @Test
    fun `padded uses the smallest dimension so a flat box grows equally on every side`() {
        // A wide, short box. Padding is driven by the height, so the absolute growth is the
        // same horizontally and vertically instead of ballooning sideways.
        val padded = BubbleBox(0.25f, 0.375f, 0.5f, 0.125f).padded(0.5f)

        assertEquals(0.625f, padded.width)
        assertEquals(0.25f, padded.height)
        assertEquals(padded.width - 0.5f, padded.height - 0.125f)
    }

    @Test
    fun `padded is driven by the width when the box is tall and narrow`() {
        // The companion to the flat-box case: here min(width, height) is the WIDTH, so an
        // implementation keyed on height would pad by 0.25 instead of 0.0625 and fail.
        val padded = BubbleBox(0.375f, 0.25f, 0.125f, 0.5f).padded(0.5f)

        assertEquals(BubbleBox(0.3125f, 0.1875f, 0.25f, 0.625f), padded)
    }

    @Test
    fun `padded truncates at the top left corner instead of shifting inward`() {
        // Unclamped this would start at -0.125 and be 0.5 wide; it must stop at the edge.
        val padded = BubbleBox(0f, 0f, 0.25f, 0.25f).padded(0.5f)

        assertEquals(BubbleBox(0f, 0f, 0.375f, 0.375f), padded)
    }

    @Test
    fun `padded truncates at the bottom right corner instead of shifting inward`() {
        val padded = BubbleBox(0.75f, 0.75f, 0.25f, 0.25f).padded(0.5f)

        assertEquals(BubbleBox(0.625f, 0.625f, 0.375f, 0.375f), padded)
        assertEquals(1f, padded.right)
        assertEquals(1f, padded.bottom)
    }

    @Test
    fun `padded on a full page box is a fixed point`() {
        val fullPage = BubbleBox(0f, 0f, 1f, 1f)

        assertEquals(fullPage, fullPage.padded(0.5f))
        assertEquals(fullPage, fullPage.padded(10f))
    }

    @Test
    fun `padded with a zero fraction returns the same instance`() {
        val box = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        assertSame(box, box.padded(0f))
    }

    @Test
    fun `padded always contains the original box, including at the edges`() {
        val boxes = listOf(
            BubbleBox(0f, 0f, 0.22f, 0.12f),
            BubbleBox(0.78f, 0f, 0.22f, 0.12f),
            BubbleBox(0f, 0.88f, 0.22f, 0.12f),
            BubbleBox(0.78f, 0.88f, 0.22f, 0.12f),
            BubbleBox(0.25f, 0.25f, 0.5f, 0.5f),
        )

        for (box in boxes) {
            val padded = box.padded(0.2f)
            assertTrue(padded.x <= box.x, "left grew inward for $box")
            assertTrue(padded.y <= box.y, "top grew inward for $box")
            assertTrue(padded.right >= box.right, "right grew inward for $box")
            assertTrue(padded.bottom >= box.bottom, "bottom grew inward for $box")
        }
    }

    @Test
    fun `padded rejects a negative or non-finite fraction`() {
        val box = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        assertThrows(IllegalArgumentException::class.java) { box.padded(-0.1f) }
        assertThrows(IllegalArgumentException::class.java) { box.padded(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { box.padded(Float.POSITIVE_INFINITY) }
    }

    // endregion

    // region contains

    @Test
    fun `contains is true inside and false outside on each side`() {
        val box = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        assertTrue(box.contains(0.5f, 0.5f))
        assertFalse(box.contains(0.2f, 0.5f))
        assertFalse(box.contains(0.8f, 0.5f))
        assertFalse(box.contains(0.5f, 0.2f))
        assertFalse(box.contains(0.5f, 0.8f))
    }

    @Test
    fun `contains counts the edges and corners as inside`() {
        val box = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        assertTrue(box.contains(0.25f, 0.5f))
        assertTrue(box.contains(0.75f, 0.5f))
        assertTrue(box.contains(0.5f, 0.25f))
        assertTrue(box.contains(0.5f, 0.75f))
        assertTrue(box.contains(0.25f, 0.25f))
        assertTrue(box.contains(0.75f, 0.75f))
    }

    @Test
    fun `contains checks each axis against its own extent`() {
        // Tall and narrow, and not centred on the diagonal, so confusing right with bottom or
        // x with y flips at least one of these answers.
        val box = BubbleBox(0.125f, 0.5f, 0.25f, 0.375f)

        assertTrue(box.contains(0.25f, 0.75f))
        assertFalse(box.contains(0.25f, 0.25f))
        assertFalse(box.contains(0.625f, 0.75f))
        assertFalse(box.contains(0.25f, 0.9375f))
        assertFalse(box.contains(0.0625f, 0.75f))
    }

    @Test
    fun `contains works for a bubble flush against the page border`() {
        assertTrue(BubbleBox(0f, 0f, 0.25f, 0.25f).contains(0f, 0f))
        assertTrue(BubbleBox(0.75f, 0.75f, 0.25f, 0.25f).contains(1f, 1f))
    }

    // endregion

    // region intersectionOverUnion

    @Test
    fun `iou of a box with itself is exactly one`() {
        val box = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        assertEquals(1f, box.intersectionOverUnion(box))
    }

    @Test
    fun `iou of a nested box is the ratio of the areas`() {
        val outer = BubbleBox(0f, 0f, 0.5f, 0.5f)
        val inner = BubbleBox(0f, 0f, 0.25f, 0.25f)

        assertEquals(0.25f, outer.intersectionOverUnion(inner))
        assertEquals(0.25f, inner.intersectionOverUnion(outer))
    }

    @Test
    fun `iou of boxes touching along an edge is exactly zero`() {
        val left = BubbleBox(0f, 0f, 0.5f, 0.5f)
        val right = BubbleBox(0.5f, 0f, 0.5f, 0.5f)

        assertEquals(0f, left.intersectionOverUnion(right))
    }

    @Test
    fun `iou of boxes touching at a corner is exactly zero`() {
        val topLeft = BubbleBox(0f, 0f, 0.5f, 0.5f)
        val bottomRight = BubbleBox(0.5f, 0.5f, 0.5f, 0.5f)

        assertEquals(0f, topLeft.intersectionOverUnion(bottomRight))
    }

    @Test
    fun `iou of boxes that miss on both axes is zero, not a product of two negatives`() {
        val a = BubbleBox(0f, 0f, 0.25f, 0.25f)
        val b = BubbleBox(0.5f, 0.5f, 0.25f, 0.25f)

        assertEquals(0f, a.intersectionOverUnion(b))
        assertEquals(0f, b.intersectionOverUnion(a))
    }

    @Test
    fun `iou of a box with itself is one for non-dyadic coordinates too`() {
        // Regression. Computed in Float, these return 1.0000004 and friends, because
        // (y + height) - y is not height. The three boxes are phase P3's hardcoded values
        // from tools/test-pages/ground-truth.json.
        val boxes = listOf(
            BubbleBox(0.62f, 0.08f, 0.23f, 0.14f),
            BubbleBox(0.15f, 0.31f, 0.28f, 0.16f),
            BubbleBox(0.58f, 0.67f, 0.26f, 0.15f),
        )

        for (box in boxes) {
            assertEquals(1f, box.intersectionOverUnion(box), "self-IoU of $box")
        }
    }

    @Test
    fun `iou never escapes the documented range over a sweep of non-dyadic boxes`() {
        val steps = (1..19).map { it / 20f }
        for (x in steps) {
            for (w in steps) {
                if (x + w > 1f) continue
                val a = BubbleBox(x, x, w, w)
                val b = BubbleBox(0.13f, 0.29f, 0.41f, 0.17f)
                for (iou in listOf(a.intersectionOverUnion(a), a.intersectionOverUnion(b))) {
                    assertTrue(iou in 0f..1f, "iou $iou out of range for $a")
                }
            }
        }
    }

    @Test
    fun `iou uses each axis for its own extent`() {
        // The overlaps differ per axis (0.25 horizontally, 0.375 vertically), so reading an x
        // field where a y field belongs changes the answer instead of cancelling out.
        val a = BubbleBox(0f, 0f, 0.5f, 0.5f)
        val b = BubbleBox(0.25f, 0.125f, 0.5f, 0.5f)

        // intersection 0.25 * 0.375 = 0.09375, union 0.25 + 0.25 - 0.09375 = 0.40625
        assertEquals(0.09375f / 0.40625f, a.intersectionOverUnion(b), 1e-7f)
        assertEquals(a.intersectionOverUnion(b), b.intersectionOverUnion(a))
    }

    @Test
    fun `iou of partially overlapping boxes is symmetric`() {
        val a = BubbleBox(0f, 0f, 0.5f, 0.5f)
        val b = BubbleBox(0.25f, 0.25f, 0.5f, 0.5f)

        // Intersection 0.0625, union 0.4375; the quotient repeats in binary, hence the delta.
        assertEquals(0.142857f, a.intersectionOverUnion(b), 1e-6f)
        assertEquals(a.intersectionOverUnion(b), b.intersectionOverUnion(a))
    }

    // endregion

    // region clampedOrNull

    @Test
    fun `clampedOrNull absorbs the one ulp overshoot a real detector emits`() {
        // Reproduces what the spec's letterbox-and-undo pipeline produces for a full-page
        // detection on a 1600x2400 page: a width a single ulp past 1.0, which the strict
        // constructor rejects outright.
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0f, 0f, 1.0000001f, 1f) }

        val clamped = BubbleBox.clampedOrNull(0f, 0f, 1.0000001f, 1f)

        assertEquals(BubbleBox(0f, 0f, 1f, 1f), clamped)
    }

    @Test
    fun `clampedOrNull normalises negative zero so equal boxes compare equal`() {
        val clamped = BubbleBox.clampedOrNull(-0.0f, -0.0f, 0.5f, 0.5f)

        assertEquals(BubbleBox(0f, 0f, 0.5f, 0.5f), clamped)
    }

    @Test
    fun `clampedOrNull clips an overhanging box at the edge instead of moving it`() {
        val clamped = BubbleBox.clampedOrNull(0.75f, 0.75f, 0.5f, 0.5f)!!

        assertEquals(0.75f, clamped.x)
        assertEquals(0.75f, clamped.y)
        assertEquals(1f, clamped.right)
        assertEquals(1f, clamped.bottom)
    }

    @Test
    fun `clampedOrNull returns null when nothing usable survives`() {
        assertNull(BubbleBox.clampedOrNull(2f, 0f, 0.5f, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, 0f, 0f, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, 0f, 0.5f, -0.5f))
    }

    @Test
    fun `clampedOrNull returns null for non-finite input`() {
        assertNull(BubbleBox.clampedOrNull(Float.NaN, 0f, 0.5f, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, Float.NaN, 0.5f, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, 0f, Float.POSITIVE_INFINITY, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, 0f, 0.5f, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `clampedOrNull leaves an already valid box untouched`() {
        // These are the phase P3 boxes from tools/test-pages/ground-truth.json. Sanitizing has
        // to be an identity here: going through right = x + width and back would drift by an
        // ulp, because (0.62f + 0.23f) - 0.62f is not 0.23f.
        val boxes = listOf(
            BubbleBox(0.62f, 0.08f, 0.23f, 0.14f),
            BubbleBox(0.15f, 0.31f, 0.28f, 0.16f),
            BubbleBox(0.58f, 0.67f, 0.26f, 0.15f),
        )

        for (box in boxes) {
            assertEquals(box, BubbleBox.clampedOrNull(box.x, box.y, box.width, box.height))
        }
    }

    @Test
    fun `clampedOrNull is idempotent on its own output`() {
        val once = BubbleBox.clampedOrNull(0.75f, 0.75f, 0.5f, 0.5f)!!
        val twice = BubbleBox.clampedOrNull(once.x, once.y, once.width, once.height)

        assertEquals(once, twice)
        // The property that actually matters: what the clamp path produces satisfies the
        // strict constructor directly, with no second pass needed.
        assertEquals(once, BubbleBox(once.x, once.y, once.width, once.height))
    }

    @Test
    fun `clampedOrNull clips a negative origin back to the page`() {
        val clamped = BubbleBox.clampedOrNull(-0.5f, -0.25f, 1f, 0.5f)!!

        assertEquals(0f, clamped.x)
        assertEquals(0f, clamped.y)
        assertEquals(0.5f, clamped.right)
        assertEquals(0.25f, clamped.bottom)
    }

    @Test
    fun `clampedOrNull normalises negative zero on the clamping path as well`() {
        // Overhangs, so this cannot take the fast path that returns the input verbatim.
        val clamped = BubbleBox.clampedOrNull(-0.0f, -0.0f, 1.5f, 1.5f)

        assertEquals(BubbleBox(0f, 0f, 1f, 1f), clamped)
    }

    @Test
    fun `negative zero and positive zero boxes are equal only after normalisation`() {
        // The hazard the KDoc warns about: the generated equals compares bit patterns.
        assertNotEquals(BubbleBox(0f, 0f, 0.5f, 0.5f), BubbleBox(-0.0f, -0.0f, 0.5f, 0.5f))
        assertEquals(
            BubbleBox(0f, 0f, 0.5f, 0.5f),
            BubbleBox.clampedOrNull(-0.0f, -0.0f, 0.5f, 0.5f),
        )
    }

    @Test
    fun `clampedOrNull returns null for a NaN size rather than throwing`() {
        assertNull(BubbleBox.clampedOrNull(0f, 0f, Float.NaN, 0.5f))
        assertNull(BubbleBox.clampedOrNull(0f, 0f, 0.5f, Float.NaN))
    }

    @Test
    fun `a box too small to shift its origin is rejected rather than left to break padded`() {
        // 1e-10f is positive, but 0.5f + 1e-10f is 0.5f, so this rectangle has no extent at
        // that position: right == x. Left constructible, padded() would throw on it.
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0.5f, 0.5f, 1e-10f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleBox(0.5f, 0.5f, 0.5f, 1e-10f) }
        assertNull(BubbleBox.clampedOrNull(0.5f, 0.5f, 1e-10f, 0.5f))
    }

    // endregion
}
