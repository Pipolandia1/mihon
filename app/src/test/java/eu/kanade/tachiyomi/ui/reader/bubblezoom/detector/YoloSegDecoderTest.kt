package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The segmentation export's two tensors, read the way the model actually writes them.
 *
 * The box half is pinned to rows the model really produced, on a page of Batman (2025-) #2 whose
 * balloons can be counted by eye, together with what Python decodes from those same rows. That
 * is the half where a wrong reading still looks like boxes: the tensor is CHANNEL-major, so an
 * anchor's columns are `anchors` apart and not adjacent, and the four box numbers are a centre
 * and a size rather than two corners. Both mistakes produce plausible rubbish.
 *
 * The mask half is arithmetic — a weighted sum of planes through a sigmoid — so it is tested on
 * prototypes small enough to write down and reason about, and confirmed on the device by eye.
 */
class YoloSegDecoderTest {

    private val fixture by lazy {
        val stream = javaClass.getResourceAsStream("/bubblezoom/yolov8mseg-page-640.json")
        assertNotNull(stream, "the fixture is missing")
        Json.parseToJsonElement(stream!!.bufferedReader().readText()).jsonObject
    }

    private val sourceWidth get() = fixture["sourceWidth"]!!.jsonPrimitive.int()
    private val sourceHeight get() = fixture["sourceHeight"]!!.jsonPrimitive.int()
    private val inputSize get() = fixture["inputSize"]!!.jsonPrimitive.int()

    /** The fixture's `[n, 5]` rows packed the way ONNX hands the tensor over: channel-major. */
    private fun packed(): Pair<FloatArray, Int> {
        val rows = fixture["rows"]!!.jsonArray
        val anchors = rows.size
        val output = FloatArray(YoloSegDecoder.COLUMNS * anchors)
        rows.forEachIndexed { anchor, row ->
            val values = row.jsonArray
            for (column in 0 until 5) {
                output[column * anchors + anchor] = values[column].jsonPrimitive.content.toFloat()
            }
            // The fixture carries no coefficients; the mask tests build their own.
        }
        return output to anchors
    }

    private fun expected(): List<FloatArray> =
        fixture["expected"]!!.jsonArray.map { entry ->
            FloatArray(5) { i -> entry.jsonArray[i].jsonPrimitive.content.toFloat() }
        }

    @Test
    fun `the page decodes to exactly the bubbles Python found, out of sixty-three anchors`() {
        val (output, anchors) = packed()
        val letterbox = Letterbox(sourceWidth, sourceHeight, inputSize)

        val kept = YoloSegDecoder.suppress(YoloSegDecoder.candidates(output, anchors))
        val boxes = kept.mapNotNull { YoloSegDecoder.boxOf(it, letterbox) }
        val want = expected()

        assertEquals(want.size, boxes.size, "a page of eight balloons came back as ${boxes.size}")
        want.forEachIndexed { index, e ->
            val got = boxes[index]
            assertTrue(abs(got.x - e[0]) < 2e-3f, "box $index x: ${got.x} vs ${e[0]}")
            assertTrue(abs(got.y - e[1]) < 2e-3f, "box $index y: ${got.y} vs ${e[1]}")
            assertTrue(abs(got.right - e[2]) < 2e-3f, "box $index right: ${got.right} vs ${e[2]}")
            assertTrue(abs(got.bottom - e[3]) < 2e-3f, "box $index bottom: ${got.bottom} vs ${e[3]}")
        }
    }

    @Test
    fun `without suppression the same page is forty-three overlapping candidates`() {
        // What the model returns is not what the reader can walk. Delete the NMS call and the
        // reader steps through the same balloon five times.
        val (output, anchors) = packed()

        val raw = YoloSegDecoder.candidates(output, anchors)

        assertEquals(43, raw.size)
        assertEquals(8, YoloSegDecoder.suppress(raw).size)
    }

