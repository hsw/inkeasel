package io.github.hsw.inkeasel

/** Lowercase hex, two digits a byte: how frame records are kept in prefs and written to the log. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** The bytes written by [toHex]; `null` if [s] is not even-length hex. */
fun bytesOfHex(s: String): ByteArray? {
    if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) out[i] = s.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
    return out
}
