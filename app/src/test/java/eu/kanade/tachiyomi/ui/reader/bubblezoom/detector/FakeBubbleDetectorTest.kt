package eu.kanade.tachiyomi.ui.reader.bubblezoom.detector

import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleBox
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleDetectionConfig
import eu.kanade.tachiyomi.ui.reader.bubblezoom.BubbleType
import eu.kanade.tachiyomi.ui.reader.bubblezoom.DEBUG_BOXES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBubbleDetectorTest {

    private val image = BubbleDetectionImage(1600, 2400) { ByteArrayInputStream(ByteArray(0)) }

    @Test
    fun `it answers with the known boxes`() = runTest {
        val found = FakeBubbleDetector(latencyMs = 0).detect(image)

        assertEquals(DEBUG_BOXES.size, found.size)
        assertEquals(DEBUG_BOXES, found.map { it.box })
        assertTrue(found.all { it.type == BubbleType.TEXT })
    }

    @Test
    fun `the latency is a suspension and not a blocked thread`() = runTest {
        // Thread.sleep would look identical on a device and would break three things at once:
        // it holds a shared dispatcher thread, it ignores cancellation, and it does not move a
        // virtual clock. This test only passes for delay, because advanceTimeBy is the only
        // thing that lets it finish.
        val detector = FakeBubbleDetector(latencyMs = 300)

        val running = async { detector.detect(image) }
        advanceTimeBy(299)
        assertFalse(running.isCompleted, "the detector answered before its own latency had passed")

        advanceUntilIdle()
        assertTrue(running.isCompleted)
        assertEquals(DEBUG_BOXES.size, running.await().size)
    }

    @Test
    fun `zero latency answers without waiting at all`() = runTest {
        val running = async { FakeBubbleDetector(latencyMs = 0).detect(image) }
        advanceUntilIdle()

        assertTrue(running.isCompleted)
    }

    @Test
    fun `a box a hair outside the page is clamped rather than thrown`() = runTest {
        // What a real detector produces: undoing a letterbox lands a fraction of an ulp past 1f,
        // and BubbleBox's constructor rejects that — from inside a background coroutine, where
        // the throw is a page that silently never becomes walkable. The fake goes through the
        // same door so that door is exercised from the day it exists.
        val overshooting = BubbleBox.clampedOrNull(0.5f, 0.5f, 0.5000001f, 0.4f)
        val detector = FakeBubbleDetector(latencyMs = 0, boxes = listOfNotNull(overshooting))

        val found = detector.detect(image)

        assertEquals(1, found.size)
        assertTrue(found[0].box.right <= 1f, "a box escaped past the edge of the page")
    }

    @Test
    fun `an empty answer is legal and produces no bubbles`() = runTest {
        // A page with no dialogue on it is the common case, not an error, and BubbleZoomState's
        // Ready has a require() on its index that an empty list has to keep satisfying.
        val found = FakeBubbleDetector(latencyMs = 0, boxes = emptyList()).detect(image)

        assertTrue(found.isEmpty())
    }

    @Test
    fun `its version is reserved, so a disk cache can never confuse it for a real model`() {
        // Phase P13 keys its cache on this. Sharing a number with a real detector would let the
        // cache serve three hardcoded debug boxes on a real manga page, with nothing in the
        // stored result to say where they came from.
        assertEquals(BubbleDetectionConfig.FAKE_MODEL_VERSION, FakeBubbleDetector().modelVersion)
        assertTrue(FakeBubbleDetector().modelVersion < 0, "a real model would use a positive version")
        assertNotEquals(0, FakeBubbleDetector().modelVersion)
    }
}
