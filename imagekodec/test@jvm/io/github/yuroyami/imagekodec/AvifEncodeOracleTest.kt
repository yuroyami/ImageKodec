package io.github.yuroyami.imagekodec

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AVIF files `encodeAvif` and `encodeAvif16` write (#176), read by other software:
 *
 * - libavif reads each one (`avifdec`, and `tools/avif_rgb.c`, which converts as libavif does with `avoidLibYUV`), to
 *   the RGB ImageKodec's decoder gives, within the bounds `AvifOracleTest` holds the decoder to: one level at 8 bits,
 *   136 at 16. The cases cover 4:2:0, 4:2:2, 4:4:4 and gray, 8, 10 and 12 bits, alpha, a `grid` with a thumbnail,
 *   one frame of AV1 tiles, and lossless, which libavif reads back to the source exactly.
 * - libheif's `heif-convert` applies the clean aperture, rotation and mirror, on 4:4:4, within one level.
 * - `exiftool` reads the EXIF orientation, the XMP and the ICC profile back.
 *
 * Skips cleanly without avifdec, heif-convert, exiftool, a C compiler or libavif's headers.
 */
class AvifEncodeOracleTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "kite-avif-encode").apply { mkdirs() }

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "300") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        return proc.waitFor() to out.decodeToString()
    }

    /** The libavif reference reader, built once from `tools/avif_rgb.c`. */
    private val reader: File? by lazy {
        val source = listOf(File("../tools/avif_rgb.c"), File("tools/avif_rgb.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = File(dir, "avif_rgb")
        val (code, text) = run(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-lavif")
        if (code != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/avif_rgb.c against libavif: $text")
            return@lazy null
        }
        out
    }

    private fun photo(w: Int, h: Int, alpha: Boolean = false, seed: Int = 3): KiteBitmap {
        var s = seed * 2654435761.toInt() + 1
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            s = s * 1103515245 + 12345
            val n = (s ushr 16) and 15
            val x = i % w
            val y = i / w
            val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < w * h / 8
            val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
            val g = (y * 255 / maxOf(1, h - 1) + (if (disc) 60 else 0)).coerceIn(0, 255)
            val b = ((x + y) * 3 + n * 2).coerceIn(0, 255)
            val a = if (alpha) (if (disc) 255 else (x * 7 + y * 3) and 0xFF) else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    private fun gray(b: KiteBitmap) = KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
        val p = b.argb[i]
        (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
    })

    /** A 16-bit picture of gradients and noise, gray when [gray]. */
    private fun deep(w: Int, h: Int, gray: Boolean, alpha: Boolean): KiteBitmap16 {
        val b = photo(w, h, alpha)
        val channels = (if (gray) 1 else 3) + (if (alpha) 1 else 0)
        val out = ShortArray(w * h * channels)
        for (i in 0 until w * h) {
            val p = b.argb[i]
            val at = i * channels
            val g = minOf(((p ushr 8) and 0xFF) * 257 + (i * 11 and 0xFF), 65535)
            if (gray) {
                out[at] = g.toShort()
            } else {
                out[at] = minOf(((p ushr 16) and 0xFF) * 257 + (i * 37 and 0xFF), 65535).toShort()
                out[at + 1] = g.toShort()
                out[at + 2] = ((p and 0xFF) * 257).toShort()
            }
            if (alpha) out[at + channels - 1] = ((p ushr 24) * 257).toShort()
        }
        return KiteBitmap16(w, h, channels, out)
    }

    /** libavif's reading of [data] at [depth] bits, before any crop or orientation. */
    private fun libavif(data: ByteArray, depth: Int): KiteBitmap16 {
        val input = File(dir, "in.avif").apply { writeBytes(data) }
        val out = File(dir, "out.raw").apply { delete() }
        val (code, text) = run(reader!!.path, input.path, "$depth", out.path)
        assertEquals(0, code, "libavif could not read the file: $text")
        val b = out.readBytes()
        fun u32(at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or
            ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)
        val w = u32(0)
        val h = u32(4)
        val channels = u32(8)
        val samples = ShortArray(w * h * channels) { i ->
            if (depth == 8) ((b[12 + i].toInt() and 255) * 257).toShort()
            else ((b[12 + 2 * i].toInt() and 255) or ((b[13 + 2 * i].toInt() and 255) shl 8)).toShort()
        }
        return KiteBitmap16(w, h, channels, samples)
    }

    private fun widen(b: KiteBitmap, channels: Int): KiteBitmap16 =
        KiteBitmap16(b.width, b.height, channels, ShortArray(b.width * b.height * channels) { i ->
            val px = b.argb[i / channels]
            val c = i % channels
            val v = if (c == 3) px ushr 24 else (px ushr (16 - 8 * c)) and 255
            (v * 257).toShort()
        })

    private fun worst(got: KiteBitmap16, want: KiteBitmap16, name: String): Int {
        assertEquals(Triple(want.width, want.height, want.channels), Triple(got.width, got.height, got.channels), "$name shape")
        var worst = 0
        for (i in want.samples.indices) worst = maxOf(worst, abs((got.samples[i].toInt() and 0xFFFF) - (want.samples[i].toInt() and 0xFFFF)))
        return worst
    }

    private fun crop(b: KiteBitmap16, w: Int, h: Int): KiteBitmap16 =
        KiteBitmap16(w, h, b.channels, ShortArray(w * h * b.channels) { i ->
            val px = i / b.channels
            b.samples[((px / w) * b.width + px % w) * b.channels + i % b.channels]
        })

    private class Case(val name: String, val file: ByteArray, val exact: KiteBitmap? = null)

    private fun cases(): List<Case> = listOf(
        Case("4:2:0 8 bits, odd size", ImageKodec.encodeAvif(photo(65, 37), HeifEncodeOptions(quality = 80))),
        Case("4:4:4 8 bits with alpha", ImageKodec.encodeAvif(photo(64, 48, alpha = true), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444))),
        Case("4:2:2 8 bits", ImageKodec.encodeAvif(photo(66, 40), HeifEncodeOptions(quality = 60, chroma = HeifChroma.Yuv422))),
        Case("gray with alpha", ImageKodec.encodeAvif(gray(photo(40, 32, alpha = true)), HeifEncodeOptions(quality = 70))),
        Case("10 bits 4:2:0", ImageKodec.encodeAvif16(deep(70, 42, false, false), HeifEncodeOptions(quality = 70))),
        Case("10 bits gray", ImageKodec.encodeAvif16(deep(45, 27, true, false), HeifEncodeOptions(quality = 70))),
        Case("12 bits 4:4:4 with alpha", ImageKodec.encodeAvif16(deep(48, 40, false, true), HeifEncodeOptions(quality = 70, bitDepth = 12, chroma = HeifChroma.Yuv444))),
        Case("12 bits 4:2:0", ImageKodec.encodeAvif16(deep(50, 30, false, false), HeifEncodeOptions(quality = 50, bitDepth = 12))),
        Case("grid with alpha and a thumbnail", ImageKodec.encodeAvif(photo(300, 200, alpha = true), HeifEncodeOptions(quality = 70, tileSize = 128, thumbnailSize = 64, effort = 1))),
        Case("AV1 tiles: one frame 4160 wide", ImageKodec.encodeAvif(photo(4160, 24), HeifEncodeOptions(quality = 60, tileSize = null, effort = 1))),
        Case("lossless 8 bits", ImageKodec.encodeAvif(photo(57, 33), HeifEncodeOptions(lossless = true)), exact = photo(57, 33)),
        Case("lossless 8 bits with alpha", ImageKodec.encodeAvif(photo(31, 20, alpha = true), HeifEncodeOptions(lossless = true)), exact = photo(31, 20, alpha = true)),
        Case("lossless 12 bits with alpha", ImageKodec.encodeAvif16(deep(33, 20, false, true), HeifEncodeOptions(lossless = true))),
    )

    @Test
    fun libavifReadsTheFiles() {
        assumeTrue("libavif's avifdec, a C compiler or libavif's headers not installed", Tools.hasAll("avifdec", "cc", "timeout") && reader != null)
        for (case in cases()) {
            val narrow = ImageKodec.decode(case.file, applyOrientation = false)
            val wide = ImageKodec.decode16(case.file, applyOrientation = false)
            // libavif leaves the clean aperture to its caller; the writer's sits at the top left.
            val ref8 = crop(libavif(case.file, 8), narrow.width, narrow.height)
            val ref16 = crop(libavif(case.file, 16), narrow.width, narrow.height)
            val w8 = worst(widen(narrow, ref8.channels), ref8, case.name)
            val w16 = worst(wide, ref16, case.name)
            println("${case.name}: ${case.file.size} bytes; against libavif, worst ${w8 / 257} levels at 8 bits and $w16 at 16")
            assertTrue(w8 <= 257, "${case.name}: differs from libavif by $w8 at 8 bits")
            assertTrue(w16 <= 136, "${case.name}: differs from libavif by $w16 at 16 bits")
            case.exact?.let { assertContentEquals(it.argb, narrow.argb, "${case.name}: lossless") }
            if (case.exact != null) assertEquals(0, w8, "${case.name}: libavif reads the lossless file exactly")
            // avifdec itself, as a user runs it.
            val input = File(dir, "case.avif").apply { writeBytes(case.file) }
            val png = File(dir, "case.png").apply { delete() }
            val (code, log) = run(Tools.require("avifdec").path, input.path, png.path)
            assertEquals(0, code, "${case.name}: avifdec failed: $log")
            // avifdec 1.0.4 leaves the clean aperture out too, so its picture is cropped as libavif's is, and it converts
            // through libyuv's fixed point, two or three levels from an exact conversion (AGENTS.md).
            val theirs = ImageKodec.decode16(png.readBytes())
            assertTrue(theirs.width >= narrow.width && theirs.height >= narrow.height, "${case.name}: avifdec's size")
            val theirs8 = crop(theirs, narrow.width, narrow.height).toBitmap()
            var worstPng = 0
            for (i in narrow.argb.indices) for (c in 0 until 4) {
                worstPng = maxOf(worstPng, abs(((narrow.argb[i] ushr (8 * c)) and 0xFF) - ((theirs8.argb[i] ushr (8 * c)) and 0xFF)))
            }
            assertTrue(worstPng <= 3, "${case.name}: avifdec's PNG differs by $worstPng")
        }
    }

    private fun libheif(file: ByteArray): KiteBitmap {
        val input = File(dir, "in.avif").apply { writeBytes(file) }
        val png = File(dir, "out.png").apply { delete() }
        val (code, log) = run(Tools.require("heif-convert").path, input.path, png.path)
        assertEquals(0, code, "heif-convert failed: $log")
        return ImageKodec.decode(png.readBytes())
    }

    @Test
    fun libheifAppliesTheGeometry() {
        assumeTrue("heif-convert not installed", Tools.hasAll("heif-convert", "timeout"))
        for (o in listOf(Orientation.Normal, Orientation.Rotate90, Orientation.Rotate180, Orientation.FlipHorizontal, Orientation.Transpose)) {
            // An odd size, so the clean aperture crops too.
            val file = ImageKodec.encodeAvif(photo(49, 31), HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444, orientation = o))
            val ours = ImageKodec.decode(file, applyOrientation = true)
            val theirs = libheif(file)
            assertEquals(ours.width to ours.height, theirs.width to theirs.height, "$o: size")
            var worst = 0
            for (i in ours.argb.indices) for (c in 0 until 3) {
                worst = maxOf(worst, abs(((ours.argb[i] ushr (8 * c)) and 0xFF) - ((theirs.argb[i] ushr (8 * c)) and 0xFF)))
            }
            println("orientation $o: against heif-convert, worst $worst")
            assertTrue(worst <= 1, "$o: differs from heif-convert by $worst")
        }
    }

    private val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:format=\"image/avif\"/></rdf:RDF></x:xmpmeta>"

    /** A little-endian EXIF block of one entry: orientation 1. */
    private val exif = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0)

    @Test
    fun exiftoolReadsTheMetadata() {
        assumeTrue("exiftool not installed", Tools.hasAll("exiftool", "timeout"))
        val icc = File("/usr/share/color/icc/sRGB.icc").takeIf { it.isFile }?.readBytes()
        if (icc == null && System.getenv(Tools.REQUIRE_ENV) == "1") error("/usr/share/color/icc/sRGB.icc (icc-profiles-free) is missing")
        val file = ImageKodec.encodeAvif(photo(64, 48), HeifEncodeOptions(quality = 80, orientation = Orientation.Rotate90), ImageMetadata(exif, xmp, icc))
        val input = File(dir, "meta.avif").apply { writeBytes(file) }
        val (code, out) = run(Tools.require("exiftool").path, "-s", "-Orientation", "-XMP-dc:Format", "-ProfileDescription", "-MajorBrand", input.path)
        assertEquals(0, code, out)
        println(out.trim())
        assertTrue(out.lines().any { it.startsWith("Orientation") && it.endsWith("Rotate 90 CW") }, out)
        assertTrue(out.lines().any { it.startsWith("Format") && it.endsWith("image/avif") }, out)
        assertTrue(out.lines().any { it.startsWith("MajorBrand") && "AVIF" in it.uppercase() }, out)
        if (icc != null) assertTrue(out.lines().any { it.startsWith("ProfileDescription") }, out)
        val back = ImageKodec.readMetadata(file)
        assertEquals(xmp, back.xmp)
        icc?.let { assertContentEquals(it, back.icc) }
    }
}
