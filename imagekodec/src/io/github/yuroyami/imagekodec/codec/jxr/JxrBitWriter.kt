package io.github.yuroyami.imagekodec.codec.jxr

/** Writes bits most significant first, as ITU-T T.832 packs every part of a codestream and [JxrBits] reads it. */
internal class JxrBitWriter(capacity: Int = 1024) {
    private var buf = ByteArray(maxOf(capacity, 16))
    private var size = 0
    // At most 7 bits wait here between writes, so 16 more fit an Int; Kotlin/JS does Long in software.
    private var acc = 0
    private var count = 0

    /** Bits written so far. */
    val bitLength: Long get() = size.toLong() * 8 + count

    /** The low [n] bits of [value], 0 to 32. */
    fun bits(value: Int, n: Int) {
        if (n > 16) {
            bits(value ushr 16, n - 16)
            bits(value and 0xFFFF, 16)
            return
        }
        if (n == 0) return
        acc = (acc shl n) or (value and ((1 shl n) - 1))
        count += n
        while (count >= 8) {
            count -= 8
            put(acc ushr count)
        }
        acc = acc and ((1 shl count) - 1)
    }

    fun bit(b: Boolean) = bits(if (b) 1 else 0, 1)

    /** A code of [JxrVlc.codes]: the code in the high bits and its length in the low 5. */
    fun code(packed: Int) = bits(packed ushr 5, packed and 31)

    fun alignToByte() {
        if (count > 0) bits(0, 8 - count)
    }

    fun bytes(b: ByteArray) {
        check(count == 0)
        for (x in b) put(x.toInt())
    }

    private fun put(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    /** The bytes written, with the last byte padded with zeros. */
    fun toByteArray(): ByteArray {
        alignToByte()
        return buf.copyOf(size)
    }
}
