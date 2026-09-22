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
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import java.util.Collections
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
    /** The viewer's scope. Also where the presenter's card decodes run, so they die with it. */
    internal val scope: CoroutineScope,
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
     * Whether the reader is cropping page borders. With that preference on the image view's
     * frame is the cropped page while every box is a fraction of the file, so nothing here
     * would line up; the feature simply does not look at those pages.
     */
    private val cropBorders: () -> Boolean = { false },
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
    private val detections: MutableMap<BubblePageKey, PageDetection> =
        Collections.synchronizedMap(LinkedHashMap())

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

    private class PageDetection(
        /**
         * Kept after inference, for the card: it is the balloon's only source of native pixels
         * that matches what is on screen. A few megabytes per live page, released in [forget].
         */
        val image: BubbleDetectionImage,
        var job: Job? = null,
        /** Fetched before the reader reached the page, so nothing will forget it for us. */
        val prefetched: Boolean = false,
    ) {
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

    /** Whether a bubble of [page] is lifted right now — the gestures' "a card is up" question. */
    fun isFocusedOn(page: BubblePageKey): Boolean {
        val ready = controller.state.value as? BubbleZoomState.Ready ?: return false
        return ready.page == page && ready.focusedIndex >= 0
    }

    /** Whether [page] is the subject, detected, and has at least one bubble to walk. */
    fun hasBubbles(page: BubblePageKey): Boolean {
        val ready = controller.state.value as? BubbleZoomState.Ready ?: return false
        return ready.page == page && ready.bubbles.isNotEmpty()
    }

    /** The bytes [page] is displayed from, while its holder lives. Null before detection. */
    fun imageFor(page: BubblePageKey): BubbleDetectionImage? = detections[page]?.image

    /**
     * A double tap at the normalized point ([nx], [ny]) of [page]. Returns whether it was Bubble
     * Zoom's, in which case the caller keeps it from the image view; otherwise it is the
     * library's own double tap zoom, exactly as upstream.
     *
     * Amendment E2's rule. With no card up, a tap on a detected balloon lifts it and a tap
     * anywhere else falls through. With a card up, a tap on another balloon jumps to it, and
     * a tap on the lifted balloon or on empty art puts the card down and is consumed — so no
     * zoom of the page is ever born from this feature. Before detection has answered, or with
     * the feature off, the state is not Ready and nothing here applies.
     */
    fun onDoubleTap(page: BubblePageKey, nx: Float, ny: Float): Boolean {
        val ready = controller.state.value as? BubbleZoomState.Ready ?: return false
        if (ready.page != page) return false
        val hit = BubbleHitTest.find(ready.bubbles, nx, ny, BubbleZoomConfig.DOUBLE_TAP_HIT_TOLERANCE)
        if (ready.focusedIndex < 0) {
            return hit >= 0 && controller.selectBubble(hit)
        }
        if (hit >= 0 && hit != ready.focusedIndex) return controller.selectBubble(hit)
        controller.reset()
        return true
    }

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
        if (!isEnabled || !detector.available || cropBorders()) return
        // Read here, synchronously, on whatever thread the holder is decoding on: what the
        // lambda peeks at is only valid before the viewer takes the source. Everything that
        // touches this object's state happens on the main thread, below.
        val prepared = image() ?: return

        // Registered here, on the decoding thread, and not inside the coroutine below. The
        // holder can detach before a coroutine dispatched to the main thread ever starts, and
        // a `forget` that arrives in that window used to find nothing to remove — leaving the
        // page's bytes, now megabytes of them, in this map until the reader closed. Atomic
        // because this is the one method not confined to the main thread, and it doubles as
        // the idempotency guard: the holder calls it from a collector that runs on every step.
        start(page, prepared, prefetched = false)
    }

    private fun start(page: BubblePageKey, prepared: BubbleDetectionImage, prefetched: Boolean) {
        val entry = PageDetection(prepared, prefetched = prefetched)
        if (detections.putIfAbsent(page, entry) != null) return

        // On the viewer's scope, which PagerViewer.destroy() cancels. The page holder's own
        // scope is a MainScope that is never cancelled, so a job left there would outlive the
        // holder, the viewer, and the activity whose view it holds.
        scope.launch {
            entry.job = coroutineContext[Job]
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
     * Starts detection for pages the reader has not reached, from their own byte streams.
     *
     * The lead a page holder gives is only however long the reader spends on the page before,
     * and a segmentation model takes seconds — measured on the device, 1.2 to 2.3 seconds of
     * waiting per page for anyone turning briskly. These are queued AFTER whatever the holders
     * asked for, which is the order that matters: the page about to be shown was enqueued when
     * its holder was built, so it stays at the head and a page further off never overtakes it.
     *
     * Cheap when there is nothing to do: a page already known is skipped without reading a
     * byte, and [BubbleZoomPrefetch] hands back nothing at all when the reader's settings mean
     * the bytes on disk are not the bytes on screen.
     */
    fun prefetch(pages: List<PrefetchPage>) {
        if (!isEnabled || !detector.available || cropBorders()) return
        var started = 0
        var known = 0
        var unreadable = 0
        for (page in pages) {
            // Checked before the bytes are read: reading them means copying a page, and a page
            // already known needs nothing.
            if (detections.containsKey(page.key)) {
                known++
                continue
            }
            val prepared = page.image()
            if (prepared == null) {
                unreadable++
                continue
            }
            start(page.key, prepared, prefetched = true)
            started++
        }
        // Said out loud because this feature is invisible when it works and invisible when it
        // does not: nothing on screen distinguishes "fetched three pages ahead" from "found
        // nothing to fetch", and the difference is seconds of waiting per page.
        logcat(LogPriority.INFO) {
            "BubbleZoom prefetch offered=${pages.size} started=$started known=$known unreadable=$unreadable"
        }
        dropPagesNoLongerAhead(pages.mapTo(HashSet()) { it.key })
    }

    /**
     * Forgets pages fetched ahead that the reader is no longer heading towards.
     *
     * Nothing else will: a holder drops the page it was built for, and a page the reader never
     * reached never had one. Each entry keeps that page's encoded bytes, because the card reads
     * from them, so without this a long chapter accumulates every page it ever looked at.
     *
     * By POSITION and not by age, which is the whole point. An earlier version dropped the
     * oldest entry once the map grew past a bound, and the oldest entry is the page queued
     * longest ago — which is the NEAREST page ahead, the one about to be needed. It cancelled
     * exactly the work it had been added to do, and the measured wait per page did not move.
     */
    private fun dropPagesNoLongerAhead(ahead: Set<BubblePageKey>) {
        val subject = (controller.state.value as? BubbleZoomState.OnPage)?.page
        val stale = synchronized(detections) {
            detections.entries
                .filter { it.value.prefetched && it.key !in ahead && it.key != subject && it.key !in ready }
                .map { it.key }
        }
        stale.forEach { detections.remove(it)?.job?.cancel() }
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
        // The holder for the subject itself is going away, which happens when the reader
        // changes a setting that rebuilds every page rather than when it turns a page. Its
        // bubbles were fractions of bytes that are about to be replaced, so the page stops
        // being the subject instead of keeping boxes that may no longer describe it.
        if ((controller.state.value as? BubbleZoomState.OnPage)?.page == page) controller.deactivate()
    }

    private fun deliver(page: BubblePageKey) {
        if (page !in ready) return
        val entry = detections[page] ?: return
        val before = controller.state.value
        entry.bubbles?.let { controller.onBubblesReady(page, it) }
        entry.failure?.let { controller.onDetectionFailed(page, it) }
        // The one moment the reader can see: this page now has its balloons. Timed against the
        // reader's own "page selected" line, it says whether the answer was waiting when they
        // arrived or whether they waited for it — which a screenshot cannot say, because
        // grabbing one takes about a second and every wait shorter than that looks the same.
        val after = controller.state.value
        if (after !== before && after is BubbleZoomState.Ready && after.page == page) {
            logcat(LogPriority.INFO) { "BubbleZoom page ready with ${after.bubbles.size} bubbles" }
        }
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
    cropBorders = { preferences.cropBorders.get() },
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
