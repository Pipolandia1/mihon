package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.DEBUG_BOXES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pinned to the model's real output, not to a reading of it.
 *
 * The fixture is every one of the 300 rows yolo26n.onnx produced for `01-three-bubbles.png`
 * at 640, dumped by `tools/diagnose-detector.py` and stored under test resources UNFILTERED.
 * That last word matters. The first version of this test carried only the three rows that
 * scored above 0.25, and so it could not see that the other 297 are not zeros but candidates
 * at 0.0004 to 0.07 - which is why the first decoder, keeping "anything above zero", walked
 * 211 bubbles on a page with eight before anyone noticed. A fixture filtered by the same
 * assumption the code makes cannot test that assumption.
 *
 * The Python side landed the three real rows on the page's known bubbles with centre error
 * under 0.004. If this decoder ever disagrees with Python, it is this decoder that is wrong.
 */
class YoloDecodingTest {

    private val letterbox = Letterbox(sourceWidth = 1600, sourceHeight = 2400, inputSize = 640)

    /** All 300 rows, flattened, exactly as the model returned them. */
    private val page01: FloatArray by lazy {
        val text = javaClass.getResourceAsStream("/bubblezoom/yolo26n-page01-640.json")!!
            .bufferedReader().use { it.readText() }
        val rows = Json.parseToJsonElement(text).jsonObject["rows"]!!.jsonArray
        FloatArray(rows.size * YoloEndToEndDecoder.COLUMNS).also { out ->
            rows.forEachIndexed { r, row ->
                row.jsonArray.forEachIndexed { c, v ->
                    out[r * YoloEndToEndDecoder.COLUMNS + c] = v.jsonPrimitive.float
                }
            }
        }
    }

    @Test
    fun `the fixture is the whole output, and none of it scores zero`() {
        // Documents the fact the first decoder got wrong, so the next reader does not have
        // to rediscover it: there are no padding rows, only low candidates.
        val scores = (0 until page01.size / YoloEndToEndDecoder.COLUMNS)
            .map { page01[it * YoloEndToEndDecoder.COLUMNS + 4] }

        assertEquals(300, scores.size)
        assertTrue(scores.all { it > 0f }, "a row scored exactly zero; the fixture is not what the model emits")
        assertEquals(3, scores.count { it >= BubbleDetectionConfig.CONFIDENCE_THRESHOLD })
        assertTrue(scores.filter { it < BubbleDetectionConfig.CONFIDENCE_THRESHOLD }.all { it < 0.1f })
    }

    @Test
    fun `page 01 decodes to exactly the three boxes Python produced, out of 300 rows`() {
        val found = YoloEndToEndDecoder.decode(page01, letterbox)

        assertEquals(3, found.size, "the threshold let candidate rows through as bubbles")
        assertBox(found[0], 0.647461f, 0.100781f, 0.826758f, 0.20293f)
        assertBox(found[1], 0.201562f, 0.337695f, 0.378516f, 0.447266f)
        assertBox(found[2], 0.59707f, 0.694141f, 0.827344f, 0.798047f)
        assertEquals(0.9111328125f, found[0].confidence, 1e-6f)
    }

    @Test
    fun `without the threshold every candidate row would become a bubble`() {
        // The discriminator for the threshold itself: the same 300 rows with the floor removed
        // are the 211-bubble page the device walked.
        val everything = YoloEndToEndDecoder.decode(page01, letterbox, threshold = 0f)

        assertTrue(everything.size > 100, "expected the candidate rows to flood through, got ${everything.size}")
    }

    @Test
    fun `each decoded box sits inside the bubble it was found in`() {
        // The model boxes the text, and the text is inside the balloon. DEBUG_BOXES are the
        // balloons' own boxes for this page, so containment is the property that says the
        // pipeline put the detection on the right bubble - IoU alone would not, because a text
        // box is a legitimately smaller box.
        for (det in YoloEndToEndDecoder.decode(page01, letterbox)) {
            val inside = DEBUG_BOXES.any { b ->
                det.box.x >= b.x - 0.005f && det.box.y >= b.y - 0.005f &&
                    det.box.right <= b.right + 0.005f && det.box.bottom <= b.bottom + 0.005f
            }
            assertTrue(inside, "detection ${det.box} is not inside any known balloon")
        }
    }

