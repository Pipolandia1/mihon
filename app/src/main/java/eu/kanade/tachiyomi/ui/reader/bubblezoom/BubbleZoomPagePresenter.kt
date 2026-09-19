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
 * This is the whole presentation side of the contract: a bubble means frame it, null means go
 * back to where the reader left the page. Phase P16 replaces the body of [apply] with a lifted
 * card and keeps everything else, because the answer it consumes is the same.
 *
 * One instance per page holder. It remembers the scale and centre from before the first frame
 * of a walk, which is what makes the page return to exactly where it was rather than to a
 * recomputed "default" that Mihon does not actually use.
 */
class BubbleZoomPagePresenter {

    private var savedScale: Float? = null
    private var savedCenter: PointF? = null

    fun apply(imageView: SubsamplingScaleImageView?, focus: BubbleDetection?) {
        if (imageView == null || !imageView.isReady) return

        if (focus != null) {
            if (savedScale == null) {
                savedScale = imageView.scale
                savedCenter = imageView.center
            }
            imageView.focusOnBubble(focus.box)
            return
        }

        val scale = savedScale
        val center = savedCenter
        savedScale = null
        savedCenter = null
        if (scale != null && center != null) {
            imageView.resetBubbleZoom(scale, center)
        }
    }
}
