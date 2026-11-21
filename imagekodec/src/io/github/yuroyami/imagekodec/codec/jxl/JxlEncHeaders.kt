package io.github.yuroyami.imagekodec.codec.jxl

/** What the image header of an encoded file says (18181-1 A.6 ImageMetadata). */
internal class JxlEncImage(
    val width: Int,
    val height: Int,
    val bits: Int,
    val gray: Boolean,
    val alpha: Boolean,
    val xyb: Boolean,
    /** Ticks per second as numerator and denominator, and the play count, for an animation. */
    val animation: IntArray?,
    val loops: Long,
    val icc: ByteArray?,
    /** A black extra channel (kBlack) ahead of alpha: the colour channels are then C, M and Y. */
    val black: Boolean = false,
) {
    val extraChannels: Int get() = (if (black) 1 else 0) + (if (alpha) 1 else 0)
}

/** The fields of one frame header the encoder sets (18181-1 C.2 FrameHeader). */
internal class JxlEncFrame(
    val modular: Boolean,
    val groupSizeShift: Int = 1,
    val xQmScale: Int = 3,
    val bQmScale: Int = 2,
    val x0: Int = 0,
    val y0: Int = 0,
    /** The frame's size when it is not the image's, else 0. */
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
    /** The reference slot a cropped frame takes the rest of the image from. */
    val source: Int = 0,
    val duration: Long = 0,
    val isLast: Boolean = true,
    val saveAsReference: Int = 0,
    val gaborish: Boolean = false,
    val epfIters: Int = 0,
    val skipLfSmoothing: Boolean = false,
)

/**
 * Writes the parts of a codestream and of its container that surround the frames' data: the
 * image header, the colour encoding and ICC profile, frame headers, tables of contents and the
 * ISO BMFF boxes of 18181-2. Each field is written as [JxlHeaders] and [JxlFrameHeader] read it.
 */
internal object JxlEncHeaders {

    private val RATIO_NUM = intArrayOf(1, 12, 4, 3, 16, 5, 2)
    private val RATIO_DEN = intArrayOf(1, 10, 3, 2, 9, 4, 1)

    private fun writeSize(bw: JxlBitWriter, w: Int, h: Int) {
        val small = w % 8 == 0 && h % 8 == 0 && w in 8..256 && h in 8..256
        var ratio = 0
        for (r in 1..7) {
            if (h.toLong() * RATIO_NUM[r - 1] % RATIO_DEN[r - 1] == 0L && h.toLong() * RATIO_NUM[r - 1] / RATIO_DEN[r - 1] == w.toLong()) {
                ratio = r
                break
            }
        }
        bw.bool(small)
        if (small) bw.write(5, h / 8 - 1) else bw.u32(h, fo(9, 1), fo(13, 1), fo(18, 1), fo(30, 1))
        bw.write(3, ratio)
        if (ratio == 0) {
            if (small) bw.write(5, w / 8 - 1) else bw.u32(w, fo(9, 1), fo(13, 1), fo(18, 1), fo(30, 1))
        }
    }

    private fun writeBitDepth(bw: JxlBitWriter, bits: Int) {
        bw.bool(false)
        bw.u32(bits, fv(8), fv(10), fv(12), fo(6, 1))
    }

    /** The codestream's signature and headers up to the first frame, padded to a byte. */
    fun writeImageHeader(bw: JxlBitWriter, im: JxlEncImage) {
        bw.write(8, 0xFF)
        bw.write(8, 0x0A)
        writeSize(bw, im.width, im.height)
        val allDefault = im.bits == 8 && im.extraChannels == 0 && im.xyb && !im.gray && im.animation == null && im.icc == null
        bw.bool(allDefault)
        if (!allDefault) {
            val extraFields = im.animation != null
            bw.bool(extraFields)
            if (extraFields) {
                bw.write(3, 0) // orientation 1
                bw.bool(false) // no intrinsic size
                bw.bool(false) // no preview
                bw.bool(true)
                val a = im.animation
                bw.u32(a[0], fv(100), fv(1000), fo(10, 1), fo(30, 1))
                bw.u32(a[1], fv(1), fv(1001), fo(8, 1), fo(10, 1))
                bw.u32(im.loops.toInt(), fv(0), fb(3), fb(16), fb(32))
                bw.bool(false) // no timecodes
            }
            writeBitDepth(bw, im.bits)
            bw.bool(im.bits <= 12)
            bw.u32(im.extraChannels, fv(0), fv(1), fo(4, 2), fo(12, 1))
            if (im.black) {
                bw.bool(false)
                bw.enumValue(JxlExtraChannel.BLACK)
                writeBitDepth(bw, im.bits)
                bw.u32(0, fv(0), fv(3), fv(4), fo(3, 1)) // not subsampled
                bw.u32(0, fv(0), fb(4), fo(5, 16), fo(10, 48)) // no name
            }
            if (im.alpha) {
                if (im.bits == 8) {
                    bw.bool(true) // the default: 8-bit straight alpha
                } else {
                    bw.bool(false)
                    bw.enumValue(JxlExtraChannel.ALPHA)
                    writeBitDepth(bw, im.bits)
                    bw.u32(0, fv(0), fv(3), fv(4), fo(3, 1))
                    bw.u32(0, fv(0), fb(4), fo(5, 16), fo(10, 48))
                    bw.bool(false) // not premultiplied
                }
            }
            bw.bool(im.xyb)
            writeColorEncoding(bw, im)
            if (extraFields) bw.bool(true) // default tone mapping
            bw.noExtensions()
        }
        bw.bool(true) // default transform data
        if (im.icc != null) writeIcc(bw, im.icc)
        bw.zeroPadToByte()
    }

