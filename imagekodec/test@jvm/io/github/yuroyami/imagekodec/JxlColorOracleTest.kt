package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JPEG XL colour (#189) against libjxl: a lossy picture whose colour is an ICC profile alone, which
 * djxl writes in that profile through its colour engine; primaries of their own, which djxl converts
 * to sRGB when asked; and the intensity target, which tone mapping takes as the peak.
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
            val inverse = assertNotNull(io.github.yuroyami.imagekodec.internal.color.IccInverse.of(file.readBytes()), profile)
            val random = kotlin.random.Random(189)
            val inputs = List(4000) { k -> if (k < 256) DoubleArray(3) { k / 255.0 } else DoubleArray(3) { random.nextDouble() } }
            val buffer = java.nio.ByteBuffer.allocate(inputs.size * 12).order(java.nio.ByteOrder.nativeOrder())
            for (v in inputs) for (c in 0 until 3) buffer.putFloat(v[c].toFloat())
            val proc = ProcessBuilder(lcms!!.path, "to-profile", file.path, if (gray) "1" else "3").start()
            proc.outputStream.use { it.write(buffer.array()) }
            val out = java.nio.ByteBuffer.wrap(proc.inputStream.readBytes()).order(java.nio.ByteOrder.nativeOrder())
            assertEquals(0, proc.waitFor())
            var worst = 0.0
            for (v in inputs) {
                val ours = DoubleArray(3) { v[it].toFloat().toDouble() }
                inverse.encode(ours)
                for (c in 0 until (if (gray) 1 else 3)) {
                    val want = out.float.toDouble().coerceIn(0.0, 1.0)
                    worst = maxOf(worst, abs(want - ours[c]) * 65535)
                }
            }
            reports += "$profile: worst %.1f of 65535 from lcms2".format(worst)
            // A table of 4097 samples a curve, read linearly between them, and lcms2's own inversion.
            assertTrue(worst <= 64, reports.last())
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

    /** The colour fields of the header of [jxl], to tell which encoding a cjxl version chose. */
    private fun describe(jxl: ByteArray): String {
        val s = io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder.open(jxl)
        val c = s.header.color
        return "xyb ${s.header.xybEncoded}, wantIcc ${c.wantIcc}, space ${c.colorSpace}, white ${c.whitePoint}, " +
            "primaries ${c.primaries} ${c.primariesXy.toList()}, gamma ${if (c.haveGamma) c.gamma else "none"}, " +
            "transfer ${c.transferFunction}, icc ${s.icc?.size} bytes, inverse ${s.header.iccInverse != null}"
    }

    /** [png] with `cHRM` and `gAMA` chunks put before its first `IDAT`. */
    private fun withChunks(png: ByteArray, vararg chunks: ByteArray): ByteArray {
        var at = 8
        while (String(png, at + 4, 4, Charsets.US_ASCII) != "IDAT") at += 12 + java.nio.ByteBuffer.wrap(png, at, 4).int
        return png.copyOf(at) + chunks.fold(ByteArray(0)) { a, b -> a + b } + png.copyOfRange(at, png.size)
    }

    @Test
    fun primariesOfTheirOwnConvertToSrgbAsDjxlConverts() {
        assumeTrue("libjxl is not installed", Tools.hasAll("cjxl", "djxl"))
        val reports = ArrayList<String>()
        for ((name, gamma) in listOf("wide primaries, gamma 2.2" to 45455, "wide primaries, linear" to 100000)) {
            val png = temp(".png").apply {
                writeBytes(withChunks(ImageKodec.encodePng(scene), PngColorFiles.gama(gamma),
                    PngColorFiles.chrm(0.3127, 0.3290, 0.70, 0.29, 0.17, 0.78, 0.13, 0.05)))
            }
            val jxl = temp(".jxl")
            assertEquals(0, run(Tools.require("cjxl").path, png.path, jxl.path, "-d", "1.0"))
            val bytes = jxl.readBytes()
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
}
