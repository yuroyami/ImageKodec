package io.github.yuroyami.imagekodec.codec.avif

/** Writes the fixed-width fields of the AV1 syntax, high bit first (specification section 4.10 and 8.1), for #176. */
internal class Av1BitWriter {
    private var buf = ByteArray(64)
    private var bitPos = 0

    val bytePosition: Int get() = (bitPos + 7) ushr 3

    fun bit(v: Int) {
        val byte = bitPos ushr 3
        if (byte == buf.size) buf = buf.copyOf(buf.size * 2)
        if (v != 0) buf[byte] = (buf[byte].toInt() or (0x80 ushr (bitPos and 7))).toByte()
        bitPos++
    }

    /** `f(n)`: [v] in [n] bits. */
    fun f(n: Int, v: Int) {
        for (i in n - 1 downTo 0) bit((v ushr i) and 1)
    }

    fun flag(v: Boolean) = bit(if (v) 1 else 0)

    /** `su(n)`: [v] in two's complement in [n] bits. */
    fun su(n: Int, v: Int) = f(n, v and ((1 shl n) - 1))

    /** `trailing_bits`: a one, then zeros to the byte. */
    fun trailingBits() {
        bit(1)
        while (bitPos and 7 != 0) bit(0)
    }

    fun byteAlignment() {
        while (bitPos and 7 != 0) bit(0)
    }

    fun bytes(b: ByteArray) {
        check(bitPos and 7 == 0) { "bytes at a bit position" }
        for (x in b) f(8, x.toInt() and 0xFF)
    }

    fun toByteArray(): ByteArray = buf.copyOf(bytePosition)

    companion object {
        /** `leb128` of [v], as an OBU's size is written. */
        fun leb128(v: Int): ByteArray {
            val out = ArrayList<Byte>()
            var x = v
            do {
                var b = x and 0x7F
                x = x ushr 7
                if (x != 0) b = b or 0x80
                out += b.toByte()
            } while (x != 0)
            return out.toByteArray()
        }

        /** An OBU of [type] with its size field and [payload] (section 5.3), no extension. */
        fun obu(type: Int, payload: ByteArray): ByteArray =
            byteArrayOf(((type shl 3) or 2).toByte()) + leb128(payload.size) + payload
    }
}

/**
 * What the AV1 encoder writes in its sequence and frame headers (#176): a still picture of one key frame with
 * `reduced_still_picture_header`, as libavif writes a still image.
 */
