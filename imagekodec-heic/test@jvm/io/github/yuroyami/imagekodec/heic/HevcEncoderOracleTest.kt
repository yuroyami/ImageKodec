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
 * The intra encoder against two other HEVC decoders. Over a matrix of sizes (1x1 to 513x257), the four chroma
 * formats, 8, 10 and 12 bits, qualities 10, 50 and 90, the three efforts and lossless coding, each stream must
 * decode in this module's decoder to the reconstruction whose MD5 the encoder wrote in its picture hash SEI,
 * and libde265 (`dec265`) and ffmpeg must decode it to the same samples. Lossless streams must give their
 * input back. ffmpeg's `trace_headers` must read the headers, with the profile, level and VUI colour the
 * encoder chose, and PSNR and size must rise with quality.
 *
 * Every external call runs under `timeout -s KILL`.
 */
class HevcEncoderOracleTest {

    private val helper = HevcEncoderTest()

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "120") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        return proc.waitFor() to out
    }

    private class Case(val w: Int, val h: Int, val format: Int, val depth: Int, val quality: Int, val effort: Int, val lossless: Boolean, val aq: Boolean = false) {
        override fun toString() = "${w}x$h ${FORMATS[format]} $depth bits " +
            (if (lossless) "lossless effort $effort" else "quality $quality effort $effort") + (if (aq) " adaptive QP" else "")
    }

    private val sizes = listOf(1 to 1, 7 to 5, 8 to 8, 64 to 64, 65 to 33, 200 to 120, 513 to 257)

    private val cases: List<Case> = buildList {
        var k = 0
        for ((w, h) in sizes) for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            add(Case(w, h, format, depth, listOf(10, 50, 90)[k % 3], 1 + (k / 3) % 3, false))
            k++
        }
        for ((w, h) in listOf(7 to 5, 65 to 33)) for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            add(Case(w, h, format, depth, 0, 1 + (k++ % 3), true))
        }
        // Adaptive QP: cu_qp_delta in quantization groups of 32, QP prediction across groups and CTBs.
        for ((w, h) in listOf(64 to 64, 200 to 120, 513 to 257)) for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            add(Case(w, h, format, depth, listOf(10, 50, 90)[k % 3], 1 + (k / 3) % 3, false, aq = true))
            k++
        }
    }

    private fun pixFmt(format: Int, depth: Int): String {
        val base = listOf("gray", "yuv420p", "yuv422p", "yuv444p")[format]
        return if (depth == 8) base else "$base${depth}le"
    }

    /** Where the raw planes [raw] (16 bits little-endian above 8) differ from [planes], or null. */
    private fun differs(raw: ByteArray, planes: HeifPlanes): String? {
        val wide = planes.bitDepthLuma > 8
        var at = 0
        for (c in planes.planes.indices) {
            val w = if (c == 0) planes.width else planes.width shr planes.subX
            val h = if (c == 0) planes.height else planes.height shr planes.subY
            if (at + w * h * (if (wide) 2 else 1) > raw.size) return "${raw.size} bytes, fewer than the picture"
            for (y in 0 until h) for (x in 0 until w) {
                val v = if (wide) (raw[at].toInt() and 0xFF) or ((raw[at + 1].toInt() and 0xFF) shl 8) else raw[at].toInt() and 0xFF
                at += if (wide) 2 else 1
                val mine = planes.planes[c][y * planes.strides[c] + x].toInt() and 0xFFFF
                if (mine != v) return "plane $c differs at ($x, $y): $mine, not $v"
            }
        }
        if (raw.size != at) return "${raw.size} bytes, not $at"
        return null
    }

    @Test
    fun threeDecodersAgree() {
        assumeTrue("ffmpeg or dec265 not installed", Tools.hasAll("ffmpeg", "dec265"))
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-hevc-encoder").apply { mkdirs() }
        var compared = 0
        var skippedLibde265 = 0
        for (case in cases) {
            val src = helper.picture(case.w, case.h, case.format, case.depth, seed = case.w + case.format)
            val settings = HevcEncoderSettings(quality = case.quality, lossless = case.lossless, effort = case.effort, hash = true, adaptiveQp = case.aq)
            val coded = HevcEncoder.encode(src, false, null, settings, null)
            // This module's decoder, held to the MD5 the encoder wrote of its own reconstruction.
            val mine = helper.decode(coded).toHeifPlanes()
            if (case.lossless) {
                for (c in src.planes.indices) {
                    val w = if (c == 0) src.width else (src.width + src.subX) shr src.subX
                    val h = if (c == 0) src.height else (src.height + src.subY) shr src.subY
                    for (y in 0 until h) for (x in 0 until w) {
                        if (src.planes[c][y * src.strides[c] + x] != mine.planes[c][y * mine.strides[c] + x]) fail("$case: plane $c differs from the input at ($x, $y)")
                    }
                }
            }
            val stream = File(dir, "enc.hevc").apply { writeBytes(coded.annexB) }
            // libde265 1.0 aborts (std::bad_alloc) on any 4:0:0 picture of more than 8 bits with a conformance
            // window, x265's included, so those go to ffmpeg alone.
            if (case.format == 0 && case.depth > 8 && (case.w % 8 != 0 || case.h % 8 != 0)) {
                skippedLibde265++
            } else {
                val yuv = File(dir, "dec265.yuv").apply { delete() }
                val (code, log) = run(Tools.require("dec265").path, "-q", "-o", yuv.path, stream.path)
                if (code != 0) fail("$case: dec265 failed: ${log.decodeToString().trim()}")
                differs(yuv.readBytes(), mine)?.let { fail("$case: libde265 $it") }
            }
            val raw = File(dir, "ffmpeg.yuv").apply { delete() }
            val (fcode, flog) = run(
                Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-i", stream.path,
                "-f", "rawvideo", "-pix_fmt", pixFmt(case.format, case.depth), "-y", raw.path,
            )
            if (fcode != 0) fail("$case: ffmpeg failed: ${flog.decodeToString().trim()}")
            differs(raw.readBytes(), mine)?.let { fail("$case: ffmpeg $it") }
            compared++
        }
        println("$compared streams: this decoder, libde265 and ffmpeg agree sample for sample, lossless ones with their input; $skippedLibde265 cropped 4:0:0 streams above 8 bits without libde265")
        assertEquals(cases.size, compared)
    }

    /** The elements trace_headers printed for [file], by name, in order. */
    private fun trace(file: File): List<Pair<String, Long>> {
        val (code, log) = run(Tools.require("ffmpeg").path, "-hide_banner", "-i", file.path, "-c", "copy", "-bsf:v", "trace_headers", "-f", "null", "-")
        assertEquals(0, code, log.decodeToString())
        val out = ArrayList<Pair<String, Long>>()
        val line = Regex("""^\[trace_headers[^]]*]\s+\d+\s+(\S+)\s+[01]+\s+=\s+(-?\d+)\s*$""")
        for (raw in log.decodeToString().lines()) {
            val m = line.find(raw.trim()) ?: continue
            out += m.groupValues[1] to m.groupValues[2].toLong()
        }
        return out
    }

    @Test
    fun ffmpegReadsTheHeaders() {
        assumeTrue("ffmpeg not installed", Tools.hasAll("ffmpeg"))
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-hevc-encoder").apply { mkdirs() }
        // Profile and level by format, depth and size: general_profile_idc, the RExt flags and general_level_idc.
        val expected = listOf(
            Triple(Triple(1, 8, 64 to 64), 3, 30),
            Triple(Triple(1, 10, 640 to 480), 2, 90),
            Triple(Triple(1, 12, 64 to 64), 4, 30),
            Triple(Triple(0, 8, 1280 to 720), 4, 93),
            Triple(Triple(0, 10, 64 to 64), 4, 30),
            Triple(Triple(2, 8, 64 to 64), 4, 30),
            Triple(Triple(2, 12, 64 to 64), 4, 30),
            Triple(Triple(3, 8, 2000 to 1100), 4, 120),
            Triple(Triple(3, 10, 64 to 64), 4, 30),
            Triple(Triple(3, 12, 64 to 64), 4, 30),
        )
        var checked = 0
        for ((k, entry) in expected.withIndex()) {
            val (shape, profile, level) = entry
            val (format, depth, size) = shape
            val src = helper.picture(size.first, size.second, format, depth)
            val colour = intArrayOf(1 + k % 3, 13, if (format == 0) 2 else 6)
            val full = k % 2 == 0
            val coded = HevcEncoder.encode(src, full, colour, HevcEncoderSettings(quality = 30, effort = 1), null)
            val file = File(dir, "headers.hevc").apply { writeBytes(coded.annexB) }
            val t = trace(file)
            fun value(name: String): Long = t.firstOrNull { it.first == name }?.second ?: fail("${coded.profile}: trace_headers has no $name")
            assertEquals(profile.toLong(), value("general_profile_idc"), "${coded.profile}: general_profile_idc")
            assertEquals(level.toLong(), value("general_level_idc"), "${coded.profile} at ${size.first}x${size.second}: general_level_idc")
            assertEquals(format.toLong(), value("chroma_format_idc"), coded.profile)
            assertEquals(depth - 8L, value("bit_depth_luma_minus8"), coded.profile)
            assertEquals(colour[0].toLong(), value("colour_primaries"), coded.profile)
            assertEquals(colour[1].toLong(), value("transfer_characteristics"), coded.profile)
            // ffmpeg's trace_headers names matrix_coeffs matrix_coefficients.
            assertEquals(colour[2].toLong(), value("matrix_coefficients"), coded.profile)
            assertEquals(if (full) 1L else 0L, value("video_full_range_flag"), coded.profile)
            if (profile == 4) {
                val flags = listOf("max_12bit", "max_10bit", "max_8bit", "max_422chroma", "max_420chroma", "max_monochrome").map { value("general_${it}_constraint_flag") }
                // Table A.2 has no 8-bit 4:2:2 profile, so 4:2:2 never sets general_max_8bit_constraint_flag.
                assertEquals(if (depth <= 8 && format != 2) 1L else 0L, flags[2], "${coded.profile}: general_max_8bit_constraint_flag")
                assertEquals(if (depth <= 10) 1L else 0L, flags[1], "${coded.profile}: general_max_10bit_constraint_flag")
                assertEquals(if (format == 0) 1L else 0L, flags[5], "${coded.profile}: general_max_monochrome_constraint_flag")
                assertEquals(if (format <= 1) 1L else 0L, flags[4], "${coded.profile}: general_max_420chroma_constraint_flag")
            }
            if (profile == 3) {
                for (j in 1..3) assertEquals(1L, value("general_profile_compatibility_flag[$j]"), "Main Still Picture compatibility flag $j")
            }
            // The hvcC record carries the same profile bytes as the SPS.
            assertEquals(profile, coded.hvcC[1].toInt() and 0x1F, "hvcC general_profile_idc")
            assertEquals(level, coded.hvcC[12].toInt() and 0xFF, "hvcC general_level_idc")
            assertEquals(format, coded.hvcC[16].toInt() and 3, "hvcC chromaFormat")
            assertEquals(depth - 8, coded.hvcC[17].toInt() and 7, "hvcC bitDepthLumaMinus8")
            println("${size.first}x${size.second} ${FORMATS[format]} $depth bits: ${coded.profile}, level ${level / 30.0}, colour ${colour.toList()}, full range $full")
            checked++
        }
        assertEquals(expected.size, checked)
    }

    @Test
    fun psnrAndSizeRiseWithQuality() {
        for (format in 0..3) for (depth in listOf(8, 10)) {
            val src = helper.picture(200, 120, format, depth, seed = 7)
            var lastSize = 0
            var lastPsnr = 0
            val line = StringBuilder("200x120 ${FORMATS[format]} $depth bits:")
            for (q in listOf(10, 50, 90)) {
                val coded = HevcEncoder.encode(src, false, null, HevcEncoderSettings(quality = q, hash = true), null)
                val p = helper.psnr(src, helper.decode(coded).toHeifPlanes())
                line.append(" q$q ${coded.sample.size} bytes ${p / 100.0} dB;")
                assertTrue(coded.sample.size > lastSize, "$line size does not grow")
                assertTrue(p > lastPsnr, "$line PSNR does not rise")
                lastSize = coded.sample.size
                lastPsnr = p
            }
            println(line)
        }
    }

    private companion object {
        val FORMATS = listOf("4:0:0", "4:2:0", "4:2:2", "4:4:4")
    }
}
