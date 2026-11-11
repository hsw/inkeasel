package io.github.hsw.inkeasel

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf

/**
 * `createComposeRule()` launches a bare `ComponentActivity` to host the content. The app's manifest does not
 * declare one, and `ui-test-manifest` would merge an exported one into the debug APK, so this registers it with
 * Robolectric's package manager before the compose rule starts it (docs/building.md).
 */
class ComposeHostRule : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
    }
}

/** The app's string [id], formatted with [args]: what a node shows, to find it by its text. */
internal fun str(id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<Application>().getString(id, *args)

/** Moves the test clock on by [ms] and lets the content settle. */
internal fun ComposeContentTestRule.advance(ms: Long) {
    mainClock.advanceTimeBy(ms)
    waitForIdle()
}

/** The empty frame list says "Searching for frames…", not yet "No frames seen yet". */
internal fun ComposeContentTestRule.searchingShows() {
    onNodeWithText(str(R.string.scanning)).assertExists()
    onAllNodesWithText(str(R.string.scan_none_seen)).assertCountEquals(0)
}

/** The empty frame list says "No frames seen yet", no longer "Searching for frames…". */
internal fun ComposeContentTestRule.noneSeenShows() {
    onNodeWithText(str(R.string.scan_none_seen)).assertExists()
    onAllNodesWithText(str(R.string.scanning)).assertCountEquals(0)
}