    @Test
    fun `the confidence floor is what separates a bubble from a candidate`() {
        val (output, anchors) = packed()

        assertEquals(63, YoloSegDecoder.candidates(output, anchors, threshold = 0.05f).size)
        assertEquals(
            43,
            YoloSegDecoder.candidates(output, anchors, BubbleDetectionConfig.SEG_CONFIDENCE_THRESHOLD).size,
        )
        assertEquals(0, YoloSegDecoder.candidates(output, anchors, threshold = 0.99f).size)
    }

    @Test
    fun `columns are read across anchors, not along them`() {
        // The tensor is [1, 37, anchors]: an anchor's score lives four times `anchors` later,
        // not four floats later. Reading it the adjacent way finds scores among the box numbers
        // and still returns something box-shaped, which is why this is worth its own test.
        val anchors = 3
        val output = FloatArray(YoloSegDecoder.COLUMNS * anchors)
        // anchor 1 only: centre (100, 200), size 40x20, score 0.9
        output[0 * anchors + 1] = 100f
        output[1 * anchors + 1] = 200f
        output[2 * anchors + 1] = 40f
        output[3 * anchors + 1] = 20f
        output[4 * anchors + 1] = 0.9f

        val found = YoloSegDecoder.candidates(output, anchors, threshold = 0.25f)

        assertEquals(1, found.size)
        assertEquals(80f, found[0].left)
        assertEquals(190f, found[0].top)
        assertEquals(120f, found[0].right)
        assertEquals(210f, found[0].bottom)
    }

    @Test
    fun `suppression keeps the strongest of a cluster and spares what does not overlap`() {
        val strong = candidate(0f, 0f, 100f, 100f, 0.9f)
        val nearlyTheSame = candidate(5f, 5f, 105f, 105f, 0.8f)
        val elsewhere = candidate(300f, 300f, 400f, 400f, 0.7f)
        val touching = candidate(90f, 90f, 190f, 190f, 0.6f)

        val kept = YoloSegDecoder.suppress(listOf(nearlyTheSame, strong, elsewhere, touching), iou = 0.5f)

        assertEquals(listOf(0.9f, 0.7f, 0.6f), kept.map { it.score })
    }

