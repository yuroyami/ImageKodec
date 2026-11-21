package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageDecodeException

/** The NAL unit types of ITU-T H.265 Table 7-1 that the decoder acts on. */
internal object NalType {
    /** TRAIL_R: a trailing picture that later pictures may refer to. */
    const val TRAIL_R = 1
    const val RASL_N = 8
    const val RASL_R = 9
    const val BLA_W_LP = 16
    const val IDR_W_RADL = 19
    const val IDR_N_LP = 20
    const val CRA_NUT = 21
    const val RSV_IRAP_VCL23 = 23
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val EOS = 36
    const val SUFFIX_SEI = 40

    /** The coded slice segments. The reserved VCL types 10 to 15 and 22 to 31 are ignored, as 7.4.2.2 asks. */
    fun isSlice(type: Int): Boolean = type in 0..RASL_R || type in BLA_W_LP..CRA_NUT
}

/** The NAL unit header of 7.3.1.2. */
internal class NalHeader(val type: Int, val layerId: Int, val temporalId: Int)

/** One element as the parser read it: the bit where it starts in the RBSP, its name and its value. */
internal class TraceEntry(val bit: Int, val name: String, val value: Long)

/** Throws the decode error of a malformed HEVC header, naming what is wrong. */
internal fun hevcError(message: String): Nothing = throw ImageDecodeException("HEVC: $message")

/**
 * The RBSP of a NAL unit: the bytes of [nal] from [from] to [to] with every emulation prevention
 * byte removed (7.3.1.1). The two header bytes are kept, so bit positions count from the header as
 * the syntax tables do.
 */
internal fun unescapeNal(nal: ByteArray, from: Int = 0, to: Int = nal.size): ByteArray {
    val out = ByteArray(to - from)
    var n = 0
    var zeros = 0
    var i = from
    while (i < to) {
        val b = nal[i].toInt() and 0xFF
        // 7.3.1.1 reads a 0x03 after two zero bytes as emulation_prevention_three_byte, past the header.
        if (zeros >= 2 && b == 3 && i - from >= 2) {
            zeros = 0
            i++
            continue
        }
        zeros = if (b == 0) zeros + 1 else 0
        out[n++] = b.toByte()
        i++
    }
    return if (n == out.size) out else out.copyOf(n)
}

/**
 * The positions, counted from [from] in the escaped bytes of [nal], of the emulation prevention bytes
 * that [unescapeNal] drops. Entry point offsets count those bytes (7.4.7.1), so the slice decoder maps
 * them back.
 */
internal fun emulationPreventionBytes(nal: ByteArray, from: Int = 0, to: Int = nal.size): IntArray {
    var out = IntArray(0)
    var n = 0
    var zeros = 0
    for (i in from until to) {
        val b = nal[i].toInt() and 0xFF
        if (zeros >= 2 && b == 3 && i - from >= 2) {
            if (n == out.size) out = out.copyOf(maxOf(8, n * 2))
            out[n++] = i - from
            zeros = 0
            continue
        }
        zeros = if (b == 0) zeros + 1 else 0
    }
    return out.copyOf(n)
}

/**
 * A reader of the fixed-length and Exp-Golomb codes of an RBSP (7.2, 9.2). Every read checks the
 * end of the data and names the element it reads in its error. With [trace] set, each element is
 * recorded, which the oracle test compares with another parser.
 */
internal class BitReader(private val data: ByteArray, var trace: MutableList<TraceEntry>? = null) {
    /** The position of the next bit, from the first bit of the NAL unit header. */
    var pos: Int = 0
        private set

    val size: Int get() = data.size * 8

    /** The bits from [pos] to the end of the data. */
    val bitsLeft: Int get() = size - pos

    /** The position of the rbsp_stop_one_bit: the last bit equal to 1, or -1 when every bit is 0. */
    val stopBit: Int by lazy {
        var i = data.size - 1
        while (i >= 0 && data[i].toInt() == 0) i--
        if (i < 0) -1 else i * 8 + 7 - (data[i].toInt() and 0xFF).countTrailingZeroBits()
    }

