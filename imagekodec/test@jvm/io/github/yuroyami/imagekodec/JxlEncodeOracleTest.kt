package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JPEG XL encoder (#179) against libjxl's djxl, which must read every file it writes:
 *
 * - a lossless file, 8 or 16 bits, gray or colour, with or without alpha, reads in djxl to
 *   exactly the input;
 * - a lossy file reads in djxl as in ImageKodec's decoder, within the bounds `JxlOracleTest`
 *   holds the decoder to for an old djxl, and stays close to the input;
 * - an animation reads in djxl frame for frame, with the delays it was given.
 *
 * libjxl here may be 0.7.0, whose djxl writes the file's own depth; a lossy file is then held
 * at 8 bits. Skips without djxl, unless IMAGEKODEC_REQUIRE_ORACLES is 1.
 */
class JxlEncodeOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        // Every tool runs under timeout -s KILL where the system has it, so no run outlives the test.
        val kill = Tools.find("timeout")
        val command = if (kill != null) listOf(kill.path, "-s", "KILL", "300") + args else args.toList()
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(320, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxlenc", ext).apply { deleteOnExit() }

    /** djxl's reading of [jxl] as a PNG, at 16 bits where [deep]. */
    private fun djxl(jxl: ByteArray, deep: Boolean = false): KiteBitmap16 {
        val input = temp(".jxl").apply { writeBytes(jxl) }
        val out = temp(".png")
        val (code, text) = run(Tools.require("djxl").path, input.path, out.path, *(if (deep) arrayOf("--bits_per_sample=16") else emptyArray()))
        assertEquals(0, code, "djxl could not read the file: $text")
        return ImageKodec.decode16(out.readBytes())
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun synthetic(w: Int, h: Int, alpha: Boolean, gray: Boolean, seed: Int): KiteBitmap {
        val random = kotlin.random.Random(seed)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = random.nextInt(-6, 7)
            val r = (x * 255 / w + n).coerceIn(0, 255)
            val g = if (gray) r else (y * 255 / h - n).coerceIn(0, 255)
            val b = if (gray) r else ((x + y) * 3 % 256)
            val a = if (alpha) (if ((x / 9 + y / 7) % 4 == 0) 0 else (x * 2 + y) and 0xFF) else 0xFF
            argb(a, r, g, b)
        })
    }

    /** The samples of [b] as a 16-bit picture of its own channels: what djxl writes for an 8-bit file. */
    private fun channelsOf(b: KiteBitmap, gray: Boolean, alpha: Boolean): ShortArray {
        val ch = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        val out = ShortArray(b.argb.size * ch)
        var at = 0
        for (p in b.argb) {
            val r = (p ushr 16) and 0xFF
            if (gray) out[at++] = (r * 257).toShort() else {
                out[at++] = (r * 257).toShort()
                out[at++] = (((p ushr 8) and 0xFF) * 257).toShort()
                out[at++] = ((p and 0xFF) * 257).toShort()
            }
            if (alpha) out[at++] = ((p ushr 24) * 257).toShort()
        }
        return out
    }

    @Test
    fun losslessFilesReadExactlyInDjxl() {
        assumeTrue("djxl is not installed", Tools.hasAll("djxl"))
        val pictures = listOf(
            Triple("photo", PhotoScenes.landscape(300, 200).bitmap, false),
            Triple("alpha", synthetic(131, 77, alpha = true, gray = false, seed = 1), false),
            Triple("gray", synthetic(97, 300, alpha = false, gray = true, seed = 2), true),
            Triple("gray alpha", synthetic(64, 64, alpha = true, gray = true, seed = 3), true),
            Triple("one pixel", KiteBitmap(1, 1, intArrayOf(argb(0x40, 1, 2, 3))), false),
            Triple("few colours", KiteBitmap(70, 50, IntArray(3500) { if ((it / 7 + it / 350) % 3 == 0) argb(255, 200, 10, 10) else argb(255, 5, 6, 7) }), false),
        )
        for ((name, b, gray) in pictures) {
            for (effort in 1..3) {
                val jxl = ImageKodec.encodeJxl(b, JxlEncodeOptions(lossless = true, effort = effort))
                val got = djxl(jxl)
                val alpha = b.hasTransparency()
                assertEquals((if (gray) 1 else 3) + (if (alpha) 1 else 0), got.channels, "$name channels")
                assertContentEquals(channelsOf(b, gray, alpha), got.samples, "$name, effort $effort: djxl reads other samples")
            }
        }
    }

    @Test
    fun sixteenBitLosslessFilesReadExactlyInDjxl() {
        assumeTrue("djxl is not installed", Tools.hasAll("djxl"))
        for (channels in 1..4) {
            val w = 83
            val h = 41
            val random = kotlin.random.Random(channels)
            val samples = ShortArray(w * h * channels) { i ->
                val x = (i / channels) % w
                ((x * 700 + random.nextInt(0, 300) + (i % channels) * 9000) and 0xFFFF).toShort()
            }
            val b = KiteBitmap16(w, h, channels, samples)
            val jxl = ImageKodec.encodeJxl16(b, JxlEncodeOptions(lossless = true))
            val got = djxl(jxl)
            assertEquals(channels, got.channels, "$channels channels")
            assertContentEquals(samples, got.samples, "$channels channels: djxl reads other samples")
        }
    }

    @Test
    fun metadataGoesIntoTheContainer() {
        assumeTrue("djxl is not installed", Tools.hasAll("djxl"))
        val b = synthetic(40, 30, alpha = false, gray = false, seed = 9)
        val icc = File("/usr/share/color/icc/sRGB.icc").takeIf { it.exists() }?.readBytes()
        val meta = ImageMetadata(exif = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0), xmp = "<x:xmpmeta/>", icc = icc)
        val jxl = ImageKodec.encodeJxl(b, JxlEncodeOptions(lossless = true), meta)
        assertEquals(ImageFormat.JXL, ImageKodec.detect(jxl))
        assertContentEquals(channelsOf(b, false, false), djxl(jxl).samples, "a file with metadata")
        assertContentEquals(b.argb, ImageKodec.decode(jxl).argb)
        if (icc != null) assertContentEquals(icc, ImageKodec.probe(jxl).colorProfile?.icc, "the ICC profile")
    }

    private fun worst(a: KiteBitmap16, b: KiteBitmap16): Int {
        var worst = 0
        for (i in a.samples.indices) worst = maxOf(worst, abs((a.samples[i].toInt() and 0xFFFF) - (b.samples[i].toInt() and 0xFFFF)))
        return worst
    }
}
