package io.github.yuroyami.imagekodec.codec

/**
 * A JBIG2 writer for one page coded as one generic region, written from ITU-T T.88: the segment
 * headers of 7.2, the page information segment of 7.4.8, the immediate generic region segment of
 * 7.4.6, the generic region coding of 6.2 with the MQ coder of Annex E, or with T.6 (MMR) coding,
 * and the file header of Annex D.4. Generic coding is lossless.
 *
 * The page comes as rows packed MSB first and padded to a byte, a set bit black, as JBIG2 counts
 * a 1 pixel as foreground.
 */
internal object Jbig2Encoder {

    /** The nominal adaptive template pixels of T.88 6.2.5.3, which jbig2dec and PDF writers use. */
    private val NOMINAL_AT = arrayOf(
        intArrayOf(3, -1, -3, -1, 2, -2, -2, -2),
        intArrayOf(3, -1),
        intArrayOf(2, -1),
        intArrayOf(2, -1),
    )

    /**
     * The context of each template, as `dx, dy` pairs from bit 0 up (T.88 Figures 3 to 6, with the
     * adaptive pixels at their nominal places), the same order [Jbig2Decoder] reads them in.
     */
    private val TEMPLATES = arrayOf(
        intArrayOf(
            -1, 0, -2, 0, -3, 0, -4, 0, 3, -1, 2, -1, 1, -1, 0, -1,
            -1, -1, -2, -1, -3, -1, 2, -2, 1, -2, 0, -2, -1, -2, -2, -2,
        ),
        intArrayOf(-1, 0, -2, 0, -3, 0, 3, -1, 2, -1, 1, -1, 0, -1, -1, -1, -2, -1, 2, -2, 1, -2, 0, -2, -1, -2),
        intArrayOf(-1, 0, -2, 0, 2, -1, 1, -1, 0, -1, -1, -1, -2, -1, 1, -2, 0, -2, -1, -2),
        intArrayOf(-1, 0, -2, 0, -3, 0, -4, 0, 2, -1, 1, -1, 0, -1, -1, -1, -2, -1, -3, -1),
    )

    /** The margin the widest template reaches: four columns to each side and two rows up. */
    private const val MARGIN_X = 4
    private const val MARGIN_Y = 2

    private const val SEGMENT_PAGE_INFORMATION = 48
    private const val SEGMENT_IMMEDIATE_GENERIC_REGION = 38
    private const val SEGMENT_END_OF_PAGE = 49
    private const val SEGMENT_END_OF_FILE = 51

