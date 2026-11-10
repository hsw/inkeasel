package io.github.hsw.inkeasel

/** OpenDisplay records for tests (docs/protocol.md#opendisplay-config). */
internal object OdFixtures {
    /**
     * A display record (config packet 0x20, 46 bytes) like a reTerminal E1001's: panel model [panel], [w]×[h],
     * [rotation], colour scheme [scheme] (5: four greys), transmission modes 25 (streaming zlib).
     */
    fun display(panel: Int = 60, w: Int = 800, h: Int = 480, rotation: Int = 0, scheme: Int = 5) = ByteArray(46).also {
        it[2] = panel.toByte(); it[3] = (panel shr 8).toByte()
        it[4] = w.toByte(); it[5] = (w shr 8).toByte()
        it[6] = h.toByte(); it[7] = (h shr 8).toByte()
        it[14] = rotation.toByte()
        it[21] = scheme.toByte()
        it[22] = 25
    }

    /** A config `[length:2][version][packets][crc:2]`: a system packet of type [firstType], then the display [d]. */
    fun config(d: ByteArray, firstType: Int = 0x01): ByteArray {
        val packets = bytes(0, firstType) + ByteArray(22) + bytes(1, 0x20) + d
        return bytes(packets.size + 1, 0, 1) + packets + bytes(0xAB, 0xCD)
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
}
