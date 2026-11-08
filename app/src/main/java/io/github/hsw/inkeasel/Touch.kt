package io.github.hsw.inkeasel

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The tv-material components (Button, Card, Tab, ListItem) react to the D-pad only. This makes one react to a
 * tap as well, so the app works on touch screens (tablets, phones). A drag that scrolls a list is not a tap.
 */
@Composable
fun Modifier.onTap(action: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) { detectTapGestures(onTap = { current() }) }
}

/**
 * [onTap] that also takes a long press ([onLongPress]; a long press is then not a tap): the touch twin of a
 * tv-material `Card`'s `onLongClick`, which takes CENTER held on the D-pad.
 */
@Composable
fun Modifier.onTap(action: () -> Unit, onLongPress: () -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    val long by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) { detectTapGestures(onTap = { current() }, onLongPress = { long() }) }
}
