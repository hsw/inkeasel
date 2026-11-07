package io.github.hsw.inkeasel

/**
 * What the settings show for notification access (see `ListenerAccess`): a [status] line; an [openButton]
 * to the system screen when the access is off and the device has one; else, with the access off, an
 * [adbHint] with the command that grants it from a computer; and on a phone or tablet ([tv] false) with
 * Android 13+, the access off and a screen to grant it on, a [restrictedHint] for a [sideloaded] app (not
 * installed by a store): it must first be allowed "restricted settings" in its App info (⋮ menu), or the
 * switch there stays greyed out. Not shown with [adbHint] (adb grants are not affected) nor on Android TV (its
 * App info has no such menu; the Android TV 14 list screen granted an adb-installed app in one press). Pure:
 * the inputs are read by the caller, which builds the texts.
 */
data class ListenerAccessUi(
    val status: Txt,
    val openButton: Boolean,
    val adbHint: Boolean,
    val restrictedHint: Boolean,
) {
    companion object {
        /** First Android version with "restricted settings" for sideloaded apps (13). */
        const val RESTRICTED_SINCE = 33

        fun of(enabled: Boolean, canOpen: Boolean, sdk: Int, tv: Boolean, sideloaded: Boolean): ListenerAccessUi = when {
            enabled -> ListenerAccessUi(Txt(R.string.access_granted), openButton = false, adbHint = false, restrictedHint = false)
            canOpen -> ListenerAccessUi(
                Txt(R.string.access_missing),
                openButton = true,
                adbHint = false,
                restrictedHint = sdk >= RESTRICTED_SINCE && !tv && sideloaded,
            )
            else -> ListenerAccessUi(Txt(R.string.access_missing), openButton = false, adbHint = true, restrictedHint = false)
        }
    }
}
