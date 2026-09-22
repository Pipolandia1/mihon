package eu.kanade.tachiyomi.ui.reader.bubblezoom

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Which pages are worth looking at before the reader gets to them.
 *
 * The rule has to be conservative in one specific way: this reads a page's own bytes, and those
 * are the bytes on screen only when the page holder's `process` would be the identity. Every
 * refusal below is that same reason wearing a different hat.
 */
class BubbleZoomPrefetchTest {

    private fun page(index: Int, loaded: Boolean = true) = ReaderPage(
        index = index,
        url = "",
        imageUrl = null,
        stream = if (loaded) ({ ByteArrayInputStream(ByteArray(0)) }) else null,
    )

    /** Stands in for a chapter transition, which the adapter puts between pages. */
    private object Transition

    @Test
    fun `the next pages after the current one, in order`() {
        val items = listOf(page(0), page(1), page(2), page(3), page(4))

        val ahead = BubbleZoomPrefetch.upcoming(items, currentIndex = 1, count = 2)

        assertEquals(listOf(2, 3), ahead.map { it.index })
    }

    @Test
    fun `a transition is stepped over rather than ending the walk`() {
        // A transition is one item wide and the pages after it are exactly the ones worth
        // having early, since arriving at a new chapter is where a reader waits longest.
        val items = listOf(page(0), Transition, page(1), page(2))

        val ahead = BubbleZoomPrefetch.upcoming(items, currentIndex = 0, count = 3)

        assertEquals(listOf(1, 2), ahead.map { it.index })
    }

    @Test
    fun `a page that has not been loaded is left to its own holder`() {
        val items = listOf(page(0), page(1, loaded = false), page(2))

        val ahead = BubbleZoomPrefetch.upcoming(items, currentIndex = 0, count = 3)

        assertEquals(listOf(2), ahead.map { it.index })
    }

    @Test
    fun `nothing is fetched ahead when the reader's settings rewrite the pages`() {
        // With split or rotate-to-fit on, the bytes on disk are not the bytes on screen, and a
        // detection made from them would describe the wrong rectangle with nothing to say so.
        val items = listOf(page(0), page(1), page(2))

        val ahead = BubbleZoomPrefetch.upcoming(items, currentIndex = 0, count = 3, processesPages = true)

        assertTrue(ahead.isEmpty())
    }

    @Test
    fun `the end of the list is not the end of the world`() {
        val items = listOf(page(0), page(1))

        assertEquals(listOf(1), BubbleZoomPrefetch.upcoming(items, currentIndex = 0, count = 5).map { it.index })
        assertTrue(BubbleZoomPrefetch.upcoming(items, currentIndex = 1, count = 5).isEmpty())
        assertTrue(BubbleZoomPrefetch.upcoming(items, currentIndex = 9, count = 5).isEmpty())
        assertTrue(BubbleZoomPrefetch.upcoming(emptyList(), currentIndex = 0, count = 5).isEmpty())
    }

    @Test
    fun `asking for none, or from nowhere, is not an error`() {
        val items = listOf(page(0), page(1), page(2))

        assertTrue(BubbleZoomPrefetch.upcoming(items, currentIndex = 0, count = 0).isEmpty())
        assertTrue(BubbleZoomPrefetch.upcoming(items, currentIndex = -1, count = 3).isEmpty())
    }
}
