package io.github.yuroyami.imagekodec.heic

/**
 * Joins three 4:0:0 streams into one stream of three separate colour planes (separate_colour_plane_flag), as no
 * encoder at hand writes one (#188). The streams must share their parameter sets and their structure of pictures
 * and slices, as three encodes of one size with the same settings and a fixed group of pictures do.
 *
 * The method rewrites headers bit by bit, as the range extension fixtures of [HevcDecoderTest] were made:
 * - the SPS gets chroma_format_idc 3 and separate_colour_plane_flag 1, and keeps every other bit;
 * - each slice segment of stream k gets colour_plane_id k after slice_type (and pic_output_flag), and in streams 1
 *   and 2 the first slice segment of a picture loses first_slice_segment_in_pic_flag and gains slice_segment_address
 *   0 (with dependent_slice_segment_flag 0 where the PPS has it), as 7.4.7.1 lets only the first VCL NAL unit of a
 *   picture carry the flag;
 * - after the three planes of a picture comes one decoded picture hash SEI of three MD5s, the luma MD5 that each
 *   source picture's own SEI carries, so the hash is the encoder's, not this decoder's.
 *
 * The slice segment data, entry points included, is copied unchanged: it starts on the byte after the header.
 */
internal object SeparateColourPlanes {

    private fun type(unit: ByteArray) = (unit[0].toInt() shr 1) and 0x3F

    private fun bitsOf(bytes: ByteArray, from: Int, to: Int): List<Boolean> =
        (from until to).map { (bytes[it ushr 3].toInt() shr (7 - (it and 7))) and 1 == 1 }

    private fun bytesOf(bits: List<Boolean>): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        for ((i, b) in bits.withIndex()) if (b) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
        return out
    }

    private fun ue(v: Int): List<Boolean> {
        val x = v + 1
        val length = 32 - x.countLeadingZeroBits()
        return List(length - 1) { false } + List(length) { k -> (x shr (length - 1 - k)) and 1 == 1 }
    }

    private fun fixed(v: Int, n: Int): List<Boolean> = List(n) { k -> (v shr (n - 1 - k)) and 1 == 1 }

    /** The position of the last 1 bit of [rbsp] before bit [end]: rbsp_stop_one_bit, or alignment_bit_equal_to_one. */
    private fun lastOne(rbsp: ByteArray, end: Int): Int {
        var p = end - 1
        while (p >= 0 && (rbsp[p ushr 3].toInt() shr (7 - (p and 7))) and 1 == 0) p--
        return p
    }

    /** The trace of the header of [unit], read as the decoder reads it after [store] took the parameter sets. */
    private fun trace(unit: ByteArray, store: ParameterSetStore): Pair<List<TraceEntry>, SliceHeader?> {
        val trace = ArrayList<TraceEntry>()
        val r = BitReader(unescapeNal(unit), trace)
        val nal = readNalHeader(r)
        val h = if (NalType.isSlice(nal.type)) readSliceHeader(r, nal, store, null, unit.size) else {
            store.add(nal, r)
            null
        }
        return trace to h
    }

    /** The SPS [unit] with chroma_format_idc 3 and separate_colour_plane_flag 1. */
    fun sps(unit: ByteArray): ByteArray {
        val rbsp = unescapeNal(unit)
        val entries = trace(unit, ParameterSetStore()).first
        val k = entries.indexOfFirst { it.name == "chroma_format_idc" }
        require(entries[k].value == 0L) { "the source SPS is not 4:0:0" }
        val start = entries[k].bit
        val end = entries[k + 1].bit
        val stop = lastOne(rbsp, rbsp.size * 8)
        val bits = bitsOf(rbsp, 0, start) + ue(3) + listOf(true) + bitsOf(rbsp, end, stop)
        return escapeNal(bytesOf(withTrailingBits(bits)))
    }

    private fun withTrailingBits(bits: List<Boolean>): List<Boolean> {
        val out = bits + listOf(true)
        return out + List((8 - out.size % 8) % 8) { false }
    }

    /** The slice segment [unit] of colour plane [plane], its header rewritten as the object's description says. */
    private fun slice(unit: ByteArray, plane: Int, store: ParameterSetStore): ByteArray {
        val rbsp = unescapeNal(unit)
        val (entries, h) = trace(unit, store)
        h!!
        fun end(name: String): Int {
            val k = entries.indexOfFirst { it.name == name }
            return entries[k + 1].bit
        }
        val first = entries.first { it.name == "first_slice_segment_in_pic_flag" }.bit
        val afterPpsId = if (h.firstSliceSegmentInPic) end("slice_pic_parameter_set_id") else 0
        // colour_plane_id follows slice_type, or pic_output_flag when the PPS has it (7.3.6.1).
        val colourAt = if (entries.any { it.name == "pic_output_flag" }) end("pic_output_flag") else end("slice_type")
        val alignment = lastOne(rbsp, h.headerBits)
        val bits = ArrayList<Boolean>()
        if (h.firstSliceSegmentInPic && plane > 0) {
            bits += bitsOf(rbsp, 0, first)
            bits += false
            bits += bitsOf(rbsp, first + 1, afterPpsId)
            if (h.pps.dependentSliceSegmentsEnabled) bits += false
            bits += fixed(0, ceilLog2(h.sps.picSizeInCtbs))
            bits += bitsOf(rbsp, afterPpsId, colourAt)
        } else {
            bits += bitsOf(rbsp, 0, colourAt)
        }
        bits += fixed(plane, 2)
        bits += bitsOf(rbsp, colourAt, alignment)
        val header = bytesOf(withTrailingBits(bits))
        return escapeNal(header + rbsp.copyOfRange(h.headerBits / 8, rbsp.size))
    }

    /**
     * The joined stream of [streams], each the NAL units of one 4:0:0 stream, and [hashes], the luma MD5 of each
     * picture of each stream in decoding order.
     */
    fun join(streams: List<List<ByteArray>>, hashes: List<List<ByteArray>>): List<ByteArray> {
        require(streams.size == 3 && hashes.size == 3)
        val sets = streams.map { s -> s.filter { type(it) in NalType.VPS..NalType.PPS } }
        for (k in 1..2) require(sets[k].map { it.toList() } == sets[0].map { it.toList() }) { "stream $k has other parameter sets" }
        // The source slices are read with the source parameter sets, in which no colour_plane_id is coded.
        val store = ParameterSetStore()
        val out = ArrayList<ByteArray>()
        for (unit in sets[0]) {
            out += if (type(unit) == NalType.SPS) sps(unit) else unit
            trace(unit, store)
        }
        // The slice segments of each stream, picture by picture.
        val pictures = streams.map { s ->
            val list = ArrayList<MutableList<ByteArray>>()
            for (u in s) if (NalType.isSlice(type(u))) {
                if ((u[2].toInt() and 0x80) != 0) list += ArrayList<ByteArray>()
                list.last() += u
            }
            list
        }
        for (k in 1..2) require(pictures[k].size == pictures[0].size) { "stream $k has another number of pictures" }
        for (p in pictures[0].indices) {
            for (plane in 0..2) for (u in pictures[plane][p]) out += slice(u, plane, store)
            out += HevcHeaderWriter.pictureHash(List(3) { plane -> hashes[plane][p] }).let { escapeNal(it) }
        }
        return out
    }
}