    private fun bits(n: Int, name: String): Int {
        if (n > bitsLeft) hevcError("$name is cut off")
        var v = 0
        repeat(n) {
            v = (v shl 1) or ((data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1)
            pos++
        }
        return v
    }

    private fun record(start: Int, name: String, value: Long) {
        trace?.add(TraceEntry(start, name, value))
    }

    /** u(n) for n up to 31. */
    fun u(n: Int, name: String): Int {
        val start = pos
        val v = bits(n, name)
        record(start, name, v.toLong())
        return v
    }

    /** u(32). */
    fun u32(name: String): Long {
        val start = pos
        val hi = bits(16, name).toLong()
        val v = (hi shl 16) or bits(16, name).toLong()
        record(start, name, v)
        return v
    }

    fun flag(name: String): Boolean = u(1, name) == 1

    /** u(n) whose value must lie in [min]..[max]. */
    fun u(n: Int, name: String, min: Int, max: Int): Int = u(n, name).also { check(name, it.toLong(), min.toLong(), max.toLong()) }

    /** A ue(v) code number (9.2), up to 2^32 - 2. */
    fun ueLong(name: String): Long {
        val start = pos
        val v = codeNum(name)
        record(start, name, v)
        return v
    }

    private fun codeNum(name: String): Long {
        var zeros = 0
        while (bits(1, name) == 0) {
            // 9.2 limits a code number to 32 bits; a longer prefix is no ue(v) code.
            if (++zeros > 31) hevcError("$name is not an Exp-Golomb code")
        }
        return (1L shl zeros) - 1 + (if (zeros == 0) 0L else readLong(zeros, name))
    }

    private fun readLong(n: Int, name: String): Long =
        if (n <= 16) bits(n, name).toLong() else (bits(n - 16, name).toLong() shl 16) or bits(16, name).toLong()

    /** A ue(v) whose value must lie in [min]..[max]. */
    fun ue(name: String, min: Int = 0, max: Int): Int {
        val v = ueLong(name)
        check(name, v, min.toLong(), max.toLong())
        return v.toInt()
    }

    /** A se(v) (9.2.2) whose value must lie in [min]..[max]. */
    fun se(name: String, min: Int, max: Int): Int {
        val start = pos
        val k = codeNum(name)
        // Table 9-3: odd code numbers are positive, even ones negative.
        val v = if (k and 1L == 1L) (k + 1) / 2 else -(k / 2)
        record(start, name, v)
        check(name, v, min.toLong(), max.toLong())
        return v.toInt()
    }

    /** Skips [n] bits that no element reads. */
    fun skip(n: Int, name: String) {
        if (n > bitsLeft) hevcError("$name is cut off")
        pos += n
    }

    /** Skips to the rbsp_stop_one_bit: the extension data that 7.4.3 has a decoder ignore. */
    fun skipToStopBit() {
        if (stopBit < pos) hevcError("the RBSP has no rbsp_stop_one_bit")
        pos = stopBit
    }

    /** True while data remains before the rbsp_stop_one_bit (more_rbsp_data, 7.2). */
    fun moreRbspData(): Boolean = pos < stopBit

    /**
     * rbsp_trailing_bits (7.3.2.11). The parser must stop on the last bit set: a set bit further on
     * means elements were left unread, and none means the unit was cut off before its end.
     */
    fun trailingBits(what: String) {
        if (pos != stopBit) hevcError("the $what does not end where its rbsp_trailing_bits are")
    }

    /** byte_alignment (7.3.2.12) at the end of a slice segment header. */
    fun byteAlignment() {
        if (u(1, "alignment_bit_equal_to_one") != 1) hevcError("alignment_bit_equal_to_one is 0")
        while (pos and 7 != 0) {
            if (u(1, "alignment_bit_equal_to_zero") != 0) hevcError("alignment_bit_equal_to_zero is 1")
        }
    }

    companion object {
        fun check(name: String, v: Long, min: Long, max: Long) {
            if (v < min || v > max) hevcError("$name $v is out of range")
        }
    }
}

/** Reads the NAL unit header (7.3.1.2) of an RBSP [r] that starts at the header. */
internal fun readNalHeader(r: BitReader): NalHeader {
    if (r.u(1, "forbidden_zero_bit") != 0) hevcError("forbidden_zero_bit is 1")
    val type = r.u(6, "nal_unit_type")
    val layer = r.u(6, "nuh_layer_id")
    val tid = r.u(3, "nuh_temporal_id_plus1", 1, 7)
    return NalHeader(type, layer, tid - 1)
}

/**
 * The NAL units of a HEIF sample [data], each behind a big-endian length of [lengthSize] bytes
 * (ISO/IEC 14496-15, 4.3.3), as ranges of [data].
 */
internal fun splitSample(data: ByteArray, lengthSize: Int): List<IntRange> {
    val out = ArrayList<IntRange>()
    var p = 0
    while (p < data.size) {
        if (data.size - p < lengthSize) hevcError("the length of a NAL unit is cut off")
        var length = 0L
        repeat(lengthSize) { length = (length shl 8) or (data[p++].toLong() and 0xFF) }
        if (length > data.size - p) hevcError("a NAL unit of $length bytes passes the end of the sample")
        // A unit shorter than its two header bytes is no NAL unit (7.3.1.1).
        if (length < 2) hevcError("a NAL unit of $length bytes has no header")
        out += p until p + length.toInt()
        p += length.toInt()
    }
    return out
}
