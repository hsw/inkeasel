package io.github.hsw.inkeasel

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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

/** Keys that are the D-pad's centre: tv-material's press keys (CENTER, Enter, keypad Enter) and a gamepad's A. */
val CENTER_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

/**
 * Takes the rest of a CENTER press already handled ([HeldCenter]: its repeats and its release) before any
 * button inside sees it. On a dialog's root, so the focused button is never pressed by the key that opened it.
 */
fun Modifier.dropHeldCenter(held: HeldCenter): Modifier = onPreviewKeyEvent { e ->
    e.key in CENTER_KEYS && (e.type == KeyEventType.KeyDown || e.type == KeyEventType.KeyUp) &&
        held.swallow(e.type == KeyEventType.KeyDown, e.nativeKeyEvent.repeatCount)
}