internal class Av1StreamParams(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val monochrome: Boolean,
    val subX: Int,
    val subY: Int,
    /** colour_primaries, transfer_characteristics and matrix_coefficients, or null to signal none. */
    val colour: IntArray?,
    val fullRange: Boolean,
    val enableFilterIntra: Boolean,
    val enableCdef: Boolean,
    val enableRestoration: Boolean,
) {
    /** seq_profile: 0 for 4:2:0 and gray up to 10 bits, 1 for 4:4:4 up to 10 bits, 2 for 12 bits and 4:2:2. */
    val profile: Int = when {
        bitDepth == 12 -> 2
        monochrome -> 0
        subX == 1 && subY == 1 -> 0
        subX == 0 && subY == 0 -> 1
        else -> 2
    }

    /**
     * seq_level_idx: the lowest level of Annex A whose picture size and sides hold the picture (2.0, 3.0, 4.0, 5.0,
     * 6.0), or 31, the level of no limits, for a larger one.
     */
    val level: Int = run {
        val area = width.toLong() * height
        val levels = arrayOf(
            intArrayOf(0, 147456, 2048, 1152), intArrayOf(4, 665856, 4096, 2176), intArrayOf(8, 2359296, 4096, 2176),
            intArrayOf(12, 8912896, 8192, 4352), intArrayOf(16, 35651584, 16384, 8704),
        )
        levels.firstOrNull { area <= it[1] && width <= it[2] && height <= it[3] }?.get(0) ?: 31
    }

    /** The sequence header OBU's payload (section 5.5) with its trailing bits. */
    fun sequenceHeader(): ByteArray {
        val w = Av1BitWriter()
        w.f(3, profile)
        w.flag(true) // still_picture
        w.flag(true) // reduced_still_picture_header
        w.f(5, level)
        val widthBits = 32 - (width - 1).countLeadingZeroBits()
        val heightBits = 32 - (height - 1).countLeadingZeroBits()
        val wb = maxOf(1, widthBits)
        val hb = maxOf(1, heightBits)
        w.f(4, wb - 1)
        w.f(4, hb - 1)
        w.f(wb, width - 1)
        w.f(hb, height - 1)
        w.flag(false) // use_128x128_superblock
        w.flag(enableFilterIntra)
        w.flag(true) // enable_intra_edge_filter
        w.flag(false) // enable_superres
        w.flag(enableCdef)
        w.flag(enableRestoration)
        // color_config( )
        w.flag(bitDepth > 8) // high_bitdepth
        if (profile == 2 && bitDepth > 8) w.flag(bitDepth == 12)
        if (profile != 1) w.flag(monochrome)
        w.flag(colour != null)
        if (colour != null) {
            w.f(8, colour[0])
            w.f(8, colour[1])
            w.f(8, colour[2])
        }
        if (monochrome) {
            w.flag(fullRange)
        } else {
            val srgbIdentity = colour != null && colour[0] == 1 && colour[1] == 13 && colour[2] == 0
            if (!srgbIdentity) {
                w.flag(fullRange)
                if (profile == 2 && bitDepth == 12) {
                    w.f(1, subX)
                    if (subX == 1) w.f(1, subY)
                }
                // chroma_sample_position 0, unknown, as libavif writes it.
                if (subX == 1 && subY == 1) w.f(2, 0)
            }
            w.flag(false) // separate_uv_delta_q
        }
        w.flag(false) // film_grain_params_present
        w.trailingBits()
        return w.toByteArray()
    }

    /** The `av1C` payload of AV1-ISOBMFF section 2.3: its four fixed bytes, then the sequence header OBU. */
    fun configuration(sequenceHeaderObu: ByteArray): ByteArray {
        val b1 = (profile shl 5) or level
        val b2 = ((if (bitDepth > 8) 1 else 0) shl 6) or ((if (bitDepth == 12) 1 else 0) shl 5) or
            ((if (monochrome) 1 else 0) shl 4) or ((if (monochrome) 1 else subX) shl 3) or ((if (monochrome) 1 else subY) shl 2)
        return byteArrayOf(0x81.toByte(), b1.toByte(), b2.toByte(), 0) + sequenceHeaderObu
    }
}

