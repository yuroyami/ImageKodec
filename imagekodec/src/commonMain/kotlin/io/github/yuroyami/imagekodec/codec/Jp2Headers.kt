package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

internal class Jp2ParameterException(message: String) : ImageDecodeException(message)

/** T.800 A.6, checked against OpenJPEG j2k.c; probe and decode share these readers. */
internal object Jp2Headers {
    class Reader(val d: ByteArray, var pos: Int, var end: Int = d.size, val origin: Int = 0) {
        var context: String = "codestream"

        private fun diagnostic(detail: String): String = "JPEG 2000: $context $detail at byte ${pos - origin}"
        fun fail(detail: String): Nothing = throw ImageDecodeException(diagnostic(detail))
        fun invalid(detail: String): Nothing = throw Jp2ParameterException(diagnostic(detail))
        fun unsupported(detail: String): Nothing =
            throw UnsupportedImageException("JPEG 2000 $detail in $context at byte ${pos - origin}")

        // Bounds checks must precede the read: a WebAssembly index fault is a trap.
        private fun need(n: Int) {
            if (pos < origin || pos > end - n) fail("header cut off (need $n bytes, segment ends at ${end - origin})")
        }
        fun u8(): Int { need(1); return d[pos++].toInt() and 0xFF }
        fun u16(): Int {
            need(2)
            val v = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
        fun u32long(): Long {
            need(4)
            val v = ((d[pos].toLong() and 0xFF) shl 24) or ((d[pos + 1].toLong() and 0xFF) shl 16) or
                ((d[pos + 2].toLong() and 0xFF) shl 8) or (d[pos + 3].toLong() and 0xFF)
            pos += 4
            return v
        }
        fun u32i(): Int = u32long().toInt()
        fun finish() {
            if (pos != end) invalid("has ${end - pos} extra parameter bytes")
        }
    }

    fun component(r: Reader, count: Int): Int {
        val c = if (count < 257) r.u8() else r.u16()
        if (c >= count) r.invalid("component index $c outside $count components")
        return c
    }

    fun unsupportedMarker(marker: Int): String? = when (marker) {
        0xFF5E -> "region of interest (RGN)"
        0xFF5F -> "progression order change (POC)"
        0xFF60, 0xFF61 -> "packed packet headers (PPM/PPT)"
        else -> null
    }

    fun markerName(marker: Int): String = when (marker) {
        0xFF51 -> "SIZ"
        0xFF52 -> "COD"
        0xFF53 -> "COC"
        0xFF5C -> "QCD"
        0xFF5D -> "QCC"
        0xFF5E -> "RGN"
        0xFF5F -> "POC"
        0xFF60 -> "PPM"
        0xFF61 -> "PPT"
        0xFF90 -> "SOT"
        else -> "marker 0x${marker.toString(16)}"
    }

    /** Coding style for one component (COD/COC). */
    class Coding(
        val progression: Int, val layers: Int, val mct: Int,
        val decompositions: Int, val cbW: Int, val cbH: Int, val cbStyle: Int,
        val reversible: Boolean,
        /** Per-resolution precinct exponents (PPx, PPy); size decompositions+1. */
        val ppx: IntArray, val ppy: IntArray,
        val sop: Boolean, val eph: Boolean,
    )

    /** Quantization for one component (QCD/QCC). */
    class Quant(val style: Int, val guardBits: Int, val exps: IntArray, val mants: IntArray)

