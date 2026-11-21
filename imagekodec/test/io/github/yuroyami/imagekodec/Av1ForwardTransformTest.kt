package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1
import io.github.yuroyami.imagekodec.codec.avif.Av1ForwardTransform
import io.github.yuroyami.imagekodec.codec.avif.Av1Tables
import io.github.yuroyami.imagekodec.codec.avif.Av1Transform
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The AV1 encoder's forward transform (#176) runs the DCT, and the ADST of 8 and 16 points, as butterflies at effort
 * 2, the inverse's steps transposed, rather than as sums over the decoder's basis. Its coefficients are held here to
 * those sums, worked out exactly, within one and a half units of an 8-bit coefficient and 1/1024 of the block's
 * largest, for every size and type the encoder codes, at 8 and 12 bits: on noise, and on the residual that drives a
 * coefficient to its largest value, where a product past an Int would show as a wrong coefficient. The basis sums
 * that efforts 1 and 3 keep are held to the same bound.
 */
class Av1ForwardTransformTest {

    private fun kind(row: Boolean, type: Int): Int = if (row) {
        if (Av1Transform.rowIsDct(type)) 0 else if (Av1Transform.rowIsAdst(type)) 1 else 2
    } else {
        if (Av1Transform.colIsDct(type)) 0 else if (Av1Transform.colIsAdst(type)) 1 else 2
    }

    /** The largest number of points of a transform of [kind]: 64 for the DCT, 16 for the ADST, 32 for the identity. */
    private fun top(kind: Int): Int = when (kind) {
        0 -> 6
        1 -> 4
        else -> 5
    }

    /** The coefficients the basis sums give, exactly, in the units of the decoder's dequantized coefficients. */
    private fun exact(residual: IntArray, txSz: Int, type: Int): DoubleArray {
        val log2W = Av1.txWidthLog2[txSz]
        val log2H = Av1.txHeightLog2[txSz]
        val w = 1 shl log2W
        val h = 1 shl log2H
        val tw = minOf(32, w)
        val th = minOf(32, h)
        val rowBasis = Av1ForwardTransform.basis(kind(true, type), log2W)
        val colBasis = Av1ForwardTransform.basis(kind(false, type), log2H)
        val flipLR = Av1Transform.flipsColumns(type)
        val flipUD = Av1Transform.flipsRows(type)
        val rows = Array(h) { i ->
            LongArray(tw) { k ->
                var s = 0L
                for (j in 0 until w) s += rowBasis[k * w + j].toLong() * residual[i * w + (if (flipLR) w - 1 - j else j)]
                s
            }
        }
        val rowShift = Av1Tables.transformRowShift[txSz]
        // D = Z 2^(rowShift + 4) / (2^24 (w / 2) (h / 2)), and sqrt(2) more for a 2:1 shape.
        var scale = 1.0
        repeat(rowShift + 4) { scale *= 2.0 }
        repeat(24) { scale /= 2.0 }
        scale /= (w / 2).toDouble() * (h / 2).toDouble()
        if (abs(log2W - log2H) == 1) scale *= sqrt(2.0)
        val out = DoubleArray(tw * th)
        for (m in 0 until th) for (k in 0 until tw) {
            var s = 0L
            for (i in 0 until h) s += colBasis[m * h + i].toLong() * rows[if (flipUD) h - 1 - i else i][k]
            out[m * tw + k] = s * scale
        }
        return out
    }

    private fun check(bitDepth: Int, txSz: Int, type: Int, residual: IntArray, what: String) {
        for (butterflies in listOf(true, false)) check(bitDepth, txSz, type, residual, what, butterflies)
    }

    private fun check(bitDepth: Int, txSz: Int, type: Int, residual: IntArray, what: String, butterflies: Boolean) {
        val fwd = Av1ForwardTransform(bitDepth, butterflies)
        fwd.newBlock()
        val got = IntArray(1024)
        fwd.forward(residual, txSz, type, got)
        val want = exact(residual, txSz, type)
        var largest = 0.0
        for (v in want) largest = maxOf(largest, abs(v))
        for (i in want.indices) {
            val e = abs(got[i] - want[i])
            assertTrue(e <= 1.5 * (1 shl (bitDepth - 8)) + largest / 1024, "${if (butterflies) "butterflies" else "basis sums"}, $bitDepth bits, size $txSz, type $type, $what: coefficient $i is ${got[i]}, the basis sums give ${want[i]}")
        }
    }

    @Test
    fun theButterfliesGiveTheBasisSums() {
        var seed = 12345
        fun next(): Int {
            seed = seed * 1103515245 + 12345
            return seed ushr 1
        }
        for (bitDepth in intArrayOf(8, 12)) {
            val max = (1 shl bitDepth) - 1
            for (txSz in 0 until Av1.TX_SIZES_ALL) {
                val log2W = Av1.txWidthLog2[txSz]
                val log2H = Av1.txHeightLog2[txSz]
                val w = 1 shl log2W
                val h = 1 shl log2H
                for (type in 0 until 16) {
                    if (log2W > top(kind(true, type)) || log2H > top(kind(false, type))) continue
                    val noise = IntArray(w * h) { (next() % (2 * max + 1)) - max }
                    check(bitDepth, txSz, type, noise, "noise")
                    val small = IntArray(w * h) { (next() % 9) - 4 }
                    check(bitDepth, txSz, type, small, "small noise")
                    if (kind(true, type) != 0 && kind(false, type) != 0) continue
                    // The residual of the largest magnitude whose signs follow a row and a column of the basis, which
                    // gives that coefficient its largest value.
                    val rowBasis = Av1ForwardTransform.basis(kind(true, type), log2W)
                    val colBasis = Av1ForwardTransform.basis(kind(false, type), log2H)
                    for ((k, m) in listOf(0 to 0, 1 to 0, 0 to 1, minOf(31, w - 1) to minOf(31, h - 1), (w / 3) to (h / 5))) {
                        val worst = IntArray(w * h) { at ->
                            val i = at / w
                            val j = at % w
                            val sr = if (rowBasis[k * w + j] < 0) -1 else 1
                            val sc = if (colBasis[m * h + i] < 0) -1 else 1
                            sr * sc * max
                        }
                        check(bitDepth, txSz, type, worst, "the largest coefficient ($k, $m)")
                    }
                }
            }
        }
    }
}
