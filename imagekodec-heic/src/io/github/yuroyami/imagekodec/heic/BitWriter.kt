package io.github.yuroyami.imagekodec.heic

/**
 * A writer of the fixed-length and Exp-Golomb codes of an RBSP (7.2, 9.2), the counterpart of [BitReader].
 * Bits go in from the most significant bit of each byte.
 */
internal class BitWriter(capacity: Int = 64) {
    private var data = ByteArray(maxOf(capacity, 16))
    /** The number of bits written. */
    var bits: Int = 0
        private set

    private fun ensure(more: Int) {
        val need = (bits + more + 7) shr 3
        if (need > data.size) data = data.copyOf(maxOf(need, data.size * 2))
    }

    /** u(n) of the low [n] bits of [v], n up to 32. */
    fun u(n: Int, v: Int) {
        ensure(n)
        for (i in n - 1 downTo 0) {
            if ((v ushr i) and 1 != 0) {
                val p = bits shr 3
                data[p] = (data[p].toInt() or (0x80 ushr (bits and 7))).toByte()
            }
            bits++
        }
    }

    fun flag(b: Boolean) = u(1, if (b) 1 else 0)

    /** ue(v), 9.2, of a code number up to 2^31 - 2. */
    fun ue(v: Int) {
        require(v >= 0) { "ue(v) of $v" }
        val x = v.toLong() + 1
        val len = 63 - x.countLeadingZeroBits()
        u(len, 0)
        // The code number plus one, with its leading 1, in len + 1 bits.
        if (len >= 31) {
            u(len + 1 - 16, (x ushr 16).toInt())
            u(16, (x and 0xFFFF).toInt())
        } else {
            u(len + 1, x.toInt())
        }
    }

    /** se(v), 9.2.2: positive values on the odd code numbers, Table 9-3. */
    fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)

    /** rbsp_trailing_bits( ), 7.3.2.11. */
    fun trailingBits() {
        u(1, 1)
        alignZero()
    }

    /** byte_alignment( ), 7.3.2.12, at the end of a slice segment header. */
    fun byteAlignment() {
        u(1, 1)
        alignZero()
    }

    /** Zero bits up to the next byte boundary. */
    fun alignZero() {
        if (bits and 7 != 0) u(8 - (bits and 7), 0)
    }

    /** The whole bytes written; the writer must stand on a byte boundary. */
    fun toByteArray(): ByteArray {
        check(bits and 7 == 0) { "the RBSP does not end on a byte boundary" }
        return data.copyOf(bits shr 3)
    }

    /** Appends whole bytes [b]; the writer must stand on a byte boundary. */
    fun bytes(b: ByteArray) {
        check(bits and 7 == 0) { "bytes written off a byte boundary" }
        ensure(b.size * 8)
        b.copyInto(data, bits shr 3)
        bits += b.size * 8
    }
}

/**
 * The NAL unit of an RBSP [rbsp] that starts with its two header bytes: an emulation_prevention_three_byte
 * after every two zero bytes that a byte of 0 to 3 follows, and after a final zero byte (7.3.1.1, 7.4.2).
 */
internal fun escapeNal(rbsp: ByteArray): ByteArray {
    val out = ByteArray(rbsp.size + rbsp.size / 2 + 2)
    var n = 0
    var zeros = 0
    for (i in rbsp.indices) {
        val b = rbsp[i].toInt() and 0xFF
        if (zeros >= 2 && b <= 3 && i >= 2) {
            out[n++] = 3
            zeros = 0
        }
        out[n++] = b.toByte()
        zeros = if (b == 0) zeros + 1 else 0
    }
    if (zeros > 0) out[n++] = 3
    return out.copyOf(n)
}

/** MD5 (IETF RFC 1321), which the decoded picture hash SEI message uses (D.3.20). */
internal object Md5 {
    private val S = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)

    /** The 16 bytes of the digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()
        val t = HevcEncoderTables.MD5_T
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        for (chunk in 0 until padded.size / 64) {
            for (i in 0 until 16) {
                val p = chunk * 64 + i * 4
                m[i] = (padded[p].toInt() and 0xFF) or ((padded[p + 1].toInt() and 0xFF) shl 8) or
                    ((padded[p + 2].toInt() and 0xFF) shl 16) or ((padded[p + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i shr 4) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) and 15 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) and 15 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) and 15 }
                }
                val s = S[(i shr 4) * 4 + (i and 3)]
                val sum = a + f + t[i] + m[g]
                a = d
                d = c
                c = b
                b += (sum shl s) or (sum ushr (32 - s))
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        for ((k, v) in intArrayOf(a0, b0, c0, d0).withIndex()) for (i in 0 until 4) out[k * 4 + i] = (v ushr (8 * i)).toByte()
        return out
    }
}