    fun readCod(r: Reader): Coding {
        val scod = r.u8()
        if (scod and 7.inv() != 0) r.invalid("invalid Scod flags 0x${scod.toString(16)}")
        val prog = r.u8()
        if (prog !in 0..4) r.invalid("invalid progression order $prog")
        val layers = r.u16()
        val mct = r.u8()
        if (mct !in 0..1) r.unsupported("component transform $mct")
        val decomp = r.u8()
        if (decomp > 32) r.invalid("decomposition levels $decomp exceed 32")
        if (layers == 0) r.invalid("invalid layers $layers")
        if (layers > 1000) r.unsupported("with $layers layers (1000-layer maximum)")
        val cbW = r.u8() + 2
        val cbH = r.u8() + 2
        val cbStyle = r.u8()
        val transform = r.u8()
        if (transform !in 0..1) r.unsupported("wavelet transform $transform")
        if (cbW > 10 || cbH > 10 || cbW + cbH > 12) {
            r.invalid("invalid code-block exponents $cbW+$cbH (sides at most 10, sum at most 12)")
        }
        val ppx = IntArray(decomp + 1) { 15 }
        val ppy = IntArray(decomp + 1) { 15 }
        if (scod and 1 != 0) {
            for (i in 0..decomp) {
                val p = r.u8()
                ppx[i] = p and 0x0F
                ppy[i] = (p shr 4) and 0x0F
                if (i != 0 && (ppx[i] == 0 || ppy[i] == 0)) r.invalid("zero precinct exponent at resolution $i")
            }
        }
        if (cbStyle != 0) r.unsupported("code-block style 0x${cbStyle.toString(16)}")
        r.finish()
        return Coding(
            prog, layers, mct, decomp, cbW, cbH, cbStyle, reversible = transform == 1,
            ppx = ppx, ppy = ppy, sop = scod and 2 != 0, eph = scod and 4 != 0,
        )
    }

    fun readCoc(r: Reader, base: Coding): Coding {
        val scoc = r.u8()
        if (scoc and 1.inv() != 0) r.invalid("invalid Scoc flags 0x${scoc.toString(16)}")
        val decomp = r.u8()
        if (decomp > 32) r.invalid("decomposition levels $decomp exceed 32")
        val cbW = r.u8() + 2
        val cbH = r.u8() + 2
        val cbStyle = r.u8()
        val transform = r.u8()
        if (transform !in 0..1) r.unsupported("wavelet transform $transform")
        if (cbW > 10 || cbH > 10 || cbW + cbH > 12) {
            r.invalid("invalid code-block exponents $cbW+$cbH (sides at most 10, sum at most 12)")
        }
        if (cbStyle != 0) r.unsupported("code-block style 0x${cbStyle.toString(16)}")
        val ppx = IntArray(decomp + 1) { 15 }
        val ppy = IntArray(decomp + 1) { 15 }
        if (scoc and 1 != 0) {
            for (i in 0..decomp) {
                val p = r.u8()
                ppx[i] = p and 0x0F
                ppy[i] = (p shr 4) and 0x0F
                if (i != 0 && (ppx[i] == 0 || ppy[i] == 0)) r.invalid("zero precinct exponent at resolution $i")
            }
        }
        r.finish()
        return Coding(
            base.progression, base.layers, base.mct, decomp, cbW, cbH, cbStyle,
            reversible = transform == 1, ppx = ppx, ppy = ppy, sop = base.sop, eph = base.eph,
        )
    }

    fun readQuant(r: Reader, end: Int): Quant {
        val sq = r.u8()
        val style = sq and 0x1F
        val guard = (sq shr 5) and 7
        val exps = ArrayList<Int>()
        val mants = ArrayList<Int>()
        when (style) {
            0 -> while (r.pos < end) { val v = r.u8(); exps.add(v shr 3); mants.add(0) }
            1 -> {
                if (end - r.pos != 2) r.invalid("scalar-derived quantization needs one 16-bit entry")
                val v = r.u16(); exps.add(v shr 11); mants.add(v and 0x7FF)
            }
            2 -> {
                if ((end - r.pos) and 1 != 0) r.invalid("quantization table cut off at a partial 16-bit entry")
                while (r.pos < end) { val v = r.u16(); exps.add(v shr 11); mants.add(v and 0x7FF) }
            }
            else -> r.invalid("invalid quantization style $style")
        }
        if (exps.isEmpty()) r.invalid("empty quantization table")
        r.finish()
        return Quant(style, guard, exps.toIntArray(), mants.toIntArray())
    }
}
