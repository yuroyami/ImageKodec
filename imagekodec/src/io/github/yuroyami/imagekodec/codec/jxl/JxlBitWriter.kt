package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Writes bits least significant first, as every part of a JPEG XL codestream is packed
 * (ISO/IEC 18181-1, 4.2). The field helpers write what [JxlBitReader] reads: the U32
 * distributions of [fv], [fo] and [fb], U64, F16 and enumerations.
 */
internal class JxlBitWriter(initialCapacity: Int = 256) {
    private var buf = ByteArray(maxOf(16, initialCapacity))
    private var bytes = 0
    private var acc = 0L
    private var accBits = 0

    /** Bits written so far. */
    val bitCount: Long get() = bytes.toLong() * 8 + accBits

    /** Writes the low [n] bits of [value], 56 at most. */
    fun write(n: Int, value: Long) {
        if (n == 0) return
        acc = acc or ((value and ((1L shl n) - 1)) shl accBits)
        accBits += n
        while (accBits >= 8) {
            push(acc.toInt())
            acc = acc ushr 8
            accBits -= 8
        }
    }

    fun write(n: Int, value: Int) {
        if (n <= 32) write(n, value.toLong() and 0xFFFFFFFFL) else write(n, value.toLong())
    }

    fun bool(b: Boolean) = write(1, if (b) 1L else 0L)

    private fun push(b: Int) {
        if (bytes == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bytes++] = b.toByte()
    }

    /** Pads with zero bits to the next byte boundary. */
    fun zeroPadToByte() {
        if (accBits > 0) write(8 - accBits, 0L)
    }

    /** Appends whole bytes; the writer must be at a byte boundary. */
    fun appendBytes(data: ByteArray, from: Int = 0, to: Int = data.size) {
        check(accBits == 0) { "bytes appended off a byte boundary" }
        val n = to - from
        while (bytes + n > buf.size) buf = buf.copyOf(buf.size * 2)
        data.copyInto(buf, bytes, from, to)
        bytes += n
    }

    /** Appends every bit of [other], which need not end on a byte boundary. */
    fun appendBits(other: JxlBitWriter) {
        if (accBits == 0) {
            appendBytes(other.buf, 0, other.bytes)
        } else {
            for (i in 0 until other.bytes) write(8, other.buf[i].toLong() and 0xFF)
        }
        if (other.accBits > 0) write(other.accBits, other.acc)
    }

    /** The bytes written, padded with zero bits. */
    fun toByteArray(): ByteArray {
        val out = buf.copyOf(bytes + if (accBits > 0) 1 else 0)
        if (accBits > 0) out[bytes] = acc.toByte()
        return out
    }

    /**
     * Writes [value] with the first of the four distributions that can hold it. A distribution
     * is [fv], [fo] or [fb] as [JxlBitReader.u32] reads them.
     */
    fun u32(value: Int, d0: Int, d1: Int, d2: Int, d3: Int) {
        val ds = intArrayOf(d0, d1, d2, d3)
        val v = value.toLong() and 0xFFFFFFFFL
        for (i in 0 until 4) {
            val d = ds[i]
            if (d < 0) {
                if ((d and Int.MAX_VALUE).toLong() == v) {
                    write(2, i)
                    return
                }
            } else {
                val bits = (d and 0x1F) + 1
                val offset = ((d ushr 5) and 0x3FFFFFF).toLong()
                if (v >= offset && v - offset < (1L shl bits)) {
                    write(2, i)
                    write(bits, v - offset)
                    return
                }
            }
        }
        throw IllegalArgumentException("JPEG XL: $value has no U32 encoding here")
    }

    fun u64(value: Long) {
        when {
            value == 0L -> write(2, 0)
            value <= 16 -> {
                write(2, 1)
                write(4, value - 1)
            }
            value <= 272 -> {
                write(2, 2)
                write(8, value - 17)
            }
            else -> {
                write(2, 3)
                write(12, value and 0xFFF)
                var rest = value ushr 12
                var shift = 12
                while (rest != 0L) {
                    write(1, 1)
                    if (shift == 60) {
                        write(4, rest and 0xF)
                        return
                    }
                    write(8, rest and 0xFF)
                    rest = rest ushr 8
                    shift += 8
                }
                write(1, 0)
            }
        }
    }

    /** An enumerated value, as [JxlBitReader.enumValue] reads it. */
    fun enumValue(v: Int) = u32(v, fv(0), fv(1), fo(4, 2), fo(6, 18))

    /** A half-precision float. [value] must be exactly representable, which every caller ensures. */
    fun f16(value: Double) = write(16, jxlHalfBits(value))

    /** An empty extension field of a bundle. */
    fun noExtensions() = u64(0)
}

/**
 * The bits of the half-precision float nearest [value], rounding to nearest even. Only + - *
 * and / are used, so every target gives the same bits.
 */
internal fun jxlHalfBits(value: Double): Int {
    if (value == 0.0) return 0
    val sign = if (value < 0) 0x8000 else 0
    var a = if (value < 0) -value else value
    var exp = 15
    while (a >= 2.0 && exp < 31) {
        a /= 2.0
        exp++
    }
    while (a < 1.0 && exp > 0) {
        a *= 2.0
        exp--
    }
    require(exp < 31) { "JPEG XL: $value does not fit a half float" }
    return if (exp == 0) {
        // Subnormal: a holds value * 2^15, and the mantissa counts steps of 2^-24.
        val m = roundHalfEven(a * 512.0)
        sign or m
    } else {
        var m = roundHalfEven((a - 1.0) * 1024.0)
        var e = exp
        if (m == 1024) {
            m = 0
            e++
        }
        require(e < 31) { "JPEG XL: $value does not fit a half float" }
        sign or (e shl 10) or m
    }
}

private fun roundHalfEven(x: Double): Int {
    val f = kotlin.math.floor(x)
    val d = x - f
    val i = f.toInt()
    return when {
        d > 0.5 -> i + 1
        d < 0.5 -> i
        else -> if (i and 1 == 0) i else i + 1
    }
}

/** The signed value [v] as JPEG XL packs it: `2 * |v|`, less one when negative. The inverse of [unpackSigned]. */
internal fun packSigned(v: Int): Int = (v shl 1) xor (v shr 31)