/** The frame header's choices that the encoder makes per frame (#176). */
internal class Av1FrameParams(
    val baseQIdx: Int,
    val allowScreenContentTools: Boolean,
    val tileColsLog2: Int,
    val tileRowsLog2: Int,
    val loopFilterLevel: IntArray = IntArray(4),
    val loopFilterSharpness: Int = 0,
    val cdefDamping: Int = 3,
    val cdefBits: Int = 0,
    /** Each CDEF strength pair as the header codes it: the primary strength times 4 plus the secondary's code (4 coded as 3). */
    val cdefY: IntArray = IntArray(8),
    val cdefUv: IntArray = IntArray(8),
    /** lr_type of each plane in the coded order of section 5.9.20: 0 none, 1 switchable, 2 Wiener, 3 self-guided. */
    val lrType: IntArray = IntArray(3),
    /** The luma restoration unit size as lr_unit_shift and lr_unit_extra_shift code it: 0 for 64, 1 for 128, 2 for 256. */
    val lrUnitShift: Int = 0,
    val lrUvShift: Int = 0,
    val reducedTxSet: Boolean = false,
) {
    /**
     * The OBU_FRAME payload's frame header, `uncompressed_header` of a shown key frame under a reduced still picture
     * header (section 5.9), then its byte alignment, for the sequence [stream] describes.
     */
    fun frameHeader(stream: Av1StreamParams): ByteArray {
        val w = Av1BitWriter()
        val numPlanes = if (stream.monochrome) 1 else 3
        w.flag(false) // disable_cdf_update
        w.flag(allowScreenContentTools)
        if (allowScreenContentTools) w.flag(false) // force_integer_mv: SELECT under a reduced header, read and then forced to 1
        // frame_size( ): no override, so the sequence's maximum; no superres. render_size( ): the same.
        w.flag(false) // render_and_frame_size_different
        if (allowScreenContentTools) w.flag(false) // allow_intrabc
        // tile_info( ): uniform spacing, the columns' and rows' log2 as increments over their minimum.
        val sbCols = (stream.width + 63) shr 6
        val sbRows = (stream.height + 63) shr 6
        val minLog2TileCols = Av1FrameHeader.tileLog2(Av1FrameHeader.MAX_TILE_WIDTH shr 6, sbCols)
        val maxLog2TileCols = Av1FrameHeader.tileLog2(1, minOf(sbCols, Av1FrameHeader.MAX_TILE_COLS))
        val maxLog2TileRows = Av1FrameHeader.tileLog2(1, minOf(sbRows, Av1FrameHeader.MAX_TILE_ROWS))
        val minLog2Tiles = maxOf(minLog2TileCols, Av1FrameHeader.tileLog2(Av1FrameHeader.MAX_TILE_AREA shr 12, sbRows * sbCols))
        w.flag(true) // uniform_tile_spacing_flag
        var cols = minLog2TileCols
        while (cols < maxLog2TileCols) {
            if (cols < tileColsLog2) {
                w.bit(1)
                cols++
            } else {
                w.bit(0)
                break
            }
        }
        var rows = maxOf(minLog2Tiles - cols, 0)
        while (rows < maxLog2TileRows) {
            if (rows < tileRowsLog2) {
                w.bit(1)
                rows++
            } else {
                w.bit(0)
                break
            }
        }
        check(cols == tileColsLog2 && rows == tileRowsLog2) { "tiles of log2 $tileColsLog2 by $tileRowsLog2 cannot be coded; $cols by $rows" }
        if (cols > 0 || rows > 0) {
            w.f(cols + rows, 0) // context_update_tile_id
            w.f(2, 3) // tile_size_bytes_minus_1: four bytes
        }
        // quantization_params( ): no deltas, no quantizer matrices.
        w.f(8, baseQIdx)
        w.flag(false) // DeltaQYDc
        if (numPlanes > 1) {
            w.flag(false) // DeltaQUDc
            w.flag(false) // DeltaQUAc
        }
        w.flag(false) // using_qmatrix
        w.flag(false) // segmentation_enabled
        if (baseQIdx > 0) w.flag(false) // delta_q_present
        val codedLossless = baseQIdx == 0
        if (!codedLossless) {
            w.f(6, loopFilterLevel[0])
            w.f(6, loopFilterLevel[1])
            if (numPlanes > 1 && (loopFilterLevel[0] != 0 || loopFilterLevel[1] != 0)) {
                w.f(6, loopFilterLevel[2])
                w.f(6, loopFilterLevel[3])
            }
            w.f(3, loopFilterSharpness)
            w.flag(false) // loop_filter_delta_enabled
            if (stream.enableCdef) {
                w.f(2, cdefDamping - 3)
                w.f(2, cdefBits)
                for (i in 0 until (1 shl cdefBits)) {
                    w.f(4, cdefY[i] shr 2)
                    w.f(2, cdefY[i] and 3)
                    if (numPlanes > 1) {
                        w.f(4, cdefUv[i] shr 2)
                        w.f(2, cdefUv[i] and 3)
                    }
                }
            }
            if (stream.enableRestoration) {
                var usesLr = false
                var usesChromaLr = false
                for (i in 0 until numPlanes) {
                    w.f(2, lrType[i])
                    if (lrType[i] != 0) {
                        usesLr = true
                        if (i > 0) usesChromaLr = true
                    }
                }
                if (usesLr) {
                    w.f(1, if (lrUnitShift > 0) 1 else 0)
                    if (lrUnitShift > 0) w.f(1, lrUnitShift - 1)
                    if (stream.subX == 1 && stream.subY == 1 && usesChromaLr) w.f(1, lrUvShift)
                }
            }
            w.flag(true) // tx_mode_select
        }
        w.flag(reducedTxSet)
        return w.toByteArray().also { check(it.isNotEmpty()) }
    }
}
