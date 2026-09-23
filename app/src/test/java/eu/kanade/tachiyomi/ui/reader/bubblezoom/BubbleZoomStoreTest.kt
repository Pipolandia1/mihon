package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The answers a rotation must not be able to throw away. Amendment E7.
 */
class BubbleZoomStoreTest {

    private val bubbles = listOf(BubbleDetection(BubbleBox(0.1f, 0.1f, 0.2f, 0.1f), 0.9f, BubbleType.TEXT))

    private fun key() = BubblePageKey(Any())

    @Test
    fun `what was remembered comes back, with what it cost`() {
        val store = BubbleZoomStore()
        val page = key()

        store.remember(page, modelVersion = 2, bubbles = bubbles, elapsedMs = 2143)

        val recalled = store.recall(page, modelVersion = 2)
        assertNotNull(recalled)
        assertEquals(bubbles, recalled!!.bubbles)
        assertEquals(2143, recalled.elapsedMs, "the panel reports the original wait, not zero")
    }

    @Test
    fun `a page nothing is known about answers nothing`() {
        assertNull(BubbleZoomStore().recall(key(), modelVersion = 2))
    }

    @Test
    fun `another detector's answer is not this detector's`() {
        val store = BubbleZoomStore()
        val page = key()

        store.remember(page, modelVersion = 2, bubbles = bubbles, elapsedMs = 10)

        assertNull(store.recall(page, modelVersion = -1), "the fake detector must see its own results")
        assertNotNull(store.recall(page, modelVersion = 2))
    }

    @Test
    fun `re-detecting a page replaces what was there`() {
        val store = BubbleZoomStore()
        val page = key()
        store.remember(page, modelVersion = 2, bubbles = bubbles, elapsedMs = 10)

        store.remember(page, modelVersion = 2, bubbles = emptyList(), elapsedMs = 20)

        assertEquals(emptyList<BubbleDetection>(), store.recall(page, 2)!!.bubbles)
        assertEquals(1, store.size, "and does not grow the store")
    }

    @Test
    fun `it holds a bounded number of pages`() {
        val store = BubbleZoomStore()
        val keys = List(BubbleZoomStore.MAX_PAGES + 5) { key() }

        keys.forEach { store.remember(it, 2, bubbles, 1) }

        assertEquals(BubbleZoomStore.MAX_PAGES, store.size)
        assertNull(store.recall(keys.first(), 2), "the oldest went")
        assertNotNull(store.recall(keys.last(), 2))
    }

    @Test
    fun `a page read again is not the page thrown away next`() {
        // Least recently USED, not least recently added: a reader who walks back to a page and
        // then forward again should not lose the one they keep returning to.
        val store = BubbleZoomStore()
        val keys = List(BubbleZoomStore.MAX_PAGES) { key() }
        keys.forEach { store.remember(it, 2, bubbles, 1) }

        assertNotNull(store.recall(keys.first(), 2))
        store.remember(key(), 2, bubbles, 1)

        assertNotNull(store.recall(keys.first(), 2), "the page just read was evicted")
        assertNull(store.recall(keys[1], 2), "and the one nobody asked for stayed")
    }

    @Test
    fun `closing the reader forgets everything`() {
        val store = BubbleZoomStore()
        val page = key()
        store.remember(page, 2, bubbles, 1)

        store.forgetAll()

        assertEquals(0, store.size)
        assertNull(store.recall(page, 2))
    }
}
