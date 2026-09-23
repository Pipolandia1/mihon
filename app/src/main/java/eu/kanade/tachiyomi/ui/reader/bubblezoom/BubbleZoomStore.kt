package eu.kanade.tachiyomi.ui.reader.bubblezoom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner

/**
 * What the model has already answered, kept where a rotation cannot reach it. Amendment E7.
 *
 * The session lives on the viewer, and the viewer is a view: turning the phone destroys the
 * activity, the viewer and everything either of them held. Measured before this existed —
 * rotate, and the log says `prefetch known=0`, the card is gone and the page the reader is
 * looking at goes back to saying "detecting…" for as long as the model takes. Nothing was
 * wrong with the answers; there was simply nowhere for them to be.
 *
 * A `ViewModel` is that somewhere, and is exactly the right somewhere: it survives a
 * configuration change and is cleared when the reader is really finished with, so a chapter's
 * answers do not outlive the screen that asked for them.
 *
 * It holds answers only — boxes, confidences and silhouettes. Never a page's bytes, which are
 * megabytes each and belong to the holder that is displaying them.
 *
 * Keyed by [BubblePageKey], which is the page object's identity: the same `ReaderPage`
 * instances come back after a rotation because they belong to the reader's own view model,
 * which survives it too. A page whose object is genuinely new — a chapter reloaded from disk —
 * is simply a miss, and costs what it cost before.
 */
class BubbleZoomStore : ViewModel() {

    /** One page's answer, with what the panel needs to tell the truth about where it came from. */
    class Remembered(
        val bubbles: List<BubbleDetection>,
        /** What the page originally waited, so the debug panel does not report a lie of 0 ms. */
        val elapsedMs: Long,
    )

    private class Entry(val modelVersion: Int, val remembered: Remembered)

    /**
     * Least-recently-used, and small on purpose.
     *
     * A silhouette is a flag per cell over the balloon's box, which on a real page runs to
     * about twenty thousand cells for a wide caption — ten balloons of those is a couple of
     * hundred kilobytes a page. The reader can only be looking at three pages at once and only
     * looks [BubbleZoomConfig.PREFETCH_PAGES] ahead, so twelve covers everything live with room
     * to walk back a few, and caps what this can hold at a couple of megabytes.
     */
    private val pages = object : LinkedHashMap<BubblePageKey, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BubblePageKey, Entry>) = size > MAX_PAGES
    }

    /** The answer for [page] from a detector of [modelVersion], or null. */
    fun recall(page: BubblePageKey, modelVersion: Int): Remembered? = synchronized(pages) {
        // A version that does not match is another detector's answer - the fake one, switched
        // on to compare against the real one mid-read - and serving it would make the two
        // indistinguishable, which is the one thing that preference exists to avoid.
        pages[page]?.takeIf { it.modelVersion == modelVersion }?.remembered
    }

    /** Remembers what [modelVersion] found on [page], replacing whatever was there. */
    fun remember(page: BubblePageKey, modelVersion: Int, bubbles: List<BubbleDetection>, elapsedMs: Long) {
        synchronized(pages) { pages[page] = Entry(modelVersion, Remembered(bubbles, elapsedMs)) }
    }

    /** How many pages are held. For tests and for saying so in a log. */
    val size: Int get() = synchronized(pages) { pages.size }

    /** Drops everything. Called when the reader is finished with, and by its tests. */
    fun forgetAll() {
        synchronized(pages) { pages.clear() }
    }

    override fun onCleared() = forgetAll()

    companion object {
        const val MAX_PAGES = 12

        /**
         * The one for [owner], created on first ask.
         *
         * The reader's activity owns it, so it is the same instance across a rotation and a
         * different one for a different reading session.
         */
        fun of(owner: ViewModelStoreOwner): BubbleZoomStore =
            ViewModelProvider(owner)[BubbleZoomStore::class.java]
    }
}
