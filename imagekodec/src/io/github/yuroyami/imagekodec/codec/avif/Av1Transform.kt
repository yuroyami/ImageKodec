package io.github.yuroyami.imagekodec.codec.avif

/**
 * Dequantization, the inverse transforms and reconstruction of AV1 (specification sections
 * 7.12 and 7.13), step for step. Products go through Long: a 12-bit row transform's values
 * take 20 bits, and times a 12-bit cosine they can pass an Int. One instance a frame
 * decoder: it keeps its working arrays.
 */
internal class Av1Transform {

    private companion object {
        val COS128 = Av1Tables.cos128Lookup
        const val SINPI_1_9 = 1321L
        const val SINPI_2_9 = 2482L
        const val SINPI_3_9 = 3344L
        const val SINPI_4_9 = 3803L

        fun cos128(angle: Int): Long {
            val a = angle and 255
            return when {
                a <= 64 -> COS128[a].toLong()
                a <= 128 -> -COS128[128 - a].toLong()
                a <= 192 -> -COS128[a - 128].toLong()
                else -> COS128[256 - a].toLong()
            }
        }

        fun sin128(angle: Int): Long = cos128(angle - 64)

        fun brev(numBits: Int, x: Int): Int {
            var t = 0
            for (i in 0 until numBits) t += ((x shr i) and 1) shl (numBits - 1 - i)
            return t
        }

        fun round2(x: Long, n: Int): Long = if (n == 0) x else (x + (1L shl (n - 1))) shr n
    }

    /** The working state of one 1D transform: the array T and the clamping range r. */
    private class Work {
        val t = LongArray(64)
        val copy = LongArray(64)
        var r = 16

        fun b(a: Int, b: Int, angle: Int, flip: Int) {
            val x = t[a] * cos128(angle) - t[b] * sin128(angle)
            val y = t[a] * sin128(angle) + t[b] * cos128(angle)
            t[a] = round2(x, 12)
            t[b] = round2(y, 12)
            if (flip == 1) {
                val s = t[a]
                t[a] = t[b]
                t[b] = s
            }
        }

        fun h(a0: Int, b0: Int, flip: Int) {
            val a = if (flip == 1) b0 else a0
            val b = if (flip == 1) a0 else b0
            val x = t[a]
            val y = t[b]
            val lo = -(1L shl (r - 1))
            val hi = (1L shl (r - 1)) - 1
            t[a] = (x + y).coerceIn(lo, hi)
            t[b] = (x - y).coerceIn(lo, hi)
        }
    }

    private fun inverseDct(w: Work, n: Int) {
        val t = w.t
        val n0 = 1 shl n
        for (i in 0 until n0) w.copy[i] = t[i]
        for (i in 0 until n0) t[i] = w.copy[brev(n, i)]
        if (n == 6) for (i in 0..15) w.b(32 + i, 63 - i, 63 - 4 * brev(4, i), 0)
        if (n >= 5) for (i in 0..7) w.b(16 + i, 31 - i, 6 + (brev(3, 7 - i) shl 3), 0)
        if (n == 6) for (i in 0..15) w.h(32 + i * 2, 33 + i * 2, i and 1)
        if (n >= 4) for (i in 0..3) w.b(8 + i, 15 - i, 12 + (brev(2, 3 - i) shl 4), 0)
        if (n >= 5) for (i in 0..7) w.h(16 + 2 * i, 17 + 2 * i, i and 1)
        if (n == 6) for (i in 0..3) for (j in 0..1) w.b(62 - i * 4 - j, 33 + i * 4 + j, 60 - 16 * brev(2, i) + 64 * j, 1)
        if (n >= 3) for (i in 0..1) w.b(4 + i, 7 - i, 56 - 32 * i, 0)
        if (n >= 4) for (i in 0..3) w.h(8 + 2 * i, 9 + 2 * i, i and 1)
        if (n >= 5) for (i in 0..1) for (j in 0..1) w.b(30 - 4 * i - j, 17 + 4 * i + j, 24 + (j shl 6) + ((1 - i) shl 5), 1)
        if (n == 6) for (i in 0..7) for (j in 0..1) w.h(32 + i * 4 + j, 35 + i * 4 - j, i and 1)
        for (i in 0..1) w.b(2 * i, 2 * i + 1, 32 + 16 * i, 1 - i)
        if (n >= 3) for (i in 0..1) w.h(4 + 2 * i, 5 + 2 * i, i)
        if (n >= 4) for (i in 0..1) w.b(14 - i, 9 + i, 48 + 64 * i, 1)
        if (n >= 5) for (i in 0..3) for (j in 0..1) w.h(16 + 4 * i + j, 19 + 4 * i - j, i and 1)
        if (n == 6) for (i in 0..1) for (j in 0..3) w.b(61 - i * 8 - j, 34 + i * 8 + j, 56 - i * 32 + (j shr 1) * 64, 1)
        for (i in 0..1) w.h(i, 3 - i, 0)
        if (n >= 3) w.b(6, 5, 32, 1)
        if (n >= 4) for (i in 0..1) for (j in 0..1) w.h(8 + 4 * i + j, 11 + 4 * i - j, i)
        if (n >= 5) for (i in 0..3) w.b(29 - i, 18 + i, 48 + (i shr 1) * 64, 1)
        if (n == 6) for (i in 0..3) for (j in 0..3) w.h(32 + 8 * i + j, 39 + 8 * i - j, i and 1)
        if (n >= 3) for (i in 0..3) w.h(i, 7 - i, 0)
        if (n >= 4) for (i in 0..1) w.b(13 - i, 10 + i, 32, 1)
        if (n >= 5) for (i in 0..1) for (j in 0..3) w.h(16 + i * 8 + j, 23 + i * 8 - j, i)
        if (n == 6) for (i in 0..7) w.b(59 - i, 36 + i, if (i < 4) 48 else 112, 1)
        if (n >= 4) for (i in 0..7) w.h(i, 15 - i, 0)
        if (n >= 5) for (i in 0..3) w.b(27 - i, 20 + i, 32, 1)
        if (n == 6) for (i in 0..7) {
            w.h(32 + i, 47 - i, 0)
            w.h(48 + i, 63 - i, 1)
        }
        if (n >= 5) for (i in 0..15) w.h(i, 31 - i, 0)
        if (n == 6) for (i in 0..7) w.b(55 - i, 40 + i, 32, 1)
        if (n == 6) for (i in 0..31) w.h(i, 63 - i, 0)
    }

