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
 * and a conformance window. Each case checks from the stream that it has the feature it is there for.
 * A few fixtures are also compared sample for sample with libde265. Pictures with the loop filters on
 * must still parse to their last bit. Then the intra streams of the H.265.1 conformance packages are
 * listed with their outcome.
 */
class HevcDecodeOracleTest {

    private class Case(
        val name: String,
        val size: String,
        val pixFmt: String,
        val params: String,
        val frames: Int = 1,
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

    private fun encode(size: String, pixFmt: String, params: String, frames: Int = 1, source: String = "testsrc2"): File {
        val out = File.createTempFile("kite-hevc", ".hevc").apply { deleteOnExit() }
        val p = params.replace("{lists}", if ("{lists}" in params) scalingListFile().path else "")
        val (code, log) = run(
            Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "$source=size=$size:rate=25",
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

    private fun decode(units: List<ByteArray>, checkLoopFilters: Boolean = true): List<DecodedPicture> {
        val d = HevcDecoder(checkLoopFilters)
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

    /** The planes as a YUV file holds them: one byte a sample, or two low first above 8 bits. */
    private fun yuvBytes(p: io.github.yuroyami.imagekodec.HeifPlanes): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in p.planes.indices) {
            val w = if (c == 0) p.width else p.width shr p.subX
            val h = if (c == 0) p.height else p.height shr p.subY
            out.write(PictureHash.pictureData(p.planes[c], 0, p.strides[c], w, h, if (c == 0) p.bitDepthLuma else p.bitDepthChroma))
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
            Triple("66x38", "yuv420p", ""),
            Triple("80x48", "yuv422p10le", ""),
            Triple("72x56", "yuv444p12le", "tskip=1"),
            Triple("64x40", "gray", "lossless=1"),
            Triple("256x128", "yuv420p", "wpp=1:slices=2:ctu=32"),
        )
        for ((size, pixFmt, params) in fixtures) {
            val file = encode(size, pixFmt, "$loopFiltersOff:$params")
            val yuv = File.createTempFile("kite-hevc", ".yuv").apply { deleteOnExit() }
            val (code, log) = run(Tools.require("dec265").path, "-q", "-o", yuv.path, file.path)
            assertEquals(0, code, "dec265 failed: ${log.decodeToString()}")
            val planes = decode(AnnexB.split(file.readBytes())).single().toHeifPlanes()
            val wide = planes.bitDepthLuma > 8
            val theirs = yuv.readBytes()
            var at = 0
            for (c in planes.planes.indices) {
                val w = if (c == 0) planes.width else planes.width shr planes.subX
                val h = if (c == 0) planes.height else planes.height shr planes.subY
                for (y in 0 until h) for (x in 0 until w) {
                    val v = if (wide) (theirs[at].toInt() and 0xFF) or ((theirs[at + 1].toInt() and 0xFF) shl 8) else theirs[at].toInt() and 0xFF
                    at += if (wide) 2 else 1
                    val mine = planes.planes[c][y * planes.strides[c] + x].toInt()
                    if (mine != v) fail("$size $pixFmt $params: plane $c differs from libde265 at ($x, $y): $mine, not $v")
                }
            }
            assertEquals(theirs.size, at, "$size $pixFmt: the size of libde265's output")
            println("$size $pixFmt $params: every sample matches libde265")
        }
    }

    /**
     * With the loop filters on, as x265 writes by default, the decoder refuses the picture; decoded
     * without them it must still parse every slice to its last bit, SAO parameters included.
     */
    @Test
    fun picturesWithLoopFiltersParseToTheirEnd() {
        requireTools()
        val fixtures = listOf(
            Triple("128x96", "yuv420p", "keyint=1"),
            Triple("96x64", "yuv422p10le", "keyint=1:sao=1"),
            Triple("64x64", "yuv444p12le", "keyint=1"),
            Triple("64x64", "gray", "keyint=1"),
            Triple("256x192", "yuv420p", "keyint=1:wpp=1:slices=3:ctu=32"),
        )
        for ((size, pixFmt, params) in fixtures) {
            val units = AnnexB.split(encode(size, pixFmt, params).readBytes())
            val slices = sliceHeaders(units)
            assertTrue(slices.any { it.saoLuma || it.saoChroma }, "$size $pixFmt: SAO is off")
            val e = runCatching { decode(units) }.exceptionOrNull()
            assertTrue(e is UnsupportedImageException, "$size $pixFmt: $e")
            assertEquals(1, decode(units, checkLoopFilters = false).size)
            println("$size $pixFmt $params: parsed to the end with SAO and deblocking on")
        }
    }

    /** The decode time of a 1920x1080 intra picture, printed for the record; it fails only past a generous bound. */
    @Test
    fun decodeTimeOf1080p() {
        requireTools()
        val units = AnnexB.split(encode("1920x1080", "yuv420p", "$loopFiltersOff:crf=22", source = "testsrc2").readBytes())
        decode(units)
        val runs = 5
        val start = System.nanoTime()
        repeat(runs) { checkHashes("1080p", decode(units)) }
        val ms = (System.nanoTime() - start) / 1_000_000.0 / runs
        println("1920x1080 8-bit intra picture of ${units.sumOf { it.size }} bytes: %.1f ms a decode on the JVM".format(ms))
        assertTrue(ms < 5000, "$ms ms")
    }

    /**
     * Each H.265.1 conformance stream: whether its first picture is intra and supported, and if so
     * whether it matches its hash SEI. Streams that need the loop filters are listed, not asserted;
     * every supported intra stream must match.
     */
    @Test
    fun conformanceStreams() {
        val root = File(System.getenv("IMAGEKODEC_HEIC_DATA") ?: "../reference/heic", "conformance")
        if (!root.isDirectory) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("no conformance streams at $root, and ${Tools.REQUIRE_ENV}=1 forbids skipping; run tools/heic_data.py")
            assumeTrue("no conformance streams at $root; run tools/heic_data.py", false)
        }
        val streams = root.walkTopDown().filter { it.isFile && (it.name.endsWith(".bit") || it.name.endsWith(".bin")) }.sortedBy { it.path }.toList()
        val failures = ArrayList<String>()
        var matched = 0
        var parsed = 0
        for (file in streams) {
            val name = file.parentFile.name
            val units = AnnexB.split(file.readBytes())
            // The units of the first picture: up to the next slice that starts a picture.
            val first = ArrayList<ByteArray>()
            var slices = 0
            for (u in units) {
                if (u.size < 3) continue
                val type = (u[0].toInt() shr 1) and 0x3F
                val layer = ((u[0].toInt() and 1) shl 5) or ((u[1].toInt() and 0xFF) shr 3)
                if (layer == 0 && NalType.isSlice(type)) {
                    // first_slice_segment_in_pic_flag is the first bit after the header.
                    if (slices > 0 && u[2].toInt() and 0x80 != 0) break
                    slices++
                }
                first += u
            }
            val line = try {
                val p = decode(first).singleOrNull() ?: error("no picture")
                val hash = p.hash
                val yuvMd5 = file.parentFile.listFiles()?.firstOrNull { it.name.endsWith("_yuv.md5") }
                if (hash != null) {
                    val ok = matchesHash(p, hash)
                    if (ok) matched++ else failures += "$name: the first picture does not match its hash SEI"
                    if (ok) "MATCHES its hash SEI (type ${hash.hashType})" else "DIFFERS from its hash SEI"
                } else if (yuvMd5 != null && first.size == units.size) {
                    // A stream of one picture without a hash SEI: the MD5 of its whole output.
                    val expected = yuvMd5.readText().trim().substringBefore(' ').lowercase()
                    val ok = hex(PictureHash.md5(yuvBytes(p.toHeifPlanes()))) == expected
                    if (ok) matched++ else failures += "$name: the picture does not match the MD5 of its YUV file"
                    if (ok) "MATCHES the MD5 of its YUV file" else "DIFFERS from the MD5 of its YUV file"
                } else {
                    "decoded, with no hash to compare"
                }
            } catch (e: UnsupportedImageException) {
                if (e.message == HevcSupport.DEBLOCKING || e.message == HevcSupport.SAO) {
                    // Decoded without the loop filters, the picture must still parse to its last bit.
                    try {
                        decode(first, checkLoopFilters = false)
                        parsed++
                        "parses; needs ${if (e.message == HevcSupport.SAO) "SAO" else "the deblocking filter"}"
                    } catch (e2: UnsupportedImageException) {
                        "unsupported: ${e2.message}"
                    } catch (e2: Exception) {
                        failures += "$name: ${e2::class.simpleName} ${e2.message}"
                        "FAILED to parse: ${e2.message}"
                    }
                } else {
                    "unsupported: ${e.message}"
                }
            } catch (e: ImageDecodeException) {
                failures += "$name: ${e.message}"
                "FAILED: ${e.message}"
            } catch (e: Exception) {
                failures += "$name: ${e::class.simpleName} ${e.message}"
                "FAILED: ${e::class.simpleName} ${e.message}"
            }
            println("  $name: $line")
        }
        println("HEVC conformance: ${streams.size} streams, $matched first pictures match their hash, $parsed more parse without the loop filters")
        assertTrue(failures.isEmpty(), "${failures.size} streams failed:\n${failures.joinToString("\n")}")
    }
}
