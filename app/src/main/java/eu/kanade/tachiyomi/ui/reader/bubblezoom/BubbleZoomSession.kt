package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetectionImage
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetector
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.FakeBubbleDetector
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.OnnxBubbleDetector
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.SwitchingBubbleDetector
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import kotlin.coroutines.coroutineContext

/**
 * Everything the reader is shown of the feature, behind the preference that switches it off.
 *
 * One object so that the gate has one owner. The reader holds this and nothing else: the
 * controller, the two preferences and the decision of when a page may be walked all live on
 * this side of the line, and the upstream classes only ever call through it. That is what makes
 * "off is vanilla" something you can check by reading one file rather than by auditing three.
 *
 * The controller itself stays as it was — pure, with no preference and no scope — because its
 * state machine is covered by a test suite that would otherwise have to grow an Android
 * dependency to say anything.
 */
class BubbleZoomSession(
    private val enabled: Preference<Boolean>,
    debug: Preference<Boolean>,
    private val scope: CoroutineScope,
    /** Swapped wholesale in P9. Nothing above this line knows what kind of detector it is. */
    private val detector: BubbleDetector = FakeBubbleDetector(),
    /**
     * Where detection runs. A parameter rather than a hardcoded [Dispatchers.Default] because
     * otherwise "detection does not run on the main thread" is a claim no test can check - and
     * a fake whose latency is a delay holds no thread, so the mistake would stay invisible
     * until a real model turned it into a dropped frame.
     */
    private val detectionDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * The page the reader is on, read lazily: switching the feature on has to give the walk a
     * subject, and the reader will not call `activatePage` again until it turns a page.
     *
     * A key rather than the page itself, so that nothing here has to know what a `ReaderPage`
     * is and the whole class can be exercised on the JVM.
     */
    private val currentPage: () -> BubblePageKey?,
) {

    private val controller = BubbleZoomController()

    /**
     * What is known about each live page: a detection in flight, or its answer waiting to be
     * claimed.
     *
     * An answer has to be able to wait, because the two halves of the story arrive in either
     * order and from different places. The reader announces a page when it selects it, from
     * `PagerViewer`; the bytes appear when it finishes decoding, in `PagerPageHolder`. A page
     * that decoded while the reader was still two pages away has its answer ready before the
     * controller has ever heard of it, and `onBubblesReady` only ever answers a pending
     * `Detecting` - so without somewhere to wait, that answer would be dropped and the page
     * would sit inert for as long as the reader stayed on it.
     *
     * Bounded by the pager rather than by a policy: `offscreenPageLimit` is 1, so three pages
     * are alive at a time and each holder drops its own entry on the way out. This is not the
     * phase P11 cache and must not grow into one here.
     */
    private val detections = mutableMapOf<BubblePageKey, PageDetection>()

    /**
     * Pages whose holder has confirmed an image that can actually be zoomed.
     *
     * This is phase P6's guarantee, restored. A detection is delivered to the controller only
     * for a page in this set; until then it waits in [detections]. Without it an animated page,
     * which has no zoomable view, would enter Ready with real boxes, the presenter could draw
     * nothing, and the next N taps would be swallowed - the critical regression P6 fixed, back
     * the moment the detector became real. A page that failed to decode never joins either.
     */
    private val ready = mutableSetOf<BubblePageKey>()

    private class PageDetection(var job: Job? = null) {
        var bubbles: List<BubbleDetection>? = null
        var failure: Throwable? = null
    }

    /**
     * What a page holder needs, in one value, so that it subscribes to one thing.
     *
     * The boxes are forced off whenever the feature is, so a holder cannot be asked to draw
     * debug output for a feature that is not running.
     *
     * Shared rather than cold, and this is not a detail. `Preference.changes()` is built on a
     * callbackFlow, so a cold combine would register one `OnSharedPreferenceChangeListener` per
     * page holder — created and torn down on every page turn, three live at once, for a feature
     * that may well be switched off. One `stateIn` on the viewer's scope makes it a single
     * subscription for the whole reader, which is what `PagerConfig` does for its own dozen
     * preferences. Being a StateFlow also gives the holders a value to re-read, which is how a
     * page whose image was still decoding when the feature was switched on catches up.
     */
    val presentation: StateFlow<BubbleZoomPresentation> =
        combine(controller.state, debug.changes()) { state, boxes ->
            BubbleZoomPresentation(state, boxes && state !is BubbleZoomState.Disabled)
        }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                BubbleZoomPresentation(controller.state.value, showBoxes = false),
            )

    init {
        // Read synchronously, not awaited. `changes()` does emit the current value, but from a
        // coroutine, so a session gated only through the flow would still be enabled for the
        // turn of the main loop between the viewer being built and that first emission — and
        // the reader selects its first page inside that window.
        if (!enabled.get()) controller.disable()
        enabled.changes()
            .distinctUntilChanged()
            .onEach { if (it) switchOn() else controller.disable() }
            .launchIn(scope)
    }

    /** Drops everything in flight. For the reader closing, ahead of the scope being cancelled. */
    fun release() {
        detections.values.forEach { it.job?.cancel() }
        detections.clear()
        ready.clear()
        runCatching { detector.close() }
    }

    /** Whether the feature is running at all. The single question every gate asks. */
    val isEnabled: Boolean get() = controller.state.value !is BubbleZoomState.Disabled

    val isFocused: Boolean get() = controller.isFocused

    fun activatePage(page: BubblePageKey, entry: PageEntry) {
        controller.activatePage(page, entry)
        // The answer may already be waiting. activatePage leaves the state Detecting, which is
        // exactly what onBubblesReady needs, so this is the moment a result that arrived early
        // gets claimed.
        deliver(page)
    }

    /**
     * Starts detection for [page] from the bytes the viewer is about to display.
     *
     * [image] is a lambda and is never called while the feature is off: from P9 building it
     * means copying a page's bytes and reading its header, and work that is switched off should
     * not be started and then discarded. It is called synchronously, because what it reads is
     * only valid before the viewer takes the source.
     *
     * Idempotent per page. The page holder reaches this from a collector that runs on every
     * state change, including every single bubble step, so without the guard a three-bubble
     * walk would start four detections of the same page.
     */
    fun detect(page: BubblePageKey, image: () -> BubbleDetectionImage?) {
        // A detector that has already found it cannot work - no model, no native library - is
        // not asked again, and no page's bytes are copied for it. The first page pays the one
        // attempt that discovers this; every page after that costs what the feature costs off.
        if (!isEnabled || !detector.available) return
        // Read here, synchronously, on whatever thread the holder is decoding on: what the
        // lambda peeks at is only valid before the viewer takes the source. Everything that
        // touches this object's state happens on the main thread, below.
        val prepared = image() ?: return

        // On the viewer's scope, which PagerViewer.destroy() cancels. The page holder's own
        // scope is a MainScope that is never cancelled, so a job left there would outlive the
        // holder, the viewer, and the activity whose view it holds.
        scope.launch {
            if (detections.containsKey(page)) return@launch
            val entry = PageDetection(job = coroutineContext[Job])
            detections[page] = entry
            try {
                val found = withContext(detectionDispatcher) { detector.detect(prepared) }
                entry.bubbles = found
                deliver(page)
            } catch (cancelled: CancellationException) {
                // Not a failure, and rethrowing matters: onDetectionFailed is not narrowed to
                // Detecting, so reporting a cancellation would write Error over a live Ready
                // and silently end a walk already in progress.
                throw cancelled
            } catch (failure: Throwable) {
                // Throwable, because a real detector can fail with an Error - a native library
                // that will not load, an allocation that does not fit - and from a job with no
                // handler that is the process dying, with the feature on and only then. The
                // page is marked failed and the reader navigates as upstream does.
                entry.failure = failure
                deliver(page)
            }
        }
    }

    /**
     * The holder has an image that can be zoomed. Only now may this page's answer reach the
     * controller. Idempotent; the holder calls it whenever it re-applies its state.
     */
    fun pageReady(page: BubblePageKey) {
        if (!ready.add(page)) return
        deliver(page)
    }

    /**
     * The page has left the pager. Stop looking for its bubbles and forget its answer.
     *
     * Cancelling is best-effort by nature: a delay gives way at once, while a real model's
     * inference is a blocking native call that will finish whatever happens. What this actually
     * guarantees is that the answer is discarded, and that is the half that stays true in P9.
     */
    fun forget(page: BubblePageKey) {
        ready.remove(page)
        detections.remove(page)?.job?.cancel()
    }

    private fun deliver(page: BubblePageKey) {
        if (page !in ready) return
        val entry = detections[page] ?: return
        entry.bubbles?.let { controller.onBubblesReady(page, it) }
        entry.failure?.let { controller.onDetectionFailed(page, it) }
    }

    fun deactivate() = controller.deactivate()

    fun reset() = controller.reset()

    fun stepTowards(readingForward: Boolean): BubbleZoomStep = controller.stepTowards(readingForward)

    /**
     * Switching on mid-read has to give the walk a subject itself.
     *
     * `enable()` only reaches Idle, and the reader announces a page exactly once, when it
     * selects it — so without this the feature would come back on and then do nothing at all
     * until the next page turn. Idempotent, because `activatePage` ignores the page it already
     * holds, which is what lets this run on every emission rather than only on a real change.
     */
    private fun switchOn() {
        controller.enable()
        val page = currentPage() ?: return
        controller.activatePage(page, PageEntry.FORWARD)
        deliver(page)
    }
}

/**
 * The reader's way in: it holds [ReaderPreferences] and should not have to know which two of
 * them this feature reads. Keeping the class itself free of that type is what lets its whole
 * behaviour be pinned by JVM tests with a fake preference.
 */
fun BubbleZoomSession(
    preferences: ReaderPreferences,
    scope: CoroutineScope,
    context: Context,
    currentPage: () -> BubblePageKey?,
): BubbleZoomSession = BubbleZoomSession(
    enabled = preferences.bubbleZoom,
    debug = preferences.bubbleZoomDebug,
    scope = scope,
    // The real detector is the default; the fake stays one preference away so the two can
    // be compared on the same page. The model is opened lazily, by its own first call.
    detector = SwitchingBubbleDetector(
        useFake = preferences.bubbleZoomFakeDetector,
        fake = FakeBubbleDetector(),
        real = OnnxBubbleDetector(context.applicationContext),
    ),
    currentPage = currentPage,
)

/**
 * The state of the walk plus whether the detected regions should be drawn over the page.
 *
 * Carried together so a page holder collects one flow and applies one answer, instead of
 * reconciling two that can disagree for a frame.
 */
data class BubbleZoomPresentation(
    val state: BubbleZoomState,
    val showBoxes: Boolean,
)