    private fun adstInputPermutation(w: Work, n: Int) {
        val n0 = 1 shl n
        for (i in 0 until n0) w.copy[i] = w.t[i]
        for (i in 0 until n0) {
            val idx = if (i and 1 != 0) i - 1 else n0 - i - 1
            w.t[i] = w.copy[idx]
        }
    }

    private fun adstOutputPermutation(w: Work, n: Int) {
        val n0 = 1 shl n
        for (i in 0 until n0) w.copy[i] = w.t[i]
        for (i in 0 until n0) {
            val a = (i shr 3) and 1
            val b = ((i shr 2) and 1) xor ((i shr 3) and 1)
            val c = ((i shr 1) and 1) xor ((i shr 2) and 1)
            val d = (i and 1) xor ((i shr 1) and 1)
            val idx = ((d shl 3) or (c shl 2) or (b shl 1) or a) shr (4 - n)
            w.t[i] = if (i and 1 != 0) -w.copy[idx] else w.copy[idx]
        }
    }

    private fun inverseAdst4(w: Work) {
        val t = w.t
        val s0 = SINPI_1_9 * t[0]
        val s1 = SINPI_2_9 * t[0]
        val s2 = SINPI_3_9 * t[1]
        val s3 = SINPI_4_9 * t[2]
        val s4 = SINPI_1_9 * t[2]
        val s5 = SINPI_2_9 * t[3]
        val s6 = SINPI_4_9 * t[3]
        val a7 = t[0] - t[2]
        val b7 = a7 + t[3]
        var x0 = s0 + s3
        var x1 = s1 - s4
        val x3s = s2
        val x2 = SINPI_3_9 * b7
        x0 += s5
        x1 -= s6
        val o0 = x0 + x3s
        val o1 = x1 + x3s
        val o2 = x2
        var o3 = x0 + x1
        o3 -= x3s
        t[0] = round2(o0, 12)
        t[1] = round2(o1, 12)
        t[2] = round2(o2, 12)
        t[3] = round2(o3, 12)
    }

    private fun inverseAdst8(w: Work) {
        adstInputPermutation(w, 3)
        for (i in 0..3) w.b(2 * i, 2 * i + 1, 60 - 16 * i, 1)
        for (i in 0..3) w.h(i, 4 + i, 0)
        for (i in 0..1) w.b(4 + 3 * i, 5 + i, 48 - 32 * i, 1)
        for (i in 0..1) for (j in 0..1) w.h(4 * j + i, 2 + 4 * j + i, 0)
        for (i in 0..1) w.b(2 + 4 * i, 3 + 4 * i, 32, 1)
        adstOutputPermutation(w, 3)
    }

