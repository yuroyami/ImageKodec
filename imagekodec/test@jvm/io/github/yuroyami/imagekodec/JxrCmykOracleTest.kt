package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import io.github.yuroyami.imagekodec.codec.jxr.JxrEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JPEG XR CMYK and the N-channel formats (#189) against jxrlib's `JxrDecApp`. No other tool
 * writes either (`JxrEncApp` stops with WMP_errUnsupportedFormat on every CMYK TIFF and takes no
 * N-channel source), so ImageKodec's encoder writes them and `JxrDecApp` decodes them to a TIFF:
 * ImageKodec's decoder must give its samples exactly, lossless or lossy, and a lossless file must
 * give the input back. Skips without `JxrDecApp` (Debian's libjxr-tools).
 */
class JxrCmykOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxr-cmyk", ext).apply { deleteOnExit() }

    /** The samples of a TIFF that JxrDecApp wrote (one strip, uncompressed, little endian), and its photometric. */
    private class Tiff(val spp: Int, val photometric: Int, val samples: IntArray)

    private fun readTiff(bytes: ByteArray): Tiff {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val at = ifd + 2 + 12 * i
            val tag = b.getShort(at).toInt() and 0xFFFF
            if (tag !in setOf(256, 257, 258, 262, 273, 277)) continue
            val type = b.getShort(at + 2).toInt()
            val count = b.getInt(at + 4)
            val size = if (type == 3) 2 else 4
            val base = if (count * size > 4) b.getInt(at + 8) else at + 8
            tags[tag] = IntArray(count) { k -> if (type == 3) b.getShort(base + 2 * k).toInt() and 0xFFFF else b.getInt(base + 4 * k) }
        }
        val w = tags.getValue(256)[0]
        val h = tags.getValue(257)[0]
        val spp = tags.getValue(277)[0]
        val bits = tags.getValue(258)[0]
        val start = tags.getValue(273)[0]
        val samples = IntArray(w * h * spp) { i -> if (bits == 8) bytes[start + i].toInt() and 0xFF else b.getShort(start + 2 * i).toInt() and 0xFFFF }
        return Tiff(spp, tags.getValue(262)[0], samples)
    }

    /** JxrDecApp's TIFF of [jxr], in pixel format [format] (its `-c`), or the file's own when null. */
    private fun reference(jxr: ByteArray, format: Int?): Tiff {
        val input = temp(".jxr").apply { writeBytes(jxr) }
        val out = temp(".tif")
        val args = listOf(Tools.require("JxrDecApp").path, "-i", input.path, "-o", out.path) + (format?.let { listOf("-c", "$it") } ?: emptyList())
        val (code, text) = run(*args.toTypedArray())
        assertTrue(code == 0, "JxrDecApp failed: $text")
        return readTiff(out.readBytes())
    }

    private var files = 0
    private var compared = 0L

    /** [jxr] read by JxrDecApp as [format] and by ImageKodec must agree sample for sample, and equal [exact] when given. */
    private fun check(label: String, jxr: ByteArray, format: Int?, exact: IntArray? = null, photometric: Int = 5): Tiff {
        val want = reference(jxr, format)
        val d = JxrDecoder.decodeRaw(jxr)
        val got = d.raster.samples
        assertEquals(photometric, want.photometric, "$label: JxrDecApp's photometric")
        assertEquals(want.spp, d.raster.slots, "$label: channels")
        assertEquals(want.samples.size, got.size, "$label: samples")
        for (i in got.indices) {
            if (got[i] != want.samples[i]) fail("$label: sample $i is ${got[i]}, JxrDecApp ${want.samples[i]}")
        }
        if (exact != null) {
            for (i in got.indices) if (got[i] != exact[i]) fail("$label: sample $i is ${got[i]}, the input ${exact[i]}")
        }
        files++
        compared += got.size
        return want
    }

    @Test
    fun cmykDecodesAsJxrDecAppDecodesIt() {
        for ((w, h) in listOf(1 to 1, 17 to 3, 40 to 33, 96 to 80)) {
            for (seed in 0 until 2) {
                val (ink, alpha) = JxrCmykFixtures.ink(w, h, seed, alpha = true)
                val ink16 = JxrCmykFixtures.ink16(w, h, seed)
                val alpha16 = IntArray(w * h) { minOf(65535, (alpha[it].toInt() and 0xFF) * 257 + seed) }
                for (options in OPTIONS) {
                    val tag = "${w}x$h seed $seed ${describe(options)}"
                    val exact = options.lossless
                    var jxr = JxrEncoder.encodeCmyk(w, h, ink, null, options, ImageMetadata(), null)
                    check("32bppCMYK $tag", jxr, 17, if (exact) IntArray(ink.size) { ink[it].toInt() and 0xFF } else null)
                    jxr = JxrEncoder.encodeCmyk(w, h, ink, alpha, options, ImageMetadata(), null)
                    check("40bppCMYKAlpha $tag", jxr, 32, if (exact) interleave(IntArray(ink.size) { ink[it].toInt() and 0xFF }, IntArray(w * h) { alpha[it].toInt() and 0xFF }) else null)
                    jxr = JxrEncoder.encodeCmyk16(w, h, ShortArray(ink16.size) { ink16[it].toShort() }, null, options, ImageMetadata(), null)
                    check("64bppCMYK $tag", jxr, 18, if (exact) ink16 else null)
                    jxr = JxrEncoder.encodeCmyk16(w, h, ShortArray(ink16.size) { ink16[it].toShort() }, ShortArray(w * h) { alpha16[it].toShort() }, options, ImageMetadata(), null)
                    check("80bppCMYKAlpha $tag", jxr, 33, if (exact) interleave(ink16, alpha16) else null)
                }
            }
        }
        println("JxrCmykOracleTest: $files files, $compared samples equal to JxrDecApp's")
    }

    /**
     * Without a profile, `decode` draws the ink as libtiff's RGBA interface draws a CMYK TIFF:
     * `tiff2rgba` of the TIFF that `JxrDecApp -c 17` writes gives the same pixels. libtiff draws
     * no alpha for CMYK, so the alpha is held to the input. At 16 bits, `decode16` gives what
     * ImageKodec's TIFF decoder gives for `JxrDecApp`'s 64bppCMYK TIFF. `JxrDecApp -c 9`, its own
     * RGB of a CMYK file, decodes into a buffer of the wrong size and is no reference.
     */
    @Test
    fun cmykDrawsAsLibtiffDrawsJxrDecAppsInk() {
        Tools.require("tiff2rgba")
        val w = 45
        val h = 21
        for (seed in 0 until 2) {
            val (ink, alpha) = JxrCmykFixtures.ink(w, h, seed, alpha = true)
            for ((label, options) in listOf("lossless" to JxrEncodeOptions(lossless = true), "q60" to JxrEncodeOptions(quality = 60))) {
                for (withAlpha in listOf(false, true)) {
                    val jxr = JxrEncoder.encodeCmyk(w, h, ink, if (withAlpha) alpha else null, options, ImageMetadata(), null)
                    val input = temp(".jxr").apply { writeBytes(jxr) }
                    val tif = temp(".tif")
                    assertEquals(0, run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", tif.path, "-c", if (withAlpha) "32" else "17").first)
                    val rgba = temp(".tif")
                    assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", tif.path, rgba.path).first)
                    val reference = javax.imageio.ImageIO.read(rgba)
                    val ours = ImageKodec.decode(jxr)
                    assertEquals(4, ImageKodec.probe(jxr).colorChannels)
                    for (y in 0 until h) for (x in 0 until w) {
                        val a = if (withAlpha) alpha[y * w + x].toInt() and 0xFF else 255
                        val want = (a shl 24) or (reference.getRGB(x, y) and 0xFFFFFF)
                        assertEquals(want, ours[x, y], "seed $seed $label alpha $withAlpha at $x, $y")
                    }
                }
            }
            val ink16 = JxrCmykFixtures.ink16(w, h, seed)
            val jxr16 = JxrEncoder.encodeCmyk16(w, h, ShortArray(ink16.size) { ink16[it].toShort() }, null, JxrEncodeOptions(quality = 80), ImageMetadata(), null)
            val input = temp(".jxr").apply { writeBytes(jxr16) }
            val tif = temp(".tif")
            assertEquals(0, run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", tif.path, "-c", "18").first)
            val viaTiff = ImageKodec.decode16(tif.readBytes())
            val ours = ImageKodec.decode16(jxr16)
            assertEquals(3, ours.channels)
            assertTrue(viaTiff.samples.contentEquals(ours.samples), "seed $seed: 64bppCMYK differs from the TIFF decoder's reading of JxrDecApp's ink")
        }
    }

    /**
     * The N-channel formats of 3 to 8 channels, 8 or 16 bits, with planar alpha or none: `JxrDecApp`
     * writes their channels to a TIFF of RGB photometric, and ImageKodec's samples must equal them.
     * The picture follows T.832 Annex C, which reads three channels as sRGB and the first four of
     * more as CMYK: `decode` gives `tiff2rgba`'s RGB of three, and of more the drawing of a CMYK TIFF
     * of the first four, which `cmykDrawsAsLibtiffDrawsJxrDecAppsInk` holds to libtiff. Interleaved
     * N-channel alpha stays refused: `JxrDecApp` fails on it.
     */
    @Test
    fun nChannelDecodesAsJxrDecAppDecodesIt() {
        val w = 37
        val h = 19
        for (n in 3..8) for (depth in listOf(8, 16)) for (alpha in listOf(false, true)) {
            val max = if (depth == 8) 255 else 65535
            val src = IntArray(w * h * n) { i -> ((i / n) * (17 + i % n * 13) + (i % n) * 31 + (i / n / w) * 5) % (max + 1) }
            val a = if (alpha) IntArray(w * h) { (it * 7 + 3) % (max + 1) } else null
            val expected = IntArray(w * h * (n + if (alpha) 1 else 0)) { i ->
                val slots = n + if (alpha) 1 else 0
                if (alpha && i % slots == n) a!![i / slots] else src[i / slots * n + i % slots]
            }
            for (options in listOf(JxrEncodeOptions(lossless = true), JxrEncodeOptions(quality = 70), JxrEncodeOptions(quality = 30, overlap = 2, tileSize = 16))) {
                val label = "$n channels, $depth bits, alpha $alpha, ${describe(options)}"
                val jxr = JxrEncoder.encodeChannels(w, h, depth, n, src, a, options, ImageMetadata(), null)
                val want = check(label, jxr, null, if (options.lossless) expected else null, photometric = 2)
                val info = ImageKodec.probe(jxr)
                assertTrue(info.isDecodable, label)
                assertEquals(if (n == 3) 3 else 4, info.colorChannels, label)
                val slots = want.spp
                val ours = ImageKodec.decode16(jxr)
                if (n == 3) {
                    for (i in 0 until w * h) for (c in 0 until slots) {
                        val v = want.samples[i * slots + c]
                        assertEquals(if (depth == 8) v * 257 else v, ours.samples[i * slots + c].toInt() and 0xFFFF, "$label: pixel $i channel $c")
                    }
                } else {
                    val ink = IntArray(w * h * (if (alpha) 5 else 4)) { i ->
                        val k = if (alpha) 5 else 4
                        want.samples[i / k * slots + if (i % k == 4) n else i % k]
                    }
                    val tiff = CmykTiffs.tiff(w, h, ink, bits = depth, extraSamples = if (alpha) 2 else null)
                    assertTrue(ImageKodec.decode16(tiff).samples.contentEquals(ours.samples), "$label: not the CMYK TIFF's picture of its first four channels")
                    assertTrue(ImageKodec.decode(tiff).argb.contentEquals(ImageKodec.decode(jxr).argb), "$label: not the CMYK TIFF's 8-bit picture")
                }
            }
            if (alpha && n == 5) {
                val interleaved = JxrEncoder.encodeChannels(w, h, depth, n, src, a, JxrEncodeOptions(lossless = true, planarAlpha = false), ImageMetadata(), null)
                val input = temp(".jxr").apply { writeBytes(interleaved) }
                val (code, _) = run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", temp(".tif").path)
                assertTrue(code != 0, "JxrDecApp now reads interleaved N-channel alpha")
                assertTrue(!ImageKodec.probe(interleaved).isDecodable)
            }
        }
        // Three channels read as RGB: tiff2rgba of JxrDecApp's TIFF gives decode's pixels.
        Tools.require("tiff2rgba")
        val src = IntArray(w * h * 3) { (it * 37) % 256 }
        val jxr = JxrEncoder.encodeChannels(w, h, 8, 3, src, null, JxrEncodeOptions(quality = 60), ImageMetadata(), null)
        val input = temp(".jxr").apply { writeBytes(jxr) }
        val tif = temp(".tif")
        assertEquals(0, run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", tif.path).first)
        val rgba = temp(".tif")
        assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", tif.path, rgba.path).first)
        val reference = javax.imageio.ImageIO.read(rgba)
        val ours = ImageKodec.decode(jxr)
        for (y in 0 until h) for (x in 0 until w) assertEquals(reference.getRGB(x, y), ours[x, y], "24bpp3Channels at $x, $y")
        println("JxrCmykOracleTest: N-channel, $files files, $compared samples equal to JxrDecApp's")
    }

    private fun interleave(ink: IntArray, alpha: IntArray) = IntArray(alpha.size * 5) { i -> if (i % 5 == 4) alpha[i / 5] else ink[i / 5 * 4 + i % 5] }

    private fun describe(o: JxrEncodeOptions) =
        if (o.lossless) "lossless" else "q${o.quality} overlap ${o.overlap} tiles ${o.tileSize} freq ${o.frequencyOrder} planar ${o.planarAlpha}"

    private companion object {
        val OPTIONS = listOf(
            JxrEncodeOptions(lossless = true),
            JxrEncodeOptions(lossless = true, planarAlpha = false, tileSize = 16),
            JxrEncodeOptions(quality = 90),
            JxrEncodeOptions(quality = 40, planarAlpha = false, alphaLossless = false),
            JxrEncodeOptions(quality = 70, overlap = 2, tileSize = 32, frequencyOrder = true),
            JxrEncodeOptions(quality = 20, overlap = 0, chromaQuality = 60, tileSize = 16, tileQualities = null),
        )
    }
}
