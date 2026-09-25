package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.coroutineContext

/**
 * The page's identity as Bubble Zoom sees it.
 *
 * Identity of the object, never a value: `InsertPage(parent)` is built with `parent.index`
 * verbatim, so a split double page and its own second half would share any value-based key.
 */
fun ReaderPage.bubblePageKey(): BubblePageKey = BubblePageKey(this)

/**
 * Turns the session's answer for one page into what that page shows, and owns every view the
 * feature puts on screen.
 *
 * One presentation. The focused bubble is lifted as a card and the page underneath never moves.
 * Amendment E2 removed the alternative — the phase P4 camera, which zoomed the page itself
 * whenever the card could not be built — so a bubble the card refuses is a silent stop in the
 * walk: nothing is shown, the focus stays where it is, and the next step moves past it.
 *
 * The card is built twice. `show` puts an instant replay of the view's tiles on screen; then
 * this class decodes the balloon's own pixels from the bytes the session kept for the page and
 * hands them to the same card through `upgrade`. The decode runs on the session's scope, which
 * the viewer cancels — never on the holder's, a MainScope nobody cancels — and is cancelled
 * again here whenever the card it was for goes. A result that lands late is checked against the
 * card it was for before it is used.
 *
 * The views are created here rather than by the page holder so that the holder never has to
 * remember to take them away again. With the feature off nothing is added to the view tree at
 * all, and switching it off while a page is being read removes what is there.
 *
 * One instance per page holder.
 */
class BubbleZoomPagePresenter(private val session: BubbleZoomSession) {

    /** Set while the card exists, so a page move made by the reader can drop a stale capture. */
    private var card: BubbleZoomOverlayView? = null

    /** The debug boxes, present only while the debug preference is on. */
    private var boxes: BubbleZoomDebugOverlay? = null

    /** What the last [apply] was told, so [isHoldingThePage] can answer without asking again. */
    private var enabled = false

    /**
     * The bubble the page was last moved for, so a balloon the view cannot bring fully into
     * view is tried once and then shown where it is, rather than panned at on every re-apply.
     */
    private var panned: BubbleDetection? = null

    /** The bubble the card shows, so a re-apply of the same state does not rebuild the card. */
    private var shown: BubbleDetection? = null

    /** The sharp decode for the current card, while it is still running. */
    private var decode: Job? = null

    /**
     * One decode at a time for this page.
     *
     * Cancelling a decode does not stop it: the work is a blocking native call with nowhere to
     * suspend. Without this, a walk held on the volume key starts one full region decode per
     * step and they pile up on an unbounded IO pool, each holding a native copy of the page.
     * With it, the next decode waits, and finds the job already cancelled when its turn comes.
     */
    private val decodeLock = Mutex()

    fun apply(
        host: ViewGroup,
        imageView: SubsamplingScaleImageView?,
        presentation: BubbleZoomPresentation,
        page: BubblePageKey,
    ) {
        // A page whose detection failed will never have a bubble to show, and the brief asks
        // that a missing model read exactly as the feature being off. So for THAT page, it is:
        // no card in the view tree, no boxes, and no veto on the reader's own re-framing.
        val state = presentation.state
        val dead = state is BubbleZoomState.Error && state.page == page
        enabled = state !is BubbleZoomState.Disabled && !dead
        if (!enabled) {
            switchOff(host)
            return
        }
        if (imageView == null || !imageView.isReady) return

        ensureCard(host, imageView)
        // The overlay draws what the controller holds for THIS page - the real detections -
        // and nothing when the page is not the subject or has not been detected yet.
        val detected = (state as? BubbleZoomState.Ready)
            ?.takeIf { it.page == page }
            ?.bubbles
            ?.map { it.box }
            .orEmpty()
        applyBoxes(host, imageView, presentation.showBoxes, detected, page)
        present(page, presentation.state.focusFor(page))
    }

    /**
     * Whether the page must not be moved out from under the feature: a card is lifted from a
     * frozen capture. Asked by the reader's delayed landscape re-frame, and a plain false with
     * the feature off, so that re-frame behaves exactly as upstream Mihon's does.
     */
    fun isHoldingThePage(): Boolean = enabled && card?.isShowing == true

    /** Whether a point in the holder's coordinates falls on the lifted card. */
    fun cardContains(x: Float, y: Float): Boolean = card?.cardContains(x, y) == true

    /** The boxes are drawn in view space, so they are stale the moment the image moves. */
    fun invalidateBoxes() {
        boxes?.invalidate()
    }

