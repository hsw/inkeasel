package io.github.hsw.inkeasel

import java.util.zip.Adler32

/**
 * A minimal DEFLATE decoder (RFC 1950/1951) for tests: it inflates a zlib stream the slow, plain way and
 * records the longest back-reference distance, which `java.util.zip.Inflater` does not report.
 */
internal class TinyInflate private constructor(private val src: ByteArray, private var pos: Int) {
    /** The inflated bytes, the longest match distance seen, and the window the header declares. */
    class Result(val data: ByteArray, val maxDistance: Int, val window: Int)

    private var bits = 0
    private var bitCount = 0
    private var out = ByteArray(1 shl 16)
    private var size = 0
    private var maxDistance = 0

    private fun bits(n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            if (bitCount == 0) {
                require(pos < src.size) { "stream ends early" }
                bits = src[pos++].toInt() and 0xFF
                bitCount = 8
            }
            v = v or ((bits and 1) shl i)
            bits = bits shr 1
            bitCount--
        }
        return v
    }

    private fun put(b: Int) {
        if (size == out.size) out = out.copyOf(out.size * 2)
        out[size++] = b.toByte()
    }

    /** A canonical Huffman code from its code lengths (zero: the symbol is unused). */
    private class Huffman(lengths: IntArray) {
        val count = IntArray(16)
        val symbol = IntArray(lengths.size)

        init {
            for (l in lengths) count[l]++
            count[0] = 0
            val offs = IntArray(16)
            for (l in 1 until 15) offs[l + 1] = offs[l] + count[l]
            for ((s, l) in lengths.withIndex()) if (l != 0) symbol[offs[l]++] = s
        }
    }

    private fun decode(h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..15) {
            code = code or bits(1)
            val n = h.count[len]
            if (code - n < first) return h.symbol[index + code - first]
            index += n
            first = (first + n) shl 1
            code = code shl 1
        }
        error("invalid Huffman code")
    }

    private fun stored() {
        bitCount = 0
        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
        val nlen = (src[pos + 2].toInt() and 0xFF) or ((src[pos + 3].toInt() and 0xFF) shl 8)
        require(len == nlen.inv() and 0xFFFF) { "stored block length" }
        pos += 4
        repeat(len) { put(src[pos++].toInt()) }
    }

    private fun codes(lit: Huffman, dist: Huffman) {
        while (true) {
            val s = decode(lit)
            when {
                s < 256 -> put(s)
                s == 256 -> return
                else -> {
                    val k = s - 257
                    require(k < LBASE.size) { "invalid length code $s" }
                    val len = LBASE[k] + bits(LEXT[k])
                    val d = decode(dist)
                    require(d < DBASE.size) { "invalid distance code $d" }
                    val distance = DBASE[d] + bits(DEXT[d])
                    require(distance <= size) { "distance $distance before the start" }
                    maxDistance = maxOf(maxDistance, distance)
                    repeat(len) { put(out[size - distance].toInt()) }
                }
            }
        }
    }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        val cl = IntArray(19)
        for (i in 0 until ncode) cl[ORDER[i]] = bits(3)
        val clCode = Huffman(cl)
        val lengths = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val s = decode(clCode)
            if (s < 16) {
                lengths[i++] = s
            } else {
                val (value, n) = when (s) {
                    16 -> { require(i > 0); lengths[i - 1] to 3 + bits(2) }
                    17 -> 0 to 3 + bits(3)
                    else -> 0 to 11 + bits(7)
                }
                require(i + n <= nlen + ndist) { "code lengths overrun" }
                repeat(n) { lengths[i++] = value }
            }
        }
        codes(Huffman(lengths.copyOf(nlen)), Huffman(lengths.copyOfRange(nlen, nlen + ndist)))
    }

    private fun inflate() {
        do {
            val last = bits(1) == 1
            when (bits(2)) {
                0 -> stored()
                1 -> codes(FIXED_LIT, FIXED_DIST)
                2 -> dynamic()
                else -> error("invalid block type")
            }
        } while (!last)
        bitCount = 0
    }

    companion object {
        private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
        private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
        private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        private val FIXED_LIT = Huffman(IntArray(288) { s -> when { s < 144 -> 8; s < 256 -> 9; s < 280 -> 7; else -> 8 } })
        private val FIXED_DIST = Huffman(IntArray(30) { 5 })

        /** Inflates the zlib stream [z] whole: checks its header, that the data ends it, and its Adler-32. */
        fun zlib(z: ByteArray): Result {
            val cmf = z[0].toInt() and 0xFF
            val flg = z[1].toInt() and 0xFF
            require(cmf and 0x0F == 8) { "not deflate" }
            require((cmf * 256 + flg) % 31 == 0) { "FCHECK" }
            require(flg and 0x20 == 0) { "preset dictionary" }
            val t = TinyInflate(z, 2)
            t.inflate()
            val data = t.out.copyOf(t.size)
            require(t.pos + 4 == z.size) { "trailing bytes" }
            val sum = Adler32().apply { update(data) }.value
            val stored = (0 until 4).fold(0L) { a, k -> (a shl 8) or (z[t.pos + k].toLong() and 0xFF) }
            require(sum == stored) { "Adler-32" }
            return Result(data, t.maxDistance, 1 shl ((cmf shr 4) + 8))
        }
    }
}
