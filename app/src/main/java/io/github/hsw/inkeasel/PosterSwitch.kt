package io.github.hsw.inkeasel

/**
 * The now-playing poster switch in the settings. It shows the poster as it works, not only as wanted: on
 * only while the poster is [wanted] (`FeatureStore.posterEnabled`) and notification access is granted. Pure;
 * `MainViewModel` keeps the state and `FeatureStore` stores it.
 *
 * - Pressed while on: the poster is switched off (and a pending wish dropped).
 * - Pressed while off with the access: switched on.
 * - Pressed while off without the access: nothing changes yet, the settings show the disclosure first
 *   ([Press.DISCLOSE]; "Grant access" leads there too). Only its affirmative action ([confirm]) remembers
 *   the wish as pending (`FeatureStore.posterPending`) and starts the grant flow ([ListenerAccessUi.grant]);
 *   cancelling it changes nothing. When the app comes back with the access granted and the wish pending,
 *   the poster is switched on.
 * - The access revoked from outside: the switch shows off, the stored wish stays, so granting the access
 *   again brings the poster back as it was.
 */
object PosterSwitch {
    /** What the switch shows. */
    fun checked(wanted: Boolean, access: Boolean): Boolean = wanted && access

    /** What a press does. */
    enum class Press { TURN_OFF, TURN_ON, DISCLOSE }

    fun press(wanted: Boolean, access: Boolean): Press = when {
        !access -> Press.DISCLOSE
        wanted -> Press.TURN_OFF
        else -> Press.TURN_ON
    }

    /** What the disclosure offers, and what its affirmative action does. */
    enum class Confirm {
        /** "Continue": the wish becomes pending and the system screen opens. */
        SET_PENDING_AND_GRANT,

        /** No screen leads to the access (adb-only TVs): the disclosure shows the adb command instead. */
        SHOW_ADB_HINT,
    }

    /** The disclosure's action, given whether a system screen for the grant exists ([grantAvailable]). */
    fun confirm(grantAvailable: Boolean): Confirm =
        if (grantAvailable) Confirm.SET_PENDING_AND_GRANT else Confirm.SHOW_ADB_HINT

    /** Back in the app: whether a [pending] wish now switches the poster on. */
    fun enableOnResume(access: Boolean, pending: Boolean): Boolean = access && pending
}
