package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1BitReader
import io.github.yuroyami.imagekodec.codec.avif.Av1Decoder
import io.github.yuroyami.imagekodec.codec.avif.Av1FrameHeader
import io.github.yuroyami.imagekodec.codec.avif.Av1RefSlots
import io.github.yuroyami.imagekodec.codec.avif.Av1SequenceHeader
import io.github.yuroyami.imagekodec.codec.avif.Av1ToolUse
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AV1 decoder (#41) against dav1d, through ffmpeg: still pictures and short sequences from
 * libaom, SVT-AV1 and rav1e with each coding tool turned on in turn, and every sample of every
 * plane of every frame compared, as the decoding process leaves no room for rounding. Each case
 * also checks that the encoder did use the tool it is there for, from its frame header or from
 * what its blocks did, so an encoder that changes its mind cannot leave a tool untested. Skips
 * cleanly without ffmpeg or one of its AV1 libraries.
 */
class Av1OracleTest {

    private class Case(
        val name: String,
        val source: String,
        val pixFmt: String,
        val encoder: String,
        val params: String,
        val extra: List<String> = emptyList(),
        val uses: String,
        val check: (Av1SequenceHeader, Av1FrameHeader) -> Boolean,
    )

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        val err = Thread { proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    /** ffmpeg with dav1d and the three encoders; with the oracles required, a missing library fails. */
    private fun tools(): Boolean {
        if (!Tools.hasAll("ffmpeg")) return false
        val ffmpeg = Tools.require("ffmpeg").path
        val encoders = run(ffmpeg, "-hide_banner", "-encoders").second.decodeToString()
        val decoders = run(ffmpeg, "-hide_banner", "-decoders").second.decodeToString()
        val missing = listOf("libaom-av1", "libsvtav1", "librav1e").filter { it !in encoders } +
            listOf("libdav1d").filter { it !in decoders }
        if (missing.isNotEmpty() && System.getenv(Tools.REQUIRE_ENV) == "1") {
            error("ffmpeg lacks ${missing.joinToString()}, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        }
        return missing.isEmpty()
    }

    private fun temp(ext: String) = File.createTempFile("kite-av1", ext).apply { deleteOnExit() }

    private fun encode(c: Case): ByteArray {
        val out = temp(".obu")
        val codec = when (c.encoder) {
            "libaom-av1" -> listOf("-aom-params", c.params, "-still-picture", "1")
            "libsvtav1" -> listOf("-svtav1-params", c.params)
            else -> if (c.params.isEmpty()) emptyList() else listOf("-rav1e-params", c.params)
        }
        val args = listOf(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", c.source, "-frames:v", "1", "-c:v", c.encoder) +
            codec + c.extra + listOf("-pix_fmt", c.pixFmt, "-f", "obu", "-y", out.path)
        val (code, _) = run(*args.toTypedArray())
        assertEquals(0, code, "${c.name}: ffmpeg could not encode")
        return out.readBytes()
    }

    /** dav1d's planes for [data], as ffmpeg writes them: each plane whole, 16-bit samples little endian. */
    private fun reference(data: ByteArray, pixFmt: String): ByteArray {
        val input = temp(".obu").apply { writeBytes(data) }
        val (code, raw) = run(Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-c:v", "libdav1d", "-i", input.path, "-f", "rawvideo", "-pix_fmt", pixFmt, "-")
        assertEquals(0, code, "dav1d could not decode")
        return raw
    }

    /** The sequence header and the first frame header of [data]. */
    private fun headers(data: ByteArray): Pair<Av1SequenceHeader, Av1FrameHeader> {
        var pos = 0
        var seq: Av1SequenceHeader? = null
        while (pos < data.size) {
            val r = Av1BitReader(data, pos, data.size)
            r.bit()
            val type = r.f(4)
            val extension = r.flag()
            r.flag()
            r.bit()
            if (extension) r.f(8)
            val size = r.leb128().toInt()
            val payload = r.bytePosition
            val pr = Av1BitReader(data, payload, payload + size)
            if (type == Av1Decoder.OBU_SEQUENCE_HEADER) seq = Av1SequenceHeader.parse(pr)
            if (type == Av1Decoder.OBU_FRAME || type == Av1Decoder.OBU_FRAME_HEADER) {
                val s = seq!!
                return s to Av1FrameHeader.parse(pr, s, Av1RefSlots(), 0, 0)
            }
            pos = payload + size
        }
        error("no frame header")
    }

    private fun check(c: Case) {
        val data = encode(c)
        val (seq, fh) = headers(data)
        assertTrue(c.check(seq, fh), "${c.name}: the encoder did not use ${c.uses}")
        val picture = Av1Decoder().decode(data)
        val ref = reference(data, c.pixFmt)
        val wide = picture.bitDepth > 8
        var at = 0
        for (p in picture.planes.indices) {
            val w = if (p == 0) picture.width else (picture.width + picture.subX) shr picture.subX
            val h = if (p == 0) picture.height else (picture.height + picture.subY) shr picture.subY
            for (y in 0 until h) for (x in 0 until w) {
                val expected = if (wide) (ref[at].toInt() and 255) or ((ref[at + 1].toInt() and 255) shl 8) else ref[at].toInt() and 255
                at += if (wide) 2 else 1
                val got = picture.planes[p][y * picture.strides[p] + x].toInt()
                if (got != expected) throw AssertionError("${c.name}: plane $p ($x, $y) is $got, dav1d has $expected")
            }
        }
        assertEquals(ref.size, at, "${c.name}: dav1d wrote another picture size")
    }

    private val mandelbrot = "mandelbrot=s=352x288:end_pts=1"
    private val odd = "testsrc2=s=203x117"
    private val screen = "testsrc=s=320x240"

    private fun aom(name: String, source: String, pixFmt: String, params: String, crf: Int, uses: String, check: (Av1SequenceHeader, Av1FrameHeader) -> Boolean) =
        Case(name, source, pixFmt, "libaom-av1", params, listOf("-crf", "$crf"), uses, check)

    @Test
    fun libaomCodingTools() {
        assumeTrue("ffmpeg with dav1d, libaom, SVT-AV1 and rav1e not installed", tools())
        val noIbc = "enable-intrabc=0"
        listOf(
            aom("lossless", odd, "yuv420p", "lossless=1:$noIbc", 0, "lossless coding") { _, f -> f.codedLossless },
            aom("unfiltered", mandelbrot, "yuv420p", "$noIbc:enable-cdef=0:enable-restoration=0:loopfilter-control=0", 40, "no filter") { _, f ->
                f.loopFilterLevel[0] == 0 && f.loopFilterLevel[1] == 0 && !f.usesLr
            },
            aom("deblocking", mandelbrot, "yuv420p", "$noIbc:enable-cdef=0:enable-restoration=0", 40, "the loop filter") { _, f -> f.loopFilterLevel[0] != 0 },
            aom("cdef", mandelbrot, "yuv420p", "$noIbc:enable-restoration=0", 40, "CDEF") { s, f -> s.enableCdef && f.cdefYPriStrength[0] + f.cdefYSecStrength[0] != 0 },
            aom("wiener", mandelbrot, "yuv420p", "$noIbc:enable-cdef=0", 40, "the Wiener filter") { _, f -> f.frameRestorationType.contains(Av1FrameHeader.RESTORE_WIENER) },
            aom("self-guided", mandelbrot, "yuv420p", "$noIbc:enable-cdef=0", 40, "the self-guided filter") { _, f -> f.frameRestorationType.contains(Av1FrameHeader.RESTORE_SGRPROJ) },
            aom("10-bit", mandelbrot, "yuv420p10le", noIbc, 35, "10 bits") { s, f -> s.bitDepth == 10 && f.usesLr },
            aom("12-bit", mandelbrot, "yuv420p12le", noIbc, 35, "12 bits") { s, f -> s.bitDepth == 12 && f.usesLr },
            aom("4:2:2", mandelbrot, "yuv422p", noIbc, 35, "4:2:2") { s, _ -> s.subsamplingX == 1 && s.subsamplingY == 0 },
            aom("4:4:4", odd, "yuv444p", noIbc, 35, "4:4:4") { s, _ -> s.subsamplingX == 0 && s.subsamplingY == 0 },
            aom("4:4:4 10-bit", odd, "yuv444p10le", noIbc, 30, "10-bit 4:4:4") { s, _ -> s.subsamplingX == 0 && s.bitDepth == 10 },
            aom("monochrome", mandelbrot, "gray", noIbc, 35, "monochrome") { s, _ -> s.monochrome },
            aom("tiles", mandelbrot, "yuv420p", "$noIbc:tile-columns=1:tile-rows=1", 35, "2 by 2 tiles") { _, f -> f.tileCols == 2 && f.tileRows == 2 },
            aom("128x128 superblocks", mandelbrot, "yuv420p", "$noIbc:sb-size=128", 35, "128x128 superblocks") { s, _ -> s.use128x128Superblock },
            aom("delta lf", "testsrc2=s=400x300", "yuv420p", "$noIbc:deltaq-mode=3:delta-lf-mode=1:aq-mode=1", 35, "delta loop filter levels") { _, f -> f.deltaLfPresent },
        ).forEach(::check)
    }

    @Test
    fun filmGrain() {
        assumeTrue("ffmpeg with dav1d, libaom, SVT-AV1 and rav1e not installed", tools())
        val grain = { _: Av1SequenceHeader, f: Av1FrameHeader -> f.filmGrain.applyGrain }
        val cases = (1..16).map { aom("grain test vector $it", mandelbrot, "yuv420p", "enable-intrabc=0:film-grain-test=$it", 35, "film grain", grain) } +
            listOf(3, 7, 10).map { aom("4:4:4 10-bit grain test vector $it", odd, "yuv444p10le", "enable-intrabc=0:film-grain-test=$it", 35, "film grain", grain) } +
            listOf(
                aom("denoised grain", mandelbrot, "yuv420p", "enable-intrabc=0:denoise-noise-level=30", 40, "film grain", grain),
                aom("12-bit denoised grain", mandelbrot, "yuv420p12le", "enable-intrabc=0:denoise-noise-level=40", 35, "film grain", grain),
                Case("SVT-AV1 grain", mandelbrot, "yuv420p", "libsvtav1", "film-grain=20:film-grain-denoise=0", listOf("-crf", "30"), "film grain", grain),
            )
        // Between them the vectors cover chroma scaled from luma, no overlap and studio-range clipping.
        assertTrue(cases.size > 16)
        cases.forEach(::check)
    }

    @Test
    fun intraBlockCopy() {
        assumeTrue("ffmpeg with dav1d, libaom, SVT-AV1 and rav1e not installed", tools())
        val ibc = { _: Av1SequenceHeader, f: Av1FrameHeader -> f.allowIntrabc }
        listOf(
            aom("screen content", screen, "yuv420p", "tune-content=screen:enable-intrabc=1", 20, "intra block copy", ibc),
            aom("screen content 4:4:4", screen, "yuv444p", "tune-content=screen:enable-intrabc=1", 25, "intra block copy", ibc),
            aom("screen content 4:2:2", screen, "yuv422p", "tune-content=screen:enable-intrabc=1", 20, "intra block copy", ibc),
            aom("screen content 10-bit", screen, "yuv420p10le", "tune-content=screen:enable-intrabc=1", 30, "intra block copy", ibc),
            aom("screen content monochrome", screen, "gray", "tune-content=screen:enable-intrabc=1", 20, "intra block copy", ibc),
            aom("screen content 128x128", "testsrc=s=640x480", "yuv420p", "tune-content=screen:enable-intrabc=1:sb-size=128", 20, "intra block copy", ibc),
            aom("screen content in tiles", "testsrc=s=640x480", "yuv420p", "tune-content=screen:enable-intrabc=1:tile-columns=1:tile-rows=1", 20, "intra block copy") { _, f ->
                f.allowIntrabc && f.tileCols == 2
            },
        ).forEach(::check)
    }

    @Test
    fun otherEncoders() {
        assumeTrue("ffmpeg with dav1d, libaom, SVT-AV1 and rav1e not installed", tools())
        listOf(
            Case("SVT-AV1 superres", mandelbrot, "yuv420p", "libsvtav1", "superres-mode=1:superres-denom=13:superres-kf-denom=13", listOf("-crf", "35"), "superres") { _, f ->
                f.useSuperres && f.frameWidth < f.upscaledWidth
            },
            Case("SVT-AV1 10-bit superres", "mandelbrot=s=400x400:end_pts=1", "yuv420p10le", "libsvtav1", "superres-mode=1:superres-denom=10:superres-kf-denom=10", listOf("-crf", "50"), "superres") { _, f ->
                f.useSuperres
            },
            Case("rav1e", mandelbrot, "yuv420p", "librav1e", "", listOf("-qp", "120"), "switchable restoration") { _, f ->
                f.frameRestorationType.contains(Av1FrameHeader.RESTORE_SWITCHABLE)
            },
            Case("rav1e 10-bit tiles", "testsrc2=s=400x400", "yuv420p10le", "librav1e", "tiles=4", listOf("-qp", "160"), "tiles") { _, f ->
                f.tileCols * f.tileRows > 1
            },
        ).forEach(::check)
    }

    /** A sequence: [frames] frames of [source] from [encoder] with [args], which must use every tool of [uses]. */
    private class Sequence(val name: String, val source: String, val frames: Int, val pixFmt: String, val encoder: String, val args: List<String>, val uses: List<String>)

    private fun checkSequence(c: Sequence) {
        val out = temp(".obu")
        val ffmpeg = Tools.require("ffmpeg").path
        val (code, _) = run(
            *(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", c.source, "-frames:v", "${c.frames}", "-c:v", c.encoder) +
                c.args + listOf("-pix_fmt", c.pixFmt, "-f", "obu", "-y", out.path)).toTypedArray(),
        )
        assertEquals(0, code, "${c.name}: ffmpeg could not encode")
        val data = out.readBytes()
        val use = Av1ToolUse()
        val pictures = Av1Decoder().also { it.toolUse = use }.decodeAll(data)
        // Each frame at its own size: ffmpeg would otherwise scale every frame to the first one's.
        val (refCode, ref) = run(ffmpeg, "-hide_banner", "-loglevel", "error", "-c:v", "libdav1d", "-i", out.path, "-autoscale", "0", "-f", "rawvideo", "-pix_fmt", c.pixFmt, "-")
        assertEquals(0, refCode, "${c.name}: dav1d could not decode")
        var at = 0
        for ((n, picture) in pictures.withIndex()) {
            val wide = picture.bitDepth > 8
            for (p in picture.planes.indices) {
                val w = if (p == 0) picture.width else (picture.width + picture.subX) shr picture.subX
                val h = if (p == 0) picture.height else (picture.height + picture.subY) shr picture.subY
                for (y in 0 until h) for (x in 0 until w) {
                    if (at >= ref.size) throw AssertionError("${c.name}: dav1d shows fewer frames than ${pictures.size}")
                    val expected = if (wide) (ref[at].toInt() and 255) or ((ref[at + 1].toInt() and 255) shl 8) else ref[at].toInt() and 255
                    at += if (wide) 2 else 1
                    val got = picture.planes[p][y * picture.strides[p] + x].toInt()
                    if (got != expected) throw AssertionError("${c.name}: frame $n plane $p ($x, $y) is $got, dav1d has $expected")
                }
            }
        }
        assertEquals(ref.size, at, "${c.name}: dav1d shows another number of frames or sizes")
        assertTrue(use["inter frame"] > 0, "${c.name}: the encoder wrote no inter frame")
        for (tool in c.uses) assertTrue(use[tool] > 0, "${c.name}: the encoder did not use $tool")
    }

    private val allInterTools = listOf(
        "-aom-params",
        "enable-obmc=1:enable-warped-motion=1:enable-global-motion=1:enable-interintra-comp=1:enable-masked-comp=1:" +
            "enable-dist-wtd-comp=1:enable-dual-filter=1:enable-ref-frame-mvs=1:enable-smooth-interintra=1:enable-interintra-wedge=1:" +
            "enable-interinter-wedge=1:enable-diff-wtd-comp=1:enable-onesided-comp=1",
    )
    private val rotating = "testsrc2=s=352x288:r=25,rotate=a=t*0.15:c=gray"
    private val panning = "testsrc2=s=640x360:r=25,crop=352:288:x=t*40:y=t*20"
    private val zooming = "mandelbrot=s=352x288:r=25"
    private val fast = "testsrc2=s=1280x720:r=25,crop=640:360:x=t*400:y=t*150"
    private val small = "testsrc2=s=640x360:r=25,crop=203:117:x=t*30:y=t*10"

    @Test
    fun interPrediction() {
        assumeTrue("ffmpeg with dav1d, libaom, SVT-AV1 and rav1e not installed", tools())
        val aom = { crf: Int, speed: Int -> listOf("-crf", "$crf", "-cpu-used", "$speed") }
        listOf(
            Sequence(
                "libaom with every inter tool", rotating, 16, "yuv420p", "libaom-av1", aom(32, 1) + allInterTools,
                listOf(
                    "averaged compound", "distance weighted compound", "difference weighted compound", "wedge compound", "unidirectional compound",
                    "smooth interintra", "wedge interintra", "obmc", "local warp", "dual filter", "skip mode", "temporal candidate",
                    "palette in inter frame", "intra block in inter frame", "show existing frame", "hidden frame",
                ),
            ),
            Sequence("libaom zooming at 10 bits", zooming, 16, "yuv420p10le", "libaom-av1", aom(28, 2) + allInterTools, listOf("global warp", "global mode")),
            Sequence("libaom 4:4:4 at 12 bits", rotating, 10, "yuv444p12le", "libaom-av1", aom(30, 3) + allInterTools, listOf("local warp", "obmc")),
            Sequence("libaom 4:2:2 at 10 bits", panning, 10, "yuv422p10le", "libaom-av1", aom(30, 3) + allInterTools, listOf("obmc", "global warp")),
            Sequence("libaom monochrome", panning, 10, "gray", "libaom-av1", aom(30, 3) + allInterTools, listOf("obmc", "local warp")),
            Sequence("libaom segmentation", panning, 16, "yuv420p", "libaom-av1", aom(34, 3) + listOf("-aom-params", "aq-mode=1"), listOf("segmentation map kept")),
            Sequence(
                "libaom cyclic refresh", panning, 16, "yuv420p", "libaom-av1",
                listOf("-b:v", "200k", "-cpu-used", "8", "-usage", "realtime", "-aom-params", "aq-mode=3"), listOf("segmentation temporal update"),
            ),
            Sequence("libaom error resilience", panning, 12, "yuv420p", "libaom-av1", aom(34, 4) + listOf("-error-resilience", "default"), listOf("error resilient inter frame", "frame ids")),
            Sequence("libaom tiles in 128x128 superblocks", fast, 12, "yuv420p", "libaom-av1", aom(36, 5) + listOf("-tiles", "2x2", "-aom-params", "sb-size=128"), listOf("tiles", "128x128 superblocks")),
            Sequence("libaom lossless at an odd size", small, 8, "yuv420p", "libaom-av1", listOf("-cpu-used", "5", "-aom-params", "lossless=1"), listOf("intra block in inter frame")),
            Sequence("SVT-AV1 resizing every frame", panning, 20, "yuv420p", "libsvtav1", listOf("-crf", "40", "-preset", "4", "-svtav1-params", "resize-mode=2"), listOf("scaled reference")),
            Sequence(
                "SVT-AV1 superres", panning, 16, "yuv420p", "libsvtav1", listOf("-crf", "40", "-preset", "4", "-svtav1-params", "superres-mode=1:superres-denom=12"),
                listOf("superres inter frame", "scaled reference"),
            ),
            Sequence("SVT-AV1 film grain at 10 bits", panning, 16, "yuv420p10le", "libsvtav1", listOf("-crf", "40", "-preset", "4", "-svtav1-params", "film-grain=10"), listOf("film grain inter frame")),
            Sequence("SVT-AV1 at an odd size", small, 12, "yuv420p10le", "libsvtav1", listOf("-crf", "35", "-preset", "6"), listOf("obmc")),
            Sequence("rav1e", rotating, 16, "yuv420p", "librav1e", listOf("-qp", "90", "-speed", "2"), listOf("averaged compound", "segmentation in inter frame")),
            Sequence("rav1e in tiles", fast, 12, "yuv420p", "librav1e", listOf("-qp", "120", "-speed", "9", "-tiles", "4"), listOf("tiles")),
        ).forEach(::checkSequence)
    }
}
