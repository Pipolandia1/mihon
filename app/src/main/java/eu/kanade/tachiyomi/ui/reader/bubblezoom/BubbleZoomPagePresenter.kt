package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.graphics.PointF
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
 * Turns the controller's answer for one page into what that page shows.
 *
 * Two presentations, one decision per bubble. The preferred one lifts the bubble as a card and
 * leaves the page where it is; when the card cannot be built — an animated page, an image not
 * ready, a bubble partly off screen, a magnification outside the band where a frozen capture
 * still adds detail — the phase P4 camera move takes over instead. The reader sees one or the
 * other, never neither, and never both.
 *
 * One instance per page holder. It remembers the scale and centre from before the first camera
 * move of a walk, so the page returns to exactly where the reader left it rather than to a
 * recomputed "default" that Mihon does not actually use.
 */
class BubbleZoomPagePresenter {

    private var savedScale: Float? = null
    private var savedCenter: PointF? = null

    /** Set while the card is up, so a page move made by the reader can drop a stale capture. */
    private var card: BubbleZoomOverlayView? = null

    fun attachCard(overlay: BubbleZoomOverlayView?) {
        card = overlay
    }

    fun apply(imageView: SubsamplingScaleImageView?, focus: BubbleDetection?) {
        if (imageView == null || !imageView.isReady) return

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
     * The reader moved the page themselves, so a frozen card no longer matches what is under it.
     *
     * The card goes, and the remembered camera is forgotten with it: the reader has taken over,
     * and animating the page back to where Bubble Zoom found it would undo their own gesture.
     * The caller is expected to reset the controller as well, so that what the model believes
     * and what is on screen do not drift apart.
     */
    fun onPageMovedByUser() {
        card?.onPageMoved()
        savedScale = null
        savedCenter = null
    }

    fun release() {
        card?.clear()
        card = null
        savedScale = null
        savedCenter = null
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
