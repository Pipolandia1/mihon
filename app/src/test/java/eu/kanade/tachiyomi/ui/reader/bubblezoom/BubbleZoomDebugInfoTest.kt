package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The instrument panel's wording. It is read at a glance over artwork, so what it says has to
 * be short and has to be unambiguous — "detecting…" and "failed" cannot look like a page that
 * simply has no balloons.
 */
class BubbleZoomDebugInfoTest {

    private fun info(
        bubbles: Int = 8,
        shaped: Int = 8,
        ms: Long? = 2143,
        prefetched: Boolean = false,
        version: Int = 2,
        focused: Int = -1,
        confidence: Float? = null,
        failure: String? = null,
        remembered: Boolean = false,
    ) = BubbleZoomDebugInfo(
        bubbles = bubbles,
        shaped = shaped,
        detectionMs = ms,
        prefetched = prefetched,
        remembered = remembered,
        modelVersion = version,
        focusedIndex = focused,
        focusedConfidence = confidence,
        failure = failure,
    )

    @Test
    fun `a finished page says what was found, how long it took and which model`() {
        assertEquals("8 bubbles · 2143ms · v2", info().summary)
    }

    @Test
    fun `a page looked at before the reader reached it says so`() {
        // The one number that explains why a page is instant, now that the cache phase is gone.
        assertEquals("8 bubbles · 2143ms · ahead · v2", info(prefetched = true).summary)
    }

    @Test
    fun `a page that came back from the store says so, and still says what it cost`() {
        // After a rotation every page on screen reads like this. Without the word it would
        // claim 2143ms for something that took none, which is how a cache hides.
        assertEquals("8 bubbles · 2143ms · kept · v2", info(remembered = true).summary)
    }

    @Test
    fun `a page still thinking is never mistaken for a page with nothing on it`() {
        assertEquals("detecting…", info(bubbles = 0, ms = null).summary)
        assertEquals("0 bubbles · 2143ms · v2", info(bubbles = 0, shaped = 0).summary)
    }

    @Test
    fun `a failure says so instead of reporting an empty page`() {
        assertEquals("failed: IOException", info(bubbles = 0, ms = null, failure = "IOException").summary)
    }

    @Test
    fun `a balloon without a silhouette is called out, and a page of them is not`() {
        // Every detection from the segmentation model carries a shape, so the count is only
        // worth the space when one does not — a fake detector, or a mask the model left empty.
        assertEquals("8 bubbles · 2143ms · v2", info(shaped = 8).summary)
        assertEquals("8 bubbles, 6 shaped · 2143ms · v2", info(shaped = 6).summary)
    }

    @Test
    fun `one balloon is not "1 bubbles"`() {
        assertEquals("1 bubble · 2143ms · v2", info(bubbles = 1, shaped = 1).summary)
    }

    @Test
    fun `the focus line is the walk's position and the model's confidence in it`() {
        assertEquals("3/8 · 87%", info(focused = 2, confidence = 0.871f).focus)
    }

    @Test
    fun `a page at rest, or with nothing on it, has no focus line to draw`() {
        assertNull(info(focused = -1).focus)
        assertNull(info(bubbles = 0, focused = 0).focus)
    }

    @Test
    fun `a focus with no confidence still says where the walk is`() {
        assertEquals("2/8", info(focused = 1, confidence = null).focus)
    }
}