    /**
     * The reader moved the page themselves, so a frozen card no longer matches what is under it.
     * The card goes. The caller is expected to reset the session as well, so that what the model
     * believes and what is on screen do not drift apart.
     */
    fun onPageMovedByUser() {
        cancelDecode()
        shown = null
        card?.onPageMoved()
    }

    /** The holder is going away. Everything goes with it, with no animation. */
    fun release(host: ViewGroup) {
        cancelDecode()
        shown = null
        card?.clear()
        detachViews(host)
        enabled = false
    }

    private fun present(page: BubblePageKey, focus: BubbleDetection?) {
        if (focus == null) {
            cancelDecode()
            shown = null
            panned = null
            card?.dismiss()
            return
        }
        // The collector re-applies on every emission and again when the image finishes loading.
        // The same bubble twice is not a new card, and must not be a second decode either.
        if (focus == shown && card?.isShowing == true) return
        cancelDecode()

        // A spread is read zoomed, so the balloon may be off screen entirely. Bring it in first
        // and come back: a card lifted from tiles the view is not drawing is a card of nothing.
        if (focus != panned) {
            panned = focus
            val moving = card?.bringIntoView(focus) { present(page, focus) } == true
            if (moving) return
        }

        val token = card?.show(focus)
        if (token == null) {
            // Nothing to show, and since E2 there is no second path. The focus is left where it
            // is on purpose: un-focusing here would put the walk back at the resting page, the
            // next step would select this same bubble, be refused again, and consume the event
            // — a page that can never be turned by tap, volume key or swipe. Leaving it focused
            // costs one silent stop instead, and the next step moves past it.
            shown = null
            card?.dismiss()
            logcat(LogPriority.WARN) { "BubbleZoom card refused for ${focus.box}" }
            return
        }
        shown = focus
        sharpen(page, token)
    }

    /** Replaces the card's replayed tiles with the balloon decoded from the page's own bytes. */
    private fun sharpen(page: BubblePageKey, token: CardToken) {
        val image = session.imageFor(page) ?: return
        val region = BubbleCardGeometry.regionOf(token.sourceBox, image.width, image.height) ?: return
        val sample = BubbleCardGeometry.sampleSize(region, token.widthPx.toFloat(), token.heightPx.toFloat())
        decode = session.scope.launch {
            // Written from inside the IO block rather than returned from it: a cancelled
            // `withContext` discards its result, and a bitmap discarded that way is never
            // recycled. Handing it out through a holder means the `finally` below always sees
            // it, whether this coroutine finished or was cancelled on the way back.
            val decoded = arrayOfNulls<Bitmap>(1)
            try {
                val started = SystemClock.elapsedRealtime()
                decodeLock.withLock {
                    // Its turn came after the card it was for had already gone.
                    coroutineContext.ensureActive()
                    withContext(Dispatchers.IO) { decoded[0] = BubbleCardDecoder.decode(image, region, sample) }
                }
                val sharp = decoded[0]
                if (sharp == null) {
                    this@BubbleZoomPagePresenter.logcat(LogPriority.WARN) {
                        "BubbleZoom card decode refused for ${region.width}x${region.height}/$sample"
                    }
                    return@launch
                }
                this@BubbleZoomPagePresenter.logcat(LogPriority.INFO) {
                    "BubbleZoom card ${region.width}x${region.height}/$sample -> ${sharp.width}x${sharp.height}" +
                        " in ${SystemClock.elapsedRealtime() - started}ms"
                }
                if (card?.upgrade(token, sharp) == true) decoded[0] = null
            } finally {
                decoded[0]?.recycle()
            }
        }
    }

    private fun cancelDecode() {
        decode?.cancel()
        decode = null
    }

    private fun switchOff(host: ViewGroup) {
        cancelDecode()
        shown = null
        card?.clear()
        detachViews(host)
    }

    private fun detachViews(host: ViewGroup) {
        card?.let(host::removeView)
        card = null
        BubbleZoomDebugOverlay.detach(host, boxes)
        boxes = null
    }

    private fun ensureCard(host: ViewGroup, imageView: SubsamplingScaleImageView) {
        if (card != null) return
        card = BubbleZoomOverlayView(host.context, imageView, session.cardSize).also {
            host.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun applyBoxes(
        host: ViewGroup,
        imageView: SubsamplingScaleImageView,
        show: Boolean,
        detected: List<BubbleBox>,
        page: BubblePageKey,
    ) {
        if (!show) {
            BubbleZoomDebugOverlay.detach(host, boxes)
            boxes = null
            return
        }
        val overlay = boxes ?: BubbleZoomDebugOverlay.attach(host, imageView).also { boxes = it }
        overlay.boxes = detected
        overlay.info = session.debugFor(page)
    }
}