    /** The file header's identification string (T.88 D.4.1). */
    private val FILE_ID = byteArrayOf(0x97.toByte(), 0x4A, 0x42, 0x32, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * The JBIG2 data of the page [rows], [width] by [height] pixels. With [standalone] it is a file
     * of the sequential organisation (T.88 D.4.2): the file header, the page information, the region,
     * the end of the page and the end of the file. Otherwise it is the embedded stream PDF's
     * `JBIG2Decode` takes (ISO 32000-1 7.4.7), the page information and the region alone, since PDF
     * leaves out the file header and the end-of-page and end-of-file segments.
     */
    fun encode(
        rows: ByteArray, width: Int, height: Int, template: Int, typicalPrediction: Boolean, mmr: Boolean,
        standalone: Boolean, cancel: (() -> Unit)?,
    ): ByteArray {
        val coded = if (mmr) {
            encodeCcittRows(rows, width, height, -1, blackIs1 = true, encodedByteAlign = false, endOfLine = false, endOfBlock = false, cancel = cancel)
        } else {
            encodeGeneric(rows, width, height, template, typicalPrediction, cancel)
        }
        val out = Output(coded.size + 128)
        if (standalone) {
            out.bytes(FILE_ID)
            out.u8(0x01) // sequential organisation, the page count known
            out.u32(1)
        }
        var number = 0
        val page = 1

        // Page information (7.4.8): the size, unknown resolution, and the flags: eventually lossless,
        // default pixel 0, default combination operator OR, and no striping.
        val info = Output(19)
        info.u32(width); info.u32(height); info.u32(0); info.u32(0)
        info.u8(0x01)
        info.u16(0)
        out.segment(number++, SEGMENT_PAGE_INFORMATION, page, info.toByteArray())

        // Region segment information (7.4.1) at the page origin with the operator OR, then the
        // generic region flags (7.4.6.2): MMR, the template and typical prediction, which MMR
        // coding leaves at 0, and the adaptive pixels where the coding is arithmetic.
        val region = Output(coded.size + 32)
        region.u32(width); region.u32(height); region.u32(0); region.u32(0)
        region.u8(0)
        if (mmr) {
            region.u8(0x01)
        } else {
            region.u8((template shl 1) or (if (typicalPrediction) 0x08 else 0))
            for (v in NOMINAL_AT[template]) region.u8(v and 0xFF)
        }
        region.bytes(coded)
        out.segment(number++, SEGMENT_IMMEDIATE_GENERIC_REGION, page, region.toByteArray())

        if (standalone) {
            out.segment(number++, SEGMENT_END_OF_PAGE, page, ByteArray(0))
            out.segment(number, SEGMENT_END_OF_FILE, 0, ByteArray(0))
        }
        return out.toByteArray()
    }

    /**
     * Generic region coding with the MQ coder (T.88 6.2.5.7): each pixel coded in the context its
     * template forms from pixels already coded, and with typical prediction, a row equal to the
     * row above (or a white first row) coded as one bit.
     */
    private fun encodeGeneric(
        rows: ByteArray, width: Int, height: Int, template: Int, typicalPrediction: Boolean, cancel: (() -> Unit)?,
    ): ByteArray {
        // One byte a pixel, with a white margin on every side the template reaches, so the context
        // needs no bounds test: T.88 6.2.5.2 counts a pixel outside the region as 0.
        val stride = width + 2 * MARGIN_X
        val padded = ByteArray(stride * (height + MARGIN_Y))
        val bytesPerRow = (width + 7) / 8
        for (y in 0 until height) {
            val base = (y + MARGIN_Y) * stride + MARGIN_X
            for (x in 0 until width) {
                padded[base + x] = ((rows[y * bytesPerRow + (x ushr 3)].toInt() ushr (7 - (x and 7))) and 1).toByte()
            }
        }
        val layout = TEMPLATES[template]
        val offsets = IntArray(layout.size / 2) { layout[2 * it + 1] * stride + layout[2 * it] }
        val cx = IntArray(1 shl 16)
        val mq = MqEncoder(width * height / 32 + 64)
        val tpContext = Jbig2Decoder.TPGDON_CONTEXTS[template]
        var ltp = 0
        for (y in 0 until height) {
            if ((y and 63) == 0) cancel?.invoke()
            val base = (y + MARGIN_Y) * stride + MARGIN_X
            if (typicalPrediction) {
                // The row above the first counts as white, so a white first row is typical too.
                val typical = if (padded.rangeEquals(base, base - stride, width)) 1 else 0
                mq.encode(cx, tpContext, typical xor ltp)
                ltp = typical
                if (typical == 1) continue
            }
            for (x in 0 until width) {
                val at = base + x
                var context = 0
                for (bit in offsets.indices) context = context or (padded[at + offsets[bit]].toInt() shl bit)
                mq.encode(cx, context, padded[at].toInt())
            }
        }
        return mq.finish()
    }

    private fun ByteArray.rangeEquals(a: Int, b: Int, length: Int): Boolean {
        for (i in 0 until length) if (this[a + i] != this[b + i]) return false
        return true
    }

    /** A big-endian byte sink for the segment fields of T.88 7. */
    private class Output(capacity: Int) {
        private var buffer = ByteArray(maxOf(16, capacity))
        private var size = 0

        fun u8(v: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = v.toByte()
        }

        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Int) { u8(v ushr 24); u8(v ushr 16); u8(v ushr 8); u8(v) }

        fun bytes(b: ByteArray) {
            if (size + b.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + b.size))
            b.copyInto(buffer, size)
            size += b.size
        }

        /**
         * A segment header (7.2) and its data: the number, the type with a one-byte page
         * association, no referred-to segments and no retention, the page and the data length.
         */
        fun segment(number: Int, type: Int, page: Int, data: ByteArray) {
            u32(number)
            u8(type)
            u8(0)
            u8(page)
            u32(data.size)
            bytes(data)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
