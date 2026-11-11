package io.github.hsw.inkeasel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The viewer's touch gestures: a swipe sideways steps, a swipe down (or a short flick down) does what Back
 * does (hides the card, or closes), a short slow pull springs back, a swipe up does nothing, a tap toggles the
 * card. A local picture whose file does not exist keeps Coil off the network.
 */
@RunWith(RobolectricTestRunner::class)
class FullscreenViewerUiTest {
    private val compose = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(compose)

    private val picture = Picture("local:a.jpg", "A picture", "", "/missing/a.jpg", "/missing/a.jpg", local = true)
    private val steps = mutableListOf<Int>()
    private var closes = 0
    private var open by mutableStateOf(true)

    private fun show() = compose.setContent {
        if (open) {
            FullscreenViewer(ViewerState.alone(picture), null, onStep = { steps += it }, onSend = {}, onClose = {
                closes++
                open = false
            })
        }
    }

    private fun picture() = compose.onNodeWithContentDescription(picture.title)

    private fun cardShows(shown: Boolean) =
        compose.onAllNodesWithText(str(R.string.viewer_no_frame)).assertCountEquals(if (shown) 1 else 0)

    @Test
    fun swipeDown_closes() {
        show()
        picture().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun shortSlowPull_springsBack_shortFlick_closes() {
        show()
        // 64 dp is 64 px at Robolectric's default density; the flick needs 800 px/s
        picture().performTouchInput { swipeDown(startY = centerY, endY = centerY + 30f, durationMillis = 1_000) }
        compose.waitForIdle()
        assertEquals(0, closes)
        picture().performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f, durationMillis = 50) }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun swipeDown_withTheCard_hidesItFirst() {
        show()
        picture().performTouchInput { click() }
        compose.waitForIdle()
        cardShows(true)
        picture().performTouchInput { swipeDown() }
        compose.waitForIdle()
        cardShows(false)
        assertEquals(0, closes)
        picture().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun swipeSideways_steps_swipeUp_doesNothing() {
        show()
        picture().performTouchInput { swipeLeft() }
        picture().performTouchInput { swipeRight() }
        picture().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(listOf(1, -1), steps)
        assertEquals(0, closes)
        cardShows(false)
    }
}
