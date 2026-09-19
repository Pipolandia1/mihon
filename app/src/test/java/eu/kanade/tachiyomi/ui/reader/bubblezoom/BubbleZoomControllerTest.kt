package eu.kanade.tachiyomi.ui.reader.bubblezoom

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BubbleZoomControllerTest {

    private val pageOne = BubblePageKey(Any())
    private val pageTwo = BubblePageKey(Any())

    private fun bubbles(count: Int, type: BubbleType = BubbleType.TEXT) = List(count) { i ->
        BubbleDetection(
            box = BubbleBox(0.1f * i, 0.1f * i, 0.05f, 0.05f),
            confidence = 0.9f,
            type = type,
        )
    }

    private val BubbleZoomController.index: Int
        get() = (state.value as BubbleZoomState.Ready).focusedIndex

    private fun controllerOn(page: BubblePageKey, count: Int, entry: PageEntry) =
        BubbleZoomController().apply { activatePage(page, bubbles(count), entry) }

    // region the walk

    @Test
    fun `entering forwards shows the page unfocused before any bubble`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)

        assertEquals(-1, controller.index)
        assertNull(controller.state.value.focusFor(pageOne))
    }

    @Test
    fun `walking forwards visits every bubble and then hands the page turn back`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)

        assertTrue(controller.tryMoveNext())
        assertEquals(0, controller.index)
        assertTrue(controller.tryMoveNext())
        assertEquals(1, controller.index)
        assertTrue(controller.tryMoveNext())
        assertEquals(2, controller.index)

        // On the last bubble: the reader turns the page, and the page it leaves is at rest.
        assertFalse(controller.tryMoveNext())
        assertEquals(-1, controller.index)
    }

    @Test
    fun `entering backwards lands on the last bubble`() {
        val controller = controllerOn(pageOne, 3, PageEntry.BACKWARD)

        assertEquals(2, controller.index)
    }

    @Test
    fun `walking backwards is the exact mirror of walking forwards`() {
        val controller = controllerOn(pageOne, 3, PageEntry.BACKWARD)

        assertTrue(controller.tryMovePrevious())
        assertEquals(1, controller.index)
        assertTrue(controller.tryMovePrevious())
        assertEquals(0, controller.index)

        // From the first bubble, back goes to the unfocused page -- it does NOT turn the page.
        // Dropping this stop would give the backward walk three stops against the forward four.
        assertTrue(controller.tryMovePrevious())
        assertEquals(-1, controller.index)

        // Only now does the reader turn back.
        assertFalse(controller.tryMovePrevious())
        assertEquals(-1, controller.index)
    }

    @Test
    fun `both directions stop the same number of times on a page`() {
        val forward = controllerOn(pageOne, 3, PageEntry.FORWARD)
        var forwardStops = 1
        while (forward.tryMoveNext()) forwardStops++

        val backward = controllerOn(pageTwo, 3, PageEntry.BACKWARD)
        var backwardStops = 1
        while (backward.tryMovePrevious()) backwardStops++

        assertEquals(4, forwardStops)
        assertEquals(forwardStops, backwardStops)
    }

    // endregion

    // region boundaries

    @Test
    fun `a page with no bubbles refuses both directions immediately`() {
        val controller = controllerOn(pageOne, 0, PageEntry.FORWARD)

        assertEquals(-1, controller.index)
        assertFalse(controller.tryMoveNext())
        assertFalse(controller.tryMovePrevious())
        assertEquals(-1, controller.index)
    }

    @Test
    fun `a page with no bubbles entered backwards is still unfocused`() {
        val controller = controllerOn(pageOne, 0, PageEntry.BACKWARD)

        assertEquals(-1, controller.index)
    }

    @Test
    fun `a page with one bubble still has both stops in both directions`() {
        val forward = controllerOn(pageOne, 1, PageEntry.FORWARD)
        assertEquals(-1, forward.index)
        assertTrue(forward.tryMoveNext())
        assertEquals(0, forward.index)
        assertFalse(forward.tryMoveNext())
        assertEquals(-1, forward.index)

        val backward = controllerOn(pageTwo, 1, PageEntry.BACKWARD)
        assertEquals(0, backward.index)
        assertTrue(backward.tryMovePrevious())
        assertEquals(-1, backward.index)
        assertFalse(backward.tryMovePrevious())
    }

    @Test
    fun `moving before any page is activated is refused`() {
        val controller = BubbleZoomController()

        assertFalse(controller.tryMoveNext())
        assertFalse(controller.tryMovePrevious())
        assertInstanceOf(BubbleZoomState.Idle::class.java, controller.state.value)
    }

    @Test
    fun `moving while detection has not finished is refused`() {
        val controller = BubbleZoomController()
        controller.activatePage(pageOne, PageEntry.FORWARD)

        assertInstanceOf(BubbleZoomState.Detecting::class.java, controller.state.value)
        assertFalse(controller.tryMoveNext())
        assertFalse(controller.tryMovePrevious())
    }

    // endregion

    // region page identity

    @Test
    fun `a new page resets the walk`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()
        controller.tryMoveNext()

        controller.activatePage(pageTwo, bubbles(3), PageEntry.FORWARD)

        assertEquals(-1, controller.index)
        assertEquals(pageTwo, (controller.state.value as BubbleZoomState.Ready).page)
    }

    @Test
    fun `re-activating the page already held does not restart the walk`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()

        controller.activatePage(pageOne, PageEntry.FORWARD)

        assertEquals(0, controller.index)
    }

    @Test
    fun `bubbles arriving for a page the reader already left are ignored`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.activatePage(pageTwo, PageEntry.FORWARD)

        controller.onBubblesReady(pageOne, bubbles(5))

        assertInstanceOf(BubbleZoomState.Detecting::class.java, controller.state.value)
    }

    @Test
    fun `focusFor answers only for the page that is the subject`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()

        assertNotEquals(null, controller.state.value.focusFor(pageOne))
        assertNull(controller.state.value.focusFor(pageTwo))
    }

    @Test
    fun `focusFor returns the bubble at the focused index, in order`() {
        // Pins the index-to-bubble mapping itself. Asserting only null vs non-null leaves a
        // reversed lookup passing every other test, and it would send the forward walk through
        // the bubbles backwards while the index still counted up.
        val list = bubbles(3)
        val controller = BubbleZoomController()
        controller.activatePage(pageOne, list, PageEntry.FORWARD)

        controller.tryMoveNext()
        assertSame(list[0], controller.state.value.focusFor(pageOne))
        controller.tryMoveNext()
        assertSame(list[1], controller.state.value.focusFor(pageOne))
        controller.tryMoveNext()
        assertSame(list[2], controller.state.value.focusFor(pageOne))
    }

    @Test
    fun `entering backwards focuses the last bubble of the list, not merely some bubble`() {
        val list = bubbles(3)
        val controller = BubbleZoomController()
        controller.activatePage(pageOne, list, PageEntry.BACKWARD)

        assertSame(list[2], controller.state.value.focusFor(pageOne))
        controller.tryMovePrevious()
        assertSame(list[1], controller.state.value.focusFor(pageOne))
    }

    @Test
    fun `two wrappers of the same token are the same key`() {
        // The positive half of the contract. Without it, narrowing equals to wrapper identity
        // would pass every other test here and then break P6, which builds a key from the
        // ReaderPage it has on hand rather than holding one wrapper forever.
        val token = Any()

        assertEquals(BubblePageKey(token), BubblePageKey(token))
        assertEquals(BubblePageKey(token).hashCode(), BubblePageKey(token).hashCode())
    }

    @Test
    fun `tokens equal by value but distinct objects are different keys`() {
        // Keys compare by identity, because a split double page reuses its parent's index and a
        // value key would collide.
        val first = BubblePageKey(listOf(1, 2, 3))
        val second = BubblePageKey(listOf(1, 2, 3))

        assertNotEquals(first, second)
    }

    @Test
    fun `the subject is matched by a freshly built key, not by the wrapper instance`() {
        val page = Any()
        val controller = BubbleZoomController()
        controller.activatePage(BubblePageKey(page), bubbles(3), PageEntry.FORWARD)
        controller.tryMoveNext()

        assertNotEquals(null, controller.state.value.focusFor(BubblePageKey(page)))
    }

    // endregion

    // region lifecycle

    @Test
    fun `reset un-focuses without giving up the page`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()

        controller.reset()

        assertEquals(-1, controller.index)
        assertEquals(pageOne, (controller.state.value as BubbleZoomState.Ready).page)
    }

    @Test
    fun `deactivate gives up the page entirely`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()

        controller.deactivate()

        assertInstanceOf(BubbleZoomState.Idle::class.java, controller.state.value)
        assertNull(controller.state.value.focusFor(pageOne))
        assertFalse(controller.tryMoveNext())
    }

    @Test
    fun `disable refuses everything until enable`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        controller.tryMoveNext()

        controller.disable()

        assertInstanceOf(BubbleZoomState.Disabled::class.java, controller.state.value)
        assertFalse(controller.tryMoveNext())
        controller.activatePage(pageTwo, bubbles(3), PageEntry.FORWARD)
        assertInstanceOf(BubbleZoomState.Disabled::class.java, controller.state.value)

        controller.enable()
        assertInstanceOf(BubbleZoomState.Idle::class.java, controller.state.value)
        controller.activatePage(pageTwo, bubbles(3), PageEntry.FORWARD)
        assertEquals(-1, controller.index)
    }

    @Test
    fun `isFocused tells P6 whether the move it is about to make ends a walk`() {
        val controller = controllerOn(pageOne, 2, PageEntry.FORWARD)
        assertFalse(controller.isFocused)

        controller.tryMoveNext()
        assertTrue(controller.isFocused)

        controller.tryMoveNext()
        assertTrue(controller.isFocused)

        // The move that hands the page turn back: focused before, at rest after.
        assertTrue(controller.isFocused)
        assertFalse(controller.tryMoveNext())
        assertFalse(controller.isFocused)
    }

    @Test
    fun `detection failure behaves like a page with no bubbles`() {
        val controller = BubbleZoomController()
        controller.activatePage(pageOne, PageEntry.FORWARD)

        controller.onDetectionFailed(pageOne, IllegalStateException("model missing"))

        assertInstanceOf(BubbleZoomState.Error::class.java, controller.state.value)
        assertFalse(controller.tryMoveNext())
        assertFalse(controller.tryMovePrevious())
        assertNull(controller.state.value.focusFor(pageOne))
    }

    // endregion

    // region selectBubble and the state invariant

    @Test
    fun `selectBubble moves to a valid index and refuses anything else`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)

        assertTrue(controller.selectBubble(2))
        assertEquals(2, controller.index)
        assertFalse(controller.selectBubble(2))
        assertTrue(controller.selectBubble(-1))
        assertFalse(controller.selectBubble(3))
        assertFalse(controller.selectBubble(-2))
        assertEquals(-1, controller.index)
    }

    @Test
    fun `panels never reach the navigable list`() {
        val controller = BubbleZoomController()
        controller.activatePage(pageOne, PageEntry.FORWARD)
        controller.onBubblesReady(
            pageOne,
            bubbles(2, BubbleType.TEXT) + bubbles(2, BubbleType.PANEL),
        )

        assertEquals(2, (controller.state.value as BubbleZoomState.Ready).bubbles.size)
    }

    @Test
    fun `Ready refuses an index its own list cannot answer`() {
        assertThrows(IllegalArgumentException::class.java) {
            BubbleZoomState.Ready(pageOne, bubbles(2), 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BubbleZoomState.Ready(pageOne, bubbles(2), -2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BubbleZoomState.Ready(pageOne, emptyList(), 0)
        }
    }

    @Test
    fun `the state flow keeps the same instance when nothing changed`() {
        val controller = controllerOn(pageOne, 3, PageEntry.FORWARD)
        val before = controller.state.value

        assertFalse(controller.tryMovePrevious())

        assertSame(before, controller.state.value)
    }

    // endregion
}
