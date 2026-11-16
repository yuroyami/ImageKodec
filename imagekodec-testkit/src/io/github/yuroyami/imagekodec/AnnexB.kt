package io.github.yuroyami.imagekodec

/**
 * HEVC data as a raw Annex B byte stream, which ffmpeg, libde265 and HM read. A HEIF item stores
 * its parameter sets in `hvcC` and its NAL units behind length fields (ISO/IEC 14496-15).
 */
public object AnnexB {

    private val START = byteArrayOf(0, 0, 0, 1)

    /** The parameter set NAL units of an `hvcC` payload [hvcC], without the box header. */
    public fun parameterSets(hvcC: ByteArray): List<ByteArray> {
        require(hvcC.size >= 23) { "hvcC is ${hvcC.size} bytes" }
        val out = ArrayList<ByteArray>()
        var p = 23
        repeat(hvcC[22].toInt() and 0xFF) {
            p += 1
            val count = u16(hvcC, p)
            p += 2
            repeat(count) {
                val length = u16(hvcC, p)
                p += 2
                out += hvcC.copyOfRange(p, p + length)
                p += length
            }
        }
        return out
    }

    /** The size of the NAL unit length fields that [hvcC] declares. */
    public fun lengthSize(hvcC: ByteArray): Int = (hvcC[21].toInt() and 3) + 1

    /** The NAL units of [sample], each behind a big-endian length of [lengthSize] bytes. */
    public fun nalUnits(sample: ByteArray, lengthSize: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var p = 0
        while (p < sample.size) {
            var length = 0
            repeat(lengthSize) { length = (length shl 8) or (sample[p++].toInt() and 0xFF) }
            out += sample.copyOfRange(p, p + length)
            p += length
        }
        return out
    }

    /** An Annex B stream: the parameter sets of [hvcC], then the NAL units of each of [samples]. */
    public fun stream(hvcC: ByteArray, samples: List<ByteArray>): ByteArray {
        val units = parameterSets(hvcC) + samples.flatMap { nalUnits(it, lengthSize(hvcC)) }
        return join(units)
    }

    /** [units] each behind a 4-byte start code. */
    public fun join(units: List<ByteArray>): ByteArray {
        var size = 0
        for (u in units) size += 4 + u.size
        val out = ByteArray(size)
        var p = 0
        for (u in units) {
            START.copyInto(out, p)
            u.copyInto(out, p + 4)
            p += 4 + u.size
        }
        return out
    }

    /** The NAL units of an Annex B [stream], split at its 3- or 4-byte start codes. */
    public fun split(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<IntArray>()
        var i = 0
        while (i + 2 < stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                starts += intArrayOf(i, i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { k, s ->
            var end = if (k + 1 < starts.size) starts[k + 1][0] else stream.size
            // A zero byte before the next start code belongs to a 4-byte code.
            while (end > s[1] && k + 1 < starts.size && stream[end - 1].toInt() == 0) end--
            stream.copyOfRange(s[1], end)
        }
    }

    /**
     * An `hvcC` payload holding [parameterSets], with the length fields [lengthSize] bytes long.
     * The profile fields come from nothing: a reader takes them from the SPS.
     */
    public fun hvcC(parameterSets: List<ByteArray>, lengthSize: Int = 4, chromaFormat: Int = 1, bitDepthLuma: Int = 8, bitDepthChroma: Int = 8): ByteArray {
        val header = ByteArray(23)
        header[0] = 1
        header[13] = 0xF0.toByte()
        header[15] = 0xFC.toByte()
        header[16] = (0xFC or chromaFormat).toByte()
        header[17] = (0xF8 or (bitDepthLuma - 8)).toByte()
        header[18] = (0xF8 or (bitDepthChroma - 8)).toByte()
        header[21] = (0x0C or (lengthSize - 1)).toByte()
        val byType = parameterSets.groupBy { (it[0].toInt() ushr 1) and 0x3F }.entries.sortedBy { it.key }
        header[22] = byType.size.toByte()
        var out = header
        for ((type, units) in byType) {
            out += byteArrayOf((0x80 or type).toByte(), (units.size ushr 8).toByte(), units.size.toByte())
            for (u in units) out += byteArrayOf((u.size ushr 8).toByte(), u.size.toByte()) + u
        }
        return out
    }

    /** [units] as one HEIF sample: each behind a length of [lengthSize] bytes. */
    public fun sample(units: List<ByteArray>, lengthSize: Int = 4): ByteArray {
        var out = ByteArray(0)
        for (u in units) {
            val len = ByteArray(lengthSize) { k -> (u.size ushr (8 * (lengthSize - 1 - k))).toByte() }
            out += len + u
        }
        return out
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
}