    private fun writeColorEncoding(bw: JxlBitWriter, im: JxlEncImage) {
        if (im.icc == null && !im.gray) {
            bw.bool(true) // sRGB
            return
        }
        bw.bool(false)
        bw.bool(im.icc != null)
        bw.enumValue(if (im.gray) JxlColorEncoding.GRAY else JxlColorEncoding.RGB)
        if (im.icc != null) return
        bw.enumValue(JxlColorEncoding.WHITE_D65)
        bw.bool(false) // no pure gamma
        bw.enumValue(JxlColorEncoding.TF_SRGB)
        bw.enumValue(1) // relative colorimetric
    }

    /**
     * The ICC profile (18181-1 E.4): its header as differences from the predicted header, then
     * the rest copied by one command, entropy coded with the 41 contexts the decoder reads.
     */
    private fun writeIcc(bw: JxlBitWriter, icc: ByteArray) {
        val size = icc.size
        val enc = ArrayList<Byte>(size + 32)
        fun varint(v: Long, into: MutableList<Byte>) {
            var x = v
            while (x >= 128) {
                into.add(((x and 127) or 128).toByte())
                x = x ushr 7
            }
            into.add(x.toByte())
        }
        val commands = ArrayList<Byte>()
        if (size > 128) {
            varint(0, commands) // no tag table prediction
            commands.add(1)
            varint((size - 128).toLong(), commands)
        }
        varint(size.toLong(), enc)
        varint(commands.size.toLong(), enc)
        enc.addAll(commands)
        // The header, predicted as the decoder predicts it from the bytes before.
        val header = ByteArray(128)
        header[0] = (size ushr 24).toByte()
        header[1] = (size ushr 16).toByte()
        header[2] = (size ushr 8).toByte()
        header[3] = size.toByte()
        header[8] = 4
        "mntrRGB XYZ ".forEachIndexed { i, c -> header[12 + i] = c.code.toByte() }
        "acsp".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        header[70] = 246.toByte()
        header[71] = 214.toByte()
        header[73] = 1
        header[78] = 211.toByte()
        header[79] = 45
        for (i in 0 until minOf(128, size)) {
            if (i == 8) for (k in 0 until 4) header[80 + k] = icc[4 + k]
            if (i == 41) {
                if (icc[40] == 'A'.code.toByte()) "PPL".forEachIndexed { k, c -> header[41 + k] = c.code.toByte() }
                if (icc[40] == 'M'.code.toByte()) "SFT".forEachIndexed { k, c -> header[41 + k] = c.code.toByte() }
            }
            if (i == 42) {
                if (icc[40] == 'S'.code.toByte() && icc[41] == 'G'.code.toByte()) "I ".forEachIndexed { k, c -> header[42 + k] = c.code.toByte() }
                if (icc[40] == 'S'.code.toByte() && icc[41] == 'U'.code.toByte()) "NW".forEachIndexed { k, c -> header[42 + k] = c.code.toByte() }
            }
            enc.add((icc[i] - header[i]).toByte())
        }
        for (i in 128 until size) enc.add(icc[i])
        bw.u64(enc.size.toLong())
        val tokens = JxlTokens(enc.size)
        var b1 = 0
        var b2 = 0
        for (i in enc.indices) {
            val ctx = if (i <= 128) 0 else 1 + byteKind1(b1) + byteKind2(b2) * 8
            val b = enc[i].toInt() and 0xFF
            tokens.add(ctx, b)
            b2 = b1
            b1 = b
        }
        val code = JxlEntropyEncoder.build(listOf(tokens), 41, 2)
        code.writeHeader(bw)
        code.write(bw, tokens)
    }

    private fun byteKind1(b: Int): Int = when {
        b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code -> 0
        b in '0'.code..'9'.code || b == '.'.code || b == ','.code -> 1
        b == 0 -> 2
        b == 1 -> 3
        b < 16 -> 4
        b == 255 -> 6
        b > 240 -> 5
        else -> 7
    }

    private fun byteKind2(b: Int): Int = when {
        b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code -> 0
        b in '0'.code..'9'.code || b == '.'.code || b == ','.code -> 1
        b < 16 -> 2
        b > 240 -> 3
        else -> 4
    }

    private fun writeBlending(bw: JxlBitWriter, numExtra: Int, partial: Boolean, source: Int) {
        bw.u32(JxlBlendingInfo.REPLACE, fv(0), fv(1), fv(2), fo(2, 3))
        if (partial) bw.u32(source, fv(0), fv(1), fv(2), fv(3))
    }

