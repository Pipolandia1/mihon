package eu.kanade.tachiyomi.ui.reader.bubblezoom

/**
 * Identity of the page Bubble Zoom is working on.
 *
 * Two things rule out the obvious alternatives. State must never be keyed by adapter position,
 * because a right-to-left viewer reverses the adapter's list and position 3 is a different page
 * in each direction. And it must not be keyed by a value such as chapter id plus page index,
 * because `InsertPage(parent)` — the half of a split double page — is constructed with
 * `parent.index` verbatim, so a split page and its own second half would share that value.
 *
 * So the key is the page object's identity and nothing else. The wrapper exists to keep this
 * file free of Android and reader types, which is what makes the controller testable on a plain
 * JVM; the reader supplies the token it already has.
 */
class BubblePageKey(private val token: Any) {

    override fun equals(other: Any?): Boolean = other is BubblePageKey && other.token === token

    override fun hashCode(): Int = System.identityHashCode(token)

    override fun toString(): String = "BubblePageKey(${token::class.simpleName}@${hashCode()})"
}

/**
 * Which way the reader was travelling when it arrived at a page.
 *
 * Entering forwards leaves the page unfocused, so the reader sees it whole before stepping in.
 * Entering backwards lands on the LAST bubble, so that walking a chapter backwards is the exact
 * reverse of walking it forwards: four stops per page either way, with the unfocused page at the
 * start of the forward walk and at the end of the backward one.
 *
 * The intent is remembered rather than applied immediately, because once detection is
 * asynchronous the bubbles do not exist yet at the moment the page is entered.
 */
enum class PageEntry {
    FORWARD,
    BACKWARD,
}

/**
 * Everything the reader is allowed to know about Bubble Zoom at one instant.
 *
 * The hierarchy is shaped so that illegal combinations cannot be written down: only [Ready] has
 * an index, and only the states that have a subject carry a page.
 */
sealed interface BubbleZoomState {

    /** The feature is switched off. Only [BubbleZoomController.enable] leaves this. */
    data object Disabled : BubbleZoomState

    /**
     * Enabled, but nothing is the subject right now: the reader just opened, a chapter
     * transition is on screen, or the current page was ruled out by a guard.
     */
    data object Idle : BubbleZoomState

    /** Every state in which some page is the subject. */
    sealed interface OnPage : BubbleZoomState {
        val page: BubblePageKey
    }

    /** [page] is the subject and its bubbles are not known yet. [entry] is the pending intent. */
    data class Detecting(
        override val page: BubblePageKey,
        val entry: PageEntry,
    ) : OnPage

    /**
     * [page]'s bubbles are known.
     *
     * [focusedIndex] is `-1` for "the page is shown normally", or an index into [bubbles]. An
     * empty list is legal and means detection finished and found nothing: `lastIndex` is then
     * `-1`, so `-1` is the only index the invariant admits and every move refuses without any
     * special case.
     */
    data class Ready(
        override val page: BubblePageKey,
        val bubbles: List<BubbleDetection>,
        val focusedIndex: Int,
    ) : OnPage {

        init {
            require(focusedIndex in -1..bubbles.lastIndex) {
                "focusedIndex must be -1 or an index of bubbles: index=$focusedIndex size=${bubbles.size}"
            }
        }

        val focusedBubble: BubbleDetection? get() = bubbles.getOrNull(focusedIndex)
    }

    /** Detection failed for [page]. Navigation behaves as if the page had no bubbles. */
    data class Error(
        override val page: BubblePageKey,
        val cause: Throwable?,
    ) : OnPage
}

/**
 * What [page] should be showing right now, or null when it must be at rest.
 *
 * This is the entire contract between the controller and whatever draws. It is a pure function
 * of the latest state, which matters because a `StateFlow` collector is conflated and may never
 * observe the states in between: a page that is no longer the subject always answers null, so it
 * returns to rest even if every intermediate state was skipped.
 *
 * Phase P4's presentation turns a non-null answer into `focusOnBubble` and a null one into
 * `resetBubbleZoom`. Phase P16 turns the same answer into a lifted card and a dismissal. One
 * intent, two presentations, no second channel.
 */
fun BubbleZoomState.focusFor(page: BubblePageKey): BubbleDetection? {
    if (this !is BubbleZoomState.Ready) return null
    if (this.page != page) return null
    return focusedBubble
}
