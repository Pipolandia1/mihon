package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BubbleDetectionTest {

    private val box = BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)

    @Test
    fun `accepts a confidence at either end of the range`() {
        assertEquals(0f, BubbleDetection(box, 0f, BubbleType.TEXT).confidence)
        assertEquals(1f, BubbleDetection(box, 1f, BubbleType.TEXT).confidence)
    }

    @Test
    fun `rejects a confidence outside the range or not a number`() {
        assertThrows(IllegalArgumentException::class.java) {
            BubbleDetection(box, -0.1f, BubbleType.TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BubbleDetection(box, 1.1f, BubbleType.TEXT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BubbleDetection(box, Float.NaN, BubbleType.TEXT)
        }
    }

    @Test
    fun `a page result carries its detections, model version and timing unchanged`() {
        val detections = listOf(
            BubbleDetection(box, 0.93f, BubbleType.TEXT),
            BubbleDetection(BubbleBox(0.15f, 0.31f, 0.28f, 0.16f), 0.81f, BubbleType.UNKNOWN),
        )

        val result = BubblePageResult(bubbles = detections, modelVersion = 1, detectionTimeMs = 42L)

        assertEquals(detections, result.bubbles)
        assertEquals(1, result.modelVersion)
        assertEquals(42L, result.detectionTimeMs)
    }

    @Test
    fun `a page with no bubbles is representable`() {
        val result = BubblePageResult(bubbles = emptyList(), modelVersion = 0, detectionTimeMs = 0L)

        assertEquals(emptyList<BubbleDetection>(), result.bubbles)
    }
}
