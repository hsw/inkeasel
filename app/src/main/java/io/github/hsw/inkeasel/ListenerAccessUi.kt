package io.github.hsw.inkeasel

/**
 * What the settings show for notification access (see `ListenerAccess`), and where the grant flow goes. Pure:
 * the inputs are read by the caller, which builds the texts.
 *
 * - [status]: granted or missing.
 * - [grant]: the system screen that "Grant access" (and pressing the poster switch without the access) opens,
 *   while the access is off; `null` when there is none. A [GrantScreen.DIRECT] screen when the device has one;
 *   else, on Android TV 9+ ([MENU_SINCE]), the Apps screen ([GrantScreen.APPS]), from where the switch is under
 *   Settings → Apps → Special app access → Notification access (Android TV 9-11, e.g. the Shield, have only
 *   that menu item). Low-RAM TVs hide the menu item, so they get no Apps route.
 * - [menuHint]: on TV with the menu route, that path as text: as the way to go ([MenuHint.ONLY]) when the
 *   button opens the Apps screen, or as a fallback ([MenuHint.FALLBACK]) when it opens the direct screen,
 *   whose rows the D-pad could not reach on Google TV 14. Shown in the disclosure, and under the poster switch
 *   only once the disclosure has been confirmed ([rowMenuHint]).
 * - [adbHint]: the adb command, only when no screen leads to the switch at all (e.g. Android TV 8, which has
 *   no notification-access screen); shown in the disclosure only, as a secondary line.
 * - [restrictedHint]: on a phone or tablet ([tv] false) with Android 13+ and a direct screen, for a
 *   [sideloaded] app (not installed by a store): it must first be allowed "restricted settings" in its App
 *   info (⋮ menu), or the switch there stays greyed out. Not on Android TV (its App info has no such menu;
 *   Google TV 14 granted an adb-installed app in one press) nor with [adbHint] (adb grants are not affected).
 *
 * Every way to the grant (the screen, the menu path, the adb command) thus appears only in or after the
 * prominent disclosure.
 */
data class ListenerAccessUi(
    val status: Txt,
    val grant: GrantScreen?,
    val menuHint: MenuHint?,
    val adbHint: Boolean,
    val restrictedHint: Boolean,
) {
    /** "Grant access" is shown: there is a screen to open. */
    val openButton: Boolean get() = grant != null

    /**
     * The menu path under the poster switch: only once the disclosure was confirmed and the poster waits for
     * the access ([pending], `FeatureStore.posterPending`), so the user finds it again after the system screen.
     */
    fun rowMenuHint(pending: Boolean): MenuHint? = menuHint.takeIf { pending }

    /** The system screens the grant flow can open. */
    enum class GrantScreen {
        /** The notification-access screen: the app's own page (phones and tablets, Android 11+) or the list. */
        DIRECT,

        /** Settings → Apps (or the Settings home where that is missing), on TV only. */
        APPS,
    }

    /** How the TV menu path is worded. */
    enum class MenuHint { ONLY, FALLBACK }

    companion object {
        /** First Android version with "restricted settings" for sideloaded apps (13). */
        const val RESTRICTED_SINCE = 33

        /** First Android TV version whose Special app access menu has Notification access (9). */
        const val MENU_SINCE = 28

        private val missing = Txt(R.string.access_missing)

        /**
         * [enabled]: the access is granted; [direct]: a direct screen resolves; [apps]: the Apps (or Settings)
         * screen resolves; [lowRam]: `ActivityManager.isLowRamDevice()`.
         */
        fun of(
            enabled: Boolean,
            direct: Boolean,
            apps: Boolean,
            sdk: Int,
            tv: Boolean,
            lowRam: Boolean,
            sideloaded: Boolean,
        ): ListenerAccessUi {
            if (enabled) return ListenerAccessUi(Txt(R.string.access_granted), null, null, adbHint = false, restrictedHint = false)
            val menu = tv && apps && sdk >= MENU_SINCE && !lowRam
            return when {
                direct -> ListenerAccessUi(
                    missing,
                    GrantScreen.DIRECT,
                    if (menu) MenuHint.FALLBACK else null,
                    adbHint = false,
                    restrictedHint = sdk >= RESTRICTED_SINCE && !tv && sideloaded,
                )
                menu -> ListenerAccessUi(missing, GrantScreen.APPS, MenuHint.ONLY, adbHint = false, restrictedHint = false)
                else -> ListenerAccessUi(missing, null, null, adbHint = true, restrictedHint = false)
            }
        }

        /** The menu path to the switch, ending in the app's name. */
        fun menuPath(): Txt = Txt(R.string.access_menu_path, Txt(R.string.app_name))

        /** The [MenuHint] in words, with the [menuPath]. */
        fun menuText(hint: MenuHint): Txt = when (hint) {
            MenuHint.ONLY -> Txt(R.string.access_menu_only, menuPath())
            MenuHint.FALLBACK -> Txt(R.string.access_menu_fallback, menuPath())
        }
    }
}