    private fun inverseAdst16(w: Work) {
        adstInputPermutation(w, 4)
        for (i in 0..7) w.b(2 * i, 2 * i + 1, 62 - 8 * i, 1)
        for (i in 0..7) w.h(i, 8 + i, 0)
        for (i in 0..1) {
            w.b(8 + 2 * i, 9 + 2 * i, 56 - 32 * i, 1)
            w.b(13 + 2 * i, 12 + 2 * i, 8 + 32 * i, 1)
        }
        for (i in 0..3) for (j in 0..1) w.h(8 * j + i, 4 + 8 * j + i, 0)
        for (i in 0..1) for (j in 0..1) w.b(4 + 8 * j + 3 * i, 5 + 8 * j + i, 48 - 32 * i, 1)
        for (i in 0..1) for (j in 0..3) w.h(4 * j + i, 2 + 4 * j + i, 0)
        for (i in 0..3) w.b(2 + 4 * i, 3 + 4 * i, 32, 1)
        adstOutputPermutation(w, 4)
    }

    private fun inverseAdst(w: Work, n: Int) = when (n) {
        2 -> inverseAdst4(w)
        3 -> inverseAdst8(w)
        else -> inverseAdst16(w)
    }

    private fun inverseIdentity(w: Work, n: Int) {
        val t = w.t
        when (n) {
            2 -> for (i in 0 until 4) t[i] = round2(t[i] * 5793, 12)
            3 -> for (i in 0 until 8) t[i] = t[i] * 2
            4 -> for (i in 0 until 16) t[i] = round2(t[i] * 11586, 12)
            else -> for (i in 0 until 32) t[i] = t[i] * 4
        }
    }

    private fun inverseWht(w: Work, shift: Int) {
        val t = w.t
        var a = t[0] shr shift
        var c = t[1] shr shift
        var d = t[2] shr shift
        var b = t[3] shr shift
        a += c
        d -= b
        val e = (a - d) shr 1
        b = e - b
        c = e - c
        a -= b
        d += c
        t[0] = a
        t[1] = b
        t[2] = c
        t[3] = d
    }

    private fun rowIsDct(type: Int) = type == Av1.DCT_DCT || type == Av1.ADST_DCT || type == Av1.FLIPADST_DCT || type == Av1.H_DCT

    private fun rowIsAdst(type: Int) = when (type) {
        Av1.DCT_ADST, Av1.ADST_ADST, Av1.DCT_FLIPADST, Av1.FLIPADST_FLIPADST,
        Av1.ADST_FLIPADST, Av1.FLIPADST_ADST, Av1.H_ADST, Av1.H_FLIPADST -> true
        else -> false
    }

    private fun colIsDct(type: Int) = type == Av1.DCT_DCT || type == Av1.DCT_ADST || type == Av1.DCT_FLIPADST || type == Av1.V_DCT

    private fun colIsAdst(type: Int) = when (type) {
        Av1.ADST_DCT, Av1.ADST_ADST, Av1.FLIPADST_DCT, Av1.FLIPADST_FLIPADST,
        Av1.ADST_FLIPADST, Av1.FLIPADST_ADST, Av1.V_ADST, Av1.V_FLIPADST -> true
        else -> false
    }

    private val work = Work()
    private val residualBuf = LongArray(64 * 64)
    private val dequantBuf = LongArray(32 * 32)

    /** dc_q( b ) and ac_q( b ) for [bitDepth]. */
    private fun dcQ(bitDepth: Int, b: Int): Int = Av1Tables.dcQlookup[((bitDepth - 8) shr 1) * 256 + b.coerceIn(0, 255)]
    private fun acQ(bitDepth: Int, b: Int): Int = Av1Tables.acQlookup[((bitDepth - 8) shr 1) * 256 + b.coerceIn(0, 255)]

