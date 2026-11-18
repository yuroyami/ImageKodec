package io.github.yuroyami.imagekodec.codec

/**
 * The boolean entropy encoder of VP8 (RFC 6386 section 7): each call narrows the interval
 * [bottom, bottom + range) by the probability of the bit, and whole bytes leave from the top of
 * [bottom] as they settle. A carry out of [bottom] adds one to the bytes already written.
 */
internal class Vp8BoolWriter(capacity: Int = 1024) {
    private var buf = ByteArray(maxOf(16, capacity))
    private var size = 0
    private var range = 255

    /** Read as an unsigned 32-bit value; bit 31 is the carry into the bytes already written. */
    private var bottom = 0

    /** Shifts left before the next byte leaves; the first byte waits for 24. */
    private var bitCount = 24

    /** Write [bit] (0 or 1), whose probability of being 0 is [prob] / 256. */
    fun put(bit: Int, prob: Int) {
        val split = 1 + (((range - 1) * prob) shr 8)
        if (bit != 0) {
            bottom += split
            range -= split
        } else {
            range = split
        }
        while (range < 128) {
            range = range shl 1
            if (bottom and Int.MIN_VALUE != 0) carry()
            bottom = bottom shl 1
            if (--bitCount == 0) {
                emit(bottom ushr 24)
                bottom = bottom and 0xFFFFFF
                bitCount = 8
            }
        }
    }

    /** [value] in [bits] bits, most significant first, each at probability one half. */
    fun literal(value: Int, bits: Int) {
        for (b in bits - 1 downTo 0) put((value shr b) and 1, 128)
    }

    /** A magnitude of [bits] bits, then its sign, as the frame header writes its deltas. */
    fun signed(value: Int, bits: Int) {
        literal(if (value < 0) -value else value, bits)
        put(if (value < 0) 1 else 0, 128)
    }

    /** A flag, then [value] when it is not zero, as the frame header writes an optional delta. */
    fun optionalSigned(value: Int, bits: Int) {
        if (value == 0) {
            put(0, 128)
        } else {
            put(1, 128)
            signed(value, bits)
        }
    }

    private fun carry() {
        var i = size - 1
        while (i >= 0 && buf[i] == 0xFF.toByte()) {
            buf[i] = 0
            i--
        }
        // A carry past the first byte cannot happen: the interval never leaves [0, 1).
        if (i >= 0) buf[i] = (buf[i] + 1).toByte()
    }

    private fun emit(b: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = b.toByte()
    }

    /**
     * The bytes, after 32 zero bits at probability one half push every pending bit out: the
     * decoder reads ahead of the bits it uses, and a read past the end is an error to it.
     */
    fun finish(): ByteArray {
        repeat(32) { put(0, 128) }
        return buf.copyOf(size)
    }
}
