package io.github.hsw.inkeasel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerNavTest {
    private fun pic(n: Int) = Picture("wd:Q$n", "Picture $n", "", "https://example.org/$n-s.jpg", "https://example.org/$n.jpg")

    private fun at(n: Int) = ViewerState(pic(n), n - 1, 10, rank = 10L * n)

    /** Steps whose every read waits until the test answers it ([answer]). */
    private class Pending : ViewerSteps {
        val asked = ArrayList<Pair<ViewerState, Int>>()
        private var reply = CompletableDeferred<ViewerState?>()

        override suspend fun step(from: ViewerState, delta: Int): ViewerState? {
            asked += from to delta
            return reply.await()
        }

        fun answer(to: ViewerState?) {
            reply.complete(to)
            reply = CompletableDeferred()
        }
    }

    private fun TestScope.nav(changes: MutableList<ViewerState?> = ArrayList()) = ViewerNav(backgroundScope) { changes += it }

    @Test
    fun aStepShowsTheNeighbourAndEveryChangeReachesTheScreen() = runTest {
        val changes = ArrayList<ViewerState?>()
        val nav = nav(changes)
        val steps = Pending()
        nav.open(at(1), steps)
        nav.step(1)
        runCurrent()
        steps.answer(at(2))
        runCurrent()
        assertEquals(at(2), nav.state)
        assertEquals(listOf(at(1), at(2)), changes)
        assertEquals(listOf(at(1) to 1), steps.asked)
    }

    @Test
    fun aStepPressedWhileOneIsReadIsDropped() = runTest {
        val nav = nav()
        val steps = Pending()
        nav.open(at(1), steps)
        nav.step(1)
        runCurrent()
        nav.step(1) // a held key repeats faster than the database answers
        nav.step(-1)
        runCurrent()
        assertEquals(1, steps.asked.size)
        steps.answer(at(2))
        runCurrent()
        assertEquals(at(2), nav.state)

        // once answered, the next press is read again
        nav.step(1)
        runCurrent()
        assertEquals(listOf(at(1) to 1, at(2) to 1), steps.asked)
    }

    @Test
    fun aStepPastTheEndChangesNothing() = runTest {
        val nav = nav()
        val steps = Pending()
        nav.open(at(1), steps)
        nav.step(-1)
        runCurrent()
        steps.answer(null)
        runCurrent()
        assertEquals(at(1), nav.state)
    }

    @Test
    fun aLateStepIsDroppedAfterCloseOrAReopen() = runTest {
        val nav = nav()
        val steps = Pending()
        nav.open(at(1), steps)
        nav.step(1)
        runCurrent()
        assertEquals(pic(1), nav.close())
        steps.answer(at(2))
        runCurrent()
        assertNull(nav.state)

        // opened again on another picture: a step read for the old one does not replace it
        nav.open(at(5), steps)
        nav.step(1)
        runCurrent()
        nav.open(at(7), steps) // the details panel's "View full screen" again
        steps.answer(at(6))
        runCurrent()
        assertEquals(at(7), nav.state)
    }

    @Test
    fun thePlaceIsAppliedOnlyWhileThePictureStillShows() = runTest {
        val nav = nav()
        val steps = Pending()
        val placed = CompletableDeferred<ViewerState?>()
        val alone = ViewerState.alone(pic(3))
        nav.open(alone, steps) { placed.await() }
        runCurrent()
        // reading the place counts as a read: a step is dropped meanwhile
        nav.step(1)
        runCurrent()
        assertEquals(emptyList<Pair<ViewerState, Int>>(), steps.asked)
        placed.complete(at(3))
        runCurrent()
        assertEquals(at(3), nav.state)

        // closed before the place came: it stays closed
        val late = CompletableDeferred<ViewerState?>()
        nav.open(alone, steps) { late.await() }
        nav.close()
        late.complete(at(3))
        runCurrent()
        assertNull(nav.state)

        // no place (the picture is gone or hidden): it stays alone
        nav.open(alone, steps) { null }
        runCurrent()
        assertEquals(alone, nav.state)
    }

    @Test
    fun closingAClosedViewerReportsNothing() = runTest {
        val changes = ArrayList<ViewerState?>()
        val nav = nav(changes)
        assertNull(nav.close())
        nav.step(1)
        assertEquals(emptyList<ViewerState?>(), changes)
    }
}
