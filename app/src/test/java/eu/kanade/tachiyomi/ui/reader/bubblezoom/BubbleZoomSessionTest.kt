package eu.kanade.tachiyomi.ui.reader.bubblezoom

import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetectionImage
import eu.kanade.tachiyomi.ui.reader.bubblezoom.detector.BubbleDetector
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.io.ByteArrayInputStream

/**
 * The gate and the pipeline, from the outside.
 *
 * Every test here is a claim about what the reader sees rather than about how the session is
 * put together: that with the preference off the app is upstream Mihon, and that a page becomes
 * walkable only once a detector has answered for it AND its holder has an image to zoom.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleZoomSessionTest {

    private val page = BubblePageKey(Any())
    private val other = BubblePageKey(Any())

    private val found = listOf(
        BubbleDetection(BubbleBox(0.1f, 0.1f, 0.2f, 0.2f), 1f, BubbleType.TEXT),
        BubbleDetection(BubbleBox(0.5f, 0.5f, 0.2f, 0.2f), 1f, BubbleType.TEXT),
    )

    // --- the gate -------------------------------------------------------------------------

    @Test
    fun `both preferences are off until the reader turns them on`() {
        // The name of another test used to claim this and never check it: its fixture sets the
        // preference to false by hand, so it would have passed just as happily with a default
        // of true. The default lives in ReaderPreferences and nowhere else.
        val preferences = ReaderPreferences(InMemoryPreferenceStore())

        assertFalse(preferences.bubbleZoom.get())
        assertFalse(preferences.bubbleZoomDebug.get())
        assertFalse(preferences.bubbleZoomFakeDetector.get())
    }

    @Test
    fun `off means navigation never branches`() = sessionTest(enabled = false) { fixture ->
        // Exactly the sequence the reader performs on every page: announce it, hand over the
        // bytes, confirm the image, then navigate. Upstream Mihon has no branch here at all, so
        // every step has to come back IGNORED or the pan check in moveRight would be skipped.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.supply(page)
        fixture.ready(page)
        advanceUntilIdle()

        assertFalse(fixture.session.isEnabled)
        assertFalse(fixture.session.isFocused)
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = false))
    }

    @Test
    fun `off means the page's bytes are never even read`() = sessionTest(enabled = false) { fixture ->
        // Building the image means copying a page's bytes and reading its header, on the very
        // thread that is about to display it. Work that is switched off must not be started and
        // then discarded.
        fixture.supply(page)
        advanceUntilIdle()

        assertFalse(fixture.prepared, "the page was prepared for a detector that is switched off")
        assertEquals(0, fixture.detector.calls)
    }

    @Test
    fun `the gate is already shut before the preference flow has had a chance to speak`() = runTest {
        // The window this closes is real on a device and invisible under UnconfinedTestDispatcher,
        // which runs launchIn eagerly. A StandardTestDispatcher does not run it until the
        // scheduler is advanced, which is what the main looper does on the NEXT tick - and the
        // reader selects its first page inside that tick.
        val session = BubbleZoomSession(
            enabled = FakePreference("bubble_zoom", false),
            debug = FakePreference("bubble_zoom_debug", false),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            detector = RecordingDetector(),
            detectionDispatcher = StandardTestDispatcher(testScheduler),
        ) { null }

        session.activatePage(page, PageEntry.FORWARD)

        assertFalse(session.isEnabled, "the gate was open before the flow delivered anything")
        assertEquals(BubbleZoomStep.IGNORED, session.stepTowards(readingForward = true))
    }

    // --- the pipeline ---------------------------------------------------------------------

    @Test
    fun `a page is not walkable until the detector has answered for it`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)

        // Mid-detection. This is the phase's own promise: the page is on screen and a tap does
        // what it does in upstream Mihon, rather than freezing or being queued up to jump once
        // the answer lands.
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))

        advanceUntilIdle()

        assertEquals(BubbleZoomStep.CONSUMED, fixture.session.stepTowards(readingForward = true))
        assertTrue(fixture.session.isFocused)
    }

    @Test
    fun `the answer waits for the latency the detector declares`() = sessionTest(latencyMs = 300) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)

        advanceTimeBy(299)
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))

        advanceUntilIdle()
        assertEquals(BubbleZoomStep.CONSUMED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `however often the holder asks, a page is detected once`() = sessionTest { fixture ->
        // Unguarded, a page handed over twice would be inferred twice, and inference is the
        // expensive thing in this whole feature.
        repeat(4) { fixture.supply(page) }
        advanceUntilIdle()

        assertEquals(1, fixture.detector.calls)
    }

    @Test
    fun `an answer that arrives before the reader selects the page is not lost`() = sessionTest { fixture ->
        // The bytes and the selection arrive from different places in either order. A page that
        // decoded while the reader was still approaching has its answer ready before the
        // controller has heard of it, and onBubblesReady only ever answers a pending Detecting.
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        fixture.session.activatePage(page, PageEntry.FORWARD)

        assertEquals(BubbleZoomStep.CONSUMED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `an answer for a page whose image never became zoomable is never delivered`() = sessionTest { fixture ->
        // An animated page has no view a bubble could be zoomed in, and a page that failed to
        // decode has none either. Phase P6's critical fix was that such a page never swallows
        // taps; a real detector would happily find text in a GIF's first frame, so the holder's
        // word that it has a zoomable image is the only thing standing between the two.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.supply(page)
        advanceUntilIdle()

        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
        assertFalse(fixture.session.isFocused)
    }

    @Test
    fun `the holder's word arriving after the answer delivers it`() = sessionTest { fixture ->
        // The other order: detection finished first, the image became ready later. Neither
        // side may assume it is last.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.supply(page)
        advanceUntilIdle()
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))

        fixture.ready(page)

        assertEquals(BubbleZoomStep.CONSUMED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `forgetting a page discards the answer it was still waiting for`() = sessionTest(latencyMs = 300) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        fixture.session.forget(page)

        advanceUntilIdle()

        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `a detector that throws leaves navigation exactly as it was`() = sessionTest(
        failure = IllegalStateException("model exploded"),
    ) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        // Not a crash and not a freeze: the page simply has no bubbles and taps turn pages.
        // Without a failure path it would sit in Detecting forever, which on a device looks
        // like "taps do nothing on this page" with nothing in the log to say why.
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
        assertFalse(fixture.session.isFocused)
    }

    @Test
    fun `a detector that fails with an Error does not take the reader down with it`() = sessionTest(
        failure = OutOfMemoryError("no room for a page"),
    ) { fixture ->
        // A real detector can fail with an Error, not an Exception: the native library refusing
        // to load, an allocation that does not fit. From a job with no handler that is the
        // process dying - with the feature on, and only then. It has to land as a failed page.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `a detector that cannot work at all is not asked again, and no page is prepared for it`() = sessionTest(
        failure = IllegalStateException("no model"),
        availableAfterFailure = false,
    ) { fixture ->
        // The first page pays the one attempt that discovers the detector is broken. After
        // that a reader with a broken detector has to cost exactly what one with the feature
        // off costs: no bytes copied, no jobs launched.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.supply(page)
        advanceUntilIdle()
        assertEquals(1, fixture.detector.calls)

        fixture.preparations = 0
        fixture.supply(other)
        advanceUntilIdle()

        assertEquals(0, fixture.preparations, "a page was prepared for a detector known to be broken")
        assertEquals(1, fixture.detector.calls)
    }

    @Test
    fun `an answer for a page the reader never selected is refused`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(other)
        fixture.supply(other)
        advanceUntilIdle()

        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `detection is handed to the dispatcher it was given`() = sessionTest { fixture ->
        // A scheduler only controls work on its own dispatchers. If the session ignored the
        // parameter and reached for Dispatchers.Default, advanceUntilIdle would return before
        // the detector had run and this count would be zero.
        fixture.supply(page)
        advanceUntilIdle()

        assertEquals(1, fixture.detector.calls)
    }

    // --- the live toggle ------------------------------------------------------------------

    @Test
    fun `switching on mid-read lets the next page that arrives be walked`() = sessionTest(
        enabled = false,
    ) { fixture ->
        // Nothing was detected while off, by design: holding a page's bytes for a feature that
        // is not running would be exactly the kind of cost "off is vanilla" forbids.
        fixture.preference.set(true)
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertEquals(BubbleZoomStep.CONSUMED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `switching off mid-walk gives navigation straight back to the reader`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()
        fixture.session.stepTowards(readingForward = true)
        assertTrue(fixture.session.isFocused)

        fixture.preference.set(false)

        assertFalse(fixture.session.isFocused)
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
    }

    // --- the double tap and the card's bytes (amendment E2) --------------------------------

    private val BubbleZoomSession.focusedIndex: Int
        get() = (presentation.value.state as BubbleZoomState.Ready).focusedIndex

    @Test
    fun `a double tap on a detected balloon lifts it and one on empty art falls through`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        // Empty art: the library's own double tap zoom, untouched.
        assertFalse(fixture.session.onDoubleTap(page, 0.9f, 0.9f))
        assertFalse(fixture.session.isFocused)

        assertTrue(fixture.session.onDoubleTap(page, 0.55f, 0.55f))
        assertEquals(1, fixture.session.focusedIndex)
    }

    @Test
    fun `a double tap before the detector has answered is not Bubble Zoom's`() = sessionTest(
        latencyMs = 300,
    ) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)

        assertFalse(fixture.session.onDoubleTap(page, 0.15f, 0.15f))

        advanceUntilIdle()
        assertTrue(fixture.session.onDoubleTap(page, 0.15f, 0.15f))
    }

    @Test
    fun `with a card up, a double tap jumps to another balloon and puts the card down otherwise`() =
        sessionTest { fixture ->
            fixture.session.activatePage(page, PageEntry.FORWARD)
            fixture.ready(page)
            fixture.supply(page)
            advanceUntilIdle()
            assertTrue(fixture.session.onDoubleTap(page, 0.15f, 0.15f))
            assertEquals(0, fixture.session.focusedIndex)

            assertTrue(fixture.session.onDoubleTap(page, 0.55f, 0.55f))
            assertEquals(1, fixture.session.focusedIndex)

            // On the lifted balloon itself: down, and consumed, so the page does not zoom.
            assertTrue(fixture.session.onDoubleTap(page, 0.55f, 0.55f))
            assertFalse(fixture.session.isFocused)

            assertTrue(fixture.session.onDoubleTap(page, 0.15f, 0.15f))
            // On empty art with a card up: down, and consumed.
            assertTrue(fixture.session.onDoubleTap(page, 0.9f, 0.9f))
            assertFalse(fixture.session.isFocused)
        }

    @Test
    fun `a double tap on a page that is not the subject is not Bubble Zoom's`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertFalse(fixture.session.onDoubleTap(other, 0.15f, 0.15f))
        assertFalse(fixture.session.isFocused)
    }

    @Test
    fun `off means a double tap is never Bubble Zoom's`() = sessionTest(enabled = false) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertFalse(fixture.session.onDoubleTap(page, 0.15f, 0.15f))
        assertFalse(fixture.session.isFocusedOn(page))
        assertEquals(null, fixture.session.imageFor(page))
    }

    @Test
    fun `the page's bytes stay with the session for the card until the page is forgotten`() = sessionTest { fixture ->
        assertEquals(null, fixture.session.imageFor(page))
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        val image = fixture.session.imageFor(page)
        assertEquals(1600, image?.width)
        assertFalse(fixture.session.isFocusedOn(page))

        fixture.session.onDoubleTap(page, 0.15f, 0.15f)
        assertTrue(fixture.session.isFocusedOn(page))
        assertFalse(fixture.session.isFocusedOn(other))

        fixture.session.forget(page)
        assertEquals(null, fixture.session.imageFor(page))
    }

    @Test
    fun `with crop borders on, no page is prepared or detected`() = sessionTest(cropBorders = true) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertFalse(fixture.prepared, "the page was prepared although its frame is not the file's")
        assertEquals(0, fixture.detector.calls)
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
    }

    @Test
    fun `a holder that detaches before the detection is registered leaves nothing behind`() = runTest {
        // The holder prepares the bytes on its decoding thread and the session registers them;
        // the reader can fling past the page before a coroutine dispatched to the main thread
        // ever starts. Standard, not Unconfined, so that window is a thing this test can stand
        // in. Registering only inside the coroutine used to leave the page's bytes - megabytes
        // of them, since the card reads from them - in the session until the reader closed.
        val detector = RecordingDetector(found, latencyMs = 300)
        val session = BubbleZoomSession(
            enabled = FakePreference("bubble_zoom", true),
            debug = FakePreference("bubble_zoom_debug", false),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            detector = detector,
            detectionDispatcher = StandardTestDispatcher(testScheduler),
        ) { null }

        var prepared = 0
        session.detect(page) {
            prepared++
            BubbleDetectionImage(1600, 2400) { ByteArrayInputStream(ByteArray(0)) }
        }
        assertEquals(1, prepared)
        assertEquals(1600, session.imageFor(page)?.width, "the bytes are registered before the coroutine runs")

        session.forget(page)
        advanceUntilIdle()

        assertEquals(null, session.imageFor(page), "the page's bytes outlived its holder")
    }

    @Test
    fun `forgetting the page that is the subject gives up the subject with it`() = sessionTest { fixture ->
        // Every holder is rebuilt when the reader changes a setting that touches the image -
        // crop borders, rotate to fit - without the reader ever turning a page. The boxes
        // describe bytes that are about to be replaced, so the page stops being the subject
        // rather than keeping them.
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()
        fixture.session.onDoubleTap(page, 0.15f, 0.15f)
        assertTrue(fixture.session.isFocusedOn(page))

        fixture.session.forget(page)

        assertFalse(fixture.session.isFocusedOn(page))
        assertFalse(fixture.session.onDoubleTap(page, 0.15f, 0.15f), "stale boxes still answered a double tap")
    }

    @Test
    fun `forgetting a page the reader has moved on from leaves the new subject alone`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()
        fixture.session.activatePage(other, PageEntry.FORWARD)
        fixture.ready(other)
        fixture.supply(other)
        advanceUntilIdle()
        fixture.session.onDoubleTap(other, 0.15f, 0.15f)

        // The ordinary page turn: the page left behind is detached a moment later.
        fixture.session.forget(page)

        assertTrue(fixture.session.isFocusedOn(other), "the page the reader is on lost its bubble")
    }

    // --- fetching ahead of the reader -------------------------------------------------------

    private val prefetchKeys = mutableListOf<BubblePageKey>()

    /** A page the reader has not reached, with bytes ready the way the viewer supplies them. */
    private fun aheadPage(): PrefetchPage {
        val key = BubblePageKey(Any())
        prefetchKeys += key
        return PrefetchPage(key) { BubbleDetectionImage(1600, 2400) { ByteArrayInputStream(ByteArray(0)) } }
    }

    @Test
    fun `pages fetched ahead are detected without a holder ever asking`() = sessionTest { fixture ->
        // The whole point: a page the reader has not reached is looked at anyway, so that by
        // the time they turn to it the answer is already there.
        val ahead = List(3) { aheadPage() }

        fixture.session.prefetch(ahead)
        advanceUntilIdle()

        assertEquals(3, fixture.detector.calls)
        ahead.forEach { assertTrue(fixture.session.imageFor(it.key) != null) }
    }

    @Test
    fun `a page already known is not fetched again`() = sessionTest { fixture ->
        val page = aheadPage()
        fixture.session.prefetch(listOf(page))
        advanceUntilIdle()

        fixture.session.prefetch(listOf(page, page))
        advanceUntilIdle()

        assertEquals(1, fixture.detector.calls)
    }

    @Test
    fun `off means nothing is fetched ahead, and no page's bytes are read`() =
        sessionTest(enabled = false) { fixture ->
            fixture.session.prefetch(List(3) { aheadPage() })
            advanceUntilIdle()

            assertEquals(0, fixture.detector.calls)
        }

    @Test
    fun `with crop borders on nothing is fetched ahead either`() = sessionTest(cropBorders = true) { fixture ->
        fixture.session.prefetch(List(3) { aheadPage() })
        advanceUntilIdle()

        assertEquals(0, fixture.detector.calls)
    }

    @Test
    fun `fetching ahead forever does not keep every page forever`() = sessionTest { fixture ->
        // Nothing forgets a page the reader never reached, and each one holds that page's
        // encoded bytes, so without this a long chapter accumulates all of it.
        val walked = mutableListOf<PrefetchPage>()
        repeat(6) {
            val ahead = List(3) { aheadPage() }
            walked += ahead
            fixture.session.prefetch(ahead)
            advanceUntilIdle()
        }

        val kept = walked.count { fixture.session.imageFor(it.key) != null }
        assertEquals(3, kept, "only the pages still ahead of the reader should be kept")
    }

    @Test
    fun `the page about to be reached is never the one dropped`() = sessionTest { fixture ->
        // The bug this replaced: dropping the OLDEST entry when the map grew meant dropping
        // the page queued longest ago, which is the nearest page ahead — the very one the
        // reader is about to need. It cancelled exactly the work it was added to do, and the
        // measured wait per page did not move.
        val near = aheadPage()
        val far = List(2) { aheadPage() }
        fixture.session.prefetch(listOf(near) + far)
        advanceUntilIdle()

        // One page on: the nearest is still ahead, so it must survive, and so must its answer.
        val newest = aheadPage()
        fixture.session.prefetch(listOf(near) + far.drop(1) + listOf(newest))
        advanceUntilIdle()

        assertTrue(fixture.session.imageFor(near.key) != null, "the next page was thrown away")
        assertEquals(null, fixture.session.imageFor(far.first().key), "a page left behind was kept")
    }

    @Test
    fun `the page the reader is on survives the eviction that its neighbours do not`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        // Many pages fetched ahead, none of which is the page being read.
        repeat(BubbleZoomConfig.PREFETCH_PAGES * 4) {
            fixture.session.prefetch(listOf(aheadPage()))
            advanceUntilIdle()
        }

        assertTrue(
            fixture.session.imageFor(page) != null,
            "the page being read was evicted in favour of pages ahead of it",
        )
    }

    // --- what survives the phone being turned ----------------------------------------------

    @Test
    fun `a page detected before a rotation is not detected again after it`() = runTest {
        // Rotation destroys the activity, the viewer and the session with it. The store belongs
        // to the reader's view model, which it does not destroy, and the page objects come back
        // from there too - so the new session asks about the same key and gets an answer.
        val store = BubbleZoomStore()
        val before = fixture(store = store)
        before.session.activatePage(page, PageEntry.FORWARD)
        before.ready(page)
        before.supply(page)
        advanceUntilIdle()
        assertEquals(1, before.detector.calls)

        val after = fixture(store = store)
        after.session.activatePage(page, PageEntry.FORWARD)
        after.ready(page)
        after.supply(page)
        advanceUntilIdle()

        assertEquals(0, after.detector.calls, "the model ran again for a page already answered")
        assertTrue(after.session.hasBubbles(page))
        assertEquals(found.size, after.session.debugFor(page)?.bubbles)
        assertTrue(after.session.debugFor(page)!!.remembered, "and the panel says where it came from")
    }

    @Test
    fun `what another detector found is not served as this one's answer`() = runTest {
        // The fake detector is one preference away, to be compared against the real one on the
        // same page. An answer from the other one would make them indistinguishable.
        val store = BubbleZoomStore()
        store.remember(page, modelVersion = 999, bubbles = found, elapsedMs = 5)

        val fixture = fixture(store = store)
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        assertEquals(1, fixture.detector.calls, "it had to ask its own detector")
    }

    // --- what the instrument panel is told --------------------------------------------------

    @Test
    fun `the panel reports the page the reader is on, its timing and its model`() = sessionTest(
        latencyMs = 300,
    ) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)

        // While the model is still running there is a page but no answer yet.
        val running = fixture.session.debugFor(page)
        assertEquals(0, running?.bubbles)
        assertEquals(null, running?.detectionMs)
        assertEquals("detecting…", running?.summary)

        advanceUntilIdle()

        val done = fixture.session.debugFor(page)
        assertEquals(found.size, done?.bubbles)
        assertEquals(7, done?.modelVersion, "the fake's version, so it can never pass for the model")
        assertTrue((done?.detectionMs ?: -1) >= 0)
        assertFalse(done!!.prefetched)
        assertEquals(-1, done.focusedIndex)
    }

    @Test
    fun `the panel follows the walk`() = sessionTest { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        fixture.session.onDoubleTap(page, 0.55f, 0.55f)

        val info = fixture.session.debugFor(page)!!
        assertEquals(1, info.focusedIndex)
        assertEquals(found[1].confidence, info.focusedConfidence)
        assertEquals("2/2 · 100%", info.focus)
    }

    @Test
    fun `a page fetched ahead says so, which is why it was instant`() = sessionTest { fixture ->
        val ahead = aheadPage()
        fixture.session.prefetch(listOf(ahead))
        advanceUntilIdle()

        assertTrue(fixture.session.debugFor(ahead.key)!!.prefetched)
    }

    @Test
    fun `a failure is reported as one, not as a page with no balloons`() = sessionTest(
        failure = java.io.IOException("no model"),
    ) { fixture ->
        fixture.session.activatePage(page, PageEntry.FORWARD)
        fixture.ready(page)
        fixture.supply(page)
        advanceUntilIdle()

        val info = fixture.session.debugFor(page)!!
        assertEquals("IOException", info.failure)
        assertEquals("failed: IOException", info.summary)
    }

    @Test
    fun `a page nothing is known about has no panel at all`() = sessionTest { fixture ->
        assertEquals(null, fixture.session.debugFor(other))
    }

    // --- the debug boxes ------------------------------------------------------------------

    @Test
    fun `the debug boxes are forced off whenever the feature is`() = runTest {
        val session = BubbleZoomSession(
            enabled = FakePreference("bubble_zoom", false),
            debug = FakePreference("bubble_zoom_debug", true),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { null }

        val presentation = session.presentation.first()

        assertTrue(presentation.state is BubbleZoomState.Disabled)
        assertFalse(presentation.showBoxes, "a holder was asked to draw boxes for a feature that is off")
    }

    @Test
    fun `the debug boxes follow their own preference while the feature is on`() = runTest {
        val debug = FakePreference("bubble_zoom_debug", false)
        val session = BubbleZoomSession(
            enabled = FakePreference("bubble_zoom", true),
            debug = debug,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        ) { null }

        assertFalse(session.presentation.first().showBoxes)

        debug.set(true)
        assertTrue(session.presentation.first().showBoxes)
    }

    // --- harness -------------------------------------------------------------------------

    private class Fixture(
        val session: BubbleZoomSession,
        val detector: RecordingDetector,
        val preference: FakePreference<Boolean>,
    ) {
        var preparations = 0
        val prepared: Boolean get() = preparations > 0

        /** Stands in for the page holder handing over the bytes it is about to display. */
        fun supply(key: BubblePageKey) = session.detect(key) {
            preparations++
            BubbleDetectionImage(1600, 2400) { ByteArrayInputStream(ByteArray(0)) }
        }

        /** Stands in for the page holder confirming it has a ready, zoomable image. */
        fun ready(key: BubblePageKey) = session.pageReady(key)
    }

    private fun sessionTest(
        enabled: Boolean = true,
        latencyMs: Long = 0,
        failure: Throwable? = null,
        availableAfterFailure: Boolean = true,
        cropBorders: Boolean = false,
        store: BubbleZoomStore = BubbleZoomStore(),
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        body(fixture(enabled, latencyMs, failure, availableAfterFailure, cropBorders, store))
    }

    /** One session with its own detector, so a test can build a second over the same store. */
    private fun TestScope.fixture(
        enabled: Boolean = true,
        latencyMs: Long = 0,
        failure: Throwable? = null,
        availableAfterFailure: Boolean = true,
        cropBorders: Boolean = false,
        store: BubbleZoomStore = BubbleZoomStore(),
    ): Fixture {
        val preference = FakePreference("bubble_zoom", enabled)
        val detector = RecordingDetector(found, latencyMs, failure, availableAfterFailure)
        val session = BubbleZoomSession(
            enabled = preference,
            debug = FakePreference("bubble_zoom_debug", false),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            detector = detector,
            // Standard rather than Unconfined, so latency and cancellation are things this test
            // steps through rather than things that have already happened by the time it looks.
            detectionDispatcher = StandardTestDispatcher(testScheduler),
            cropBorders = { cropBorders },
            currentPage = { null },
            store = store,
        )
        return Fixture(session, detector, preference)
    }

    private class RecordingDetector(
        private val bubbles: List<BubbleDetection> = emptyList(),
        private val latencyMs: Long = 0,
        private val failure: Throwable? = null,
        private val availableAfterFailure: Boolean = true,
    ) : BubbleDetector {
        var calls = 0
            private set
        private var broken = false

        override val modelVersion: Int = 7
        override val available: Boolean get() = !broken

        override suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection> {
            calls++
            if (latencyMs > 0) delay(latencyMs)
            failure?.let {
                if (!availableAfterFailure) broken = true
                throw it
            }
            return bubbles
        }
    }

    /**
     * What `InMemoryPreferenceStore` cannot be: its `changes()` is `flow { data }`, which never
     * emits, so nothing driven by a preference change can be tested through it.
     */
    private class FakePreference<T : Any>(
        private val key: String,
        private val default: T,
    ) : Preference<T> {
        private val flow = MutableStateFlow(default)

        override fun key(): String = key
        override fun get(): T = flow.value
        override fun set(value: T) {
            flow.value = value
        }
        override fun isSet(): Boolean = flow.value != default
        override fun delete() {
            flow.value = default
        }
        override fun defaultValue(): T = default
        override fun changes(): Flow<T> = flow
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = flow
    }
}
