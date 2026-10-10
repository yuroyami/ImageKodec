package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.PngColorFiles.cicp
import io.github.yuroyami.imagekodec.PngColorFiles.clli
import io.github.yuroyami.imagekodec.PngColorFiles.gama
import io.github.yuroyami.imagekodec.PngColorFiles.iccp
import io.github.yuroyami.imagekodec.PngColorFiles.mdcv
import io.github.yuroyami.imagekodec.PngColorFiles.png
import io.github.yuroyami.imagekodec.PngColorFiles.srgb
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PNG's colour chunks (#108): which one decides, in the PNG third edition's order (`cICP`,
 * `iCCP`, `sRGB`, `gAMA` with `cHRM`), narrow-range `cICP`, and HDR `cICP` tone-mapped to SDR.
 * The HDR expectations restate BT.2100's PQ and HLG and BT.2390's EETF from the
 * recommendations' own text, with BT.2087's BT.2020 to BT.709 matrix, independently of the
 * decoder. `IccOracleTest` holds the SDR chunks to lcms2 and the transfer functions to zimg.
 */
class PngColorChunksTest {

    private val random = Random(108)
    private val w = 23
    private val h = 7
    private val rgb8 = IntArray(w * h * 3) { random.nextInt(256) }
    private val target = ColorTarget.Srgb()

    private fun decode(vararg chunks: ByteArray) = ImageKodec.decode(png(w, h, 3, 8, rgb8, *chunks), colorTarget = target).argb

    @Test
    fun theThirdEditionsOrderDecides() {
        val plain = decode()
        val p3 = ColorConversionTest().displayP3
        val viaIcc = decode(iccp(p3))
        val viaCicp2020 = decode(cicp(9, 13))
        assertFalse(viaIcc.contentEquals(plain), "iCCP converts")
        assertFalse(viaCicp2020.contentEquals(viaIcc), "BT.2020 is not Display P3")
        // cICP wins over iCCP.
        assertContentEquals(viaCicp2020, decode(cicp(9, 13), iccp(p3)))
        // A cICP that does not code RGB, or that this cannot read, steps aside for iCCP.
        assertContentEquals(viaIcc, decode(cicp(9, 13, matrix = 1), iccp(p3)))
        assertContentEquals(viaIcc, decode(cicp(9, 9), iccp(p3)))
        // iCCP wins over sRGB, and sRGB over gAMA and cHRM.
        assertContentEquals(viaIcc, decode(iccp(p3), srgb()))
        assertContentEquals(plain, decode(srgb(), gama(100_000)))
        // cICP saying sRGB, in full or unspecified, leaves the samples alone.
        assertContentEquals(plain, decode(cicp(1, 13)))
        assertContentEquals(plain, decode(cicp(2, 2)))
        // A gamma outside reason is ignored as if absent.
        assertContentEquals(plain, decode(gama(0)))
        // Without a target, nothing converts.
        assertContentEquals(plain, ImageKodec.decode(png(w, h, 3, 8, rgb8, cicp(9, 13))).argb)
    }

    @Test
    fun aGammaChunkAloneMeansSrgbPrimariesWithThatPower() {
        val linear = decode(gama(100_000))
        for (i in 0 until w * h) for (c in 0 until 3) {
            val v = rgb8[3 * i + c] / 255.0
            val want = (srgbEncode(v) * 255).roundToInt()
            val got = (linear[i] shr (16 - 8 * c)) and 0xFF
            assertTrue(abs(got - want) <= 1, "pixel $i channel $c: $got, wanted $want")
        }
        // Gray stays gray.
        val gray = IntArray(w * h) { random.nextInt(256) }
        val decoded = ImageKodec.decode(png(w, h, 1, 8, gray, gama(100_000)), colorTarget = target)
        for (i in gray.indices) {
            val want = (srgbEncode(gray[i] / 255.0) * 255).roundToInt()
            val p = decoded.argb[i]
            assertTrue(abs((p and 0xFF) - want) <= 1 && (p and 0xFF) == (p shr 8) and 0xFF && (p and 0xFF) == (p shr 16) and 0xFF, "gray $i")
        }
    }

