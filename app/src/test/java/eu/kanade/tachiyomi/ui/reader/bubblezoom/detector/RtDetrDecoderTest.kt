package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of the model's three classes become stops, and what a query has to clear to be one.
 * Amendment E9.
 *
 * The arrays are shaped the way the runtime hands them over: one label and one score per query,
 * four floats per box, already fractions of the page.
 */
class RtDetrDecoderTest {

    private fun decode(
        vararg queries: Triple<Long, FloatArray, Float>,
        confidence: Float = 0.5f,
    ) = RtDetrDecoder.decode(
        labels = queries.map { it.first }.toLongArray(),
        boxes = queries.flatMap { it.second.toList() }.toFloatArray(),
        scores = queries.map { it.third }.toFloatArray(),
        minimumConfidence = confidence,
    )

    private fun query(label: Long, score: Float, box: FloatArray = floatArrayOf(0.1f, 0.1f, 0.3f, 0.2f)) =
        Triple(label, box, score)

    @Test
    fun `a balloon is a stop`() {
        val found = decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.9f, floatArrayOf(0.2f, 0.3f, 0.5f, 0.45f)))

        assertEquals(1, found.size)
        val box = found.single().box
        assertEquals(0.2f, box.x, 1e-6f)
        assertEquals(0.3f, box.y, 1e-6f)
        assertEquals(0.3f, box.width, 1e-6f, "right minus left")
        assertEquals(0.15f, box.height, 1e-6f, "bottom minus top")
        assertEquals(0.9f, found.single().confidence)
        assertEquals(BubbleType.TEXT, found.single().type)
    }

    @Test
    fun `a caption with no balloon around it is a stop too`() {
        // Half the dialogue on a modern comic page is lettering with no balloon; dropping this
        // class would lose eleven of one measured page's twenty-three pieces.
        assertEquals(1, decode(query(RtDetrDecoder.CLASS_TEXT_FREE, 0.7f)).size)
    }

    @Test
    fun `the lettering inside a balloon is not a second stop`() {
        val found = decode(
            query(RtDetrDecoder.CLASS_BUBBLE, 0.9f),
            query(RtDetrDecoder.CLASS_TEXT_IN_BUBBLE, 0.9f),
        )

        assertEquals(1, found.size, "the balloon and its own text are one stop")
    }

    @Test
    fun `a query below the threshold is not a stop`() {
        assertEquals(0, decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.49f), confidence = 0.5f).size)
        assertEquals(1, decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.50f), confidence = 0.5f).size)
    }

    @Test
    fun `nothing this model returns carries a silhouette`() {
        // It draws rectangles. The card traces the balloon out of the page instead, and this is
        // what tells it to.
        assertNull(decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.9f)).single().shape)
    }

    @Test
    fun `a box that runs off the page is clipped, not dropped`() {
        val found = decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.9f, floatArrayOf(-0.05f, 0.8f, 0.4f, 1.2f)))

        val box = found.single().box
        assertEquals(0f, box.x)
        assertTrue(box.bottom <= 1f && box.right <= 1f)
    }

    @Test
    fun `a box with no area at all is dropped`() {
        assertEquals(0, decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.9f, floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))).size)
        assertEquals(0, decode(query(RtDetrDecoder.CLASS_BUBBLE, 0.9f, floatArrayOf(0.6f, 0.5f, 0.4f, 0.7f))).size)
    }

    @Test
    fun `a score that is not a number is not trusted`() {
        assertEquals(0, decode(query(RtDetrDecoder.CLASS_BUBBLE, Float.NaN)).size)
    }

    @Test
    fun `the queries come back in the model's own order`() {
        val found = decode(
            query(RtDetrDecoder.CLASS_BUBBLE, 0.6f, floatArrayOf(0.5f, 0.5f, 0.7f, 0.6f)),
            query(RtDetrDecoder.CLASS_TEXT_FREE, 0.9f, floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f)),
        )

        // Reading order is decided later, by the page's panels and the balloons' layout; this
        // step must not quietly impose one of its own, such as by confidence.
        assertEquals(listOf(0.6f, 0.9f), found.map { it.confidence })
    }

    @Test
    fun `an answer with nothing in it is not an error`() {
        assertEquals(emptyList<Nothing>(), RtDetrDecoder.decode(LongArray(0), FloatArray(0), FloatArray(0)))
    }

    @Test
    fun `an answer whose arrays disagree is read as far as they agree`() {
        // Defensive: a re-export with a different query count must not throw inside a detection
        // job, where the only outcome is the page being marked failed.
        val found = RtDetrDecoder.decode(
            labels = longArrayOf(0, 0, 0),
            boxes = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f),
            scores = floatArrayOf(0.9f, 0.9f, 0.9f),
        )

        assertEquals(1, found.size)
    }
}
