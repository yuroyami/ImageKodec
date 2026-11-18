package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JPEG XL CMYK (#189) against libjxl. cjxl 0.7 reads no CMYK source, so ImageKodec's encoder
 * writes lossless CMYK files: C, M and Y as the colour channels, K as a kBlack extra channel, and
 * a CMYK profile that `tools/icc_lcms.c` makes. libjxl stores ink as 1 for none and 0 for full.
 *
 * The djxl 0.7 here has no colour engine: it writes the stored samples, the colour channels
 * (with alpha) to one file and the black to another named `-ec1`, and ImageKodec's ink must be
 * their complement exactly. `IccOracleTest.cmykJxlConvertsThroughItsProfile` holds the picture to
 * lcms2. djxl 0.11, which CI runs, converts through its colour engine; there the test holds its
 * sRGB PNG to `ColorTarget.Srgb(RelativeColorimetric)` within 3 levels of 255. That branch has
 * not been run here: what djxl 0.11 writes for a CMYK file, and how near its engine comes, is
 * unverified. Skips without djxl, cc or lcms2.
 */
class JxlCmykOracleTest {

    private fun run(vararg args: String, input: ByteArray? = null): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (input != null) proc.outputStream.use { it.write(input) } else proc.outputStream.close()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private val dir by lazy { kotlin.io.path.createTempDirectory("kite-jxl-cmyk").toFile().apply { deleteOnExit() } }

    /** The CMYK profile, made by `tools/icc_lcms.c` against lcms2. */
    private val profile: ByteArray by lazy {
        val source = listOf(File("../tools/icc_lcms.c"), File("tools/icc_lcms.c")).first { it.isFile }
        val tool = File(dir, "icc_lcms")
        val (built, _) = run(Tools.require("cc").path, "-O2", "-o", tool.path, source.path, "-llcms2", "-lm")
        check(built == 0) { "could not build tools/icc_lcms.c against lcms2" }
        val (made, icc) = run(tool.path, "make", "cmyk-tiny-v4")
        check(made == 0)
        icc
    }

    /** True when the installed libjxl is 0.11 or newer, which has a colour engine. */
    private val current: Boolean by lazy {
        val text = run(Tools.require("djxl").path, "--version").second.decodeToString()
        val match = Regex("""v(\d+)\.(\d+)""").find(text) ?: return@lazy false
        val (major, minor) = match.destructured
        major.toInt() > 0 || minor.toInt() >= 11
    }

    /** The samples of a binary PNM or PAM file, and how many a pixel. */
    private fun readPnm(file: File): Pair<Int, IntArray> {
        val b = file.readBytes()
        var at = 0
        fun token(): String {
            while (b[at].toInt().toChar().isWhitespace() || b[at] == '#'.code.toByte()) {
                if (b[at] == '#'.code.toByte()) while (b[at] != '\n'.code.toByte()) at++ else at++
            }
            val start = at
            while (!b[at].toInt().toChar().isWhitespace()) at++
            return String(b, start, at - start)
        }
        val magic = token()
        val w: Int
        val h: Int
        val depth: Int
        val max: Int
        if (magic == "P7") {
            val fields = HashMap<String, String>()
            while (true) {
                val key = token()
                if (key == "ENDHDR") break
                fields[key] = token()
            }
            w = fields.getValue("WIDTH").toInt()
            h = fields.getValue("HEIGHT").toInt()
            depth = fields.getValue("DEPTH").toInt()
            max = fields.getValue("MAXVAL").toInt()
        } else {
            w = token().toInt()
            h = token().toInt()
            max = token().toInt()
            depth = if (magic == "P6") 3 else 1
        }
        at++
        val n = w * h * depth
        return depth to IntArray(n) { i ->
            if (max < 256) b[at + i].toInt() and 0xFF else ((b[at + 2 * i].toInt() and 0xFF) shl 8) or (b[at + 2 * i + 1].toInt() and 0xFF)
        }
    }

    @Test
    fun storedSamplesMatchDjxl() {
        Tools.require("djxl")
        var files = 0
        var compared = 0L
        for ((w, h) in listOf(1 to 1, 24 to 16, 67 to 41)) for (bits in listOf(8, 16)) for (withAlpha in listOf(false, true)) {
            val max = (1 shl bits) - 1
            val ink = if (bits == 8) {
                val (b, _) = JxrCmykFixtures.ink(w, h, w + h, alpha = false)
                IntArray(b.size) { b[it].toInt() and 0xFF }
            } else {
                JxrCmykFixtures.ink16(w, h, w + h)
            }
            val alpha = if (withAlpha) IntArray(w * h) { (it * 37 + 11) % (max + 1) } else null
            val label = "$w x $h, $bits bits, alpha $withAlpha"
            val jxl = JxlEncoder.encodeCmyk(w, h, bits, ink, alpha, profile, JxlEncodeOptions(lossless = true), ImageMetadata(), null)
            val info = ImageKodec.probe(jxl)
            assertTrue(info.isDecodable, "$label: ${info.unsupportedReason}")
            assertEquals(4, info.colorChannels, label)
            assertEquals("CMYK", info.colorProfile?.iccColorSpace, label)
            // ImageKodec's ink is the input, the encoder's and decoder's reading of libjxl's convention agreeing.
            val ours = JxlDecoder.decodeInk(jxl, wide = true)!!
            for (i in ink.indices) {
                val got = ours.ink16!![i].toInt() and 0xFFFF
                val want = if (bits == 8) ink[i] * 257 else ink[i]
                if (got != want) fail("$label: ink sample $i is $got, the input $want")
            }
            if (alpha != null) for (i in alpha.indices) assertEquals(if (bits == 8) alpha[i] * 257 else alpha[i], ours.alpha16!![i].toInt() and 0xFFFF, "$label: alpha $i")
            if (current) continue
            // djxl 0.7: the stored samples, colour (with alpha) in one file and black in another.
            val input = File(dir, "c.jxl").apply { writeBytes(jxl) }
            dir.listFiles { f -> f.name.startsWith("out") }?.forEach { it.delete() }
            val (code, _) = run(Tools.require("djxl").path, input.path, File(dir, "out.ppm").path)
            assertEquals(0, code, "$label: djxl failed")
            val (colorDepth, color) = readPnm(File(dir, "out-ec0.ppm"))
            val (_, black) = readPnm(File(dir, "out-ec1.pgm"))
            assertEquals(if (withAlpha) 4 else 3, colorDepth, label)
            for (i in 0 until w * h) {
                for (c in 0 until 3) assertEquals(max - ink[i * 4 + c], color[i * colorDepth + c], "$label: djxl's channel $c at $i")
                assertEquals(max - ink[i * 4 + 3], black[i], "$label: djxl's black at $i")
                if (alpha != null) assertEquals(alpha[i], color[i * 4 + 3], "$label: djxl's alpha at $i")
            }
            files++
            compared += color.size + black.size
        }
        println("JxlCmykOracleTest: $files files, $compared samples of djxl's equal to ImageKodec's stored samples")
    }

    /** djxl 0.11's picture of a CMYK file, through its colour engine. Not run here: see the class comment. */
    @Test
    fun djxlWithAColourEngineConvertsAsColorTargetSrgb() {
        Tools.require("djxl")
        if (!current) return
        val w = 40
        val h = 28
        val (b, _) = JxrCmykFixtures.ink(w, h, 3, alpha = false)
        val ink = IntArray(b.size) { b[it].toInt() and 0xFF }
        val jxl = JxlEncoder.encodeCmyk(w, h, 8, ink, null, profile, JxlEncodeOptions(lossless = true), ImageMetadata(), null)
        val input = File(dir, "c11.jxl").apply { writeBytes(jxl) }
        val png = File(dir, "c11.png")
        val (code, _) = run(Tools.require("djxl").path, input.path, png.path, "--color_space=RGB_D65_SRG_Rel_SRG")
        assertEquals(0, code, "djxl failed")
        val reference = javax.imageio.ImageIO.read(png)
        val ours = ImageKodec.decode(jxl, colorTarget = ColorTarget.Srgb(RenderingIntent.RelativeColorimetric))
        var worst = 0
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            val shift = 16 - 8 * c
            worst = maxOf(worst, abs(((reference.getRGB(x, y) shr shift) and 0xFF) - ((ours[x, y] shr shift) and 0xFF)))
        }
        println("JxlCmykOracleTest: djxl 0.11's sRGB of CMYK is at most $worst levels from ColorTarget.Srgb")
        assertTrue(worst <= 3, "djxl's sRGB of a CMYK file is $worst levels from ImageKodec's")
    }
}
