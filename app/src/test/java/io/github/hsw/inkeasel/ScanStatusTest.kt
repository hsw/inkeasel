package io.github.hsw.inkeasel

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStatusTest {
    private val searching = Txt(R.string.scanning)
    private val noneSeen = Txt(R.string.scan_none_seen)

    @Test
    fun locationOff_namesLocation() {
        assertEquals(ScanStatus(searching, Txt(R.string.scanning_hint_location)), ScanStatus.of(locationOff = true, noneSeen = false))
        assertEquals(ScanStatus(noneSeen, Txt(R.string.scan_none_seen_hint_location)), ScanStatus.of(locationOff = true, noneSeen = true))
    }

    @Test
    fun locationNotOff_doesNotNameIt() { // on, or Android 12+ where the scan does not need it
        assertEquals(ScanStatus(searching, Txt(R.string.scanning_hint)), ScanStatus.of(locationOff = false, noneSeen = false))
        assertEquals(ScanStatus(noneSeen, Txt(R.string.scan_none_seen_hint)), ScanStatus.of(locationOff = false, noneSeen = true))
    }

    @Test
    fun location_changesOnlyTheHint() {
        for (late in listOf(false, true)) {
            val title = if (late) noneSeen else searching
            assertEquals(title, ScanStatus.of(locationOff = false, noneSeen = late).title)
            assertEquals(title, ScanStatus.of(locationOff = true, noneSeen = late).title)
        }
    }

    @Test
    fun noneSeenAfter25Seconds() {
        assertEquals(25_000L, ScanStatus.NONE_SEEN_MS)
    }
}
