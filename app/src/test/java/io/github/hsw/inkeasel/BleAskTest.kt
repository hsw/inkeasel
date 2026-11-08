package io.github.hsw.inkeasel

import io.github.hsw.inkeasel.BleAsk.After
import io.github.hsw.inkeasel.BleAsk.Send
import io.github.hsw.inkeasel.BleAsk.Share
import io.github.hsw.inkeasel.BleAsk.Stage
import io.github.hsw.inkeasel.BleAsk.Start
import org.junit.Assert.assertEquals
import org.junit.Test

class BleAskTest {
    @Test
    fun firstStart_withoutPermission_showsThePanel_notTheSettings() {
        assertEquals(Start.PANEL, BleAsk.atStart(granted = false, frameSelected = false, introDone = false, blocked = false))
        assertEquals(Start.PANEL, BleAsk.atStart(granted = false, frameSelected = true, introDone = false, blocked = false))
    }

    @Test
    fun start_afterNotNow_showsTheCatalog() {
        assertEquals(Start.CATALOG, BleAsk.atStart(granted = false, frameSelected = false, introDone = true, blocked = false))
        assertEquals(Start.CATALOG, BleAsk.atStart(granted = false, frameSelected = true, introDone = true, blocked = false))
    }

    @Test
    fun start_blocked_neverShowsThePanel() {
        assertEquals(Start.CATALOG, BleAsk.atStart(granted = false, frameSelected = false, introDone = false, blocked = true))
    }

    @Test
    fun start_withPermission_opensTheSettingsOnlyWithoutAFrame() {
        assertEquals(Start.SETTINGS, BleAsk.atStart(granted = true, frameSelected = false, introDone = false, blocked = false))
        assertEquals(Start.SETTINGS, BleAsk.atStart(granted = true, frameSelected = false, introDone = true, blocked = false))
        assertEquals(Start.CATALOG, BleAsk.atStart(granted = true, frameSelected = true, introDone = true, blocked = false))
    }

    @Test
    fun send() {
        assertEquals(Send.SEND, BleAsk.onSend(granted = true, frameSelected = true, blocked = false))
        assertEquals(Send.NO_FRAME, BleAsk.onSend(granted = true, frameSelected = false, blocked = false))
        // without the permission it asks in context, frame or not (a frame is found only with it)
        assertEquals(Send.PANEL, BleAsk.onSend(granted = false, frameSelected = true, blocked = false))
        assertEquals(Send.PANEL, BleAsk.onSend(granted = false, frameSelected = false, blocked = false))
        // denied for good: says why, no panel
        assertEquals(Send.BLOCKED, BleAsk.onSend(granted = false, frameSelected = true, blocked = true))
        assertEquals(Send.BLOCKED, BleAsk.onSend(granted = false, frameSelected = false, blocked = true))
    }

    @Test
    fun share() {
        assertEquals(Share.NOTHING, BleAsk.onShare(granted = true, blocked = false))
        assertEquals(Share.PANEL, BleAsk.onShare(granted = false, blocked = false))
        assertEquals(Share.SETTINGS, BleAsk.onShare(granted = false, blocked = true))
    }

    @Test
    fun stage_followsTheRationale() {
        assertEquals(Stage.FIRST, BleAsk.stage(rationale = false))
        assertEquals(Stage.RATIONALE, BleAsk.stage(rationale = true))
    }

    private val app = Txt(R.string.app_name)

    @Test
    fun panel_android12_namesNearbyDevices_withoutLocation() {
        val p = BleAsk.panel(Stage.FIRST, frameSelected = false, sdk = 31)
        assertEquals(Txt(R.string.perm_bt), p.title)
        assertEquals(listOf(Txt(R.string.perm_panel_intro, app, Txt(R.string.perm_name_nearby))), p.text)
        assertEquals(Txt(R.string.perm_panel_find), p.confirm)
        assertEquals(Txt(R.string.perm_panel_later), p.dismiss)
    }

    @Test
    fun panel_android11_namesLocation_andThatItMustBeOn() {
        val p = BleAsk.panel(Stage.FIRST, frameSelected = false, sdk = 30)
        assertEquals(
            listOf(Txt(R.string.perm_panel_intro, app, Txt(R.string.perm_name_location)), Txt(R.string.perm_panel_location, app)),
            p.text,
        )
        val r = BleAsk.panel(Stage.RATIONALE, frameSelected = false, sdk = 26)
        assertEquals(
            listOf(Txt(R.string.perm_panel_rationale, app, Txt(R.string.perm_name_location)), Txt(R.string.perm_panel_location, app)),
            r.text,
        )
    }

    @Test
    fun panel_rationale_saysWhatStopsWorking() {
        val p = BleAsk.panel(Stage.RATIONALE, frameSelected = false, sdk = 34)
        assertEquals(listOf(Txt(R.string.perm_panel_rationale, app, Txt(R.string.perm_name_nearby))), p.text)
    }

    @Test
    fun panel_withAFrame_offersAllow() {
        assertEquals(Txt(R.string.allow), BleAsk.panel(Stage.FIRST, frameSelected = true, sdk = 34).confirm)
    }

    @Test
    fun afterRequest_granted_findsTheFrameOrSendsThePicture() {
        assertEquals(After.FIND, BleAsk.afterRequest(answered = true, granted = true, blocked = false, frameSelected = false, picture = true))
        assertEquals(After.FIND, BleAsk.afterRequest(answered = true, granted = true, blocked = false, frameSelected = false, picture = false))
        assertEquals(After.SEND, BleAsk.afterRequest(answered = true, granted = true, blocked = false, frameSelected = true, picture = true))
        assertEquals(After.CLOSE, BleAsk.afterRequest(answered = true, granted = true, blocked = false, frameSelected = true, picture = false))
    }

    @Test
    fun afterRequest_deniedOnce_staysForTheRationale() {
        assertEquals(After.STAY, BleAsk.afterRequest(answered = true, granted = false, blocked = false, frameSelected = false, picture = false))
    }

    @Test
    fun afterRequest_deniedForGood_closes() {
        assertEquals(After.CLOSE, BleAsk.afterRequest(answered = true, granted = false, blocked = true, frameSelected = false, picture = true))
    }

    @Test
    fun afterRequest_notAnswered_stays() {
        assertEquals(After.STAY, BleAsk.afterRequest(answered = false, granted = false, blocked = true, frameSelected = false, picture = false))
        assertEquals(After.STAY, BleAsk.afterRequest(answered = false, granted = false, blocked = false, frameSelected = true, picture = true))
    }
}