    @Test
    fun narrowRangeStretchesToFull() {
        // sRGB itself in the narrow range of video: 16 is black, 235 white.
        val samples = IntArray(w * h * 3) { 16 + random.nextInt(220) }
        val decoded = ImageKodec.decode(png(w, h, 3, 8, samples, cicp(1, 13, fullRange = false)), colorTarget = target).argb
        for (i in 0 until w * h) for (c in 0 until 3) {
            val want = ((samples[3 * i + c] - 16) * 255 / 219.0).roundToInt().coerceIn(0, 255)
            val got = (decoded[i] shr (16 - 8 * c)) and 0xFF
            assertTrue(abs(got - want) <= 1, "pixel $i channel $c: $got, wanted $want")
        }
        val wide = IntArray(w * h * 3) { 4096 + random.nextInt(56065) }
        val decoded16 = ImageKodec.decode16(png(w, h, 3, 16, wide, cicp(1, 13, fullRange = false)), colorTarget = target)
        for (i in wide.indices) {
            val want = ((wide[i] - 4096) * 65535.0 / 56064.0).roundToInt()
            assertTrue(abs((decoded16.samples[i].toInt() and 0xFFFF) - want) <= 2, "16-bit sample $i")
        }
    }

    // --- BT.2100, BT.2390 and BT.2087 as written -----------------------------------------

    private fun srgbEncode(l: Double): Double {
        val v = l.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    }

    private val m1 = 0.1593017578125
    private val m2 = 78.84375
    private val c1 = 0.8359375
    private val c2 = 18.8515625
    private val c3 = 18.6875

    private fun pqEotf(e: Double): Double {
        val p = e.pow(1 / m2)
        return 10000 * (max(p - c1, 0.0) / (c2 - c3 * p)).pow(1 / m1)
    }

    private fun pqInverseEotf(fd: Double): Double {
        val y = (fd / 10000).pow(m1)
        return ((c1 + c2 * y) / (1 + c3 * y)).pow(m2)
    }

    private fun hlgInverseOetf(e: Double): Double {
        val a = 0.17883277
        val b = 0.28466892
        val c = 0.55991073
        return if (e <= 0.5) e * e / 3 else (exp((e - c) / a) + b) / 12
    }

    /** BT.2390's EETF from a mastering peak of [lw] to a target peak of [lmax], blacks at 0. */
    private fun eetf(fd: Double, lw: Double, lmax: Double): Double {
        // E1 runs over the source's range, which brighter light than its declared peak leaves.
        val e1 = min(pqInverseEotf(fd) / pqInverseEotf(lw), 1.0)
        val maxLum = pqInverseEotf(lmax) / pqInverseEotf(lw)
        val ks = 1.5 * maxLum - 0.5
        val e2 = if (e1 < ks) e1 else {
            val t = (e1 - ks) / (1 - ks)
            (2 * t.pow(3) - 3 * t.pow(2) + 1) * ks + (t.pow(3) - 2 * t.pow(2) + t) * (1 - ks) + (-2 * t.pow(3) + 3 * t.pow(2)) * maxLum
        }
        return pqEotf(e2 * pqInverseEotf(lw))
    }

