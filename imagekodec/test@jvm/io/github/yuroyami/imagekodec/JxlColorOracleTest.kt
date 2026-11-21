package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import io.github.yuroyami.imagekodec.codec.jxl.JxlColorEncoding
import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.internal.color.IccProfile
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JPEG XL colour (#189) against libjxl and lcms2: a lossy picture whose colour is an ICC profile alone,
 * of matrix and curves or of lookup tables, which libjxl writes in that profile through a colour engine;
 * primaries of their own, which djxl converts to sRGB when asked, with an SDR curve or with PQ and HLG;
 * and the intensity target, which tone mapping takes as the peak.
 */
class JxlColorOracleTest {

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        proc.inputStream.copyTo(ByteArrayOutputStream())
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxlcolor", ext).apply { deleteOnExit() }

    private val scene: KiteBitmap get() = PhotoScenes.portrait(96, 128).bitmap

    /** The worst and mean difference of [ours] from [theirs], 16-bit samples, at 8-bit scale. */
    private fun compare(name: String, theirs: KiteBitmap16, ours: KiteBitmap16, bound: Double, meanBound: Double = bound): String {
        assertEquals(theirs.width, ours.width, name)
        assertEquals(theirs.channels, ours.channels, name)
        var worst = 0.0
        var total = 0.0
        for (i in theirs.samples.indices) {
            val d = abs((theirs.samples[i].toInt() and 0xFFFF) - (ours.samples[i].toInt() and 0xFFFF)) / 257.0
            worst = maxOf(worst, d)
            total += d
        }
        val report = "$name: mean %.3f, worst %.2f of 255".format(total / theirs.samples.size, worst)
        println(report)
        assertTrue(worst <= bound && total / theirs.samples.size <= meanBound, report)
        return report
    }

    /** lcms2 through `tools/icc_lcms.c`, built here as IccOracleTest builds it. */
    private val lcms: File? by lazy {
        val source = listOf(File("../tools/icc_lcms.c"), File("tools/icc_lcms.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = File.createTempFile("icc_lcms", ".bin").apply { deleteOnExit() }
        val ok = run(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-llcms2", "-lm") == 0
        if (!ok && System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/icc_lcms.c against lcms2")
        out.takeIf { ok }
    }

    private val profiles = listOf("compatibleWithAdobeRGB1998.icc" to false, "LStar-RGB.icc" to false, "Gray.icc" to true)

    /**
     * The way from linear sRGB into a profile of matrix and curves, which a lossy JPEG XL picture with a
     * profile alone takes, against lcms2's transform from a linear sRGB profile to the same profile.
     */
    @Test
    fun linearSrgbIntoAProfileMatchesLcms2() {
        assumeTrue("a C compiler or lcms2 is not installed", Tools.hasAll("cc") && lcms != null)
        val reports = ArrayList<String>()
        for ((profile, gray) in profiles) {
            val file = File("/usr/share/color/icc/$profile")
            assumeTrue("icc-profiles-free is not installed", file.exists())
            val (worst, _) = intoProfile(file, gray, randomLinear())
            reports += "$profile: worst %.1f of 65535 from lcms2".format(worst)
            // A table of 4097 samples a curve, read linearly between them, and lcms2's own inversion.
            assertTrue(worst <= 64, reports.last())
        }
        println(reports.joinToString("\n"))
    }

    /** Linear sRGB: the grays, then random colours, some of them past what sRGB holds. */
    private fun randomLinear(): List<DoubleArray> {
        val random = kotlin.random.Random(189)
        return List(4000) { k ->
            when {
                k < 256 -> DoubleArray(3) { k / 255.0 }
                k < 3000 -> DoubleArray(3) { random.nextDouble() }
                else -> DoubleArray(3) { random.nextDouble() * 1.3 - 0.1 }
            }
        }
    }

    /**
     * [inputs], linear sRGB, into the profile [file] by `IccInverse` and by lcms2's `to-profile`: the
     * worst and the mean difference, in 65535ths, of every channel clamped to 0 to 1.
     */
    private fun intoProfile(file: File, gray: Boolean, inputs: List<DoubleArray>): Pair<Double, Double> {
        val inverse = assertNotNull(io.github.yuroyami.imagekodec.internal.color.IccInverse.of(file.readBytes()), file.name)
        val buffer = java.nio.ByteBuffer.allocate(inputs.size * 12).order(java.nio.ByteOrder.nativeOrder())
        for (v in inputs) for (c in 0 until 3) buffer.putFloat(v[c].toFloat())
        val input = temp(".f32").apply { writeBytes(buffer.array()) }
        val proc = ProcessBuilder(lcms!!.path, "to-profile", file.path, if (gray) "1" else "3").redirectInput(input).start()
        val out = java.nio.ByteBuffer.wrap(proc.inputStream.readBytes()).order(java.nio.ByteOrder.nativeOrder())
        assertEquals(0, proc.waitFor())
        var worst = 0.0
        var total = 0.0
        var count = 0
        for (v in inputs) {
            val ours = DoubleArray(3) { v[it].toFloat().toDouble() }
            inverse.encode(ours)
            for (c in 0 until (if (gray) 1 else 3)) {
                val want = out.float.toDouble().coerceIn(0.0, 1.0)
                val d = abs(want - ours[c].coerceIn(0.0, 1.0)) * 65535
                worst = maxOf(worst, d)
                total += d
                count++
            }
        }
        return worst to total / count
    }

    /** A profile of `tools/icc_lcms.c`'s [kind], made by lcms2. */
    private fun made(kind: String): File {
        val file = temp(".icc")
        val proc = ProcessBuilder(lcms!!.path, "make", kind).redirectOutput(file).start()
        assertEquals(0, proc.waitFor(), "icc_lcms make $kind")
        return file
    }

    /** The two-way table profiles of `tools/icc_lcms.c`, and whether each is gray. */
    private val tableKinds = listOf(
        "io-lut8-v2" to false, "io-lut16-v2" to false, "io-xyz-v2" to false,
        "io-mab-v4" to false, "io-mab-xyz-v4" to false, "io-gray-v4" to true,
    )

    /**
     * The way from linear sRGB into a profile of lookup tables, through its B2A0: lut8Type and lut16Type
     * with a Lab PCS (lut16Type's in v2's encoding), lut16Type with an XYZ PCS and a matrix,
     * lutBToAType with curves and a grid, and with every element, a matrix with an offset included,
     * and a gray one, against lcms2's transform to the same profile.
     */
    @Test
    fun linearSrgbIntoATableProfileMatchesLcms2() {
        assumeTrue("a C compiler or lcms2 is not installed", Tools.hasAll("cc") && lcms != null)
        val reports = ArrayList<String>()
        for ((kind, gray) in tableKinds) {
            val (worst, mean) = intoProfile(made(kind), gray, randomLinear())
            reports += "$kind: worst %.2f, mean %.3f of 65535 from lcms2".format(worst, mean)
            println(reports.last())
            // The tables are read as lcms2 reads them, in its fixed point, so only the float rounding of
            // the PCS ahead of them is left.
            assertTrue(worst <= TABLE_BOUND, reports.last())
        }
        println(reports.joinToString("\n"))
    }

    /**
     * A lossy picture whose colour is a profile alone comes out in that profile, as libjxl writes it
     * through a colour engine. The djxl here has none and writes linear sRGB instead, so the picture is
     * held to the source it was coded from, which is in the profile, within what the coding loses.
     */
    @Test
    fun aLossyPictureWithAProfileComesOutInTheProfile() {
        assumeTrue("libjxl or ImageMagick is not installed", Tools.hasAll("cjxl", "magick"))
        val reports = ArrayList<String>()
        // cjxl describes the gray profile with code points of its own, so only the RGB ones stay profiles alone.
        for ((profile, gray) in profiles.filter { !it.second }) {
            val icc = File("/usr/share/color/icc/$profile")
            assumeTrue("icc-profiles-free is not installed", icc.exists())
            val src = temp(".png").apply { writeBytes(ImageKodec.encodePng(scene)) }
            val png = temp(".png")
            val args = mutableListOf(Tools.require("magick").path, src.path)
            if (gray) args += listOf("-colorspace", "Gray")
            args += listOf("-profile", icc.path, png.path)
            assertEquals(0, run(*args.toTypedArray()), "ImageMagick could not embed $profile")
            val jxl = temp(".jxl")
            assertEquals(0, run(Tools.require("cjxl").path, png.path, jxl.path, "-d", "0.3"))
            val bytes = jxl.readBytes()
            assertNotNull(ImageKodec.probe(bytes).colorProfile?.icc, "$profile: the profile the samples are in")
            val source = ImageKodec.decode16(png.readBytes())
            val ours = ImageKodec.decode16(bytes)
            val theirs = if (gray && source.channels == 1 && ours.channels == 3) {
                KiteBitmap16(source.width, source.height, 3, ShortArray(source.samples.size * 3) { source.samples[it / 3] })
            } else source
            // Lossy coding moves single samples at hard edges, so the mean is what tells; the samples of
            // sRGB, which this gave before #189, are other numbers for every saturated colour.
            reports += compare("lossy in $profile, against its source", theirs, ours, 48.0, meanBound = 1.0)
        }
        println(reports.joinToString("\n"))
    }

    /** The colour fields of the header of [jxl], to tell which encoding a file has. */
    private fun describe(jxl: ByteArray): String {
        val s = JxlDecoder.open(jxl)
        val c = s.header.color
        return "xyb ${s.header.xybEncoded}, wantIcc ${c.wantIcc}, space ${c.colorSpace}, white ${c.whitePoint}, " +
            "primaries ${c.primaries} ${c.primariesXy.toList()}, gamma ${if (c.haveGamma) c.gamma else "none"}, " +
            "transfer ${c.transferFunction}, icc ${s.icc?.size} bytes, inverse ${s.header.iccInverse != null}"
    }

    /** [bitmap] as a 16-bit RGB PNG, its samples times [scale], with [chunks] before its data. */
    private fun png16(bitmap: KiteBitmap, scale: Double = 1.0, vararg chunks: ByteArray): ByteArray {
        val samples = IntArray(bitmap.width * bitmap.height * 3) { i ->
            val v = (bitmap.argb[i / 3] ushr (16 - 8 * (i % 3))) and 0xFF
            (v * 257 * scale + 0.5).toInt()
        }
        return PngColorFiles.png(bitmap.width, bitmap.height, 3, 16, samples, *chunks)
    }

    /** [bitmap]'s samples, linear sRGB, into [profile] by lcms2's `to-profile`. */
    private fun lcmsIntoProfile(bitmap: KiteBitmap16, profile: File): KiteBitmap16 {
        val n = bitmap.width * bitmap.height
        val buffer = java.nio.ByteBuffer.allocate(n * 12).order(java.nio.ByteOrder.nativeOrder())
        for (i in 0 until n) for (c in 0 until 3) buffer.putFloat((bitmap.samples[i * bitmap.channels + c].toInt() and 0xFFFF) / 65535f)
        // From a file, so that a picture larger than a pipe holds cannot stall both sides.
        val input = temp(".f32").apply { writeBytes(buffer.array()) }
        val proc = ProcessBuilder(lcms!!.path, "to-profile", profile.path, "3").redirectInput(input).start()
        val out = java.nio.ByteBuffer.wrap(proc.inputStream.readBytes()).order(java.nio.ByteOrder.nativeOrder())
        assertEquals(0, proc.waitFor())
        return KiteBitmap16(bitmap.width, bitmap.height, 3, ShortArray(n * 3) {
            (out.float.toDouble().coerceIn(0.0, 1.0) * 65535 + 0.5).toInt().toShort()
        })
    }

    /**
     * A lossy picture whose only colour is a profile of lookup tables comes out in that profile through
     * its B2A0, held to the source it was coded from and to lcms2. djxl 0.7, with no colour engine, gives
     * linear sRGB, which lcms2 takes into the profile; a djxl with one gives the profile's samples itself.
     */
    @Test
    fun aLossyPictureWithATableProfileComesOutInTheProfile() {
        assumeTrue("libjxl, a C compiler or lcms2 is not installed", Tools.hasAll("cjxl", "djxl", "cc") && lcms != null)
        val reports = ArrayList<String>()
        for ((kind, _) in tableKinds.filter { !it.second }) {
            val icc = made(kind)
            val png = temp(".png").apply { writeBytes(png16(scene, 1.0, PngColorFiles.iccp(icc.readBytes()))) }
            val jxl = temp(".jxl")
            assertEquals(0, run(Tools.require("cjxl").path, png.path, jxl.path, "-d", "0.3"))
            val bytes = jxl.readBytes()
            val reported = assertNotNull(ImageKodec.probe(bytes).colorProfile?.icc, "$kind: the profile the samples are in; ${describe(bytes)}")
            assertContentEquals(icc.readBytes(), reported, kind)
            val ours = ImageKodec.decode16(bytes)
            // Lossy coding moves single samples at hard edges, so the mean is what tells. A grid indexed by
            // XYZ, linear light, has few nodes in the dark, so there the profile's own two tables are not
            // each other's inverse, and its pictures come back further from their source.
            val meanBound = if ("xyz" in kind) 5.0 else 1.0
            reports += compare("$kind, against its source", ImageKodec.decode16(png.readBytes()), ours, 48.0, meanBound = meanBound)
            val out = temp(".png")
            assertEquals(0, run(Tools.require("djxl").path, jxl.path, out.path, "--bits_per_sample=16"))
            val theirs = ImageKodec.decode16(out.readBytes())
            val description = ImageKodec.probe(out.readBytes()).colorProfile?.iccDescription.orEmpty()
            reports += if (description.endsWith("_Lin")) {
                // libjxl's own XYB to linear sRGB, in 16 bits, then lcms2 into the profile.
                compare("$kind, against djxl's linear sRGB into the profile by lcms2", lcmsIntoProfile(theirs, icc), ours,
                    LOSSY_TABLE_BOUND, meanBound = LOSSY_TABLE_MEAN)
            } else {
                // A djxl with a colour engine writes the profile's samples through its own engine.
                compare("$kind, against djxl ($description)", theirs, ours, 48.0, meanBound = 1.0)
            }
        }
        println(reports.joinToString("\n"))
    }

    /** Wide primaries, red, green and blue x and y, beyond what any code point names. */
    private val wide = doubleArrayOf(0.70, 0.29, 0.17, 0.78, 0.13, 0.05)

    /**
     * Primaries of their own, which a header gives no code point: the picture converts through the profile
     * made of the header. cjxl 0.7 takes no such primaries on its command line and cjxl 0.11 drops a PNG's
     * cHRM, so the header of a cjxl file is rewritten to carry them. djxl converts the XYB picture to sRGB
     * straight, whatever the header's primaries, so ours, through the primaries and back, has to agree.
     */
    @Test
    fun primariesOfTheirOwnConvertToSrgbAsDjxlConverts() {
        assumeTrue("libjxl is not installed", Tools.hasAll("cjxl", "djxl"))
        val reports = ArrayList<String>()
        val png = temp(".png").apply { writeBytes(png16(scene)) }
        val coded = temp(".jxl")
        assertEquals(0, run(Tools.require("cjxl").path, png.path, coded.path, "-d", "1.0"))
        val encodings = listOf(
            "wide primaries, gamma 2.2" to JxlHeaderRewrite.custom(wide, gamma = 4545455),
            "wide primaries, linear" to JxlHeaderRewrite.custom(wide, transfer = JxlColorEncoding.TF_LINEAR),
        )
        for ((name, encoding) in encodings) {
            val bytes = JxlHeaderRewrite.withColor(coded.readBytes(), encoding)
            val header = JxlDecoder.open(bytes).header
            assertEquals(JxlColorEncoding.PRIMARIES_CUSTOM, header.color.primaries, describe(bytes))
            assertTrue(header.xybEncoded, describe(bytes))
            val jxl = temp(".jxl").apply { writeBytes(bytes) }
            val profile = assertNotNull(ImageKodec.probe(bytes).colorProfile)
            assertNotNull(profile.icc, "$name: a profile made of the header; ${describe(bytes)}")
            val out = temp(".png")
            // djxl 0.7 converts to another colour space only into an 8-bit PNG, which it dithers.
            assertEquals(0, run(Tools.require("djxl").path, jxl.path, out.path, "--color_space=RGB_D65_SRG_Rel_SRG"))
            val theirs = ImageKodec.decode16(out.readBytes())
            val ours = ImageKodec.decode16(bytes, colorTarget = ColorTarget.Srgb(RenderingIntent.RelativeColorimetric))
            reports += compare(name, theirs, ours, 3.0)
        }
        println(reports.joinToString("\n"))
    }

    // ---- PQ and HLG, BT.2100 tables 4 and 5, restated for the tests ----

    private val m1 = 2610.0 / 16384
    private val m2 = 2523.0 / 4096 * 128
    private val c1 = 3424.0 / 4096
    private val c2 = 2413.0 / 4096 * 32
    private val c3 = 2392.0 / 4096 * 32

    private fun pqToLight(e: Double): Double {
        val p = e.pow(1 / m2)
        return (maxOf(p - c1, 0.0) / (c2 - c3 * p)).pow(1 / m1)
    }

    private fun lightToPq(y: Double): Double {
        val p = y.coerceIn(0.0, 1.0).pow(m1)
        return ((c1 + c2 * p) / (1 + c3 * p)).pow(m2)
    }

    private val ha = 0.17883277
    private val hb = 1 - 4 * ha
    private val hc = 0.5 - ha * ln(4 * ha)

    private fun hlgToScene(e: Double): Double = if (e <= 0.5) e * e / 3 else (exp((e - hc) / ha) + hb) / 12

    private fun sceneToHlg(x: Double): Double {
        val v = x.coerceIn(0.0, 1.0)
        return if (v <= 1.0 / 12) sqrt(3 * v) else ha * ln(12 * v - hb) + hc
    }

    /** Linear RGB of primaries [p] (red, green, blue x and y) under D65 to XYZ, white's Y at 1, row by row. */
    private fun toXyz(p: DoubleArray): Array<DoubleArray> {
        fun column(x: Double, y: Double) = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val r = column(p[0], p[1])
        val g = column(p[2], p[3])
        val b = column(p[4], p[5])
        val w = column(0.3127, 0.3290)
        val m = Array(3) { i -> doubleArrayOf(r[i], g[i], b[i]) }
        val inv = invert(m)
        val s = DoubleArray(3) { i -> inv[i][0] * w[0] + inv[i][1] * w[1] + inv[i][2] * w[2] }
        return Array(3) { i -> DoubleArray(3) { j -> m[i][j] * s[j] } }
    }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray> {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        return Array(3) { i ->
            DoubleArray(3) { j ->
                (m[(j + 1) % 3][(i + 1) % 3] * m[(j + 2) % 3][(i + 2) % 3] - m[(j + 1) % 3][(i + 2) % 3] * m[(j + 2) % 3][(i + 1) % 3]) / det
            }
        }
    }

    /** The PQ or HLG samples of [stored], RGB of primaries [own] under D65, as the same light in BT.2020's primaries. */
    private fun asBt2020(stored: KiteBitmap16, own: DoubleArray, pq: Boolean): IntArray {
        val a = toXyz(own)
        val b = invert(toXyz(doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)))
        val m = Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> b[i][k] * a[k][j] } } }
        val out = IntArray(stored.width * stored.height * 3)
        val light = DoubleArray(3)
        for (p in 0 until stored.width * stored.height) {
            for (c in 0 until 3) {
                val e = (stored.samples[p * stored.channels + c].toInt() and 0xFFFF) / 65535.0
                light[c] = if (pq) pqToLight(e) else hlgToScene(e)
            }
            for (c in 0 until 3) {
                val l = m[c][0] * light[0] + m[c][1] * light[1] + m[c][2] * light[2]
                out[p * 3 + c] = ((if (pq) lightToPq(l) else sceneToHlg(l)) * 65535 + 0.5).toInt()
            }
        }
        return out
    }

    /**
     * PQ and HLG with primaries of their own (#189): the samples come out in those primaries as djxl gives
     * them, `probe` describes them with a profile as libjxl makes one (its colorants, and the curve as a
     * table of 4096 samples, against djxl's own), and a conversion to sRGB takes their own primaries. djxl
     * 0.7 converts neither curve to sRGB, so that conversion is held to an exact one computed here from the
     * header: the same light in BT.2020's primaries, coded as a PNG of `cICP` 9 that names the same peak,
     * which must convert to the same sRGB.
     */
    @Test
    fun pqAndHlgWithPrimariesOfTheirOwnConvertWithThem() {
        assumeTrue("libjxl is not installed", Tools.hasAll("cjxl", "djxl"))
        val own = doubleArrayOf(0.64, 0.33, 0.21, 0.71, 0.15, 0.06)
        val reports = ArrayList<String>()
        for (pq in listOf(true, false)) for (distance in listOf("0", "1")) {
            val name = "${if (pq) "PQ" else "HLG"}, own primaries, ${if (distance == "0") "lossless" else "lossy"}"
            val src = temp(".png").apply { writeBytes(png16(scene, 0.9)) }
            val coded = temp(".jxl")
            val args = mutableListOf(Tools.require("cjxl").path, src.path, coded.path, "-d", distance,
                "-x", "color_space=RGB_D65_202_Rel_${if (pq) "PeQ" else "HLG"}")
            if (pq) args += "--intensity_target=4000"
            assertEquals(0, run(*args.toTypedArray()))
            val transfer = if (pq) JxlColorEncoding.TF_PQ else JxlColorEncoding.TF_HLG
            val bytes = JxlHeaderRewrite.withColor(coded.readBytes(), JxlHeaderRewrite.custom(own, transfer = transfer))
            val jxl = temp(".jxl").apply { writeBytes(bytes) }
            val profile = assertNotNull(ImageKodec.probe(bytes).colorProfile, describe(bytes))
            assertContentEquals(intArrayOf(2, transfer, 0, 1), profile.cicp, name)
            val icc = assertNotNull(profile.icc, "$name: a profile made of the header")

            // djxl writes the samples in the header's encoding, and the profile libjxl makes of it.
            val out = temp(".png")
            val theirIcc = temp(".icc")
            assertEquals(0, run(Tools.require("djxl").path, jxl.path, out.path, "--bits_per_sample=16", "--icc_out=${theirIcc.path}"))
            val stored = ImageKodec.decode16(bytes)
            val lossless = distance == "0"
            reports += compare("$name, samples against djxl", ImageKodec.decode16(out.readBytes()), stored,
                if (lossless) 0.0 else HDR_SAMPLES_BOUND, meanBound = if (lossless) 0.0 else HDR_SAMPLES_MEAN)
            val ourProfile = assertNotNull(IccProfile.parse(icc))
            val libjxl = assertNotNull(IccProfile.parse(theirIcc.readBytes()))
            var colorant = 0.0
            for (tag in listOf("rXYZ", "gXYZ", "bXYZ")) {
                val a = assertNotNull(ourProfile.xyz(tag))
                val b = assertNotNull(libjxl.xyz(tag), "libjxl's profile has no $tag")
                for (k in 0 until 3) colorant = maxOf(colorant, abs(a[k] - b[k]))
            }
            val ours = assertNotNull(ourProfile.curve("rTRC"))
            val theirs = assertNotNull(libjxl.curve("rTRC"))
            var curve = 0.0
            for (i in 0..4095) curve = maxOf(curve, abs(ours.eval(i / 4095.0) - theirs.eval(i / 4095.0)) * 65535)
            reports += "$name, profile against libjxl's: colorants %.6f, curve %.2f of 65535".format(colorant, curve)
            println(reports.last())
            // s15Fixed16 colorants of the same matrix.
            assertTrue(colorant <= 2.0 / 65536, reports.last())
            // libjxl 0.7 holds the curve alone, which this profile follows to libjxl's rounding. A libjxl
            // that adds tone-mapped A2B0 tables (0.11 is said to) may shape its curve otherwise, so the
            // curve is held to libjxl's only where its profile is of matrix and curves alone.
            if (!libjxl.has("A2B0")) assertTrue(curve <= 2.0, reports.last())

            // To sRGB with its own primaries: as the same light in BT.2020's.
            val reference = PngColorFiles.png(stored.width, stored.height, 3, 16, asBt2020(stored, own, pq),
                PngColorFiles.cicp(9, transfer), PngColorFiles.clli(4000.0, 1000.0))
            val want = ImageKodec.decode16(reference, colorTarget = ColorTarget.Srgb())
            val got = ImageKodec.decode16(bytes, colorTarget = ColorTarget.Srgb())
            reports += compare("$name, to sRGB against the same light in BT.2020", want, got, HDR_BOUND, meanBound = HDR_MEAN)
        }
        println(reports.joinToString("\n"))
    }

    @Test
    fun theIntensityTargetIsThePeakToneMappingTakes() {
        assumeTrue("libjxl is not installed", Tools.hasAll("cjxl"))
        val w = 32
        val h = 24
        // A PQ picture up to 4000 cd/m2, in 16-bit samples, coded lossless.
        val samples = IntArray(w * h * 3) { i -> ((i % (w * 3)) * 65535 / (w * 3 - 1) * ((i / (w * 3)) + 8) / (h + 8)) }
        val png16 = PngColorFiles.png(w, h, 3, 16, samples)
        val src = temp(".png").apply { writeBytes(png16) }
        val reports = ArrayList<String>()
        for (target in listOf(4000, 1000)) {
            val jxl = temp(".jxl")
            assertEquals(0, run(Tools.require("cjxl").path, src.path, jxl.path, "-d", "0", "-x", "color_space=RGB_D65_202_Rel_PeQ",
                "--intensity_target=$target"))
            val bytes = jxl.readBytes()
            val light = assertNotNull(ImageKodec.probe(bytes).colorProfile?.contentLight)
            assertEquals(target * 10000, light[0])
            // The same samples in a PNG that says the same peak with cLLi: the two tone mappings must agree.
            val stored = ImageKodec.decode16(bytes)
            val png = PngColorFiles.png(w, h, 3, 16, IntArray(stored.samples.size) { stored.samples[it].toInt() and 0xFFFF },
                PngColorFiles.cicp(9, 16), PngColorFiles.clli(target.toDouble(), target / 4.0))
            val want = ImageKodec.decode(png, colorTarget = ColorTarget.Srgb())
            val got = ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb())
            assertContentEquals(want.argb, got.argb, "intensity target $target")
            reports += "intensity target $target: tone mapped as a PNG of cLLi $target"
        }
        println(reports.joinToString("\n"))
    }

    private companion object {
        /** IccInverse through a B2A table against lcms2, in 65535ths. */
        const val TABLE_BOUND = 6.0

        /**
         * A lossy table-profile picture against djxl's linear sRGB taken into the profile by lcms2, 8-bit
         * scale; measured 0.02 worst, 0.002 mean, both reading the same 16-bit linear sRGB.
         */
        const val LOSSY_TABLE_BOUND = 0.25
        const val LOSSY_TABLE_MEAN = 0.02

        /** A lossy PQ or HLG picture in its own primaries against djxl's, 8-bit scale; measured equal with libjxl 0.7. */
        const val HDR_SAMPLES_BOUND = 1.0
        const val HDR_SAMPLES_MEAN = 0.05

        /** The conversion to sRGB of own primaries against the same light in BT.2020, 8-bit scale; measured 0.02 worst. */
        const val HDR_BOUND = 0.1
        const val HDR_MEAN = 0.01
    }
}
