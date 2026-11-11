package io.github.hsw.inkeasel

/**
 * What the settings' frame list says while a running scan has heard no frame: a [title] and a [hint] under it.
 * At first "Searching for frames…"; after [NONE_SEEN_MS] with nothing heard, "No frames seen yet" with what to
 * check (an OpenDisplay frame wakes only about once a minute, and its button wakes it at once). Before Android 12
 * a BLE scan also needs location turned on, so there the hint names it while location is off. Each variant is
 * one whole string. Only the settings list uses it; the upload error keeps `BlePermissions.scanHint`. Pure.
 */
data class ScanStatus(val title: Txt, val hint: Txt) {
    companion object {
        /** How long a running scan hears nothing before the list says "No frames seen yet". */
        const val NONE_SEEN_MS = 25_000L

        /**
         * The status while location is switched off where the scan needs it ([locationOff]: before Android 12,
         * decided by the caller), before or once [noneSeen].
         */
        fun of(locationOff: Boolean, noneSeen: Boolean): ScanStatus =
            if (noneSeen) {
                ScanStatus(Txt(R.string.scan_none_seen), Txt(if (locationOff) R.string.scan_none_seen_hint_location else R.string.scan_none_seen_hint))
            } else {
                ScanStatus(Txt(R.string.scanning), Txt(if (locationOff) R.string.scanning_hint_location else R.string.scanning_hint))
            }
    }
}
