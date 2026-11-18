package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1Decoder
import io.github.yuroyami.imagekodec.codec.avif.Av1EncodeSettings
import io.github.yuroyami.imagekodec.codec.avif.Av1Encoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AV1 encoder (#176) against dav1d, through ffmpeg: for a matrix of sizes (1x1, 7x5, 64x64, 65x33, 200x120,
 * 513x257), formats (4:2:0, 4:2:2, 4:4:4, monochrome), depths (8, 10, 12), qualities, efforts and lossless,
 * dav1d decodes each stream to exactly the encoder's own reconstruction, and so does ImageKodec's decoder, every
 * sample of every plane. A lossless stream decodes to its input. Skips cleanly without ffmpeg or its libdav1d.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class Av1EncoderOracleTest {

    private var lastError = ""

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "300") + args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        var stderr = ByteArray(0)
        val err = Thread { stderr = proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        lastError = stderr.decodeToString().trim()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun dav1d(): Boolean {
        if (!Tools.hasAll("ffmpeg", "timeout")) return false
        val decoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-decoders").second.decodeToString()
        if ("libdav1d" !in decoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libdav1d, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            return false
        }
        return true
    }

    private fun temp(ext: String) = File.createTempFile("kite-av1enc", ext).apply { deleteOnExit() }

    /** dav1d's planes of [data], each whole, as ffmpeg writes [pixFmt]; 16-bit samples little endian. */
    private fun reference(data: ByteArray, pixFmt: String): ByteArray {
        // A temporal delimiter first, as the obu demuxer reads a temporal unit.
        val input = temp(".obu").apply { writeBytes(byteArrayOf(0x12, 0) + data) }
        val (code, raw) = run(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-c:v", "libdav1d", "-f", "obu", "-i", input.path, "-f", "rawvideo", "-pix_fmt", pixFmt, "-")
        assertEquals(0, code, "dav1d could not decode: $lastError")
        return raw
    }

    /** A photo-like picture: gradients, a disc with a hard edge, a fine texture and seeded noise, chroma smoother. */
    private fun picture(w: Int, h: Int, depth: Int, subX: Int, subY: Int, mono: Boolean, seed: Int = 1): HeifPlanes {
        val max = (1 shl depth) - 1
        var s = seed * 1103515245 + 7
        fun noise(): Int {
            s = s * 1103515245 + 12345
            return ((s ushr 16) and 31) - 16
        }
        val luma = ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < (w * h) / 6
            val texture = if ((x / 3 + y / 5) % 2 == 0) 20 else -20
            val v = 60 + x * 120 / maxOf(1, w) + y * 50 / maxOf(1, h) + (if (disc) 50 else 0) + (if (x > w * 2 / 3) texture else 0) + noise() / 4
            (v.coerceIn(0, 255) * max / 255).toShort()
        }
        if (mono) return HeifPlanes(w, h, depth, depth, 0, 0, arrayOf(luma), intArrayOf(w), true)
        val cw = (w + subX) shr subX
        val ch = (h + subY) shr subY
        val u = ShortArray(cw * ch) { i -> ((100 + (i % cw) * 60 / maxOf(1, cw) + noise() / 8).coerceIn(0, 255) * max / 255).toShort() }
        val v = ShortArray(cw * ch) { i -> ((150 - (i / cw) * 40 / maxOf(1, ch) + noise() / 8).coerceIn(0, 255) * max / 255).toShort() }
        return HeifPlanes(w, h, depth, depth, subX, subY, arrayOf(luma, u, v), intArrayOf(w, cw, cw), true)
    }

    private fun pixFmt(depth: Int, subX: Int, subY: Int, mono: Boolean): String {
        val base = if (mono) "gray" else if (subX == 1 && subY == 1) "yuv420p" else if (subX == 1) "yuv422p" else "yuv444p"
        return when (depth) {
            8 -> base
            else -> if (mono) "gray${depth}le" else "${base}${depth}le"
        }
    }

    private class Case(val w: Int, val h: Int, val depth: Int, val subX: Int, val subY: Int, val mono: Boolean, val qIndex: Int, val effort: Int) {
        override fun toString() = "${w}x$h ${if (mono) "gray" else "4:${if (subX == 0) 4 else 2}:${if (subY == 0 && subX == 1) 2 else if (subY == 0) 4 else 0}"} " +
            "$depth-bit q$qIndex effort $effort"
    }

    private fun check(c: Case) {
        val input = picture(c.w, c.h, c.depth, c.subX, c.subY, c.mono)
        // Even sizes in subsampled chroma, as the HEIF writer hands the encoder.
        val encoder = Av1Encoder(input, Av1EncodeSettings(c.qIndex, c.effort, intArrayOf(1, 13, if (c.qIndex == 0 && !c.mono) 0 else 6)), null)
        val coded = encoder.encode()
        val expected = encoder.decodedPlanes()
        val ours = Av1Decoder().decode(coded.data)
        for (p in expected.indices) {
            val pw = if (p == 0) c.w else (c.w + c.subX) shr c.subX
            val ph = if (p == 0) c.h else (c.h + c.subY) shr c.subY
            val got = ShortArray(pw * ph) { i -> ours.planes[p][(i / pw) * ours.strides[p] + i % pw] }
            assertContentEquals(expected[p], got, "$c: ImageKodec's decoder, plane $p")
            if (c.qIndex == 0) {
                val src = ShortArray(pw * ph) { i -> input.planes[p][(i / pw) * input.strides[p] + i % pw] }
                assertContentEquals(src, got, "$c: lossless, plane $p")
            }
        }
        // ffmpeg hands the identity matrix's planes out as G, B and R, the order AV1 codes them in.
        val identity = c.qIndex == 0 && !c.mono
        val raw = reference(coded.data, if (identity) (if (c.depth == 8) "gbrp" else "gbrp${c.depth}le") else pixFmt(c.depth, c.subX, c.subY, c.mono))
        val bytes = if (c.depth > 8) 2 else 1
        var at = 0
        for (p in expected.indices) {
            val e = expected[p]
            for (i in e.indices) {
                val v = if (bytes == 1) raw[at].toInt() and 0xFF else (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)
                if (v != e[i].toInt()) error("$c: dav1d differs in plane $p at sample $i: ${e[i]} expected, $v read")
                at += bytes
            }
        }
        assertEquals(raw.size, at, "$c: dav1d's output size")
        var se = 0.0
        var n = 0
        for (p in expected.indices) {
            val pw = if (p == 0) c.w else (c.w + c.subX) shr c.subX
            for (i in expected[p].indices) {
                val e = expected[p][i] - input.planes[p][(i / pw) * input.strides[p] + i % pw]
                se += e.toDouble() * e
                n++
            }
        }
        val max = ((1 shl c.depth) - 1).toDouble()
        val psnr = if (se == 0.0) 99.0 else 10 * kotlin.math.log10(max * max * n / se)
        println("$c: ${coded.data.size} bytes, PSNR ${"%.2f".format(psnr)} dB, dav1d and ImageKodec agree")
    }

    /** Text-like screen content: a few flat colours in strokes and boxes, some anti-aliased. */
    private fun screen(w: Int, h: Int, depth: Int): HeifPlanes {
        val max = (1 shl depth) - 1
        val luma = ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = when {
                (x / 6 + y / 9) % 5 == 0 && y % 9 < 7 -> 20
                x % 37 < 3 || y % 29 < 2 -> 200
                (x / 16) % 3 == 1 && (y / 16) % 2 == 0 -> 90
                else -> 240
            }
            (v * max / 255).toShort()
        }
        val cw = (w + 1) shr 1
        val ch = (h + 1) shr 1
        val u = ShortArray(cw * ch) { i -> ((if ((i % cw) / 8 % 2 == 0) 100 else 160) * max / 255).toShort() }
        val v = ShortArray(cw * ch) { (128 * max / 255).toShort() }
        return HeifPlanes(w, h, depth, depth, 1, 1, arrayOf(luma, u, v), intArrayOf(w, cw, cw), true)
    }

    /**
     * Each intra tool the encoder chooses (directional modes with angle deltas, smooth, Paeth, chroma from luma, filter
     * intra, the palette, transform types besides DCT, splits and rectangles, skip) shows up in streams dav1d decodes
     * to the encoder's reconstruction, so no tool is merely allowed and untested.
     */
    @Test
    fun everyToolIsUsed() {
        assumeTrue("ffmpeg with libdav1d is needed", dav1d())
        val use = HashMap<String, Int>()
        // Chroma that follows luma, as a coloured object's does, which chroma from luma predicts.
        val follow = picture(96, 64, 8, 0, 0, false).let { p ->
            val luma = p.planes[0]
            val u = ShortArray(96 * 64) { i -> (40 + luma[i] / 2).toShort() }
            val v = ShortArray(96 * 64) { i -> (230 - luma[i] * 3 / 4).coerceIn(0, 255).toShort() }
            HeifPlanes(96, 64, 8, 8, 0, 0, arrayOf(luma, u, v), intArrayOf(96, 96, 96), true)
        }
        for ((input, q) in listOf(follow to 80, picture(200, 120, 8, 1, 1, false) to 60, picture(130, 90, 10, 0, 0, false) to 100, screen(160, 96, 8) to 90, screen(96, 64, 8) to 0)) {
            val encoder = Av1Encoder(input, Av1EncodeSettings(q, 3, null), null)
            val coded = encoder.encode()
            val expected = encoder.decodedPlanes()
            val identity = false
            val raw = reference(coded.data, pixFmt(input.bitDepthLuma, input.subX, input.subY, false).let { if (identity) it else it })
            val ours = Av1Decoder().decode(coded.data)
            var at = 0
            val bytes = if (input.bitDepthLuma > 8) 2 else 1
            for (p in expected.indices) {
                val pw = if (p == 0) input.width else (input.width + input.subX) shr input.subX
                for (i in expected[p].indices) {
                    val v = if (bytes == 1) raw[at].toInt() and 0xFF else (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8)
                    assertEquals(expected[p][i].toInt(), v, "dav1d differs in plane $p at $i")
                    assertEquals(expected[p][i], ours.planes[p][(i / pw) * ours.strides[p] + i % pw], "ImageKodec differs in plane $p at $i")
                    at += bytes
                }
            }
            for ((k, n) in encoder.toolUse()) use[k] = (use[k] ?: 0) + n
            val f = encoder.chosenFilters!!
            println("q$q ${input.width}x${input.height}: ${coded.data.size} bytes; loop filter ${f.loopFilterLevel.toList()}, cdef bits ${f.cdefBits}, restoration ${f.lrType.toList()}")
        }
        println(use.toSortedMap())
        for (tool in listOf(
            "directional with angle delta", "smooth", "paeth", "dc", "chroma from luma", "filter intra", "palette", "skip",
            "transform type 1", "transform type 2", "transform type 3", "transform type 9", "partition 1", "partition 2", "partition 3",
        )) assertTrue((use[tool] ?: 0) > 0, "no block used $tool: $use")
    }

    private val sizes = listOf(1 to 1, 7 to 5, 64 to 64, 65 to 33, 200 to 120, 513 to 257)

    /** Every size in every format and depth at the default effort and a middle quantizer. */
    @Test
    fun formatsAndDepths() {
        assumeTrue("ffmpeg with libdav1d is needed", dav1d())
        val formats = listOf(Triple(1, 1, false), Triple(1, 0, false), Triple(0, 0, false), Triple(1, 1, true))
        for ((w, h) in sizes) for ((sx, sy, mono) in formats) for (depth in listOf(8, 10, 12)) {
            check(Case(w, h, depth, sx, sy, mono, 128, 2))
        }
    }

    /** Qualities and efforts on the middle sizes, 8 and 10 bits. */
    @Test
    fun qualitiesAndEfforts() {
        assumeTrue("ffmpeg with libdav1d is needed", dav1d())
        for ((w, h) in listOf(64 to 64, 200 to 120)) for (q in listOf(1, 40, 128, 220, 255)) for (effort in 1..3) {
            check(Case(w, h, 8, 1, 1, false, q, effort))
        }
        for (effort in 1..3) check(Case(65, 33, 10, 0, 0, false, 90, effort))
    }

    /** Lossless: base_q_idx 0 decodes to the input, in every format and depth. */
    @Test
    fun lossless() {
        assumeTrue("ffmpeg with libdav1d is needed", dav1d())
        val formats = listOf(Triple(0, 0, false), Triple(1, 1, true))
        for ((w, h) in sizes) for ((sx, sy, mono) in formats) for (depth in listOf(8, 10, 12)) for (effort in listOf(1, 2)) {
            check(Case(w, h, depth, sx, sy, mono, 0, effort))
        }
    }
}