    @Test
    fun `a mask is the planes the coefficients ask for, through a sigmoid`() {
        // Two planes that matter: the first is positive on the left half, the second on the
        // right. A coefficient of +1 and -1 keeps the left half and drops the right.
        val grid = 4
        val prototypes = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS * grid * grid)
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                prototypes[y * grid + x] = if (x < 2) 4f else -4f
                prototypes[grid * grid + y * grid + x] = if (x < 2) -4f else 4f
            }
        }
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS)
        coefficients[0] = 1f
        coefficients[1] = -1f

        // A box over the whole grid: the input is 4 times the grid, so 0..16 input pixels.
        val shape = YoloSegDecoder.shapeOf(
            candidate = candidate(0f, 0f, 16f, 16f, 0.9f, coefficients),
            prototypes = prototypes,
            gridWidth = grid,
            gridHeight = grid,
            inputSize = 16,
            refine = 1,
        )

        assertNotNull(shape)
        assertEquals(4, shape!!.width)
        assertEquals(4, shape.height)
        assertTrue(shape.at(0, 0) && shape.at(1, 3), "the left half is inside")
        assertTrue(!shape.at(2, 0) && !shape.at(3, 3), "the right half is outside")
        assertEquals(0.5f, shape.coverage)
    }

    @Test
    fun `a mask covers only the candidate's own box`() {
        val grid = 8
        // Every plane positive everywhere: whatever the coefficients, the sigmoid is above a
        // half across the whole grid, so only the crop can keep the shape small.
        val prototypes = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS * grid * grid) { 1f }
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS) { 1f }

        val shape = YoloSegDecoder.shapeOf(
            candidate = candidate(8f, 16f, 24f, 32f, 0.9f, coefficients),
            prototypes = prototypes,
            gridWidth = grid,
            gridHeight = grid,
            inputSize = 64,
            refine = 1,
        )

        assertNotNull(shape)
        assertEquals(2, shape!!.width, "24-8 input pixels is 2 grid cells at a stride of 8")
        assertEquals(2, shape.height)
        assertEquals(1f, shape.coverage)
    }

    @Test
    fun `refining resolves the edge where the confidence crosses a half, not on a grid line`() {
        // One row of four cells, the logits ramping from well inside to well outside. On the
        // model's own grid the edge can only land between cells; refined four times it lands
        // where the ramp actually crosses zero, which is inside the second cell.
        val grid = 4
        val prototypes = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS * grid * grid)
        val ramp = floatArrayOf(6f, 2f, -2f, -6f)
        for (y in 0 until grid) {
            for (x in 0 until grid) prototypes[y * grid + x] = ramp[x]
        }
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS).also { it[0] = 1f }
        val candidate = candidate(0f, 0f, 16f, 16f, 0.9f, coefficients)

        val coarse = YoloSegDecoder.shapeOf(candidate, prototypes, grid, grid, 16, refine = 1)!!
        val fine = YoloSegDecoder.shapeOf(candidate, prototypes, grid, grid, 16, refine = 4)!!

        assertEquals(4, coarse.width)
        assertEquals(16, fine.width, "four times the cells across")
        assertEquals(16, fine.height)
        // Coarse: cells 0 and 1 are inside, 2 and 3 are not - half the row.
        assertEquals(0.5f, coarse.coverage)
        // Refined: the edge sits between the fifth and the twelfth column rather than exactly
        // at the eighth, because it follows the ramp instead of the grid.
        assertTrue(fine.at(5, 0), "still inside where the model was confident")
        assertTrue(!fine.at(11, 0), "already outside where it was not")
        assertTrue(abs(fine.coverage - 0.5f) < 0.2f, "coverage ${fine.coverage} drifted from the coarse answer")
    }

    @Test
    fun `refining never invents a mask where there was none`() {
        val grid = 4
        val prototypes = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS * grid * grid) { -8f }
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS) { 1f }

        assertNull(
            YoloSegDecoder.shapeOf(
                candidate(0f, 0f, 16f, 16f, 0.9f, coefficients),
                prototypes,
                grid,
                grid,
                16,
                refine = 4,
            ),
        )
    }

    @Test
    fun `a mask the model marked nothing in is no mask at all`() {
        val grid = 4
        val prototypes = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS * grid * grid) { -8f }
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS) { 1f }

        assertNull(
            YoloSegDecoder.shapeOf(
                candidate = candidate(0f, 0f, 16f, 16f, 0.9f, coefficients),
                prototypes = prototypes,
                gridWidth = grid,
                gridHeight = grid,
                inputSize = 16,
            ),
        )
    }

    @Test
    fun `degenerate tensors are refused rather than read past their end`() {
        val coefficients = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS) { 1f }
        val tooSmall = FloatArray(4)

        assertNull(YoloSegDecoder.shapeOf(candidate(0f, 0f, 8f, 8f, 0.9f, coefficients), tooSmall, 4, 4, 16))
        assertNull(YoloSegDecoder.shapeOf(candidate(0f, 0f, 8f, 8f, 0.9f, coefficients), tooSmall, 0, 4, 16))
        assertEquals(0, YoloSegDecoder.candidates(FloatArray(10), anchors = 100).size)
        assertEquals(0, YoloSegDecoder.candidates(FloatArray(10), anchors = 0).size)
    }

    private fun candidate(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float,
        coefficients: FloatArray = FloatArray(YoloSegDecoder.MASK_COEFFICIENTS),
    ) = YoloSegDecoder.Candidate(left, top, right, bottom, score, coefficients)

    private fun kotlinx.serialization.json.JsonPrimitive.int() = content.toFloat().toInt()
}
