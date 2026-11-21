package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The sequence encoder (#177) against two other HEVC decoders. Over a matrix of sizes, the four chroma formats,
 * 8, 10 and 12 bits, IPPP and B pyramids of 2 to 8, periodic IDR pictures, the three efforts and lossless coding,
 * each stream must decode in this module's decoder, picture by picture in output order, to the reconstruction
 * whose MD5 the encoder wrote in its picture hash SEI; libde265 (`dec265 -c`) and ffmpeg (`-err_detect crccheck`)
 * must check every hash and decode the stream to the same samples. Lossless streams must give their input back.
 * HM's `TAppDecoderStatic` decodes them too where it is installed. The streams must really use P and B slices,
 * skipped and merged units, AMVP, bi-prediction, the partitions and intra units, counted through the decoder.
 *
 * Every external call runs under `timeout -s KILL`.
 */
class HevcInterOracleTest {

    private val helper = HevcInterEncoderTest()

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "300") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        return proc.waitFor() to out
    }

    private class Case(
        val w: Int, val h: Int, val format: Int, val depth: Int, val quality: Int, val effort: Int, val lossless: Boolean,
        val gop: Int, val period: Int, val frames: Int,
    ) {
        override fun toString() = "${w}x$h ${FORMATS[format]} $depth bits, $frames frames, group $gop" + (if (period > 0) ", IDR every $period" else "") +
            (if (lossless) ", lossless effort $effort" else ", quality $quality effort $effort")
    }

    private val sizes = listOf(8 to 8, 33 to 17, 64 to 64, 72 to 40, 130 to 66)

    private val cases: List<Case> = buildList {
        var k = 0
        for ((w, h) in sizes) for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            val gop = listOf(1, 4, 2, 8, 3)[k % 5]
            add(Case(w, h, format, depth, listOf(20, 50, 80)[k % 3], 1 + (k / 3) % 3, false, gop, if (k % 4 == 3) 4 else 0, 6 + k % 4))
            k++
        }
        for ((w, h) in listOf(33 to 17, 72 to 40)) for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            add(Case(w, h, format, depth, 0, 1 + k % 3, true, listOf(1, 4)[k % 2], 0, 5))
            k++
        }
    }

    private fun pixFmt(format: Int, depth: Int): String {
        val base = listOf("gray", "yuv420p", "yuv422p", "yuv444p")[format]
        return if (depth == 8) base else "$base${depth}le"
    }

    /** Where the raw frames [raw] (16 bits little-endian above 8) differ from [pictures], in order, or null. */
    private fun differs(raw: ByteArray, pictures: List<HeifPlanes>): String? {
        var at = 0
        for ((k, planes) in pictures.withIndex()) {
            val wide = planes.bitDepthLuma > 8
            for (c in planes.planes.indices) {
                val w = if (c == 0) planes.width else planes.width shr planes.subX
                val h = if (c == 0) planes.height else planes.height shr planes.subY
                if (at + w * h * (if (wide) 2 else 1) > raw.size) return "${raw.size} bytes, fewer than ${pictures.size} pictures"
                for (y in 0 until h) for (x in 0 until w) {
                    val v = if (wide) (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8) else raw[at].toInt() and 0xFF
                    at += if (wide) 2 else 1
                    val mine = planes.planes[c][y * planes.strides[c] + x].toInt() and 0xFFFF
                    if (mine != v) return "picture $k plane $c differs at ($x, $y): $mine, not $v"
                }
            }
        }
        if (raw.size != at) return "${raw.size} bytes, not $at"
        return null
    }

    private fun encode(case: Case): Pair<List<HeifPlanes>, HevcSequence> {
        val src = helper.frames(case.w, case.h, case.format, case.depth, case.frames, seed = case.w + case.format)
        val settings = HevcEncoderSettings(quality = case.quality, lossless = case.lossless, effort = case.effort, hash = true)
        val seq = HevcEncoder.encodeSequence(src, LongArray(src.size) { 1 }, false, null, settings, HevcSequenceSettings(gopSize = case.gop, irapPeriod = case.period), null)
        return src to seq
    }

    @Test
    fun threeDecodersAgree() {
        assumeTrue("ffmpeg or dec265 not installed", Tools.hasAll("ffmpeg", "dec265"))
        val hm = Tools.find("TAppDecoderStatic") ?: Tools.find("TAppDecoder")
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-hevc-inter").apply { mkdirs() }
        var compared = 0
        var skippedLibde265 = 0
        var hmChecked = 0
        val stats = IntArray(HevcStats.COUNT)
        for (case in cases) {
            val (src, seq) = encode(case)
            // This module's decoder, held to the MD5 the encoder wrote of each reconstruction.
            val mine = helper.decode(seq, stats).map { it.toHeifPlanes() }
            assertEquals(src.size, mine.size, "$case: pictures out")
            if (case.lossless) {
                for (i in src.indices) for (c in src[i].planes.indices) {
                    val s = src[i]
                    val w = if (c == 0) s.width else (s.width + s.subX) shr s.subX
                    val h = if (c == 0) s.height else (s.height + s.subY) shr s.subY
                    for (y in 0 until h) for (x in 0 until w) {
                        if (s.planes[c][y * s.strides[c] + x] != mine[i].planes[c][y * mine[i].strides[c] + x]) fail("$case: frame $i plane $c differs from the input at ($x, $y)")
                    }
                }
            }
            val stream = File(dir, "seq.hevc").apply { writeBytes(seq.annexB) }
            // libde265 1.0 aborts (std::bad_alloc) on any 4:0:0 picture of more than 8 bits with a conformance
            // window (AGENTS.md), so those go to ffmpeg alone.
            if (case.format == 0 && case.depth > 8 && (case.w % 8 != 0 || case.h % 8 != 0)) {
                skippedLibde265++
            } else {
                val yuv = File(dir, "dec265.yuv").apply { delete() }
                val (code, log) = run(Tools.require("dec265").path, "-q", "-c", "-o", yuv.path, stream.path)
                val text = log.decodeToString()
                if (code != 0 || text.contains("MD5", ignoreCase = true) || text.contains("error", ignoreCase = true)) fail("$case: dec265 failed: ${text.trim()}")
                differs(yuv.readBytes(), mine)?.let { fail("$case: libde265 $it") }
            }
            val raw = File(dir, "ffmpeg.yuv").apply { delete() }
            val (fcode, flog) = run(
                Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-err_detect", "crccheck", "-i", stream.path,
                "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", pixFmt(case.format, case.depth), "-y", raw.path,
            )
            val ftext = flog.decodeToString().trim()
            if (fcode != 0 || ftext.isNotEmpty()) fail("$case: ffmpeg failed: $ftext")
            differs(raw.readBytes(), mine)?.let { fail("$case: ffmpeg $it") }
            if (hm != null) {
                val out = File(dir, "hm.yuv").apply { delete() }
                val (hcode, hlog) = run(hm.path, "-b", stream.path, "-o", out.path, "-d", "0")
                if (hcode != 0) fail("$case: HM failed: ${hlog.decodeToString().trim()}")
                hmChecked++
            }
            compared++
        }
        println(
            "$compared sequences: this decoder, libde265 and ffmpeg agree sample for sample and check every MD5, lossless ones " +
                "give their input back; $skippedLibde265 cropped 4:0:0 sequences above 8 bits without libde265; " +
                (if (hm == null) "HM's decoder is not installed" else "$hmChecked decoded by HM"),
        )
        println("tools: ${describe(stats)}")
        assertEquals(cases.size, compared)
    }

    private fun describe(s: IntArray): String =
        "I ${s[HevcStats.I_SLICES]}, P ${s[HevcStats.P_SLICES]}, B ${s[HevcStats.B_SLICES]} slices; units skipped ${s[HevcStats.SKIP]}, " +
            "inter ${s[HevcStats.INTER]}, intra ${s[HevcStats.INTRA_IN_INTER]}; prediction units merged ${s[HevcStats.MERGE]}, " +
            "AMVP ${s[HevcStats.AMVP]}, bi-predicted ${s[HevcStats.BI]}; partitions " +
            (0 until 8).filter { it != PartMode.PART_NxN }.joinToString { "${PART_NAMES[it]} ${s[HevcStats.PART_2Nx2N + it]}" }

    /**
     * The tools the encoder uses, counted by this module's decoder over a panning scene with a moving disc: every
     * kind of slice, skipped, merged and AMVP units, bi-prediction, intra units in P and B pictures, and at effort 3
     * every partition.
     */
    @Test
    fun streamsUseTheInterTools() {
        for (effort in 1..3) {
            val src = helper.frames(160, 96, 1, 8, 9, dx = 2, dy = 2, seed = 9)
            val seq = HevcEncoder.encodeSequence(src, LongArray(9) { 1 }, false, null, HevcEncoderSettings(quality = 50, effort = effort, hash = true), HevcSequenceSettings(gopSize = 4), null)
            val s = IntArray(HevcStats.COUNT)
            helper.decode(seq, s)
            println("effort $effort, ${seq.annexB.size} bytes: ${describe(s)}")
            assertEquals(1, s[HevcStats.I_SLICES])
            assertEquals(2, s[HevcStats.P_SLICES])
            assertEquals(6, s[HevcStats.B_SLICES])
            for (k in listOf(HevcStats.SKIP, HevcStats.INTER, HevcStats.MERGE, HevcStats.AMVP, HevcStats.BI)) {
                assertTrue(s[k] > 0, "effort $effort: no use of tool $k: ${describe(s)}")
            }
            if (effort >= 2) {
                assertTrue(s[HevcStats.INTRA_IN_INTER] > 0, "effort $effort: no intra unit in a P or B picture")
                assertTrue(s[HevcStats.PART_2Nx2N + PartMode.PART_2NxN] + s[HevcStats.PART_2Nx2N + PartMode.PART_Nx2N] > 0, "effort $effort: no 2NxN or Nx2N unit")
            }
            if (effort == 3) {
                val amp = (4..7).sumOf { s[HevcStats.PART_2Nx2N + it] }
                assertTrue(amp > 0, "effort 3: no asymmetric partition")
            }
        }
    }

    private companion object {
        val FORMATS = listOf("4:0:0", "4:2:0", "4:2:2", "4:4:4")
        val PART_NAMES = listOf("2Nx2N", "2NxN", "Nx2N", "NxN", "2NxnU", "2NxnD", "nLx2N", "nRx2N")
    }
}
