package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ImageDecodeException

/** A four-character code as the big-endian integer of its bytes. */
internal fun fourcc(s: String): Int =
    (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code

internal fun fourccName(v: Int): String =
    CharArray(4) { (((v ushr (24 - 8 * it)) and 0xFF)).toChar() }.concatToString()

/** One box of an ISO base media file (ISO/IEC 14496-12 section 4.2): its type and its payload's span. */
internal class IsoBox(val type: Int, val start: Int, val end: Int) {
    val size: Int get() = end - start
    override fun toString(): String = "'${fourccName(type)}' $start..$end"
}

/**
 * A bounds-checked big-endian reader over [data] from [pos] to [end], whose failures name
 * the box being read. Every length a file gives is checked before it is used, so a hostile
 * size can neither run past the data nor make an allocation.
 */
internal class IsoReader(val data: ByteArray, var pos: Int, val end: Int, private val context: String) {

    constructor(data: ByteArray, box: IsoBox, context: String = fourccName(box.type)) : this(data, box.start, box.end, context)

    val remaining: Int get() = end - pos

    fun fail(message: String): Nothing = throw ImageDecodeException("AVIF: '$context' $message")

    private fun need(n: Int) {
        if (n < 0 || n > end - pos) fail("is cut off")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun s32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 8
        if (v < 0) fail("holds a 64-bit value past 2^63")
        return v
    }

    /** An unsigned field of [bytes] bytes: 0, 1, 2, 4 or 8, as `iloc` sizes its fields. */
    fun sized(bytes: Int): Long = when (bytes) {
        0 -> 0L
        1 -> u8().toLong()
        2 -> u16().toLong()
        4 -> u32()
        8 -> u64()
        else -> fail("has a field of $bytes bytes")
    }

    fun fourcc(): Int = u32().toInt()

    fun skip(n: Int) {
        need(n)
        pos += n
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** A NUL-terminated UTF-8 string; one the box ends before its NUL ends with the box. */
    fun string(): String {
        val from = pos
        while (pos < end && data[pos] != 0.toByte()) pos++
        val s = data.decodeToString(from, pos)
        if (pos < end) pos++
        return s
    }

    /** A FullBox's version and flags. */
    fun fullBox(): Pair<Int, Int> {
        val v = u32().toInt()
        return (v ushr 24) to (v and 0xFFFFFF)
    }

    /** The boxes from here to [end], in order. */
    fun boxes(): List<IsoBox> {
        val out = ArrayList<IsoBox>()
        while (pos < end) {
            if (end - pos < 8) fail("ends with ${end - pos} stray bytes")
            val start = pos
            var size = u32()
            val type = fourcc()
            if (size == 1L) size = u64()
            else if (size == 0L) size = (end - start).toLong()
            if (type == UUID) skip(16)
            val header = pos - start
            if (size < header || size > (end - start).toLong()) {
                fail("holds a '${fourccName(type)}' box of $size bytes in ${end - start}")
            }
            out += IsoBox(type, pos, start + size.toInt())
            pos = start + size.toInt()
        }
        return out
    }

    companion object {
        val UUID = fourcc("uuid")

        /** The boxes of [box]'s payload, after a FullBox header when [full]. */
        fun children(data: ByteArray, box: IsoBox, full: Boolean = false): List<IsoBox> {
            val r = IsoReader(data, box)
            if (full) r.fullBox()
            return r.boxes()
        }
    }
}
