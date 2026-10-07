package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AVIF files (#41) written by libavif's avifenc, read through [ImageKodec] and compared with
 * libavif's own reading of them: every subsampling and depth, both ranges, the matrices, alpha
 * straight and premultiplied, grids and the first frame of a sequence.
 *
 * The reference is `tools/avif_rgb.c`, compiled here against the installed libavif, which
 * converts through libavif's built-in float path with bilinear chroma rather than through
 * libyuv's fixed point, as avifdec does. That path computes in single precision and this
 * library in double, so an 8-bit sample may differ by one level. At 16 bits each sample is
 * also held to the 8-bit sample [ImageKodec.decode] gives, so it may sit up to half a level
 * of 8 bits from libavif's.
 *
 * libavif leaves the clean aperture, rotation and mirror to its caller; libheif's heif-convert
 * applies all three, so it checks the geometry on 4:4:4 files, where the two libraries convert
 * alike. Skips cleanly without avifenc, heif-convert, a C compiler and libavif's headers.
 */
class AvifOracleTest {

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

    private fun temp(ext: String) = File.createTempFile("kite-avif", ext).apply { deleteOnExit() }

    /** The libavif reference reader, built once from `tools/avif_rgb.c`. */
    private val reader: File? by lazy {
        val source = listOf(File("../tools/avif_rgb.c"), File("tools/avif_rgb.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = temp(".bin")
        val (code, text) = run(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-lavif")
        if (code != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/avif_rgb.c against libavif: $text")
            return@lazy null
        }
        out
    }

    private fun tools(): Boolean = Tools.hasAll("avifenc", "heif-convert", "cc") && reader != null

    /** A source with smooth gradients, hard edges and, for [alpha], a ramp of opacity reaching 0. */
    private fun source(width: Int, height: Int, alpha: Boolean): File {
        val argb = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val r = x * 255 / (width - 1)
            val g = if ((x / 9 + y / 7) % 2 == 0) 230 else 20
            val b = y * 255 / (height - 1)
            val a = if (alpha) (x + y) * 255 / (width + height - 2) else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return temp(".png").apply { writeBytes(ImageKodec.encodePng(KiteBitmap(width, height, argb))) }
    }

    private fun encode(vararg args: String): ByteArray {
        val out = temp(".avif")
        val quality = if ("-l" in args) emptyArray() else arrayOf("-q", "60")
        val (code, text) = run(Tools.require("avifenc").path, "-s", "8", *quality, *args, out.path)
        assertEquals(0, code, "avifenc failed: $text")
        return out.readBytes()
    }

    /** libavif's reading of [data] at [depth] bits, before any crop or orientation. */
    private fun reference(data: ByteArray, depth: Int): KiteBitmap16 {
        val input = temp(".avif").apply { writeBytes(data) }
        val out = temp(".raw")
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

    /**
     * [got] within [tolerance] of [want] in every sample. With [premultiplied] the colour is
     * compared multiplied by its alpha, as it is shown: libavif rounds a premultiplied 4:4:4 image's
     * colour to 8 bits before dividing by alpha, which a nearly transparent pixel magnifies.
     */
    private fun assertClose(name: String, got: KiteBitmap16, want: KiteBitmap16, tolerance: Int, premultiplied: Boolean = false) {
        assertEquals(Triple(want.width, want.height, want.channels), Triple(got.width, got.height, got.channels), "$name shape")
        fun sample(b: KiteBitmap16, i: Int): Int {
            val v = b.samples[i].toInt() and 0xFFFF
            if (!premultiplied || b.channels != 4 || i % 4 == 3) return v
            val a = b.samples[i - i % 4 + 3].toInt() and 0xFFFF
            return ((v.toLong() * a + 32767) / 65535).toInt()
        }
        var worst = 0
        var at = 0
        for (i in want.samples.indices) {
            val d = kotlin.math.abs(sample(got, i) - sample(want, i))
            if (d > worst) {
                worst = d
                at = i
            }
        }
        assertTrue(worst <= tolerance, "$name differs from libavif by $worst at pixel ${at / want.channels}, channel ${at % want.channels}")
    }

    /** [data] read by ImageKodec at 8 and 16 bits and by libavif, and probed. */
    private fun check(name: String, data: ByteArray, frames: Int = 1, premultiplied: Boolean = false) {
        val narrow = ImageKodec.decode(data)
        val wide = ImageKodec.decode16(data)
        val ref8 = reference(data, 8)
        val ref16 = reference(data, 16)
        assertClose("$name at 8 bits", widen(narrow, ref8.channels), ref8, if (premultiplied) 3 * 257 else 257, premultiplied)
        assertClose("$name at 16 bits", wide, ref16, if (premultiplied) 3 * 257 else 136, premultiplied)
        assertContentEquals(narrow.argb, wide.toBitmap().argb, "$name: decode16 does not narrow to decode")
        val info = ImageKodec.probe(data)
        assertEquals(ImageFormat.AVIF, info.format, name)
        assertEquals(narrow.width to narrow.height, info.width to info.height, "$name probe size")
        assertEquals(ref8.channels == 4, info.hasAlpha, "$name probe alpha")
        assertEquals(frames, info.frameCount, "$name frame count")
        assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
    }

    @Test
    fun subsamplingsDepthsAndRanges() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        val src = source(203, 117, alpha = false)
        for (yuv in listOf("420", "422", "444", "400")) {
            for (depth in listOf(8, 10, 12)) {
                check("$yuv at $depth bits", encode("-y", yuv, "-d", "$depth", src.path))
            }
        }
        check("limited range", encode("-y", "420", "-r", "limited", src.path))
        check("lossless", encode("-l", src.path))
    }

    @Test
    fun matrices() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        val src = source(160, 96, alpha = false)
        // BT.709, BT.601, BT.2020, chromaticity-derived, FCC, SMPTE 240, unspecified, identity, YCgCo.
        for (cicp in listOf("1/13/1", "1/13/5", "9/16/9", "12/13/12", "4/13/4", "7/13/7", "2/2/2", "1/13/0", "2/2/8")) {
            val yuv = if (cicp.endsWith("/0") || cicp.endsWith("/8")) "444" else "420"
            val data = encode("-y", yuv, "--cicp", cicp, src.path)
            check("matrix $cicp", data)
            val reported = ImageKodec.probe(data).colorProfile?.cicp?.toList()
            assertEquals(cicp.split("/").map { it.toInt() } + 1, reported, "matrix $cicp reported")
        }
    }

    @Test
    fun alpha() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        val src = source(201, 149, alpha = true)
        check("alpha 4:2:0", encode("-y", "420", src.path))
        check("alpha 4:2:2 10-bit", encode("-y", "422", "-d", "10", src.path))
        check("premultiplied alpha", encode("-y", "420", "-p", src.path), premultiplied = true)
        check("premultiplied 12-bit 4:4:4", encode("-y", "444", "-d", "12", "-p", src.path), premultiplied = true)
    }

    @Test
    fun gridsAndSequences() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        check("grid 2x2", encode("-y", "420", "-g", "2x2", source(256, 192, alpha = false).path))
        check("grid 3x2 with alpha", encode("-y", "420", "-g", "3x2", source(192, 128, alpha = true).path))
        check("grid 4:4:4 10-bit", encode("-y", "444", "-d", "10", "-g", "3x2", source(150, 100, alpha = false).path))
        val frames = List(3) { source(120, 80, alpha = false).path }
        check("sequence", encode("-y", "420", *frames.toTypedArray()), frames = 3)
    }

    @Test
    fun geometryAsLibheifShowsIt() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        val src = source(90, 60, alpha = false)
        val cases = mutableListOf<Array<String>>()
        for (rotation in 0..3) for (mirror in listOf(null, 0, 1)) {
            if (rotation == 0 && mirror == null) continue
            cases += (listOf("--irot", "$rotation") + (mirror?.let { listOf("--imir", "$it") } ?: emptyList())).toTypedArray()
        }
        cases += arrayOf("--crop", "10,6,50,40")
        cases += arrayOf("--crop", "10,6,50,40", "--irot", "1", "--imir", "1")
        for (transform in cases) {
            val name = transform.joinToString(" ")
            val data = encode("-y", "444", *transform, src.path)
            val input = temp(".avif").apply { writeBytes(data) }
            val out = temp(".png")
            val (code, text) = run(Tools.require("heif-convert").path, input.path, out.path)
            assertEquals(0, code, "heif-convert failed: $text")
            val heif = ImageKodec.decode(out.readBytes())
            val ours = ImageKodec.decode(data, applyOrientation = true)
            assertEquals(heif.width to heif.height, ours.width to ours.height, "$name size")
            val info = ImageKodec.probe(data)
            assertEquals(heif.width to heif.height, info.displayWidth to info.displayHeight, "$name probe")
            var worst = 0
            for (i in heif.argb.indices) for (c in 0 until 3) {
                worst = maxOf(worst, kotlin.math.abs(((heif.argb[i] ushr (8 * c)) and 255) - ((ours.argb[i] ushr (8 * c)) and 255)))
            }
            assertTrue(worst <= 1, "$name differs from libheif by $worst")
        }
    }

    @Test
    fun iccProfilesAreReported() {
        assumeTrue("libavif, libheif or a C compiler not installed", tools())
        // A profile need not describe the pixels for this: any well-formed ICC payload is carried as is.
        val profile = temp(".icc").apply { writeBytes(MINIMAL_PROFILE) }
        val data = encode("-y", "420", "--icc", profile.path, source(64, 48, alpha = false).path)
        assertContentEquals(MINIMAL_PROFILE, ImageKodec.probe(data).colorProfile?.icc)
        check("with an ICC profile", data)
    }

    private companion object {
        /** A minimal ICC v2 header with an empty tag table: 128 bytes of header and a zero tag count. */
        val MINIMAL_PROFILE: ByteArray = ByteArray(132).also { p ->
            val size = 132
            p[0] = (size ushr 24).toByte(); p[1] = (size ushr 16).toByte(); p[2] = (size ushr 8).toByte(); p[3] = size.toByte()
            p[8] = 2
            "mntr".forEachIndexed { i, ch -> p[12 + i] = ch.code.toByte() }
            "RGB ".forEachIndexed { i, ch -> p[16 + i] = ch.code.toByte() }
            "XYZ ".forEachIndexed { i, ch -> p[20 + i] = ch.code.toByte() }
            "acsp".forEachIndexed { i, ch -> p[36 + i] = ch.code.toByte() }
        }
    }
}
