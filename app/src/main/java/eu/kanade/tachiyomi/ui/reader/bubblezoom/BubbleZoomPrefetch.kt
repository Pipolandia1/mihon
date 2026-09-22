package eu.kanade.tachiyomi.ui.reader.bubblezoom

import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetectionImage
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.bubbleDetectionImage
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import okio.Buffer

/**
 * A page to look at early: what to call it, and how to get its bytes when the time comes.
 *
 * A pair rather than the page itself so that [BubbleZoomSession] never has to know what a
 * `ReaderPage` is — the same reason its other seams are lambdas, and what keeps the whole of
 * its behaviour checkable by a JVM test.
 */
class PrefetchPage(val key: BubblePageKey, val image: () -> BubbleDetectionImage?)

/**
 * The pages whose balloons are worth looking for before the reader gets to them.
 *
 * Detection already ran one page ahead by accident: the pager keeps a holder either side of the
 * current page, each holder decodes as soon as it is built, and decoding is what starts a
 * detection. But the lead that gives is only however long the reader spends on the page before
 * — and a segmentation model takes seconds, so anyone turning pages briskly arrives first and
 * waits. Measured on the device: 1.2 to 2.3 seconds of waiting per page.
 *
 * So the pages after the next one are asked for explicitly, from the page's own byte stream
 * rather than from a holder that does not exist yet.
 *
 * That is only sound when the bytes on disk are the bytes on screen, which is exactly when the
 * page holder's `process` is the identity: no rotating a wide page to fit, and no splitting a
 * double page in half. When either is on, this returns nothing and the feature falls back to
 * the accidental one-page lead — the alternative would be reimplementing `process` here, and a
 * second copy of that logic drifting from the first is how a detection ends up describing the
 * wrong rectangle with nothing to say so.
 *
 * An [InsertPage] is refused for the same reason under any setting: it is the second half of a
 * split spread and its stream is its parent's, so it would hand back the whole spread.
 */
object BubbleZoomPrefetch {

    /**
     * Up to [count] pages after [currentIndex] in [items] that can be looked at now.
     *
     * [items] is the pager adapter's list, which holds chapter transitions between the pages,
     * so anything that is not a [ReaderPage] is skipped rather than ending the walk — a
     * transition is one item wide and the pages after it are worth having. A page whose stream
     * is not set yet has not been loaded, and is left for its own holder.
     */
    fun upcoming(
        items: List<Any>,
        currentIndex: Int,
        count: Int = BubbleZoomConfig.PREFETCH_PAGES,
        processesPages: Boolean = false,
    ): List<ReaderPage> {
        if (processesPages || count <= 0) return emptyList()
        if (currentIndex < 0) return emptyList()

        val found = ArrayList<ReaderPage>(count)
        var index = currentIndex + 1
        while (index < items.size && found.size < count) {
            val item = items[index]
            if (item is ReaderPage && item !is InsertPage && item.stream != null) found += item
            index++
        }
        return found
    }

    /**
     * Turns a page into something the session can fetch, reading its bytes only when asked.
     *
     * The read itself is the one Android-touching line here, and it happens on whatever thread
     * the session is on — the same as the page holder's own call, and for the same reason: what
     * comes back is a snapshot of the encoded bytes, not a decoded page.
     */
    fun readable(page: ReaderPage): PrefetchPage = PrefetchPage(page.bubblePageKey()) {
        val open = page.stream ?: return@PrefetchPage null
        runCatching { open().use { bubbleDetectionImage(Buffer().readFrom(it)) } }.getOrNull()
    }
}
