package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.PictureHash
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
 * Intra pictures from libx265 (through ffmpeg) with the loop filters off, decoded and held to the MD5
 * of each plane that x265 writes in the decoded picture hash SEI (D.3.20), over every chroma format,
 * depths 8 to 12, CTB sizes, scaling lists, transform skip, lossless coding, constrained intra
 * prediction, several slices, wavefront rows on and off, extreme QPs and sizes that leave partial CTBs
 * and a conformance window; then the same with the loop filters on, and sequences of P and B pictures.
 * Each case checks from the stream that it has the feature it is there for. A few fixtures are also
 * compared sample for sample with libde265. Then every picture of the H.265.1 conformance packages is
 * held to its hash SEI or to the MD5 of the package's output, and the streams are listed with their outcome.
 */
class HevcDecodeOracleTest {

    private class Case(
        val name: String,
        val size: String,
        val pixFmt: String,
        val params: String,
        val frames: Int = 1,
        val filter: String = "",
        val check: (List<SliceHeader>) -> Boolean = { true },
    )

    private val loopFiltersOff = "keyint=1:hash=1:no-deblock=1:no-sao=1"

    private val cases = listOf(
        Case("8 bits 4:2:0 64x48", "64x48", "yuv420p", "") { s -> s[0].sps.chromaFormatIdc == 1 && s[0].sps.bitDepthY == 8 },
        Case("partial CTBs and a conformance window", "66x38", "yuv420p", "") { s -> s[0].sps.confWin.any { it != 0 } && s[0].sps.width % 64 != 0 },
        Case("odd size 4:2:0 10 bits", "90x70", "yuv420p10le", "") { s -> s[0].sps.bitDepthY == 10 && s[0].sps.confWin.any { it != 0 } },
        Case("10 bits 4:2:0", "96x64", "yuv420p10le", "") { s -> s[0].sps.bitDepthY == 10 && s[0].sps.bitDepthC == 10 },
        Case("12 bits 4:2:0", "96x64", "yuv420p12le", "") { s -> s[0].sps.bitDepthY == 12 },
        Case("4:0:0 8 bits", "64x64", "gray", "") { s -> s[0].sps.chromaFormatIdc == 0 },
        Case("4:0:0 10 bits", "72x40", "gray10le", "") { s -> s[0].sps.chromaFormatIdc == 0 && s[0].sps.bitDepthY == 10 },
        Case("4:0:0 12 bits", "64x64", "gray12le", "") { s -> s[0].sps.chromaFormatIdc == 0 && s[0].sps.bitDepthY == 12 },
        Case("4:2:2 8 bits", "64x64", "yuv422p", "") { s -> s[0].sps.chromaFormatIdc == 2 },
        Case("4:2:2 10 bits", "80x48", "yuv422p10le", "") { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 10 },
        Case("4:2:2 12 bits", "64x64", "yuv422p12le", "") { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 12 },
        Case("4:4:4 8 bits", "64x64", "yuv444p", "") { s -> s[0].sps.chromaFormatIdc == 3 },
        Case("4:4:4 10 bits", "72x56", "yuv444p10le", "") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthY == 10 },
        Case("4:4:4 12 bits", "64x64", "yuv444p12le", "") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthC == 12 },
        Case("ctu 16", "200x120", "yuv420p", "ctu=16:min-cu-size=8") { s -> s[0].sps.ctbLog2 == 4 },
        Case("ctu 32", "200x120", "yuv420p", "ctu=32") { s -> s[0].sps.ctbLog2 == 5 },
        Case("ctu 64", "200x120", "yuv420p", "ctu=64") { s -> s[0].sps.ctbLog2 == 6 },
        Case("ctu 16, 4:4:4 12 bits", "48x40", "yuv444p12le", "ctu=16:min-cu-size=8") { s -> s[0].sps.ctbLog2 == 4 && s[0].sps.chromaFormatIdc == 3 },
        Case("default scaling lists", "64x64", "yuv420p", "scaling-list=default") { s -> s[0].sps.scalingListEnabled && s[0].sps.scalingLists === ScalingLists.DEFAULT },
        Case("default scaling lists 4:4:4", "64x64", "yuv444p10le", "scaling-list=default") { s -> s[0].sps.scalingListEnabled && s[0].sps.chromaFormatIdc == 3 },
        Case("explicit scaling lists", "64x64", "yuv420p", "scaling-list={lists}") { s -> s[0].sps.scalingLists.let { it != null && it !== ScalingLists.DEFAULT } },
        Case("explicit scaling lists 4:2:2", "64x64", "yuv422p10le", "scaling-list={lists}") { s -> s[0].sps.scalingLists.let { it != null && it !== ScalingLists.DEFAULT } },
        Case("transform skip", "64x64", "yuv420p", "tskip=1:rdoq-level=0") { s -> s[0].pps.transformSkipEnabled },
        Case("transform skip 4:4:4", "64x64", "yuv444p", "tskip=1") { s -> s[0].pps.transformSkipEnabled },
        Case("lossless", "64x48", "yuv420p", "lossless=1") { s -> s[0].pps.transquantBypassEnabled },
        Case("lossless 4:2:2 10 bits", "64x48", "yuv422p10le", "lossless=1") { s -> s[0].pps.transquantBypassEnabled },
        Case("cu-lossless", "64x48", "yuv420p", "cu-lossless=1") { s -> s[0].pps.transquantBypassEnabled },
        Case("constrained intra", "64x64", "yuv420p", "constrained-intra=1") { s -> s[0].pps.constrainedIntraPred },
        Case("no strong intra smoothing", "128x96", "yuv420p", "no-strong-intra-smoothing=1") { s -> !s[0].sps.strongIntraSmoothing },
        Case("strong intra smoothing, flat 32x32", "128x128", "yuv420p", "strong-intra-smoothing=1:qp=40") { s -> s[0].sps.strongIntraSmoothing },
        // ipratio=1 keeps x265 from moving the QP of an I picture away from the one asked for.
        Case("qp 1", "64x48", "yuv420p", "qp=1:ipratio=1") { s -> s[0].sliceQpY == 1 },
        Case("qp 1 12 bits 4:4:4", "64x48", "yuv444p12le", "qp=1:ipratio=1") { s -> s[0].sliceQpY == 1 && s[0].sps.bitDepthY == 12 },
        Case("crf 0", "64x48", "yuv420p10le", "crf=0") { s -> s[0].pps.cuQpDeltaEnabled || s[0].sliceQpY <= 4 },
        Case("qp 51", "64x48", "yuv420p", "qp=51:ipratio=1") { s -> s[0].sliceQpY == 51 },
        Case("qp 51 12 bits", "64x48", "yuv420p12le", "qp=51:ipratio=1") { s -> s[0].sliceQpY == 51 },
        Case("chroma QP offsets", "64x64", "yuv420p", "cbqpoffs=-5:crqpoffs=7") { s -> s[0].pps.cbQpOffset == -5 && s[0].pps.crQpOffset == 7 },
        Case("adaptive QP with deltas", "128x96", "yuv420p", "aq-mode=2:aq-strength=2:qg-size=16") { s -> s[0].pps.cuQpDeltaEnabled && s[0].pps.diffCuQpDeltaDepth > 0 },
        Case("no sign hiding", "64x48", "yuv420p", "signhide=0") { s -> !s[0].pps.signDataHiding },
        Case("two slices", "256x192", "yuv420p", "slices=2:ctu=32:wpp=1") { s -> s.size >= 2 && s.any { !it.firstSliceSegmentInPic } },
        Case("four slices", "256x256", "yuv420p", "slices=4:ctu=32:wpp=1") { s -> s.size >= 4 },
        Case("wavefront rows", "512x256", "yuv420p", "wpp=1:ctu=32") { s -> s[0].pps.entropyCodingSync && s[0].entryPointOffsets.isNotEmpty() },
        Case("wavefront rows 4:2:2", "384x192", "yuv422p10le", "wpp=1:ctu=32") { s -> s[0].pps.entropyCodingSync && s[0].entryPointOffsets.isNotEmpty() },
        Case("no wavefront rows", "512x256", "yuv420p", "wpp=0:ctu=32") { s -> !s[0].pps.entropyCodingSync },
        Case("several pictures", "96x64", "yuv420p", "", frames = 3) { s -> s.size >= 3 },
    )

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes()
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to out
        }
        return proc.exitValue() to out
    }

    /** A scaling list file in the format of HM, which x265 reads: every list explicit and each its own. */
    private fun scalingListFile(): File {
        val text = StringBuilder()
        for ((size, n) in listOf(4 to 16, 8 to 64, 16 to 64, 32 to 64)) for ((m, mode) in listOf("INTRA", "INTER").withIndex()) {
            for ((k, comp) in listOf("LUMA", "CHROMAU", "CHROMAV").withIndex()) {
                if (size == 32 && comp != "LUMA") continue
                val list = 3 * m + k
                val key = "$mode${size}X${size}_$comp"
                text.append("$key =\n").append(List(n) { 6 + (it * 7 + size * 3 + list * 11) % 53 }.joinToString(",")).append('\n')
                if (size >= 16) text.append("${key}_DC =\n${10 + size + list}\n")
            }
        }
        return File.createTempFile("kite-hevc-lists", ".txt").apply { deleteOnExit(); writeText(text.toString()) }
    }

    private fun encode(size: String, pixFmt: String, params: String, frames: Int = 1, source: String = "testsrc2", filter: String = ""): File {
        val out = File.createTempFile("kite-hevc", ".hevc").apply { deleteOnExit() }
        val p = params.replace("{lists}", if ("{lists}" in params) scalingListFile().path else "")
        val (code, log) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "$source=size=$size:rate=25$filter",
            "-frames:v", frames.toString(), "-pix_fmt", pixFmt, "-c:v", "libx265",
            "-x265-params", "${p.trim(':')}:log-level=none".trimStart(':'), "-f", "hevc", "-y", out.path,
        )
        assertEquals(0, code, "ffmpeg failed for $size $pixFmt $params: ${log.decodeToString()}")
        return out
    }

    private fun sliceHeaders(units: List<ByteArray>): List<SliceHeader> {
        val store = ParameterSetStore()
        val out = ArrayList<SliceHeader>()
        for (unit in units) {
            val r = BitReader(unescapeNal(unit))
            val nal = readNalHeader(r)
            if (NalType.isSlice(nal.type)) out += readSliceHeader(r, nal, store, out.lastOrNull(), unit.size) else store.add(nal, r)
        }
        return out
    }

    private fun decode(units: List<ByteArray>): List<DecodedPicture> {
        val d = HevcDecoder()
        for (u in units) d.decodeNal(u, 0, u.size, null)
        return d.finish()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Every plane of every picture against the MD5 of its hash SEI. Returns the number of planes checked. */
    private fun checkHashes(what: String, pictures: List<DecodedPicture>): Int {
        var planes = 0
        for ((i, p) in pictures.withIndex()) {
            val hash = p.hash ?: fail("$what: picture $i has no decoded picture hash SEI")
            assertEquals(0, hash.hashType, "$what: hash_type")
            for (c in p.planes.indices) {
                val w = if (c == 0) p.sps.width else p.sps.width / p.sps.subWidthC
                val h = if (c == 0) p.sps.height else p.sps.height / p.sps.subHeightC
                val depth = if (c == 0) p.sps.bitDepthY else p.sps.bitDepthC
                val md5 = PictureHash.md5(PictureHash.pictureData(p.planes[c], 0, p.strides[c], w, h, depth))
                assertEquals(hex(hash.values[c]), hex(md5), "$what: picture $i, plane $c")
                planes++
            }
        }
        return planes
    }

    private fun matchesHash(p: DecodedPicture, hash: PictureHashSei): Boolean = p.planes.indices.all { c ->
        val w = if (c == 0) p.sps.width else p.sps.width / p.sps.subWidthC
        val h = if (c == 0) p.sps.height else p.sps.height / p.sps.subHeightC
        val depth = if (c == 0) p.sps.bitDepthY else p.sps.bitDepthC
        val data = PictureHash.pictureData(p.planes[c], 0, p.strides[c], w, h, depth)
        val v = hash.values[c].fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        when (hash.hashType) {
            0 -> PictureHash.md5(data).contentEquals(hash.values[c])
            1 -> PictureHash.crc(data) == v
            else -> PictureHash.checksum(p.planes[c], 0, p.strides[c], w, h, depth) == v
        }
    }

    /**
     * The planes as a YUV file holds them: one byte a sample, or two low first when either depth passes
     * 8 bits, as HM writes a picture whose luma and chroma depths differ.
     */
    private fun yuvBytes(p: io.github.yuroyami.imagekodec.HeifPlanes): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val depth = maxOf(p.bitDepthLuma, p.bitDepthChroma)
        for (c in p.planes.indices) {
            val w = if (c == 0) p.width else p.width shr p.subX
            val h = if (c == 0) p.height else p.height shr p.subY
            out.write(PictureHash.pictureData(p.planes[c], 0, p.strides[c], w, h, depth))
        }
        return out.toByteArray()
    }

    private fun requireTools() {
        assumeTrue("ffmpeg not installed", Tools.hasAll("ffmpeg"))
        val encoders = run(Tools.require("ffmpeg").path, "-hide_banner", "-encoders").second.decodeToString()
        if ("libx265" !in encoders) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("ffmpeg lacks libx265, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
            assumeTrue("ffmpeg lacks libx265", false)
        }
    }

    @Test
    fun everyPlaneMatchesTheHashSei() {
        requireTools()
        val failures = ArrayList<String>()
        for (c in cases) {
            val file = encode(c.size, c.pixFmt, "$loopFiltersOff:${c.params}", c.frames)
            val units = AnnexB.split(file.readBytes())
            val slices = sliceHeaders(units)
            if (!c.check(slices)) {
                failures += "${c.name}: the stream lacks the feature the case is for"
                continue
            }
            try {
                val pictures = decode(units)
                assertEquals(c.frames, pictures.size, "${c.name}: pictures")
                val planes = checkHashes(c.name, pictures)
                println("${c.name}: ${pictures.size} pictures, $planes planes match their MD5 (${slices.size} slice segments)")
            } catch (e: AssertionError) {
                failures += "${c.name}: ${e.message}"
            } catch (e: Exception) {
                failures += "${c.name}: ${e::class.simpleName} ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}"
            }
        }
        for (f in failures) println("FAILED $f")
        assertTrue(failures.isEmpty(), "${failures.size} of ${cases.size} cases failed:\n${failures.joinToString("\n")}")
    }

    /** The same pictures as libde265 decodes them, sample for sample, after the conformance window. */
    @Test
    fun samplesMatchLibde265() {
        requireTools()
        assumeTrue("dec265 not installed", Tools.hasAll("dec265"))
        val fixtures = listOf(
            Triple("66x38", "yuv420p", loopFiltersOff),
            Triple("80x48", "yuv422p10le", loopFiltersOff),
            Triple("72x56", "yuv444p12le", "$loopFiltersOff:tskip=1"),
            Triple("64x40", "gray", "$loopFiltersOff:lossless=1"),
            Triple("256x128", "yuv420p", "$loopFiltersOff:wpp=1:slices=2:ctu=32"),
            // The loop filters on, as x265 writes by default.
            Triple("66x38", "yuv420p", "keyint=1"),
            Triple("96x64", "yuv422p10le", "keyint=1:deblock=-3,2"),
            Triple("64x64", "yuv444p12le", "keyint=1:hash=1:no-deblock=1"),
            Triple("256x192", "yuv420p", "keyint=1:wpp=1:ctu=32:cu-lossless=1"),
        )
        for ((size, pixFmt, params) in fixtures) {
            val units = AnnexB.split(encode(size, pixFmt, params).readBytes())
            val planes = decode(units).single().toHeifPlanes()
            libde265Differs(units, planes)?.let { fail("$size $pixFmt $params: $it") }
            println("$size $pixFmt $params: every sample matches libde265")
        }
    }

    /** Where [planes] differ from libde265's decode of [units], or null when every sample matches. */
    private fun libde265Differs(units: List<ByteArray>, planes: io.github.yuroyami.imagekodec.HeifPlanes): String? =
        libde265Differs(units, listOf(planes))

    /** Where the pictures [pictures] differ from libde265's decode of [units], in order, or null when every sample matches. */
    private fun libde265Differs(units: List<ByteArray>, pictures: List<io.github.yuroyami.imagekodec.HeifPlanes>): String? {
        val input = File.createTempFile("kite-hevc", ".hevc").apply { deleteOnExit(); writeBytes(AnnexB.join(units)) }
        val yuv = File.createTempFile("kite-hevc", ".yuv").apply { deleteOnExit() }
        val (code, log) = run(Tools.require("dec265").path, "-q", "-o", yuv.path, input.path)
        if (code != 0) return "$DEC265_FAILED: ${log.decodeToString().trim()}"
        val theirs = yuv.readBytes()
        var at = 0
        for ((i, planes) in pictures.withIndex()) {
            val wide = planes.bitDepthLuma > 8
            for (c in planes.planes.indices) {
                val w = if (c == 0) planes.width else planes.width shr planes.subX
                val h = if (c == 0) planes.height else planes.height shr planes.subY
                if (at + w * h * (if (wide) 2 else 1) > theirs.size) return "libde265 gave ${theirs.size} bytes, fewer than the picture"
                for (y in 0 until h) for (x in 0 until w) {
                    val v = if (wide) (theirs[at].toInt() and 0xFF) or ((theirs[at + 1].toInt() and 0xFF) shl 8) else theirs[at].toInt() and 0xFF
                    at += if (wide) 2 else 1
                    val mine = planes.planes[c][y * planes.strides[c] + x].toInt() and 0xFFFF
                    if (mine != v) return "plane $c of picture $i differs from libde265 at ($x, $y): $mine, not $v"
                }
            }
        }
        if (theirs.size != at) return "libde265 gave ${theirs.size} bytes, not $at"
        return null
    }

    /** The loop filters on, as x265 writes by default; each case asserts from the stream which filters it runs. */
    private val loopFilterCases = listOf(
        Case("deblocking only", "128x96", "yuv420p", "no-sao=1") { s -> deblocks(s) && !sao(s) },
        Case("SAO only", "128x96", "yuv420p", "no-deblock=1") { s -> !deblocks(s) && sao(s) },
        Case("both filters", "128x96", "yuv420p", "") { s -> deblocks(s) && sao(s) },
        Case("deblocking offsets -3:2", "128x96", "yuv420p", "deblock=-3,2") { s -> deblocks(s) && offsets(s) == (-3 to 2) },
        Case("deblocking offsets 2:-1", "128x96", "yuv420p", "deblock=2,-1") { s -> deblocks(s) && offsets(s) == (2 to -1) },
        Case("deblocking offsets 6:6 at qp 40", "96x64", "yuv420p", "deblock=6,6:qp=40:ipratio=1") { s -> offsets(s) == (6 to 6) },
        Case("deblocking offsets -6:-6", "96x64", "yuv420p", "deblock=-6,-6") { s -> offsets(s) == (-6 to -6) },
        Case("limit-sao", "128x96", "yuv420p", "limit-sao=1") { s -> sao(s) },
        Case("sao-non-deblock", "128x96", "yuv420p", "sao-non-deblock=1") { s -> sao(s) },
        Case("4:0:0 8 bits", "64x64", "gray", "") { s -> s[0].sps.chromaFormatIdc == 0 && deblocks(s) && sao(s) },
        Case("4:0:0 10 bits", "72x40", "gray10le", "") { s -> s[0].sps.chromaFormatIdc == 0 && s[0].sps.bitDepthY == 10 },
        Case("4:0:0 12 bits", "64x64", "gray12le", "") { s -> s[0].sps.chromaFormatIdc == 0 && s[0].sps.bitDepthY == 12 },
        Case("4:2:0 8 bits, partial CTBs", "66x38", "yuv420p", "") { s -> s[0].sps.chromaFormatIdc == 1 && s[0].sps.width % 64 != 0 },
        Case("4:2:0 10 bits", "90x70", "yuv420p10le", "") { s -> s[0].sps.chromaFormatIdc == 1 && s[0].sps.bitDepthY == 10 },
        Case("4:2:0 12 bits", "96x64", "yuv420p12le", "") { s -> s[0].sps.chromaFormatIdc == 1 && s[0].sps.bitDepthY == 12 },
        Case("4:2:2 8 bits", "64x64", "yuv422p", "") { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 8 },
        Case("4:2:2 10 bits", "80x48", "yuv422p10le", "") { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 10 },
        Case("4:2:2 12 bits", "64x64", "yuv422p12le", "") { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 12 },
        Case("4:4:4 8 bits", "64x64", "yuv444p", "") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthY == 8 },
        Case("4:4:4 10 bits", "72x56", "yuv444p10le", "") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthY == 10 },
        Case("4:4:4 12 bits", "64x64", "yuv444p12le", "") { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthC == 12 },
        Case("ctu 16", "200x120", "yuv420p", "ctu=16:min-cu-size=8") { s -> s[0].sps.ctbLog2 == 4 },
        Case("ctu 16 4:2:2", "96x80", "yuv422p10le", "ctu=16:min-cu-size=8") { s -> s[0].sps.ctbLog2 == 4 && s[0].sps.chromaFormatIdc == 2 },
        Case("ctu 64", "200x120", "yuv420p", "ctu=64") { s -> s[0].sps.ctbLog2 == 6 },
        // x265 writes several slices only with wavefront rows.
        Case("two slices", "256x192", "yuv420p", "slices=2:ctu=32") { s -> s.size == 2 },
        Case("four slices with wavefront rows", "256x256", "yuv420p", "slices=4:ctu=32:wpp=1") { s -> s.size == 4 && s[0].pps.entropyCodingSync },
        Case("wavefront rows", "512x256", "yuv420p", "wpp=1:ctu=32") { s -> s[0].pps.entropyCodingSync && s[0].entryPointOffsets.isNotEmpty() },
        Case("no wavefront rows", "512x256", "yuv420p", "wpp=0:ctu=32") { s -> !s[0].pps.entropyCodingSync },
        Case("lossless coding units", "128x96", "yuv420p", "cu-lossless=1") { s -> s[0].pps.transquantBypassEnabled },
        Case("lossless coding units in three slices", "256x192", "yuv420p", "wpp=1:slices=3:ctu=32:cu-lossless=1") { s -> s.size == 3 && s[0].pps.transquantBypassEnabled },
        Case("lossless", "64x48", "yuv422p10le", "lossless=1") { s -> s[0].pps.transquantBypassEnabled },
        Case("transform skip", "64x64", "yuv420p", "tskip=1") { s -> s[0].pps.transformSkipEnabled },
        Case("adaptive QP with deltas", "128x96", "yuv420p", "aq-mode=2:aq-strength=2:qg-size=16") { s -> s[0].pps.cuQpDeltaEnabled },
        Case("adaptive QP 4:4:4 12 bits", "96x64", "yuv444p12le", "aq-mode=3:aq-strength=3:qg-size=8:ctu=16:min-cu-size=8") { s -> s[0].pps.cuQpDeltaEnabled },
        Case("chroma QP offsets", "64x64", "yuv420p", "cbqpoffs=-5:crqpoffs=7") { s -> s[0].pps.cbQpOffset == -5 && s[0].pps.crQpOffset == 7 },
        Case("chroma QP offsets 4:2:2", "64x64", "yuv422p", "cbqpoffs=9:crqpoffs=-12") { s -> s[0].pps.cbQpOffset == 9 },
        Case("qp 51", "64x48", "yuv420p", "qp=51:ipratio=1") { s -> s[0].sliceQpY == 51 },
        Case("qp 45 12 bits", "64x48", "yuv420p12le", "qp=45:ipratio=1") { s -> s[0].sliceQpY == 45 },
        Case("constrained intra", "64x64", "yuv420p", "constrained-intra=1") { s -> s[0].pps.constrainedIntraPred },
        Case("several pictures", "96x64", "yuv420p", "", frames = 3) { s -> s.size >= 3 },
    )

    private fun deblocks(s: List<SliceHeader>) = s.any { !it.deblockingFilterDisabled }
    private fun sao(s: List<SliceHeader>) = s.any { it.saoLuma || it.saoChroma }

    /** x265's deblock=tC,beta (ffmpeg splits -x265-params at colons) as the slice applies them, in units of the _div2 fields. */
    private fun offsets(s: List<SliceHeader>) = s[0].tcOffsetDiv2 to s[0].betaOffsetDiv2

    @Test
    fun loopFiltersMatchTheHashSei() {
        requireTools()
        val failures = ArrayList<String>()
        for (c in loopFilterCases) {
            val units = AnnexB.split(encode(c.size, c.pixFmt, "keyint=1:hash=1:${c.params}", c.frames).readBytes())
            val slices = sliceHeaders(units)
            if (!c.check(slices)) {
                failures += "${c.name}: the stream lacks the feature the case is for"
                continue
            }
            try {
                val pictures = decode(units)
                assertEquals(c.frames, pictures.size, "${c.name}: pictures")
                val planes = checkHashes(c.name, pictures)
                println("${c.name}: ${pictures.size} pictures, $planes planes match their MD5 (${slices.size} slice segments)")
            } catch (e: AssertionError) {
                failures += "${c.name}: ${e.message}"
            } catch (e: Exception) {
                failures += "${c.name}: ${e::class.simpleName} ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}"
            }
        }
        for (f in failures) println("FAILED $f")
        assertTrue(failures.isEmpty(), "${failures.size} of ${loopFilterCases.size} cases failed:\n${failures.joinToString("\n")}")
    }

    /**
     * Sequences of P and B pictures, each with the loop filters as x265 sets them; each case asserts from the
     * stream the tool it is there for. x265 writes no tiles (the conformance streams TILES_A and TILES_B
     * have them), nor long-term pictures (LTRPSPS_A has them).
     */
    private val interCases = listOf(
        Case("P pictures only", "128x96", "yuv420p", "bframes=0", frames = 6) { s -> s.count { it.sliceType == SliceType.P } >= 5 && s.none { it.sliceType == SliceType.B } },
        Case("B pictures in a pyramid", "128x96", "yuv420p", "bframes=3:b-pyramid=1", frames = 9) { s -> s.count { it.sliceType == SliceType.B } >= 4 },
        Case("asymmetric partitions", "128x96", "yuv420p", "amp=1:rect=1:bframes=2", frames = 8) { s -> s[0].sps.ampEnabled },
        Case("explicit weights in P pictures", "128x96", "yuv420p", "weightp=1:bframes=0", frames = 10, filter = ",fade=in:0:10") { s ->
            s.any { it.sliceType == SliceType.P && it.weights != null && it.pps.weightedPred }
        },
        Case("explicit weights in B pictures", "128x96", "yuv420p", "weightp=1:weightb=1:bframes=3:b-pyramid=1", frames = 10, filter = ",fade=out:0:10") { s ->
            s.any { it.sliceType == SliceType.B && it.weights != null && it.pps.weightedBipred }
        },
        Case("explicit weights at 10 bits", "128x96", "yuv420p10le", "weightp=1:weightb=1:bframes=2", frames = 8, filter = ",fade=out:0:8") { s ->
            s[0].sps.bitDepthY == 10 && s.any { it.weights != null }
        },
        Case("4:2:2 8 bits", "128x96", "yuv422p", "bframes=2", frames = 6) { s -> s[0].sps.chromaFormatIdc == 2 },
        Case("4:2:2 10 bits", "128x96", "yuv422p10le", "bframes=2", frames = 6) { s -> s[0].sps.chromaFormatIdc == 2 && s[0].sps.bitDepthY == 10 },
        Case("4:4:4 8 bits", "128x96", "yuv444p", "bframes=2", frames = 6) { s -> s[0].sps.chromaFormatIdc == 3 },
        Case("4:4:4 12 bits", "128x96", "yuv444p12le", "bframes=2", frames = 6) { s -> s[0].sps.chromaFormatIdc == 3 && s[0].sps.bitDepthY == 12 },
        Case("4:0:0", "128x96", "gray", "bframes=2", frames = 6) { s -> s[0].sps.chromaFormatIdc == 0 },
        Case("10 bits", "128x96", "yuv420p10le", "bframes=2", frames = 6) { s -> s[0].sps.bitDepthY == 10 },
        Case("12 bits", "128x96", "yuv420p12le", "bframes=2", frames = 6) { s -> s[0].sps.bitDepthY == 12 },
        Case("wide motion search", "160x96", "yuv420p", "merange=92:subme=7:me=star:bframes=3", frames = 8) { s -> s.any { it.sliceType == SliceType.B } },
        Case("four reference pictures", "128x96", "yuv420p", "ref=4:bframes=3:b-pyramid=1", frames = 10) { s -> s.any { it.numRefIdxL0ActiveMinus1 >= 3 } },
        Case("wavefront rows and two slices", "256x160", "yuv420p", "wpp=1:ctu=32:slices=2:bframes=2", frames = 6) { s ->
            s[0].pps.entropyCodingSync && s.any { !it.firstSliceSegmentInPic && it.sliceType != SliceType.I }
        },
        Case("open GOP with CRA and RASL pictures", "128x96", "yuv420p", "keyint=6:min-keyint=6:open-gop=1:bframes=3:scenecut=0", frames = 16) { s ->
            s.any { it.nalType == NalType.CRA_NUT } && s.any { it.nalType == NalType.RASL_N || it.nalType == NalType.RASL_R }
        },
        Case("IDR and CRA pictures", "128x96", "yuv420p", "keyint=5:min-keyint=5:open-gop=0:bframes=2:scenecut=0", frames = 14) { s ->
            s.count { it.nalType == NalType.IDR_W_RADL || it.nalType == NalType.IDR_N_LP } >= 2
        },
        Case("CTBs of 16 and deep inter transform trees", "128x96", "yuv420p", "ctu=16:min-cu-size=8:tu-inter-depth=3:bframes=2", frames = 6) { s ->
            s[0].sps.ctbLog2 == 4 && s[0].sps.maxTransformHierarchyDepthInter >= 2
        },
        Case("no temporal candidates", "128x96", "yuv420p", "temporal-mvp=0:bframes=2", frames = 6) { s -> s.none { it.temporalMvpEnabled } },
        Case("one merge candidate", "128x96", "yuv420p", "max-merge=1:bframes=2", frames = 8) { s -> s.any { it.sliceType != SliceType.I && it.maxNumMergeCand == 1 } },
        Case("two merge candidates", "128x96", "yuv420p", "max-merge=2:bframes=2:rd=6", frames = 8) { s -> s.any { it.sliceType != SliceType.I && it.maxNumMergeCand == 2 } },
        Case("adaptive QP and chroma offsets", "128x96", "yuv420p", "aq-mode=2:qg-size=16:cbqpoffs=-3:crqpoffs=4:bframes=2", frames = 8) { s -> s[0].pps.cuQpDeltaEnabled },
        Case("lossless", "128x96", "yuv420p", "lossless=1:bframes=2", frames = 5) { s -> s[0].pps.transquantBypassEnabled },
        Case("lossless coding units", "128x96", "yuv420p", "cu-lossless=1:bframes=2", frames = 6) { s -> s[0].pps.transquantBypassEnabled },
        Case("transform skip", "128x96", "yuv420p", "tskip=1:bframes=2", frames = 5) { s -> s[0].pps.transformSkipEnabled },
        Case("default scaling lists", "128x96", "yuv420p", "scaling-list=default:bframes=2", frames = 6) { s -> s[0].sps.scalingListEnabled },
        Case("the loop filters off", "128x96", "yuv420p", "no-deblock=1:no-sao=1:bframes=2", frames = 6) { s -> !deblocks(s) && !sao(s) },
        Case("deblocking offsets", "128x96", "yuv420p", "deblock=-3,2:bframes=2", frames = 6) { s -> deblocks(s) && offsets(s) == (-3 to 2) },
        Case("partial CTBs", "132x68", "yuv420p", "bframes=2", frames = 6) { s -> s[0].sps.width % 64 != 0 && s[0].sps.confWin.any { it != 0 } },
    )

    @Test
    fun interPicturesMatchTheHashSei() {
        requireTools()
        val failures = ArrayList<String>()
        var pictures = 0
        for (c in interCases) {
            val units = AnnexB.split(encode(c.size, c.pixFmt, "keyint=30:hash=1:${c.params}", c.frames, filter = c.filter).readBytes())
            val slices = sliceHeaders(units)
            if (!c.check(slices)) {
                failures += "${c.name}: the stream lacks the feature the case is for"
                continue
            }
            try {
                val decoded = decode(units)
                assertEquals(c.frames, decoded.size, "${c.name}: pictures")
                val planes = checkHashes(c.name, decoded)
                pictures += decoded.size
                val types = slices.map { "IPB"[2 - it.sliceType] }.joinToString("")
                println("${c.name}: ${decoded.size} pictures ($types), $planes planes match their MD5")
            } catch (e: AssertionError) {
                failures += "${c.name}: ${e.message}"
            } catch (e: Exception) {
                failures += "${c.name}: ${e::class.simpleName} ${e.message}\n${e.stackTrace.take(6).joinToString("\n")}"
            }
        }
        for (f in failures) println("FAILED $f")
        println("HEVC inter sequences: $pictures pictures in ${interCases.size} cases")
        assertTrue(failures.isEmpty(), "${failures.size} of ${interCases.size} cases failed:\n${failures.joinToString("\n")}")
    }

    /** P and B sequences against libde265, sample for sample, in output order. */
    @Test
    fun interSamplesMatchLibde265() {
        requireTools()
        assumeTrue("dec265 not installed", Tools.hasAll("dec265"))
        val fixtures = listOf(
            Triple("128x96", "yuv420p", "bframes=3:b-pyramid=1:amp=1:rect=1"),
            Triple("96x64", "yuv422p10le", "bframes=2:weightp=1:weightb=1"),
            Triple("96x64", "yuv444p12le", "bframes=2:ref=3"),
            // libde265 1.0 fails x265's hash with several slices and SAO (AGENTS.md), so one slice here.
            Triple("256x160", "yuv420p", "wpp=1:ctu=32:bframes=2:no-sao=1:slices=2"),
        )
        for ((size, pixFmt, params) in fixtures) {
            val units = AnnexB.split(encode(size, pixFmt, "keyint=30:hash=1:$params", 8).readBytes())
            val d = HevcDecoder(keepOutput = true)
            for (u in units) d.decodeNal(u, 0, u.size, null)
            val pictures = (d.takeOutput() + d.flush()).map { it.toHeifPlanes() }
            assertEquals(8, pictures.size, "$size $pixFmt $params: pictures")
            libde265Differs(units, pictures)?.let { fail("$size $pixFmt $params: $it") }
            println("$size $pixFmt $params: every sample of ${pictures.size} pictures matches libde265")
        }
    }

    /**
     * The decode time of a 1920x1080 P picture and B picture of x265's default settings, printed for the
     * record and bounded only generously. The sequence is decoded from its start each time; the P and B
     * times are the differences from decoding it up to the picture before.
     */
    @Test
    fun decodeTimeOfInterPictures() {
        requireTools()
        val units = AnnexB.split(encode("1920x1080", "yuv420p", "keyint=30:hash=1:crf=22:bframes=1:b-adapt=0", 3).readBytes())
        val slices = sliceHeaders(units)
        assertEquals(listOf(SliceType.I, SliceType.P, SliceType.B), slices.map { it.sliceType })
        checkHashes("1920x1080 inter", decode(units))
        // The units of the first one, two and three pictures.
        val starts = units.indices.filter { NalType.isSlice((units[it][0].toInt() shr 1) and 0x3F) } + units.size
        // The fastest of several runs, as a difference of two means wanders with the JIT and the collector.
        fun time(pictures: Int): Double {
            val part = units.subList(0, starts[pictures])
            repeat(3) { decode(part) }
            return (0 until 9).minOf {
                val start = System.nanoTime()
                decode(part)
                (System.nanoTime() - start) / 1_000_000.0
            }
        }
        val i = time(1)
        val ip = time(2)
        val ipb = time(3)
        val sizes = (0 until 3).map { units.subList(starts[it], starts[it + 1]).sumOf { u -> u.size } }
        println("1920x1080 8-bit P picture of ${sizes[1]} bytes: %.1f ms a decode on the JVM".format(ip - i))
        println("1920x1080 8-bit B picture of ${sizes[2]} bytes: %.1f ms a decode on the JVM".format(ipb - ip))
        assertTrue(ipb < 30_000, "$ipb ms")
    }

    /**
     * The decode time of a 1920x1080 intra picture without and with the loop filters, and of a 4032x3024
     * one with them, printed for the record; it fails only past a generous bound.
     */
    @Test
    fun decodeTimeOfLargePictures() {
        requireTools()
        for ((size, params) in listOf("1920x1080" to "$loopFiltersOff:crf=22", "1920x1080" to "keyint=1:hash=1:crf=22", "4032x3024" to "keyint=1:hash=1:crf=22")) {
            val units = AnnexB.split(encode(size, "yuv420p", params, source = "testsrc2").readBytes())
            checkHashes(size, decode(units))
            decode(units)
            val runs = 5
            val start = System.nanoTime()
            repeat(runs) { decode(units) }
            val ms = (System.nanoTime() - start) / 1_000_000.0 / runs
            val filters = if (params.startsWith(loopFiltersOff)) "without" else "with"
            println("$size 8-bit intra picture of ${units.sumOf { it.size }} bytes, $filters the loop filters: %.1f ms a decode on the JVM".format(ms))
            assertTrue(ms < 10_000, "$ms ms")
        }
    }

    /** The conformance streams of [package] under the data folder, or a skip (a failure in CI) without them. */
    private fun conformanceRoot(pkg: String): File {
        val root = File(System.getenv("IMAGEKODEC_HEIC_DATA") ?: "../reference/heic", "conformance/$pkg")
        if (!root.isDirectory) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("no conformance streams at $root, and ${Tools.REQUIRE_ENV}=1 forbids skipping; run tools/heic_data.py")
            assumeTrue("no conformance streams at $root; run tools/heic_data.py", false)
        }
        return root
    }

    /**
     * Every picture of each stream of [root], held to its hash SEI, and the pictures the decoded picture
     * buffer outputs, in output order and cropped, to the MD5 of the package's YUV file. Returns the
     * folders whose stream matches; every other outcome but "unsupported" is a failure.
     */
    private fun conformance(root: File, failures: MutableList<String>): List<String> {
        val matching = ArrayList<String>()
        for (dir in root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
            val file = dir.listFiles()?.firstOrNull { it.name.endsWith(".bit") || it.name.endsWith(".bin") } ?: continue
            val line = try {
                wholeStream(file)
            } catch (e: UnsupportedImageException) {
                "unsupported: ${e.message}"
            } catch (e: ImageDecodeException) {
                "FAILED: ${e.message}"
            } catch (e: Exception) {
                "FAILED: ${e::class.simpleName} ${e.message}"
            }
            if (line.startsWith("MATCHES")) matching += dir.name
            if (line.startsWith("DIFFERS") || line.startsWith("FAILED") || line.startsWith("decoded")) failures += "${dir.name}: $line"
            println("  ${dir.name}: $line")
        }
        return matching
    }

    /**
     * The outcome of one conformance stream. Its pictures are checked as they complete, so a long stream
     * holds no more than its decoded picture buffer. The MD5 is the one in the package's md5 file: the HM
     * output of the whole stream in output order, RASL pictures of the first CRA picture left out.
     */
    private fun wholeStream(file: File): String {
        val units = AnnexB.split(file.readBytes())
        val d = HevcDecoder(keepOutput = true)
        val md5 = java.security.MessageDigest.getInstance("MD5")
        var pictures = 0
        var hashed = 0
        var output = 0
        val bad = ArrayList<Int>()
        fun check(list: List<DecodedPicture>) {
            for (p in list) {
                pictures++
                val hash = p.hash ?: continue
                hashed++
                if (!matchesHash(p, hash)) bad += p.poc
            }
        }
        fun out(list: List<DecodedPicture>) {
            for (p in list) {
                md5.update(yuvBytes(p.toHeifPlanes()))
                output++
            }
        }
        var started = false
        for (u in units) {
            if (u.size < 2) continue
            val type = (u[0].toInt() shr 1) and 0x3F
            // first_slice_segment_in_pic_flag is the first bit after the header: the picture before is complete.
            if (NalType.isSlice(type) && u.size > 2 && u[2].toInt() and 0x80 != 0) {
                if (started) check(d.finish())
                started = true
            }
            d.decodeNal(u, 0, u.size, null)
            out(d.takeOutput())
        }
        check(d.finish())
        out(d.flush())
        if (bad.isNotEmpty()) return "DIFFERS from the hash SEI of ${bad.size} of $pictures pictures, the first of PicOrderCntVal ${bad.first()}"
        val expected = file.parentFile.listFiles().orEmpty().filter { "md5" in it.name.lowercase() }
            .flatMap { it.readLines() }.flatMap { line -> Regex("[0-9a-fA-F]{32}").findAll(line).map { it.value.lowercase() }.toList() }
        val mine = hex(md5.digest())
        val hashes = if (hashed == pictures) "the hash SEI of all $pictures pictures" else "the hash SEI of $hashed of $pictures pictures"
        if (mine in expected) return "MATCHES $hashes and the MD5 of its $output output pictures"
        if (expected.isNotEmpty()) return "DIFFERS from the MD5 of its $output output pictures ($mine)"
        if (hashed == pictures && pictures > 0) return "MATCHES $hashes; the package has no MD5 of its output"
        return "decoded $pictures pictures, $hashed with a hash SEI, and the package has no MD5 of its output"
    }

    /**
     * Every picture of the HEVC_v1 and SCC packages. Every HEVC_v1 stream matches; the SCC streams all use
     * palette mode, which is not supported, and are listed as such.
     */
    @Test
    fun conformanceStreams() {
        val failures = ArrayList<String>()
        val v1 = conformanceRoot("HEVC_v1")
        val matching = conformance(v1, failures)
        val scc = File(v1.parentFile, "SCC")
        val sccMatching = if (scc.isDirectory) conformance(scc, failures) else emptyList()
        println("HEVC conformance: ${matching.size} HEVC_v1 streams and ${sccMatching.size} SCC streams match in every picture")
        assertTrue(failures.isEmpty(), "${failures.size} streams failed:\n${failures.joinToString("\n")}")
        val expected = v1.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
        assertEquals(expected.sorted(), matching.sorted(), "the HEVC_v1 streams that match in every picture")
        assertEquals(emptyList(), sccMatching, "the SCC streams, all with palette mode")
    }

    /** Every picture of each range extension stream; the whole package matches. */
    @Test
    fun rangeExtensionStreams() {
        val failures = ArrayList<String>()
        val matching = conformance(conformanceRoot("RExt"), failures)
        println("HEVC range extension conformance: ${matching.size} streams match in every picture")
        assertTrue(failures.isEmpty(), "${failures.size} streams failed:\n${failures.joinToString("\n")}")
        assertEquals(MATCHING_REXT_STREAMS.sorted(), matching.sorted(), "the range extension streams that match in every picture")
    }

    private companion object {
        const val DEC265_FAILED = "libde265 cannot decode it"

        /** The range extension package: every stream, inter pictures included. */
        val MATCHING_REXT_STREAMS = setOf(
            "ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2",
            "Bitdepth_A_RExt_Sony_1",
            "Bitdepth_B_RExt_Sony_1",
            "CCP_10bit_RExt_QCOM_1",
            "CCP_12bit_RExt_QCOM_1",
            "CCP_8bit_RExt_QCOM_1",
            "EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_10BIT_RExt_Sony_1",
            "EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_12BIT_RExt_Sony_1",
            "EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_16BIT_RExt_Sony_1",
            "EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1",
            "EXTPREC_MAIN_444_16_INTRA_10BIT_RExt_Sony_1",
            "EXTPREC_MAIN_444_16_INTRA_12BIT_RExt_Sony_1",
            "EXTPREC_MAIN_444_16_INTRA_16BIT_RExt_Sony_1",
            "EXTPREC_MAIN_444_16_INTRA_8BIT_RExt_Sony_1",
            "ExplicitRdpcm_A_BBC_1",
            "ExplicitRdpcm_B_BBC_2",
            "GENERAL_10b_420_RExt_Sony_1",
            "GENERAL_10b_422_RExt_Sony_1",
            "GENERAL_10b_444_RExt_Sony_2",
            "GENERAL_12b_400_RExt_Sony_1",
            "GENERAL_12b_420_RExt_Sony_1",
            "GENERAL_12b_422_RExt_Sony_1",
            "GENERAL_12b_444_RExt_Sony_2",
            "GENERAL_16b_400_RExt_Sony_1",
            "GENERAL_16b_444_RExt_Sony_2",
            "GENERAL_16b_444_highThroughput_RExt_Sony_2",
            "GENERAL_8b_400_RExt_Sony_1",
            "GENERAL_8b_420_RExt_Sony_1",
            "GENERAL_8b_444_RExt_Sony_2",
            "IPCM_A_RExt_NEC_2",
            "IPCM_B_RExt_NEC",
            "Main_422_10_A_RExt_Sony_2",
            "Main_422_10_B_RExt_Sony_2",
            "PERSIST_RPARAM_A_RExt_Sony_3",
            "QMATRIX_A_RExt_Sony_1",
            "SAO_A_RExt_MediaTek_1",
            "TSCTX_10bit_I_RExt_SHARP_1",
            "TSCTX_10bit_RExt_SHARP_1",
            "TSCTX_12bit_I_RExt_SHARP_1",
            "TSCTX_12bit_RExt_SHARP_1",
            "TSCTX_8bit_I_RExt_SHARP_1",
            "TSCTX_8bit_RExt_SHARP_1",
            "WAVETILES_RExt_Sony_2",
            "WPP_AND_TILE_10Bit422Test_HIGH_TP_444_10BIT_RExt_Apple_2",
            "WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_0_HIGH_TP_444_14BIT_RExt_Apple_2",
            "WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2",
            "WPP_AND_TILE_AND_CABAC_EXT_PREC_1_HIGH_TP_444_14BIT_RExt_Apple_2",
            "WPP_AND_TILE_HIGH_TP_444_8BIT_RExt_Apple_2",
            "WPP_HIGH_TP_444_8BIT_RExt_Apple_2",
        )
    }
}