    /** SMPTE RP 177's normalised primary matrix: linear RGB of red, green, blue and white x and y to XYZ. */
    private fun npm(p: DoubleArray): DoubleArray {
        fun xyz(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val r = xyz(p[0], p[1])
        val g = xyz(p[2], p[3])
        val b = xyz(p[4], p[5])
        val m = doubleArrayOf(r[0], g[0], b[0], r[1], g[1], b[1], r[2], g[2], b[2])
        val s = solve(m, xyz(p[6], p[7]))
        return DoubleArray(9) { m[it] * s[it % 3] }
    }

    /** [m] times x equals [v], by Cramer's rule. */
    private fun solve(m: DoubleArray, v: DoubleArray): DoubleArray {
        fun det(a: DoubleArray) = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        val d = det(m)
        return DoubleArray(3) { k -> det(DoubleArray(9) { if (it % 3 == k) v[it / 3] else m[it] }) / d }
    }

    /** Linear BT.2020 to linear BT.709 through XYZ, both under D65, as BT.2087 derives it. */
    private val bt2020To709: DoubleArray = run {
        val to709 = npm(doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290))
        val from2020 = npm(doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290))
        // Each column of the result solves to709 x = a column of from2020.
        val columns = (0 until 3).map { c -> solve(to709, doubleArrayOf(from2020[c], from2020[3 + c], from2020[6 + c])) }
        DoubleArray(9) { columns[it % 3][it / 3] }
    }

    /** What an SDR sRGB display shows for BT.2020 light [fd] in cd/m², 16 bits a channel. */
    private fun shown(fd: DoubleArray, peak: Double): IntArray {
        val y = 0.2627 * fd[0] + 0.6780 * fd[1] + 0.0593 * fd[2]
        val scale = if (y <= 0) 0.0 else (if (peak <= 203) min(y, 203.0) else eetf(y, peak, 203.0)) / y / 203
        val l = DoubleArray(3) { r -> (0 until 3).sumOf { c -> bt2020To709[3 * r + c] * fd[c] * scale } }
        // Out of gamut: toward the grey of the same luminance, until it fits.
        val yl = (0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]).coerceIn(0.0, 1.0)
        var t = 1.0
        for (v in l) {
            if (v > 1) t = min(t, (1 - yl) / (v - yl))
            if (v < 0) t = min(t, yl / (yl - v))
        }
        return IntArray(3) { (srgbEncode(yl + t * (l[it] - yl)) * 65535).roundToInt() }
    }

    private fun checkHdr(name: String, transfer: Int, peak: Double, vararg metadata: ByteArray, light: (DoubleArray) -> DoubleArray) {
        val samples = IntArray(w * h * 3) { if (it < 3 * 16) (it / 3) * 4369 else random.nextInt(65536) }
        val file = png(w, h, 3, 16, samples, cicp(9, transfer), *metadata)
        val decoded = ImageKodec.decode16(file, colorTarget = target)
        assertEquals(3, decoded.channels)
        var worst = 0
        for (i in 0 until w * h) {
            val want = shown(light(DoubleArray(3) { samples[3 * i + it] / 65535.0 }), peak)
            for (c in 0 until 3) worst = max(worst, abs((decoded.samples[3 * i + c].toInt() and 0xFFFF) - want[c]))
        }
        assertTrue(worst <= 2, "$name: a sample is $worst 16-bit levels from BT.2100 and BT.2390")
        // The 8-bit decode is the same conversion, rounded to 8 bits.
        val narrow = ImageKodec.decode(file, colorTarget = target).argb
        val high = decoded.toBitmap().argb
        for (i in narrow.indices) for (shift in intArrayOf(16, 8, 0)) {
            assertTrue(abs(((narrow[i] shr shift) and 0xFF) - ((high[i] shr shift) and 0xFF)) <= 1, "$name 8-bit pixel $i")
        }
    }

    @Test
    fun pqToneMapsFromItsContentPeak() {
        val pq: (DoubleArray) -> DoubleArray = { e -> DoubleArray(3) { pqEotf(e[it]) } }
        checkHdr("PQ, no metadata", 16, 1000.0, light = pq)
        checkHdr("PQ, cLLi 4000", 16, 4000.0, clli(4000.0, 400.0), mdcv(1000.0, 0.005), light = pq)
        checkHdr("PQ, mDCv 600", 16, 600.0, mdcv(600.0, 0.005), light = pq)
        checkHdr("PQ, cLLi 150", 16, 150.0, clli(150.0, 100.0), light = pq)
    }

    @Test
    fun hlgGoesThroughItsOotfAtAThousandNits() {
        checkHdr("HLG", 18, 1000.0) { e ->
            val scene = DoubleArray(3) { hlgInverseOetf(e[it]) }
            val ys = 0.2627 * scene[0] + 0.6780 * scene[1] + 0.0593 * scene[2]
            DoubleArray(3) { 1000 * ys.pow(1.2 - 1) * scene[it] }
        }
    }

    @Test
    fun theReportedLightLevelsAreTheChunks() {
        val info = ImageKodec.probe(png(w, h, 3, 16, IntArray(w * h * 3), cicp(9, 16), clli(4000.0, 400.0), mdcv(1000.0, 0.005)))
        val profile = info.colorProfile!!
        assertContentEquals(intArrayOf(40_000_000, 4_000_000), profile.contentLight)
        assertContentEquals(intArrayOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 10_000_000, 50), profile.masteringDisplay)
        assertContentEquals(intArrayOf(9, 16, 0, 1), profile.cicp)
    }
}