    /** The reconstruct process: dequantize Quant, inverse transform it and add it to the prediction. */
    fun reconstruct(d: Av1FrameDecoder, plane: Int, x: Int, y: Int, txSz: Int) {
        val fh = d.fh
        val bitDepth = d.bitDepth
        val dqDenom = when (txSz) {
            Av1.TX_32X32, Av1.TX_16X32, Av1.TX_32X16, Av1.TX_16X64, Av1.TX_64X16 -> 2
            Av1.TX_64X64, Av1.TX_32X64, Av1.TX_64X32 -> 4
            else -> 1
        }
        val log2W = Av1.txWidthLog2[txSz]
        val log2H = Av1.txHeightLog2[txSz]
        val w = 1 shl log2W
        val h = 1 shl log2H
        val tw = minOf(32, w)
        val th = minOf(32, h)
        val type = d.planeTxType
        val flipUD = type == Av1.FLIPADST_DCT || type == Av1.FLIPADST_ADST || type == Av1.V_FLIPADST || type == Av1.FLIPADST_FLIPADST
        val flipLR = type == Av1.DCT_FLIPADST || type == Av1.ADST_FLIPADST || type == Av1.H_FLIPADST || type == Av1.FLIPADST_FLIPADST
        val qIndex = fh.qIndex(false, d.segmentId, d.currentQIndex)
        val dcQ = when (plane) {
            0 -> dcQ(bitDepth, qIndex + fh.deltaQYDc)
            1 -> dcQ(bitDepth, qIndex + fh.deltaQUDc)
            else -> dcQ(bitDepth, qIndex + fh.deltaQVDc)
        }
        val acQ = when (plane) {
            0 -> acQ(bitDepth, qIndex)
            1 -> acQ(bitDepth, qIndex + fh.deltaQUAc)
            else -> acQ(bitDepth, qIndex + fh.deltaQVAc)
        }
        val qmLevel = if (fh.usingQmatrix) fh.segQmLevel[plane][d.segmentId] else 15
        val useQm = fh.usingQmatrix && type < Av1.IDTX && qmLevel < 15
        val qmBase = if (useQm) (qmLevel * 2 + (if (plane > 0) 1 else 0)) * 3344 + Av1Tables.qmOffset[txSz] else 0
        val clampLo = -(1L shl (7 + bitDepth))
        val clampHi = (1L shl (7 + bitDepth)) - 1
        val quant = d.quant
        for (i in 0 until th) for (j in 0 until tw) {
            val qv = quant[i * tw + j]
            if (qv == 0) {
                dequantBuf[i * 32 + j] = 0
                continue
            }
            val q = if (i == 0 && j == 0) dcQ else acQ
            val q2 = if (useQm) Av1.round2(q * Av1Tables.quantizerMatrix[qmBase + i * tw + j], 5) else q
            val dq = qv.toLong() * q2
            val sign = if (dq < 0) -1L else 1L
            val dq2 = sign * ((kotlin.math.abs(dq) and 0xFFFFFF) / dqDenom)
            dequantBuf[i * 32 + j] = dq2.coerceIn(clampLo, clampHi)
        }
        inverseTransform2d(d, txSz, log2W, log2H, w, h)
        val buf = d.frame[plane]
        val stride = d.planeWidth[plane]
        val max = (1 shl bitDepth) - 1
        for (i in 0 until h) for (j in 0 until w) {
            val xx = if (flipLR) w - j - 1 else j
            val yy = if (flipUD) h - i - 1 else i
            val at = (y + yy) * stride + x + xx
            buf[at] = (buf[at] + residualBuf[i * w + j]).coerceIn(0, max.toLong()).toInt().toShort()
        }
    }

    private fun inverseTransform2d(d: Av1FrameDecoder, txSz: Int, log2W: Int, log2H: Int, w: Int, h: Int) {
        val lossless = d.lossless
        val type = d.planeTxType
        val bitDepth = d.bitDepth
        val rowShift = if (lossless) 0 else Av1Tables.transformRowShift[txSz]
        val colShift = if (lossless) 0 else 4
        val rowClampRange = bitDepth + 8
        val colClampRange = maxOf(bitDepth + 6, 16)
        val wk = work
        val t = wk.t
        for (i in 0 until h) {
            for (j in 0 until w) t[j] = if (i < 32 && j < 32) dequantBuf[i * 32 + j] else 0L
            if (kotlin.math.abs(log2W - log2H) == 1) for (j in 0 until w) t[j] = round2(t[j] * 2896, 12)
            wk.r = rowClampRange
            when {
                lossless -> inverseWht(wk, 2)
                rowIsDct(type) -> inverseDct(wk, log2W)
                rowIsAdst(type) -> inverseAdst(wk, log2W)
                else -> inverseIdentity(wk, log2W)
            }
            for (j in 0 until w) residualBuf[i * w + j] = round2(t[j], rowShift)
        }
        val lo = -(1L shl (colClampRange - 1))
        val hi = (1L shl (colClampRange - 1)) - 1
        for (k in 0 until w * h) residualBuf[k] = residualBuf[k].coerceIn(lo, hi)
        for (j in 0 until w) {
            for (i in 0 until h) t[i] = residualBuf[i * w + j]
            wk.r = colClampRange
            when {
                lossless -> inverseWht(wk, 0)
                colIsDct(type) -> inverseDct(wk, log2H)
                colIsAdst(type) -> inverseAdst(wk, log2H)
                else -> inverseIdentity(wk, log2H)
            }
            for (i in 0 until h) residualBuf[i * w + j] = round2(t[i], colShift)
        }
    }
}
