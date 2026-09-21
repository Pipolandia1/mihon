package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.graphics.PointF
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

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
 * Two presentations, one decision per bubble. The preferred one lifts the bubble as a card and
 * leaves the page where it is; when the card cannot be built — an animated page, an image not
 * ready, a bubble partly off screen, a magnification outside the band where a frozen capture
 * still adds detail — the phase P4 camera move takes over instead. The reader sees one or the
 * other, never neither, and never both.
 *
 * The views are created here rather than by the page holder so that the holder never has to
 * remember to take them away again. With the feature off nothing is added to the view tree at
 * all, and switching it off while a page is being read removes what is there and gives the page
 * its camera back — which is the part that is easy to forget, because a card that is merely
 * cleared leaves a page still zoomed on a bubble nobody can see any more.
 *
 * One instance per page holder. It remembers the scale and centre from before the first camera
 * move of a walk, so the page returns to exactly where the reader left it rather than to a
 * recomputed "default" that Mihon does not actually use.
 */
class BubbleZoomPagePresenter {

    private var savedScale: Float? = null
    private var savedCenter: PointF? = null

    /** Set while the card exists, so a page move made by the reader can drop a stale capture. */
    private var card: BubbleZoomOverlayView? = null

    /** The debug boxes, present only while the debug preference is on. */
    private var boxes: BubbleZoomDebugOverlay? = null

    /** What the last [apply] was told, so [isHoldingThePage] can answer without asking again. */
    private var enabled = false

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
            switchOff(host, imageView)
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
        applyBoxes(host, imageView, presentation.showBoxes, detected)
        present(imageView, presentation.state.focusFor(page))
    }

    /**
     * Whether the page must not be moved out from under the feature.
     *
     * Two ways that can be true: a card is lifted from a frozen capture, or the P4 camera has
     * framed a bubble and the page is no longer at its fit scale. Both are asked as one question
     * so that the reader's delayed landscape re-frame has a single thing to consult — and so
     * that with the feature off the answer is a plain false and the re-frame behaves exactly as
     * upstream Mihon's does.
     */
    fun isHoldingThePage(imageView: SubsamplingScaleImageView?): Boolean {
        if (!enabled) return false
        if (card?.isShowing == true) return true
        val image = imageView ?: return false
        return image.isReady && image.scale != image.minScale
    }

    /** The boxes are drawn in view space, so they are stale the moment the image moves. */
    fun invalidateBoxes() {
        boxes?.invalidate()
    }

    /**
     * The reader moved the page themselves, so a frozen card no longer matches what is under it.
     *
     * The card goes, and the remembered camera is forgotten with it: the reader has taken over,
     * and animating the page back to where Bubble Zoom found it would undo their own gesture.
     * The caller is expected to reset the session as well, so that what the model believes and
     * what is on screen do not drift apart.
     */
    fun onPageMovedByUser() {
        card?.onPageMoved()
        savedScale = null
        savedCenter = null
    }

    /** The holder is going away. Everything goes with it, with no animation and no restore. */
    fun release(host: ViewGroup) {
        card?.clear()
        detachViews(host)
        savedScale = null
        savedCenter = null
        enabled = false
    }

    private fun present(imageView: SubsamplingScaleImageView, focus: BubbleDetection?) {
        if (focus == null) {
            card?.dismiss()
            restoreCamera(imageView)
            return
        }

        // The card is tried first and captures before anything is torn down, so a refusal here
        // leaves whatever is already shown untouched.
        if (card?.show(focus.box) == true) {
            // A card means the page itself never moved, so there is nothing to restore later.
            restoreCamera(imageView)
            return
        }

        card?.dismiss()
        if (savedScale == null) {
            savedScale = imageView.scale
            savedCenter = imageView.center
        }
        imageView.focusOnBubble(focus.box)
    }

    /**
     * Gives the page back exactly as the feature found it, then takes the views away.
     *
     * The camera first and the views second: a page the P4 camera had framed is still zoomed on
     * a bubble, and dropping the card without restoring it would leave the reader staring at a
     * magnified fragment of a page with no way back short of a page turn.
     */
    private fun switchOff(host: ViewGroup, imageView: SubsamplingScaleImageView?) {
        if (imageView != null && imageView.isReady) restoreCamera(imageView)
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
        card = BubbleZoomOverlayView(host.context, imageView).also {
            host.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun applyBoxes(
        host: ViewGroup,
        imageView: SubsamplingScaleImageView,
        show: Boolean,
        detected: List<BubbleBox>,
    ) {
        if (!show) {
            BubbleZoomDebugOverlay.detach(host, boxes)
            boxes = null
            return
        }
        val overlay = boxes ?: BubbleZoomDebugOverlay.attach(host, imageView).also { boxes = it }
        overlay.boxes = detected
    }

    private fun restoreCamera(imageView: SubsamplingScaleImageView) {
        val scale = savedScale
        val center = savedCenter
        savedScale = null
        savedCenter = null
        if (scale != null && center != null) {
            imageView.resetBubbleZoom(scale, center)
        }
    }
}
