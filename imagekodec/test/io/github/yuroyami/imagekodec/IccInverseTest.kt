package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.color.ColorMath
import io.github.yuroyami.imagekodec.internal.color.IccInverse
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A small RGB display profile whose only way back from the PCS is a lutBToAType table, built here. */
internal object TableProfile {

    /** The gamma of the table's A curves, on the device side. */
    const val GAMMA = 1 / 2.2

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun text(s: String) = s.encodeToByteArray()
    private fun fixed(v: Double) = u32(kotlin.math.round(v * 65536.0).toInt())
    private fun gammaCurve(g: Double) = text("para") + ByteArray(4) + u16(0) + ByteArray(2) + fixed(g)

    /**
     * An ICC v4 RGB profile with an XYZ PCS whose B2A0 (ICC.1 10.13) has every element: B curves of
     * gamma 1, a matrix from the PCS's XYZ encoding to linear sRGB with an offset of nothing, M curves
     * of gamma 1, a grid of two points a side that is the identity, and A curves of [GAMMA]. It takes
     * linear sRGB to its samples to [GAMMA], clipped.
     */
    fun build(): ByteArray {
        val maxXyz = 1.0 + 32767.0 / 32768.0
        val toSrgb = ColorMath.D50_TO_SRGB
        val curves1 = gammaCurve(1.0).let { it + it + it }
        val matrix = (0 until 9).fold(ByteArray(0)) { acc, i -> acc + fixed(toSrgb[i] * maxXyz) } + fixed(0.0) + fixed(0.0) + fixed(0.0)
        val grid = ByteArray(16).also { it[0] = 2; it[1] = 2; it[2] = 2 } + byteArrayOf(2, 0, 0, 0) +
            (0 until 8).fold(ByteArray(0)) { acc, node ->
                // Node r, g, b, the last input fastest, holds itself.
                acc + u16(if (node and 4 != 0) 65535 else 0) + u16(if (node and 2 != 0) 65535 else 0) + u16(if (node and 1 != 0) 65535 else 0)
            }
        val curvesA = gammaCurve(GAMMA).let { it + it + it }
        val offB = 32
        val offMatrix = offB + curves1.size
        val offM = offMatrix + matrix.size
        val offClut = offM + curves1.size
        val offA = offClut + grid.size
        val mba = text("mBA ") + ByteArray(4) + byteArrayOf(3, 3, 0, 0) + u32(offB) + u32(offMatrix) + u32(offM) + u32(offClut) +
            u32(offA) + curves1 + matrix + curves1 + grid + curvesA
        val wtpt = text("XYZ ") + ByteArray(4) + fixed(ColorMath.D50[0]) + fixed(ColorMath.D50[1]) + fixed(ColorMath.D50[2])
        val tags = listOf("wtpt" to wtpt, "B2A0" to mba)
        var at = 128 + 4 + 12 * tags.size
        var table = u32(tags.size)
        var data = ByteArray(0)
        for ((signature, body) in tags) {
            table += text(signature) + u32(at) + u32(body.size)
            val padded = body + ByteArray((4 - body.size % 4) % 4)
            data += padded
            at += padded.size
        }
        val header = u32(at) + ByteArray(4) + byteArrayOf(4, 0x30, 0, 0) + text("mntr") + text("RGB ") + text("XYZ ") +
            ByteArray(12) + text("acsp") + ByteArray(28) + fixed(ColorMath.D50[0]) + fixed(ColorMath.D50[1]) + fixed(ColorMath.D50[2]) +
            ByteArray(48)
        return header + table + data
    }
}

class IccInverseTest {

    /**
     * Linear sRGB into [TableProfile] through its B2A0, the order of a lutBToAType's elements and the
     * PCS's XYZ encoding included, on every platform; `JxlColorOracleTest` holds such tables to lcms2.
     */
    @Test
    fun aLutBToATableTakesLinearSrgbIntoTheProfile() {
        val inverse = assertNotNull(IccInverse.of(TableProfile.build()))
        var worst = 0.0
        val rgb = DoubleArray(3)
        for (r in 0..8) for (g in 0..8) for (b in 0..8) {
            val want = doubleArrayOf(0.05 + r * 0.11875, 0.05 + g * 0.11875, 0.05 + b * 0.11875)
            for (c in 0 until 3) rgb[c] = want[c]
            inverse.encode(rgb)
            for (c in 0 until 3) worst = maxOf(worst, abs(rgb[c] - want[c].pow(TableProfile.GAMMA)))
        }
        // The matrix's s15Fixed16 and the grid's 16 bits, through a curve that is steep in the dark.
        assertTrue(worst * 65535 <= 40, "worst ${worst * 65535} of 65535")
        // Past the gamut, the grid clips.
        rgb[0] = 1.4; rgb[1] = -0.2; rgb[2] = 0.5
        inverse.encode(rgb)
        assertTrue(abs(rgb[0] - 1.0) < 1e-4 && abs(rgb[1]) < 1e-4, "${rgb.toList()}")
    }

    /** A lossy JPEG XL picture whose only colour is that profile comes out in it, and `probe` reports it. */
    @Test
    fun aLossyJxlWithATableProfileComesOutInIt() {
        val icc = TableProfile.build()
        val source = KiteBitmap(32, 24, IntArray(32 * 24) { i ->
            val x = i % 32
            val y = i / 32
            (0xFF shl 24) or ((40 + x * 6) shl 16) or ((50 + y * 8) shl 8) or (200 - x * 3)
        })
        val jxl = ImageKodec.encodeJxl(source, JxlEncodeOptions(quality = 95), ImageMetadata(icc = icc))
        kotlin.test.assertContentEquals(icc, ImageKodec.probe(jxl).colorProfile?.icc)
        val decoded = ImageKodec.decode(jxl)
        var total = 0.0
        for (i in source.argb.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                // The encoder took the samples as sRGB; the profile holds linear sRGB to the 1/2.2.
                val linear = ColorMath.srgbDecode(((source.argb[i] ushr shift) and 0xFF) / 255.0)
                val want = linear.pow(TableProfile.GAMMA) * 255
                total += abs(((decoded.argb[i] ushr shift) and 0xFF) - want)
            }
        }
        val mean = total / (source.argb.size * 3)
        assertTrue(mean < 1.5, "mean $mean of 255 from the source taken into the profile")
    }
}