    @Test
    fun `the letterbox is the one Python used`() {
        // scale = 640 / 2400, page scaled to 427x640, centred horizontally with 106px each side.
        assertEquals(0.26666668f, letterbox.scale, 1e-6f)
        assertEquals(427, letterbox.scaledWidth)
        assertEquals(640, letterbox.scaledHeight)
        assertEquals(106, letterbox.padX)
        assertEquals(0, letterbox.padY)
    }

    @Test
    fun `a wide page pads the other axis`() {
        val wide = Letterbox(sourceWidth = 2400, sourceHeight = 1600, inputSize = 640)

        assertEquals(640, wide.scaledWidth)
        assertEquals(427, wide.scaledHeight)
        assertEquals(0, wide.padX)
        assertEquals(106, wide.padY)
    }

    @Test
    fun `undoing the letterbox is the exact inverse of applying it`() {
        // The Android side paints the page at (padX + x * scale); the decoder must map that
        // input pixel back to x / sourceWidth or the two halves of the pipeline disagree.
        val sourceX = 1234f
        val sourceY = 987f

        val inputX = letterbox.padX + sourceX * letterbox.scale
        val inputY = letterbox.padY + sourceY * letterbox.scale

        assertEquals(sourceX / 1600f, letterbox.normalizeX(inputX), 1e-5f)
        assertEquals(sourceY / 2400f, letterbox.normalizeY(inputY), 1e-5f)
    }

    @Test
    fun `a wide page decodes through the vertical padding`() {
        // Page 01 is portrait, so its padY is 0 and every decode test above would still pass
        // with padY applied to x, or with sourceWidth and sourceHeight swapped in normalizeY.
        // A wide page is the only thing that makes those mistakes visible.
        val wide = Letterbox(sourceWidth = 2400, sourceHeight = 1600, inputSize = 640)
        val sx1 = 600f
        val sy1 = 400f
        val sx2 = 1200f
        val sy2 = 1000f
        val rows = floatArrayOf(
            wide.padX + sx1 * wide.scale,
            wide.padY + sy1 * wide.scale,
            wide.padX + sx2 * wide.scale,
            wide.padY + sy2 * wide.scale,
            0.8f,
            0f,
        )

        val found = YoloEndToEndDecoder.decode(rows, wide)

        assertEquals(1, found.size)
        assertBox(found[0], sx1 / 2400f, sy1 / 1600f, sx2 / 2400f, sy2 / 1600f)
    }

    @Test
    fun `a genuine low-confidence bubble is kept and a candidate just under the floor is not`() {
        // 0.28 is the lowest real bubble on the synthetic pages and 0.07 the highest candidate.
        // The threshold has to sit between them, and P10 must not move it without knowing that.
        val rows = floatArrayOf(100f, 100f, 200f, 200f, 0.28f, 0f) +
            floatArrayOf(300f, 300f, 400f, 400f, 0.07f, 0f)

        assertEquals(1, YoloEndToEndDecoder.decode(rows, letterbox).size)
    }

    @Test
    fun `a box a hair past the page edge is clamped rather than thrown`() {
        // What undoing a letterbox produces at the edge: the right side lands at 640.0001 in
        // input pixels, which normalizes a fraction past 1f. BubbleBox's constructor would
        // throw; the decoder must go through clampedOrNull instead.
        val rows = floatArrayOf(500f, 10f, 640.0001f, 100f, 0.9f, 0f)

        val found = YoloEndToEndDecoder.decode(rows, letterbox)

        assertEquals(1, found.size)
        assertTrue(found[0].box.right <= 1f)
    }

    @Test
    fun `a degenerate row is dropped rather than crashing the page`() {
        // x2 < x1 gives a negative width; clampedOrNull refuses it and the page simply has one
        // fewer bubble, which is the only acceptable outcome inside a background coroutine.
        val rows = floatArrayOf(300f, 100f, 200f, 200f, 0.9f, 0f)

        assertEquals(0, YoloEndToEndDecoder.decode(rows, letterbox).size)
    }

    private fun assertBox(
        det: eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetection,
        ex: Float,
        ey: Float,
        er: Float,
        eb: Float,
    ) {
        assertEquals(ex, det.box.x, 1e-4f, "x")
        assertEquals(ey, det.box.y, 1e-4f, "y")
        assertEquals(er, det.box.right, 1e-4f, "right")
        assertEquals(eb, det.box.bottom, 1e-4f, "bottom")
    }
}
