package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-44: the pure-Kotlin JPX decoder against OpenJPEG. Fixtures are produced
 * on the fly with opj_compress from a deterministic PPM, decoded with
 * [JpxDecoder], and compared per-pixel against opj_decompress on the same
 * file. Skips cleanly when the OpenJPEG tools are absent.
 */
class JpxOracleTest {

    private val compress get() = Tools.require("opj_compress")
    private val decompress get() = Tools.require("opj_decompress")

    private fun tools(): Boolean = Tools.hasAll("opj_compress", "opj_decompress")

    /** Deterministic 97x61 RGB test card: gradients + blocks + a few edges. */
    private fun ppm(w: Int = 97, h: Int = 61, gray: Boolean = false): File {
        val header = "P${if (gray) 5 else 6}\n$w $h\n255\n".encodeToByteArray()
        val body = ByteArray(w * h * (if (gray) 1 else 3))
        var i = 0
        for (y in 0 until h) for (x in 0 until w) {
            val r = (x * 255 / (w - 1))
            val g = (y * 255 / (h - 1))
            val b = if ((x / 8 + y / 8) % 2 == 0) 230 else 25
            if (gray) body[i++] = ((r + g + b) / 3).toByte()
            else { body[i++] = r.toByte(); body[i++] = g.toByte(); body[i++] = b.toByte() }
        }
        return File.createTempFile("kite-jpx", ".ppm").apply {
            deleteOnExit()
            writeBytes(header + body)
        }
    }

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -1 }
        return proc.exitValue()
    }

    private class Pnm(val w: Int, val h: Int, val comps: Int, val data: ByteArray)

    private fun readPnm(f: File): Pnm {
        val d = f.readBytes()
        var p = 0
        fun token(): String {
            while (d[p].toInt().toChar().isWhitespace()) p++
            if (d[p].toInt().toChar() == '#') { while (d[p].toInt().toChar() != '\n') p++; return token() }
            val sb = StringBuilder()
            while (p < d.size && !d[p].toInt().toChar().isWhitespace()) sb.append(d[p++].toInt().toChar())
            return sb.toString()
        }
        val magic = token()
        val comps = if (magic == "P6") 3 else 1
        val w = token().toInt(); val h = token().toInt(); token() // maxval
        p++ // single whitespace after maxval
        return Pnm(w, h, comps, d.copyOfRange(p, p + w * h * comps))
    }

    /** Encode [src] with opj_compress + [extra] args, return the .jp2 file. */
    private fun encode(src: File, vararg extra: String): File {
        val jp2 = File.createTempFile("kite-jpx", ".jp2").apply { deleteOnExit() }
        val code = run(compress.absolutePath, "-i", src.absolutePath, "-o", jp2.absolutePath, *extra)
        assertEquals(0, code, "opj_compress failed")
        return jp2
    }

    /** Decode [jp2] with both decoders and return (kite, oracle). */
    private fun both(jp2: File): Pair<JpxDecoder.Result, Pnm> {
        val kite = JpxDecoder.decode(jp2.readBytes())
        assertNotNull(kite, "JpxDecoder returned null")
        val out = File.createTempFile("kite-jpx-ref", if (kite.colorSpace == "DeviceRGB") ".ppm" else ".pgm")
            .apply { deleteOnExit() }
        val code = run(decompress.absolutePath, "-i", jp2.absolutePath, "-o", out.absolutePath)
        assertEquals(0, code, "opj_decompress failed")
        return kite to readPnm(out)
    }

    /** Decode [jp2] with the [levels] finest wavelet levels dropped, by both decoders. */
    private fun bothReduced(jp2: File, levels: Int): Pair<JpxDecoder.Result, Pnm> {
        val kite = JpxDecoder.decode(jp2.readBytes(), 1 shl levels)
        assertNotNull(kite, "JpxDecoder returned null")
        val out = File.createTempFile("kite-jpx-ref", if (kite.colorSpace == "DeviceRGB") ".ppm" else ".pgm")
            .apply { deleteOnExit() }
        val code = run(decompress.absolutePath, "-i", jp2.absolutePath, "-o", out.absolutePath, "-r", "$levels")
        assertEquals(0, code, "opj_decompress -r $levels failed")
        return kite to readPnm(out)
    }

    private fun compare(tag: String, kite: JpxDecoder.Result, ref: Pnm, tolerance: Int) {
        assertEquals(ref.w, kite.width, "$tag width")
        assertEquals(ref.h, kite.height, "$tag height")
        val comps = if (kite.colorSpace == "DeviceRGB") 3 else 1
        assertEquals(ref.comps, comps, "$tag component count")
        var maxDiff = 0
        var sum = 0L
        for (i in kite.pixelBytes.indices) {
            val a = kite.pixelBytes[i].toInt() and 0xFF
            val b = ref.data[i].toInt() and 0xFF
            val diff = if (a > b) a - b else b - a
            if (diff > maxDiff) maxDiff = diff
            sum += diff
        }
        val mean = sum.toDouble() / kite.pixelBytes.size
        println("[T-44] $tag: maxDiff=$maxDiff meanDiff=${(mean * 1000).toInt() / 1000.0}")
        assertTrue(maxDiff <= tolerance, "$tag max per-pixel diff $maxDiff must be <= $tolerance")
    }

    @Test
    fun lossless_rgb_53_matches_openjpeg_exactly() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm()) // defaults: reversible 5/3, RCT, LRCP, one tile
        val (kite, ref) = both(jp2)
        compare("lossless-rgb", kite, ref, tolerance = 0)
    }

    @Test
    fun lossless_gray_matches_openjpeg_exactly() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(gray = true))
        val (kite, ref) = both(jp2)
        compare("lossless-gray", kite, ref, tolerance = 0)
    }

    @Test
    fun lossy_97_stays_close_to_openjpeg() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-I", "-r", "10")
        val (kite, ref) = both(jp2)
        // The fixed-point 9/7 path tolerates small rounding differences.
        compare("lossy-97", kite, ref, tolerance = 4)
    }

    @Test
    fun rpcl_progression_and_multiple_layers_decode() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-p", "RPCL", "-r", "20,10,1")
        val (kite, ref) = both(jp2)
        compare("rpcl-layers", kite, ref, tolerance = 0)
    }

    @Test
    fun tiled_image_decodes() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-t", "64,64")
        val (kite, ref) = both(jp2)
        compare("tiled", kite, ref, tolerance = 0)
    }

    @Test
    fun explicit_precincts_and_sop_eph_decode() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = encode(ppm(), "-c", "[32,32]", "-SOP", "-EPH")
        val (kite, ref) = both(jp2)
        compare("precincts", kite, ref, tolerance = 0)
    }

    @Test
    fun an_image_whose_origin_is_not_zero_decodes() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // At (5, 3) the first precinct of a high-pass band starts at an odd sample.
        val (kite, ref) = both(encode(ppm(), "-d", "5,3"))
        compare("offset", kite, ref, tolerance = 0)
    }

    @Test
    fun a_reduced_decode_matches_openjpeg_reduce() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // Each case at an eighth, a quarter and a half: lossless cases must match exactly.
        val cases = listOf(
            Triple("lossless-rgb", encode(ppm()), 0),
            Triple("lossless-gray", encode(ppm(gray = true)), 0),
            Triple("lossy-97", encode(ppm(), "-I", "-r", "10"), 4),
            Triple("rpcl-layers", encode(ppm(), "-p", "RPCL", "-r", "20,10,1"), 0),
            Triple("tiled", encode(ppm(), "-t", "64,64"), 0),
            Triple("precincts", encode(ppm(), "-c", "[32,32]", "-SOP", "-EPH"), 0),
        )
        for ((tag, jp2, tolerance) in cases) {
            for (levels in 1..3) {
                val (kite, ref) = bothReduced(jp2, levels)
                compare("$tag reduced by ${1 shl levels}", kite, ref, tolerance)
            }
        }
    }

    @Test
    fun an_offset_image_matches_openjpeg_where_both_have_pixels() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // The image starts at (5, 3), so its reduced grid starts part of a sample in. OpenJPEG's
        // reduced image can then be one pixel smaller than the rounded-up size this decoder gives.
        val jp2 = encode(ppm(), "-d", "5,3")
        for (levels in 1..3) {
            val kite = assertNotNull(JpxDecoder.decode(jp2.readBytes(), 1 shl levels), "JpxDecoder returned null")
            val out = File.createTempFile("kite-jpx-ref", ".ppm").apply { deleteOnExit() }
            assertEquals(0, run(decompress.absolutePath, "-i", jp2.absolutePath, "-o", out.absolutePath, "-r", "$levels"))
            val ref = readPnm(out)
            assertEquals((97 + (1 shl levels) - 1) shr levels, kite.width, "width at level $levels")
            assertEquals((61 + (1 shl levels) - 1) shr levels, kite.height, "height at level $levels")
            for (y in 0 until minOf(ref.h, kite.height)) for (x in 0 until minOf(ref.w, kite.width)) for (c in 0 until 3) {
                val a = kite.pixelBytes[(y * kite.width + x) * 3 + c].toInt() and 0xFF
                val b = ref.data[(y * ref.w + x) * 3 + c].toInt() and 0xFF
                assertEquals(b, a, "level $levels, pixel ($x, $y), component $c")
            }
        }
    }

    @Test
    fun a_reduced_decode_allocates_for_the_smaller_image() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // 1,600 by 1,200 lossy: the dropped levels keep neither coefficients nor samples.
        val bytes = encode(ppm(1600, 1200), "-I", "-r", "20").readBytes()
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated(block: () -> Unit): Long {
            val before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            block()
            return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before
        }
        repeat(2) { JpxDecoder.decode(bytes); JpxDecoder.decode(bytes, 8) } // warm up
        val full = allocated { JpxDecoder.decode(bytes) }
        val eighth = allocated { JpxDecoder.decode(bytes, 8) }
        println("jpx full: $full bytes, 1/8: $eighth bytes")
        assertTrue(eighth < full / 8, "full $full bytes, 1/8 $eighth bytes")
    }

    @Test
    fun a_reduction_past_the_wavelet_levels_averages_the_rest() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        // Two resolutions: one wavelet level. An eighth drops that level, then averages 4 by 4.
        val jp2 = encode(ppm(), "-n", "2")
        val kite = JpxDecoder.decode(jp2.readBytes(), 8)
        assertNotNull(kite, "JpxDecoder returned null")
        val (_, half) = bothReduced(jp2, 1)
        val w = (half.w + 3) / 4
        val h = (half.h + 3) / 4
        assertEquals(w, kite.width)
        assertEquals(h, kite.height)
        for (oy in 0 until h) for (ox in 0 until w) for (c in 0 until 3) {
            var sum = 0
            var count = 0
            for (y in oy * 4 until minOf(oy * 4 + 4, half.h)) for (x in ox * 4 until minOf(ox * 4 + 4, half.w)) {
                sum += half.data[(y * half.w + x) * 3 + c].toInt() and 0xFF
                count++
            }
            val expected = (sum + count / 2) / count
            assertEquals(expected, kite.pixelBytes[(oy * w + ox) * 3 + c].toInt() and 0xFF, "pixel ($ox, $oy), component $c")
        }
    }

    /**
     * A file no OpenJPEG run produced: the ffmpeg-encoded JP2 that `Jp2DecoderTest` and
     * `FuzzTest` share. It used to be a JPX stream cut out of a PDF in a sibling repository,
     * so the test skipped on every checkout without that repository, CI included. The file
     * uses the irreversible 9/7 wavelet, so it gets the same allowance as `lossy-97`.
     */
    @Test
    fun a_jp2_from_another_encoder_matches_openjpeg() {
        assumeTrue("OpenJPEG tools not found, skipping.", tools())
        val jp2 = File.createTempFile("kite-jpx-ffmpeg", ".jp2").apply {
            deleteOnExit()
            writeBytes(hex(JP2))
        }
        val (kite, ref) = both(jp2)
        compare("ffmpeg-fixture", kite, ref, tolerance = 4)
    }
}
