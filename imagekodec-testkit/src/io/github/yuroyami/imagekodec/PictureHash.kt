package io.github.yuroyami.imagekodec

/**
 * The three hashes of a decoded picture that the HEVC decoded picture hash SEI message carries,
 * as ITU-T H.265 (07/2024) D.3.20 defines them for one colour component.
 */
public object PictureHash {

    /**
     * pictureData of D.3.20 (D-22): each sample of a [width] by [height] component, rows [stride]
     * apart, as one byte, or two bytes low first when [depth] is above 8.
     */
    public fun pictureData(samples: ShortArray, offset: Int, stride: Int, width: Int, height: Int, depth: Int): ByteArray {
        val wide = depth > 8
        val out = ByteArray(width * height * (if (wide) 2 else 1))
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val v = samples[offset + y * stride + x].toInt() and 0xFFFF
            out[n++] = v.toByte()
            if (wide) out[n++] = (v ushr 8).toByte()
        }
        return out
    }

    /** The MD5 of [data] (IETF RFC 1321), as D-23 uses it. */
    public fun md5(data: ByteArray): ByteArray = Md5.digest(data)

    /** The CRC of D-24: CCITT polynomial 0x1021 from 0xFFFF, over the data and two zero bytes. */
    public fun crc(data: ByteArray): Int {
        var crc = 0xFFFF
        for (bitIdx in 0 until (data.size + 2) * 8) {
            val byteIdx = bitIdx ushr 3
            val dataByte = if (byteIdx < data.size) data[byteIdx].toInt() and 0xFF else 0
            val crcMsb = (crc ushr 15) and 1
            val bitVal = (dataByte ushr (7 - (bitIdx and 7))) and 1
            crc = (((crc shl 1) + bitVal) and 0xFFFF) xor (crcMsb * 0x1021)
        }
        return crc
    }

    /** The checksum of D-25 over a [width] by [height] component, rows [stride] apart. */
    public fun checksum(samples: ShortArray, offset: Int, stride: Int, width: Int, height: Int, depth: Int): Int {
        var sum = 0
        for (y in 0 until height) for (x in 0 until width) {
            val xorMask = (x and 0xFF) xor (y and 0xFF) xor (x ushr 8) xor (y ushr 8)
            val v = samples[offset + y * stride + x].toInt() and 0xFFFF
            sum += (v and 0xFF) xor xorMask
            if (depth > 8) sum += (v ushr 8) xor xorMask
        }
        return sum
    }
}

/** MD5 (IETF RFC 1321) in pure Kotlin, so the hash checks run on every target. */
internal object Md5 {
    // RFC 1321 section 3.4: T[i] is the integer part of 4294967296 times abs(sin(i)), i in radians.
    private val T = IntArray(64) { i -> (kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong().toInt() }
    private val S = intArrayOf(7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21)

    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bitLength ushr (8 * i)).toByte()
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
                when (i / 16) {
                    0 -> { f = (b and c) or (b.inv() and d); g = i }
                    1 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) and 15 }
                    2 -> { f = b xor c xor d; g = (3 * i + 5) and 15 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) and 15 }
                }
                val rotated = (a + f + T[i] + m[g]).rotateLeft(S[(i / 16) * 4 + (i and 3)])
                a = d
                d = c
                c = b
                b += rotated
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
