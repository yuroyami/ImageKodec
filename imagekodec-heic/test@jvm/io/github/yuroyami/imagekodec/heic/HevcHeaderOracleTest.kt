package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.Tools
import io.github.yuroyami.imagekodec.UnsupportedImageException
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The header parser (#140) against ffmpeg's `trace_headers`, which prints every element of every
 * SPS, PPS and slice segment header with the bit it starts at. Streams from libx265 vary the size,
 * depth, chroma format, CTB size, scaling lists, slices, wavefront rows and inter coding; every
 * element this parser reads must sit at the same bit with the same value, and every element ffmpeg
 * prints must be one this parser read. Each case also checks that the stream has the feature it is
 * there for. Then every parameter set and slice header of the H.265.1 conformance streams must
 * parse, or be refused as unsupported for a reason listed here.
 */
class HevcHeaderOracleTest {

    private class Case(
        val name: String,
        val size: String,
        val pixFmt: String,
        val params: String,
        val frames: Int = 1,
        val check: (List<SliceHeader>) -> Boolean = { true },
    )

    private val cases = listOf(
        Case("main 64x48", "64x48", "yuv420p", "keyint=1"),
        Case("odd size, conformance window", "66x38", "yuv420p", "keyint=1") { s -> s[0].sps.confWin.any { it != 0 } },
        Case("10 bits", "96x64", "yuv420p10le", "keyint=1") { s -> s[0].sps.bitDepthY == 10 },
        Case("12 bits", "96x64", "yuv420p12le", "keyint=1") { s -> s[0].sps.bitDepthY == 12 },
        Case("4:0:0", "64x64", "gray", "keyint=1") { s -> s[0].sps.chromaFormatIdc == 0 },
        Case("4:0:0 10 bits", "64x64", "gray10le", "keyint=1") { s -> s[0].sps.chromaFormatIdc == 0 && s[0].sps.bitDepthY == 10 },
        Case("4:2:2", "64x64", "yuv422p", "keyint=1") { s -> s[0].sps.chromaFormatIdc == 2 },
        Case("4:4:4", "64x64", "yuv444p", "keyint=1") { s -> s[0].sps.chromaFormatIdc == 3 },
        Case("4:4:4 12 bits", "64x64", "yuv444p12le", "keyint=1") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthC == 12 },
        Case("ctu 16", "200x120", "yuv420p", "keyint=1:ctu=16:min-cu-size=8") { s -> s[0].sps.ctbLog2 == 4 },
        Case("ctu 32", "200x120", "yuv420p", "keyint=1:ctu=32") { s -> s[0].sps.ctbLog2 == 5 },
        Case("ctu 64", "200x120", "yuv420p", "keyint=1:ctu=64") { s -> s[0].sps.ctbLog2 == 6 },
        Case("default scaling lists", "64x64", "yuv420p", "keyint=1:scaling-list=default") { s -> s[0].sps.scalingListEnabled },
        // Lists the stream carries, each its own. Given equal intra and inter lists, x265 3.5 writes an
        // SPS that ffmpeg 6 refuses; the conformance streams cover predicted lists.
        Case("explicit scaling lists", "64x64", "yuv420p", "keyint=1:scaling-list={lists}") { s ->
            val l = s[0].sps.scalingLists
            l != null && l !== ScalingLists.DEFAULT && l.lists[6].sorted() == List(64) { scalingEntry(8, it) }.sorted() && l.dc[12] == scalingDc(16)
        },
        Case("two slices", "256x256", "yuv420p", "keyint=1:slices=2:ctu=32") { s -> s.size >= 2 && !s[1].firstSliceSegmentInPic },
        Case("wavefront rows", "512x256", "yuv420p", "keyint=1:wpp=1:ctu=32") { s -> s[0].pps.entropyCodingSync && s[0].entryPointOffsets.isNotEmpty() },
        Case("no wavefront rows", "512x256", "yuv420p", "keyint=1:wpp=0:ctu=32") { s -> !s[0].pps.entropyCodingSync },
        Case(
            "lossless, transform skip, offsets", "64x64", "yuv420p",
            "keyint=1:lossless=1:tskip=1:cbqpoffs=-3:crqpoffs=2:constrained-intra=1:signhide=0",
        ) { s -> s[0].pps.transquantBypassEnabled && s[0].pps.transformSkipEnabled && s[0].pps.cbQpOffset == -3 },
        Case("deblocking offsets, no SAO", "64x64", "yuv420p", "keyint=1:deblock=-2,3:sao=0") { s -> s[0].pps.deblockingFilterControlPresent && !s[0].sps.saoEnabled },
        Case(
            "P and B slices, weighted prediction", "128x96", "yuv420p", "keyint=8:bframes=2:ref=3:weightp=1:weightb=1:b-pyramid=1", frames = 6,
        ) { s -> s.any { it.sliceType == SliceType.B } && s.any { it.sliceType == SliceType.P } && s[0].pps.weightedPred },
        Case(
            "P slices, lists and temporal MVP", "128x96", "yuv420p", "keyint=10:bframes=0:ref=4:temporal-mvp=1", frames = 5,
        ) { s -> s.count { it.sliceType == SliceType.P } >= 3 && s.any { it.temporalMvpEnabled } },
    )

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    private fun scalingEntry(size: Int, i: Int, list: Int = 0) = 6 + (i * 7 + size * 3 + list * 11) % 53

    private fun scalingDc(size: Int) = 10 + size

    /** A scaling list file in the format of HM, which x265 reads: every list explicit. */
    private fun scalingListFile(): File {
        val text = StringBuilder()
        for ((size, n) in listOf(4 to 16, 8 to 64, 16 to 64, 32 to 64)) for ((m, mode) in listOf("INTRA", "INTER").withIndex()) {
            for ((k, comp) in listOf("LUMA", "CHROMAU", "CHROMAV").withIndex()) {
                if (size == 32 && comp != "LUMA") continue
                val key = "$mode${size}X${size}_$comp"
                text.append("$key =\n").append(List(n) { scalingEntry(size, it, 3 * m + k) }.joinToString(",")).append('\n')
                if (size >= 16) text.append("${key}_DC =\n${scalingDc(size)}\n")
            }
        }
        return File.createTempFile("kite-hevc-lists", ".txt").apply { deleteOnExit(); writeText(text.toString()) }
    }

    private fun encode(c: Case): File {
        val out = File.createTempFile("kite-hevc", ".hevc").apply { deleteOnExit() }
        val params = c.params.replace("{lists}", if ("{lists}" in c.params) scalingListFile().path else "")
        val (code, log) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=${c.size}:rate=25",
            "-frames:v", c.frames.toString(), "-pix_fmt", c.pixFmt, "-c:v", "libx265",
            "-x265-params", "$params:log-level=none", "-f", "hevc", "-y", out.path,
        )
        assertEquals(0, code, "ffmpeg failed for ${c.name}: $log")
        return out
    }

    private class Entry(val bit: Int, val name: String, val value: Long)

    /** One header that trace_headers printed: its kind, as the line before it names it, and its elements. */
    private class Block(val kind: String, val entries: List<Entry>)

    private val line = Regex("""^(\d+)\s+(\S+)\s+[01]+\s+=\s+(-?\d+)$""")

    /** The headers of the packets, after the extradata, that trace_headers prints for [file]. */
    private fun ffmpegTrace(file: File): List<Block> {
        val (code, log) = run(Tools.require("ffmpeg").path, "-hide_banner", "-i", file.path, "-c", "copy", "-bsf:v", "trace_headers", "-f", "null", "-")
        assertEquals(0, code, log)
        val blocks = ArrayList<Block>()
        var kind: String? = null
        var entries = ArrayList<Entry>()
        var inPackets = false
        for (raw in log.lineSequence()) {
            val text = raw.substringAfter("] ", raw).trim()
            if (!raw.startsWith("[trace_headers")) continue
            if (text.startsWith("Packet:")) { inPackets = true; continue }
            if (!inPackets) continue
            val m = line.matchEntire(text)
            if (m != null) {
                if (kind != null) entries += Entry(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].toLong())
                continue
            }
            if (kind != null) blocks += Block(kind, entries)
            kind = text.takeIf { it in KINDS }
            entries = ArrayList()
        }
        if (kind != null) blocks += Block(kind, entries)
        return blocks
    }

    /** ffmpeg's names where they differ from the syntax tables of H.265. */
    private val aliases = mapOf("matrix_coeffs" to "matrix_coefficients", "scaling_list_delta_coef" to "scaling_list_delta_coeff")

    private fun normal(name: String) = name.substringBefore('[').let { aliases[it] ?: it }

    /** Elements this parser skips on purpose: reserved bits and the trailing and alignment bits. */
    private fun ignored(name: String) = "reserved" in name || name == "rbsp_stop_one_bit" || name == "rbsp_alignment_zero_bit" ||
        name == "slice_segment_header_extension_data_byte"

    /** Elements compared, so a trace that printed nothing cannot pass. */
    private var compared = 0

    private fun compare(what: String, mine: List<TraceEntry>, theirs: Block) {
        val byBit = theirs.entries.associateBy { it.bit }
        for (e in mine) {
            val t = byBit[e.bit] ?: fail("$what: ffmpeg has no element at bit ${e.bit}, where this parser read ${e.name} = ${e.value}")
            assertEquals(normal(t.name), normal(e.name), "$what: the element at bit ${e.bit}")
            assertEquals(t.value, e.value, "$what: ${e.name} at bit ${e.bit}")
            compared++
        }
        val read = mine.map { it.bit }.toSet()
        for (t in theirs.entries) {
            if (!ignored(normal(t.name)) && t.bit !in read) fail("$what: this parser did not read ${t.name} at bit ${t.bit}")
        }
    }

    @Test
    fun everyHeaderFieldMatchesFfmpeg() {
        assumeTrue("ffmpeg with libx265 not installed", Tools.hasAll("ffmpeg"))
        val encoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-encoders").second
        if ("libx265" !in encoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libx265, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            assumeTrue("ffmpeg lacks libx265", false)
        }
        for (c in cases) {
            compared = 0
            val file = encode(c)
            val theirs = ffmpegTrace(file)
            val store = ParameterSetStore()
            val slices = ArrayList<SliceHeader>()
            var k = 0
            var previous: SliceHeader? = null
            for (unit in AnnexB.split(file.readBytes())) {
                val trace = ArrayList<TraceEntry>()
                val r = BitReader(unescapeNal(unit), trace)
                val nal = readNalHeader(r)
                val kind = when {
                    nal.type == NalType.SPS -> "Sequence Parameter Set"
                    nal.type == NalType.PPS -> "Picture Parameter Set"
                    NalType.isSlice(nal.type) -> "Slice Segment Header"
                    nal.type == NalType.VPS -> "Video Parameter Set"
                    else -> null
                }
                if (kind != null) {
                    // ffmpeg prints the header of every unit it knows, in stream order.
                    while (k < theirs.size && theirs[k].kind != kind) k++
                    if (k == theirs.size) fail("${c.name}: ffmpeg printed no $kind for unit type ${nal.type}")
                }
                when {
                    NalType.isSlice(nal.type) -> {
                        // A dependent slice segment takes its fields from the segment before it.
                        val h = readSliceHeader(r, nal, store, previous, unit.size)
                        previous = h
                        slices += h
                        compare("${c.name}, slice ${slices.size}", trace, theirs[k])
                    }
                    nal.type == NalType.SPS || nal.type == NalType.PPS -> {
                        store.add(nal, r)
                        compare("${c.name}, ${theirs[k].kind}", trace, theirs[k])
                    }
                }
                if (kind != null) k++
            }
            assertTrue(slices.size >= c.frames, "${c.name}: ${slices.size} slices for ${c.frames} frames")
            // An SPS of x265 alone holds about a hundred elements.
            assertTrue(compared > 100 + 5 * slices.size, "${c.name}: only $compared elements compared")
            println("${c.name}: $compared elements of ${slices.size} slices match ffmpeg")
            assertTrue(c.check(slices), "${c.name}: the stream lacks the feature the case is for")
        }
    }

    /**
     * The reasons a conformance stream may give for a header this parser does not follow. Each names a
     * stream and the start of the message of its UnsupportedImageException.
     */
    private val expectedUnsupported = mapOf<String, String>()

    @Test
    fun everyConformanceParameterSetParses() {
        val root = File(System.getenv("IMAGEKODEC_HEIC_DATA") ?: "../reference/heic", "conformance")
        if (!root.isDirectory) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("no conformance streams at $root, and ${Tools.REQUIRE_ENV}=1 forbids skipping; run tools/heic_data.py")
            assumeTrue("no conformance streams at $root; run tools/heic_data.py", false)
        }
        val streams = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".bit") || it.name.endsWith(".bin")) }.sortedBy { it.path }.toList()
        assertTrue(streams.size >= 200, "${streams.size} conformance streams")
        val failures = ArrayList<String>()
        val unsupported = LinkedHashMap<String, String>()
        var parameterSets = 0
        var sliceHeaders = 0
        for (file in streams) {
            val name = file.parentFile.name
            val store = ParameterSetStore()
            var previous: SliceHeader? = null
            try {
                for (unit in AnnexB.split(file.readBytes())) {
                    val head = unescapeNal(unit, 0, minOf(unit.size, 16384))
                    val r = BitReader(head)
                    val nal = readNalHeader(r)
                    if (nal.layerId != 0) continue
                    when {
                        nal.type == NalType.VPS || nal.type == NalType.SPS || nal.type == NalType.PPS -> {
                            store.add(nal, BitReader(unescapeNal(unit)).also { readNalHeader(it) })
                            parameterSets++
                        }
                        NalType.isSlice(nal.type) -> {
                            val h = readSliceHeader(r, nal, store, previous, unit.size)
                            previous = h
                            sliceHeaders++
                        }
                    }
                }
            } catch (e: UnsupportedImageException) {
                unsupported[name] = e.message ?: ""
            } catch (e: ImageDecodeException) {
                failures += "$name: ${e.message}"
            } catch (e: Exception) {
                failures += "$name: ${e::class.simpleName} ${e.message}"
            }
        }
        println("HEVC conformance: ${streams.size} streams, $parameterSets parameter sets and $sliceHeaders slice headers read")
        for ((name, reason) in unsupported) println("  unsupported $name: $reason")
        for (f in failures) println("  FAILED $f")
        assertTrue(failures.isEmpty(), "${failures.size} streams failed:\n${failures.joinToString("\n")}")
        for ((name, reason) in unsupported) {
            val expected = expectedUnsupported[name] ?: fail("$name is refused as unsupported, for no listed reason: $reason")
            assertTrue(reason.startsWith(expected), "$name: $reason")
        }
    }

    private companion object {
        val KINDS = setOf("Video Parameter Set", "Sequence Parameter Set", "Picture Parameter Set", "Slice Segment Header")
    }
}
