package io.github.yuroyami.imagekodec.codec.jxl

/**
 * The two shapes a JPEG XL file takes (ISO/IEC 18181-2): a bare codestream that starts
 * `FF 0A`, or a file of ISO base media boxes whose `jxlc` box, or `jxlp` boxes in order,
 * hold the codestream.
 */
internal object JxlContainer {

    private val SIGNATURE = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte(), 0x0D, 0x0A, 0x87.toByte(), 0x0A)

    private const val JXLC = 0x6A786C63
    private const val JXLP = 0x6A786C70

    fun isCodestream(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x0A.toByte()

    fun isContainer(data: ByteArray): Boolean {
        if (data.size < SIGNATURE.size) return false
        for (i in SIGNATURE.indices) if (data[i] != SIGNATURE[i]) return false
        return true
    }

    /**
     * The codestream of a file: bytes [start] until [end] of [data]. A file that ends inside a
     * box, as the first bytes of a file do, gives the part of the codestream it holds.
     */
    class Codestream(val data: ByteArray, val start: Int, val end: Int)

    fun codestream(data: ByteArray): Codestream {
        if (isCodestream(data)) return Codestream(data, 0, data.size)
        if (!isContainer(data)) jxlFail("neither a codestream nor a container")
        // One 'jxlc' box is used in place; 'jxlp' boxes are joined.
        var parts: ArrayList<IntArray>? = null
        var complete = true
        var sawLast = false
        var pos = SIGNATURE.size.toLong()
        while (pos + 8 <= data.size) {
            val at = pos.toInt()
            var size = u32(data, at)
            val type = u32(data, at + 4).toInt()
            var header = 8
            if (size == 1L) {
                if (pos + 16 > data.size) {
                    complete = false
                    break
                }
                size = (u32(data, at + 8) shl 32) or u32(data, at + 12)
                header = 16
                // Past this size the end of the box wraps around, and the walk would go backwards.
                if (size < 0 || size > Long.MAX_VALUE - pos) jxlFail("box of more than 2^63 bytes")
            }
            // A size of 0 means the box runs to the end of the file.
            val boxEnd = if (size == 0L) data.size.toLong() else pos + size
            if (size != 0L && size < header) jxlFail("box smaller than its header")
            val payload = at + header
            val payloadEnd = minOf(boxEnd, data.size.toLong()).toInt()
            if (boxEnd > data.size) complete = false
            if (type == JXLC) {
                if (parts != null) jxlFail("a 'jxlc' box beside 'jxlp' boxes")
                return Codestream(data, payload, payloadEnd)
            }
            if (type == JXLP) {
                if (payloadEnd - payload < 4) {
                    if (complete) jxlFail("'jxlp' box without its index")
                    break
                }
                if (sawLast) jxlFail("a 'jxlp' box after the last one")
                // The high bit of the index marks the last part.
                if (data[payload].toInt() and 0x80 != 0) sawLast = true
                (parts ?: ArrayList<IntArray>().also { parts = it }).add(intArrayOf(payload + 4, payloadEnd))
            }
            if (boxEnd > data.size) break
            pos = boxEnd
        }
        val list = parts ?: jxlFail("container without a codestream box")
        if (list.size == 1) return Codestream(data, list[0][0], list[0][1])
        var total = 0L
        for (p in list) total += p[1] - p[0]
        val joined = ByteArray(total.toInt())
        var at = 0
        for (p in list) {
            data.copyInto(joined, at, p[0], p[1])
            at += p[1] - p[0]
        }
        return Codestream(joined, 0, joined.size)
    }

    private fun u32(d: ByteArray, at: Int): Long =
        ((d[at].toLong() and 0xFF) shl 24) or ((d[at + 1].toLong() and 0xFF) shl 16) or
            ((d[at + 2].toLong() and 0xFF) shl 8) or (d[at + 3].toLong() and 0xFF)
}
