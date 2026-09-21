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

        // Not ENDED_WALK: that return value tells moveRight to skip its pan check for one
        // event, which would be a divergence from upstream on the very first tap after the
        // reader switched the feature off.
        assertFalse(fixture.session.isFocused)
        assertEquals(BubbleZoomStep.IGNORED, fixture.session.stepTowards(readingForward = true))
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
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
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
        ) { null }
        body(Fixture(session, detector, preference))
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
