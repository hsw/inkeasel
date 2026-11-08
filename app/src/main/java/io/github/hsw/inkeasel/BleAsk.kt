package io.github.hsw.inkeasel

/**
 * When and how the app asks for the Bluetooth permission ([BlePermissions]): in context, never by itself
 * before the user has seen the app. Pure; `MainActivity` applies it, the Bluetooth panel (`BlePanel`) shows it.
 *
 * - A fresh start without the permission shows the first-run panel ([Stage.FIRST]) until it has been answered
 *   once (`BlePermissions.introDone`: "Not now", Back, or an answer in the system dialog). Its affirmative
 *   button ("Find my frame") opens the system dialog; with the permission and no frame chosen the start opens
 *   the frame settings as before, and the two never open together.
 * - After that the panel comes back only in context: a picture pressed, or a picture shared, without the
 *   permission. Once the user has denied it ([BlePermissions] rationale true) it says what stops working
 *   ([Stage.RATIONALE]) before the dialog again.
 * - Once the system no longer shows its dialog ([BlePermissions.blocked]) the panel is not shown: the app
 *   degrades in place. A pressed picture says why it cannot be sent ([Send.BLOCKED]); the settings, where the
 *   user asks for it, keep the path to App info.
 */
object BleAsk {
    /** What a fresh start shows besides the catalog. */
    enum class Start { PANEL, SETTINGS, CATALOG }

    fun atStart(granted: Boolean, frameSelected: Boolean, introDone: Boolean, blocked: Boolean): Start = when {
        !granted && !introDone && !blocked -> Start.PANEL
        granted && !frameSelected -> Start.SETTINGS // the frame search, as before
        else -> Start.CATALOG // without the permission the settings could only ask for it again
    }

    /** What pressing a picture does. */
    enum class Send { SEND, NO_FRAME, PANEL, BLOCKED }

    fun onSend(granted: Boolean, frameSelected: Boolean, blocked: Boolean): Send = when {
        granted && frameSelected -> Send.SEND
        granted -> Send.NO_FRAME
        blocked -> Send.BLOCKED
        else -> Send.PANEL // no frame either: it can only be found with the permission
    }

    /** What a picture shared without the permission (a pending share) opens. */
    enum class Share { NOTHING, PANEL, SETTINGS }

    fun onShare(granted: Boolean, blocked: Boolean): Share = when {
        granted -> Share.NOTHING
        blocked -> Share.SETTINGS // the user asked to send it: the settings name the path to App info
        else -> Share.PANEL
    }

    /** What the panel says: the first request, or, after a denial, what stops working without it. */
    enum class Stage { FIRST, RATIONALE }

    /** [rationale]: `shouldShowRequestPermissionRationale` of one of the permissions. */
    fun stage(rationale: Boolean): Stage = if (rationale) Stage.RATIONALE else Stage.FIRST

    /** The panel's [title], its paragraphs ([text]) and its two buttons. */
    data class Panel(val title: Txt, val text: List<Txt>, val confirm: Txt, val dismiss: Txt)

    /**
     * The panel for [stage] on Android [sdk]: it names the permission as the system dialog does, and before
     * Android 12 adds that a Bluetooth search uses location, which must be on. "Find my frame" while no frame
     * is chosen, else "Allow" (the permission was taken back after a frame was chosen).
     */
    fun panel(stage: Stage, frameSelected: Boolean, sdk: Int): Panel {
        val app = Txt(R.string.app_name)
        val main = Txt(if (stage == Stage.FIRST) R.string.perm_panel_intro else R.string.perm_panel_rationale, app, BlePermissions.settingsName(sdk))
        return Panel(
            title = Txt(R.string.perm_bt),
            text = listOfNotNull(main, Txt(R.string.perm_panel_location, app).takeIf { sdk < 31 }),
            confirm = Txt(if (frameSelected) R.string.allow else R.string.perm_panel_find),
            dismiss = Txt(R.string.perm_panel_later),
        )
    }

    /** What the open panel does once the system dialog came back, or the app came back with the permission. */
    enum class After { FIND, SEND, CLOSE, STAY }

    /**
     * [answered]: the dialog was answered (not cancelled); [picture]: a pressed picture opened the panel.
     * Granted: the frame search ([After.FIND]) without a frame, else the picture is sent ([After.SEND]) or
     * the panel closes. Denied for good: the panel closes (degrade in place). Denied once, or not answered: it
     * stays, with the text for the new [stage].
     */
    fun afterRequest(answered: Boolean, granted: Boolean, blocked: Boolean, frameSelected: Boolean, picture: Boolean): After = when {
        granted && !frameSelected -> After.FIND
        granted -> if (picture) After.SEND else After.CLOSE
        answered && blocked -> After.CLOSE
        else -> After.STAY
    }
}
