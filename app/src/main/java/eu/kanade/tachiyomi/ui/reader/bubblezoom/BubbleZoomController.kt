package eu.kanade.tachiyomi.ui.reader.bubblezoom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which bubble, if any, the reader is looking at.
 *
 * It knows nothing about views, bitmaps, ML or Android: it publishes [state], and whatever draws
 * derives what to show from it with [focusFor]. "Resetting the zoom" here means moving the index
 * back to -1; turning that into an actual camera move or a dismissed card is the presentation's
 * job, which is what lets phase P16 change the presentation without touching this file.
 *
 * One instance serves the whole viewer and holds exactly one subject page at a time. Page state
 * is not memoised: a page that leaves the screen and comes back is walked from its entry point
 * again, which is also what the reader expects after a chapter change.
 *
 * Not thread safe, and deliberately so. Every caller is the reader's navigation, which runs on
 * the main thread; adding a lock would only hide a caller that should not exist.
 */
class BubbleZoomController {

    private val mutableState = MutableStateFlow<BubbleZoomState>(BubbleZoomState.Idle)

    val state: StateFlow<BubbleZoomState> = mutableState.asStateFlow()

    private var enabled = true

    /**
     * Whether a bubble is focused right now.
     *
     * Phase P6 must read this **before** calling [tryMoveNext] or [tryMovePrevious]: the reader's
     * `moveRight`/`moveLeft` pan the image instead of turning the page when it can still be
     * panned, and a page that has just finished a bubble walk always can. Knowing the move ended
     * a walk is what lets P6 skip that check for exactly that one event.
     */
    val isFocused: Boolean
        get() = (mutableState.value as? BubbleZoomState.Ready)?.focusedIndex?.let { it >= 0 } == true

    /**
     * Makes [page] the subject, entering from [entry].
     *
     * Idempotent for the page already held, so a redundant call from the reader does not restart
     * a walk in progress. Does nothing while disabled.
     */
    fun activatePage(page: BubblePageKey, entry: PageEntry) {
        if (!enabled) return
        val current = mutableState.value
        if (current is BubbleZoomState.OnPage && current.page == page) return
        mutableState.value = BubbleZoomState.Detecting(page, entry)
    }

    /**
     * Convenience for a caller that already has the bubbles, which is how phase P6 feeds the
     * hardcoded boxes until a detector exists.
     */
    fun activatePage(page: BubblePageKey, bubbles: List<BubbleDetection>, entry: PageEntry) {
        activatePage(page, entry)
        onBubblesReady(page, bubbles)
    }

    /**
     * Supplies the bubbles for [page], consuming the entry intent latched by [activatePage]:
     * entering backwards lands on the last bubble, entering forwards leaves the page unfocused.
     *
     * Ignored when [page] is no longer the subject, which is what makes a late detection result
     * for a page the reader already left harmless.
     */
    fun onBubblesReady(page: BubblePageKey, bubbles: List<BubbleDetection>) {
        val current = mutableState.value
        if (current !is BubbleZoomState.OnPage || current.page != page) return
        val entry = (current as? BubbleZoomState.Detecting)?.entry ?: PageEntry.FORWARD
        val navigable = bubbles.filter { it.type != BubbleType.PANEL }
        val index = if (entry == PageEntry.BACKWARD) navigable.lastIndex else -1
        mutableState.value = BubbleZoomState.Ready(page, navigable, index)
    }

    /** Records that detection failed for [page]. Navigation then behaves as if it had no bubbles. */
    fun onDetectionFailed(page: BubblePageKey, cause: Throwable?) {
        val current = mutableState.value
        if (current !is BubbleZoomState.OnPage || current.page != page) return
        mutableState.value = BubbleZoomState.Error(page, cause)
    }

    /**
     * Steps to the next bubble.
     *
     * Returns true when it moved, so the reader must not also turn the page. On the last bubble
     * it un-focuses the page and returns false: the reader turns the page, and the page it leaves
     * is already back at rest rather than sliding out zoomed — which also means a page turn that
     * does not happen, at the end of a chapter, leaves the page whole instead of stuck on a box.
     */
    fun tryMoveNext(): Boolean {
        val current = mutableState.value as? BubbleZoomState.Ready ?: return false
        val next = current.focusedIndex + 1
        if (next > current.bubbles.lastIndex) {
            if (current.focusedIndex != -1) {
                mutableState.value = current.copy(focusedIndex = -1)
            }
            return false
        }
        mutableState.value = current.copy(focusedIndex = next)
        return true
    }

    /**
     * Steps to the previous bubble.
     *
     * The exact mirror of [tryMoveNext]: from the first bubble it goes to the unfocused page and
     * returns true, and only from the unfocused page does it return false so the reader turns
     * back. Four stops per page in either direction, with the unfocused page at the start of the
     * forward walk and at the end of the backward one.
     */
    fun tryMovePrevious(): Boolean {
        val current = mutableState.value as? BubbleZoomState.Ready ?: return false
        if (current.focusedIndex < 0) return false
        mutableState.value = current.copy(focusedIndex = current.focusedIndex - 1)
        return true
    }

    /** Focuses [index] directly, or -1 to un-focus. Returns whether anything changed. */
    fun selectBubble(index: Int): Boolean {
        val current = mutableState.value as? BubbleZoomState.Ready ?: return false
        if (index !in -1..current.bubbles.lastIndex) return false
        if (index == current.focusedIndex) return false
        mutableState.value = current.copy(focusedIndex = index)
        return true
    }

    /** Un-focuses the subject without giving it up. */
    fun reset() {
        val current = mutableState.value as? BubbleZoomState.Ready ?: return
        if (current.focusedIndex == -1) return
        mutableState.value = current.copy(focusedIndex = -1)
    }

    /**
     * Gives up the subject entirely: a chapter transition is on screen, or the current page was
     * ruled out by a guard such as an animated image or a split double page.
     */
    fun deactivate() {
        if (mutableState.value is BubbleZoomState.Disabled) return
        mutableState.value = BubbleZoomState.Idle
    }

    /** Switches the feature off. Every operation refuses until [enable]. */
    fun disable() {
        enabled = false
        mutableState.value = BubbleZoomState.Disabled
    }

    /** Switches the feature back on, with no subject. */
    fun enable() {
        enabled = true
        if (mutableState.value is BubbleZoomState.Disabled) {
            mutableState.value = BubbleZoomState.Idle
        }
    }
}