    /** Writes a regular frame's header for an image described by [im]. */
    fun writeFrameHeader(bw: JxlBitWriter, im: JxlEncImage, f: JxlEncFrame) {
        bw.bool(false) // not all default
        bw.u32(JxlFrameHeader.REGULAR_FRAME, fv(0), fv(1), fv(2), fv(3))
        bw.bool(f.modular)
        bw.u64(if (f.skipLfSmoothing) JxlFrameHeader.FLAG_SKIP_ADAPTIVE_DC_SMOOTHING else 0L)
        if (!im.xyb) bw.bool(false) // no YCbCr
        bw.u32(1, fv(1), fv(2), fv(4), fv(8))
        for (i in 0 until im.extraChannels) bw.u32(1, fv(1), fv(2), fv(4), fv(8))
        if (f.modular) bw.write(2, f.groupSizeShift)
        if (!f.modular && im.xyb) {
            bw.write(3, f.xQmScale)
            bw.write(3, f.bQmScale)
        }
        bw.u32(1, fv(1), fv(2), fv(3), fo(3, 4)) // one pass
        val custom = f.cropWidth != 0
        bw.bool(custom)
        var partial = false
        if (custom) {
            val d0 = fb(8)
            val d1 = fo(11, 256)
            val d2 = fo(14, 2304)
            val d3 = fo(30, 18688)
            bw.u32(packSigned(f.x0), d0, d1, d2, d3)
            bw.u32(packSigned(f.y0), d0, d1, d2, d3)
            bw.u32(f.cropWidth, d0, d1, d2, d3)
            bw.u32(f.cropHeight, d0, d1, d2, d3)
            partial = f.x0 > 0 || f.y0 > 0 || f.cropWidth.toLong() + f.x0 < im.width || f.cropHeight.toLong() + f.y0 < im.height
        }
        writeBlending(bw, im.extraChannels, partial, f.source)
        for (i in 0 until im.extraChannels) writeBlending(bw, im.extraChannels, partial, f.source)
        if (im.animation != null) bw.u32(f.duration.toInt(), fv(0), fv(1), fb(8), fb(32))
        bw.bool(f.isLast)
        if (!f.isLast) bw.u32(f.saveAsReference, fv(0), fv(1), fv(2), fv(3))
        val referenced = !f.isLast && (f.duration == 0L || f.saveAsReference != 0)
        if (referenced && !partial) bw.bool(false) // saved after the colour transform
        bw.u32(0, fv(0), fb(4), fo(5, 16), fo(10, 48)) // no name
        // The loop filter.
        val lfDefault = f.gaborish && f.epfIters == 2 && !f.modular
        bw.bool(lfDefault)
        if (!lfDefault) {
            bw.bool(f.gaborish)
            if (f.gaborish) bw.bool(false) // default weights
            bw.write(2, f.epfIters)
            if (f.epfIters > 0) {
                if (!f.modular) bw.bool(false) // default sharpness table
                bw.bool(false) // default channel scales
                bw.bool(false) // default sigmas
                if (f.modular) bw.f16(1.0)
            }
            bw.noExtensions()
        }
        bw.noExtensions()
    }

    /** Writes the table of contents of [sections] and the sections after it. */
    fun writeSections(bw: JxlBitWriter, sections: List<ByteArray>) {
        bw.bool(false) // not permuted
        bw.zeroPadToByte()
        for (s in sections) bw.u32(s.size, fb(10), fo(14, 1024), fo(22, 17408), fo(30, 4211712))
        bw.zeroPadToByte()
        for (s in sections) bw.appendBytes(s)
    }

    private fun box(out: JxlBitWriter, type: String, payload: ByteArray) {
        val size = 8L + payload.size
        require(size <= 0xFFFFFFFFL) { "JPEG XL: box of $size bytes" }
        out.write(32, bigEndian(size))
        for (c in type) out.write(8, c.code)
        out.appendBytes(payload)
    }

    /** [v] with its four bytes reversed, so that [JxlBitWriter.write], which packs the low byte first, writes it big-endian. */
    private fun bigEndian(v: Long): Long =
        ((v ushr 24) and 0xFF) or (((v ushr 16) and 0xFF) shl 8) or (((v ushr 8) and 0xFF) shl 16) or ((v and 0xFF) shl 24)

    /**
     * The ISO BMFF file of 18181-2: the signature box, `ftyp`, then `Exif` and `xml ` boxes
     * when there is metadata, then the codestream in one `jxlc` box.
     */
    fun container(codestream: ByteArray, exif: ByteArray?, xmp: ByteArray?): ByteArray {
        val out = JxlBitWriter(codestream.size + 256)
        box(out, "JXL ", byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A))
        box(out, "ftyp", "jxl ".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "jxl ".encodeToByteArray())
        if (exif != null) box(out, "Exif", byteArrayOf(0, 0, 0, 0) + exif)
        if (xmp != null) box(out, "xml ", xmp)
        box(out, "jxlc", codestream)
        return out.toByteArray()
    }
}
