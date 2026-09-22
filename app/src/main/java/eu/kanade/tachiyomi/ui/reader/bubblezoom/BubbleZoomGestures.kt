package eu.kanade.tachiyomi.ui.reader.bubblezoom

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import kotlin.math.abs

/**
 * The gestures that are Bubble Zoom's, taken ahead of the image view.
 *
 * Runs from the page holder's `dispatchTouchEvent`, the one place that sees every event before
 * the image view does. It has to be there: the library decides its double tap zoom inside its
 * own detector on the second tap's DOWN, and a vertical drag becomes a pan the moment it passes
 * the slop, and neither can be switched off for one gesture. So the second tap of a double tap
 * that lands on a balloon is swallowed here and the image view never learns of it, and while a
 * card is lifted the single-finger stream is swallowed whole, so nothing pans or zooms the page
 * under a frozen capture.
 *
 * Everything else passes through untouched, which is what keeps "no card" upstream Mihon. A
 * double tap on empty art is the library's own zoom. A single tap still reaches the pager's
 * detector, which is fed after the view tree whatever this returns, so the edge and centre
 * zones keep working with a card up. A pinch on a page with no card is a pinch. A horizontal
 * drag is still intercepted by the pager, which takes the gesture away with ACTION_CANCEL
 * exactly as it takes it from the image view.
 *
 * Swipes step the way a tap on the edge does — through `moveToNext` / `moveToPrevious`, which
 * already know the reading direction and already turn the page after the last bubble. They are
 * Bubble Zoom's ONLY while a card is up. Play Books does take a vertical swipe on a page with no
 * card — measured: it turns the page and then walks into its bubbles — but doing the same here
 * would mean a flick down turning the page backwards in a horizontal pager, where upstream Mihon
 * does nothing at all with a vertical drag on a page at fit. The card is what the reader asked
 * for with a double tap, and it is what this may take gestures for.
 */
class BubbleZoomGestures(
    private val host: View,
    private val viewer: PagerViewer,
    private val page: BubblePageKey,
    private val imageView: () -> SubsamplingScaleImageView?,
    /** Whether a point in [host]'s coordinates falls on the lifted card itself. */
    private val cardContains: (Float, Float) -> Boolean,
) {

    private val session: BubbleZoomSession get() = viewer.bubbleZoom

    /** A vertical pager wants vertical drags for itself; while a card is up it may not have them. */
    private val stealsVerticalFromPager = viewer is VerticalPagerViewer

    private val density = host.resources.displayMetrics.density
    private val swipeMinDistancePx = BubbleZoomConfig.SWIPE_MIN_DISTANCE_DP * density
    private val swipeMinVelocityPx = BubbleZoomConfig.SWIPE_MIN_VELOCITY_DP_PER_S * density
    private val touchSlopPx = ViewConfiguration.get(host.context).scaledTouchSlop.toFloat()

    /** True from a DOWN that arrived with a card lifted until that gesture ends. */
    private var owning = false

    /** True from the second tap's DOWN that activated or deactivated a balloon until its UP. */
    private var swallowingTap = false

    private var downX = 0f
    private var downY = 0f
    private var flingVelocityY = 0f
    private var pointers = 1

    private val detector = GestureDetector(
        host.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // On the card itself: down, whatever the page happens to hold under it. The card
                // is nearly three times the balloon and routinely covers its neighbours, so
                // asking the page would jump to one of those instead of dismissing — which is
                // neither what the reader asked for nor what amendment E2 says.
                if (cardContains(e.x, e.y)) {
                    session.reset()
                    swallowingTap = true
                    return true
                }
                val view = imageView() ?: return false
                if (!view.isReady || view.sWidth <= 0 || view.sHeight <= 0) return false
                val source = view.viewToSourceCoord(e.x, e.y) ?: return false
                if (session.onDoubleTap(page, source.x / view.sWidth, source.y / view.sHeight)) {
                    swallowingTap = true
                }
                return swallowingTap
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityY) > abs(velocityX)) flingVelocityY = velocityY
                return false
            }
        },
    ).apply {
        // Long press is the pager's own gesture, and its detector already handles it.
        setIsLongpressEnabled(false)
    }

    /** True when the event is Bubble Zoom's and must not reach the image view. */
    fun dispatch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallowingTap = false
                flingVelocityY = 0f
                pointers = 1
                downX = ev.x
                downY = ev.y
                owning = session.isFocusedOn(page)
                if (owning && stealsVerticalFromPager) host.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> pointers++
        }
        // The double tap fires from in here, on the second tap's DOWN.
        detector.onTouchEvent(ev)
        val ending = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL

        if (swallowingTap) {
            if (ending) swallowingTap = false
            return true
        }
        if (owning) {
            // A second finger means a pinch, which a frozen card cannot honour: the card goes and
            // the rest of this gesture is dropped, so the page does not jump under it.
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) session.reset()
            if (ev.actionMasked == MotionEvent.ACTION_UP && pointers == 1) stepIfSwipe(ev)
            if (ending) {
                owning = false
                if (stealsVerticalFromPager) host.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        // With no card up, nothing here is Bubble Zoom's: the gesture is the reader's and the
        // library's, exactly as upstream.
        return false
    }

    private fun stepIfSwipe(ev: MotionEvent) {
        val dx = ev.x - downX
        val dy = ev.y - downY
        val dragged = abs(dy) >= swipeMinDistancePx && abs(dy) > abs(dx)
        val flung = abs(flingVelocityY) >= swipeMinVelocityPx && abs(dy) > touchSlopPx && abs(dy) > abs(dx)
        if (!dragged && !flung) return
        val up = dy < 0f
        val next = if (BubbleZoomConfig.SWIPE_UP_IS_NEXT) up else !up
        if (next) viewer.moveToNext() else viewer.moveToPrevious()
    }
}
