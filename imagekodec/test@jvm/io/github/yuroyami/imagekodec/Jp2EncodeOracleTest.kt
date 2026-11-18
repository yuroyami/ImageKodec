package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JPEG 2000 encoder against OpenJPEG (#180): `opj_decompress` decodes every file the encoder
 * writes, for every option it takes, to the samples ImageKodec's decoder gives, exactly on the 5/3
 * path and within the 9/7 allowance of [JpxOracleTest] on the other; a lossless file holds its
 * source exactly at every depth; `opj_dump` reads back the parameters asked for; a reduced decode
 * matches `opj_decompress -r` at every level; and exiftool reads the metadata of a JP2 file.
 */
class Jp2EncodeOracleTest {

    private fun tools(): Boolean = Tools.hasAll("opj_decompress", "opj_dump")

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -1 to "timed out" }
        return proc.exitValue() to out.toString()
    }

    private fun temp(suffix: String): File = File.createTempFile("kite-j2e", suffix).apply { deleteOnExit() }

    /** Planar components, each at its own precision: what OpenJPEG decodes. */
    private class Planes(val width: Int, val height: Int, val precision: IntArray, val samples: Array<IntArray>)

    /** [bytes] decoded by `opj_decompress` with [reduce] levels dropped, as planar raw samples. */
    private fun openjpeg(bytes: ByteArray, codestream: Boolean, reduce: Int = 0): Planes {
        val input = temp(if (codestream) ".j2k" else ".jp2").apply { writeBytes(bytes) }
        // .rawl: planar, a byte a sample up to 8 bits, else two, low byte first.
        val raw = temp(".rawl")
        val args = mutableListOf(Tools.require("opj_decompress").path, "-i", input.path, "-o", raw.path)
        if (reduce > 0) args += listOf("-r", "$reduce")
        val (code, log) = run(*args.toTypedArray())
        assertEquals(0, code, "opj_decompress failed: $log")
        // "Component 0 characteristics: 97x61x8 unsigned"
        val comps = Regex("""Component \d+ characteristics: (\d+)x(\d+)x(\d+)""").findAll(log).map { it.groupValues }.toList()
        assertTrue(comps.isNotEmpty(), "no component lines in: $log")
        val w = comps[0][1].toInt()
        val h = comps[0][2].toInt()
        val precision = IntArray(comps.size) { comps[it][3].toInt() }
        val data = raw.readBytes()
        var at = 0
        val samples = Array(comps.size) { c ->
            IntArray(w * h) {
                if (precision[c] <= 8) data[at++].toInt() and 0xFF
                else (data[at++].toInt() and 0xFF) or ((data[at++].toInt() and 0xFF) shl 8)
            }
        }
        return Planes(w, h, precision, samples)
    }

    /** [bytes] decoded by ImageKodec at full precision, each channel narrowed back to [precision] bits. */
    private fun kodec(bytes: ByteArray, precision: Int): Planes {
        val wide = ImageKodec.decode16(bytes)
        val max = (1 shl precision) - 1
        val n = wide.channels
        val samples = Array(n) { c ->
            IntArray(wide.width * wide.height) { i ->
                val s = wide.samples[i * n + c].toInt() and 0xFFFF
                when {
                    precision == 16 -> s
                    precision >= 8 -> s shr (16 - precision)
                    else -> ((s / 257) * max + 127) / 255
                }
            }
        }
        return Planes(wide.width, wide.height, IntArray(n) { precision }, samples)
    }

    /** [bitmap]'s samples at [precision] bits, as the encoder scales them. */
    private fun source(bitmap: KiteBitmap16, precision: Int): Planes {
        val max = (1 shl precision) - 1
        val n = bitmap.channels
        val samples = Array(n) { c ->
            IntArray(bitmap.width * bitmap.height) { i ->
                val s = bitmap.samples[i * n + c].toInt() and 0xFFFF
                if (precision == 16) s else (s * max + 32767) / 65535
            }
        }
        return Planes(bitmap.width, bitmap.height, IntArray(n) { precision }, samples)
    }

    private fun compare(tag: String, expected: Planes, actual: Planes, tolerance: Int, maxMean: Double) {
        assertEquals(expected.width, actual.width, "$tag width")
        assertEquals(expected.height, actual.height, "$tag height")
        assertEquals(expected.samples.size, actual.samples.size, "$tag components")
        var worst = 0
        var sum = 0L
        var count = 0
        for (c in expected.samples.indices) for (i in expected.samples[c].indices) {
            val d = kotlin.math.abs(expected.samples[c][i] - actual.samples[c][i])
            if (d > worst) worst = d
            sum += d
            count++
        }
        val mean = sum.toDouble() / count
        println("[#180] $tag: worst=$worst mean=${"%.4f".format(mean)}")
        assertTrue(worst <= tolerance, "$tag: a sample differs by $worst, more than $tolerance")
        assertTrue(mean <= maxMean, "$tag: the mean difference $mean is more than $maxMean")
    }

    /** A picture of [channels] channels at [bits] bits, each sample widened to 16 bits as decode16 widens one. */
    private fun picture(w: Int, h: Int, channels: Int, bits: Int = 8, seed: Int = 1): KiteBitmap16 {
        val max = (1 shl bits) - 1
        val random = kotlin.random.Random(seed)
        val samples = ShortArray(w * h * channels)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until channels) {
            val alpha = channels % 2 == 0 && c == channels - 1
            val v = when {
                alpha -> if ((x / 7 + y / 5) % 3 == 0) 0.0 else (x + y).toDouble() / (w + h)
                else -> {
                    val gradient = (x * (c + 1) + y * (3 - c)).toDouble() / (w * 2 + h * 3)
                    val edge = if ((x / 9 + y / 6 + c) % 2 == 0) 0.25 else 0.0
                    val noise = random.nextInt(-3, 4) / 64.0
                    (gradient * 0.7 + edge + noise).coerceIn(0.0, 1.0)
                }
            }
            val q = (v * max + 0.5).toInt()
            val wide = when {
                bits == 16 -> q
                bits >= 8 -> (q shl (16 - bits)) or (q shr (2 * bits - 16))
                else -> (q * 255 / max) * 257
            }
            samples[(y * w + x) * channels + c] = wide.toShort()
        }
        return KiteBitmap16(w, h, channels, samples)
    }

    private fun narrow(p: KiteBitmap16): KiteBitmap = p.toBitmap()

    private class Case(val name: String, val options: Jp2EncodeOptions)

    private val lossyAllowance = 1
    private val lossyMean = 0.05

    /** Every option the encoder takes, lossy and lossless. */
    private fun cases(): List<Case> {
        val list = ArrayList<Case>()
        for (lossless in listOf(false, true)) {
            fun o(
                levels: Int = 5, layers: Int = 1, ratios: List<Double> = emptyList(), tileW: Int = 0, tileH: Int = 0,
                cbw: Int = 64, cbh: Int = 64, precinct: Int = 0, progression: Jp2Progression = Jp2Progression.LRCP, effort: Int = 2,
                bypass: Boolean = false, reset: Boolean = false, termAll: Boolean = false, vsc: Boolean = false, segSym: Boolean = false,
                sop: Boolean = false, eph: Boolean = false, codestream: Boolean = false, quality: Int = 75,
            ) = Jp2EncodeOptions(
                quality = quality, lossless = lossless, ratios = ratios, layers = layers, codestreamOnly = codestream,
                tileWidth = tileW, tileHeight = tileH, levels = levels, codeBlockWidth = cbw, codeBlockHeight = cbh,
                precinctSize = precinct, progression = progression, effort = effort, bypass = bypass, resetContexts = reset,
                terminateEachPass = termAll, verticalCausal = vsc, segmentationSymbols = segSym, sopMarkers = sop, ephMarkers = eph,
            )
            val tag = if (lossless) "lossless" else "lossy"
            list += Case("$tag default", o())
            list += Case("$tag quality 20", o(quality = 20))
            list += Case("$tag quality 100", o(quality = 100))
            list += Case("$tag three layers", o(layers = 3))
            list += Case("$tag ratios 40,20,10", o(ratios = listOf(40.0, 20.0, 10.0)))
            list += Case("$tag ratio 200", o(ratios = listOf(200.0)))
            list += Case("$tag tiles 40x24", o(tileW = 40, tileH = 24, levels = 3, layers = 2))
            list += Case("$tag levels 0", o(levels = 0))
            list += Case("$tag levels 1", o(levels = 1))
            list += Case("$tag code-blocks 32x16", o(cbw = 32, cbh = 16))
            list += Case("$tag code-blocks 4x4", o(cbw = 4, cbh = 4, levels = 2))
            list += Case("$tag code-blocks 128x32", o(cbw = 128, cbh = 32))
            list += Case("$tag bypass", o(bypass = true, cbw = 16, cbh = 16, layers = 3))
            list += Case("$tag reset", o(reset = true, layers = 2))
            list += Case("$tag terminate each pass", o(termAll = true, layers = 2))
            list += Case("$tag vertical causal", o(vsc = true))
            list += Case("$tag segmentation symbols", o(segSym = true))
            list += Case("$tag every style", o(bypass = true, reset = true, termAll = true, vsc = true, segSym = true, cbw = 16, cbh = 32, layers = 2))
            list += Case("$tag bypass and termination", o(bypass = true, termAll = true, layers = 3))
            list += Case("$tag sop eph", o(sop = true, eph = true, layers = 2))
            list += Case("$tag codestream", o(codestream = true))
            list += Case("$tag effort 1", o(effort = 1, layers = 2))
            list += Case("$tag effort 3", o(effort = 3))
            for (p in Jp2Progression.entries) {
                list += Case("$tag $p precincts 32", o(progression = p, precinct = 32, layers = 2, cbw = 16, cbh = 16))
            }
            list += Case("$tag precincts 8", o(precinct = 8, progression = Jp2Progression.RPCL, levels = 3))
            list += Case("$tag precincts 2", o(precinct = 2, levels = 2, cbw = 4, cbh = 4))
            list += Case("$tag tiles and RPCL", o(tileW = 33, tileH = 29, precinct = 16, progression = Jp2Progression.RPCL, levels = 2, layers = 2))
        }
        return list
    }

    /** Pictures of every channel count, odd sizes. */
    private val pictures = listOf(
        "gray 61x47" to picture(61, 47, 1, seed = 2),
        "gray+alpha 37x29" to picture(37, 29, 2, seed = 3),
        "rgb 97x61" to picture(97, 61, 3, seed = 4),
        "rgba 53x41" to picture(53, 41, 4, seed = 5),
    )

    @Test
    fun openjpegDecodesEveryOptionToTheSamplesOfTheDecoder() {
        assumeTrue("OpenJPEG tools not installed", tools())
        var files = 0
        for (case in cases()) for ((name, picture) in pictures) {
            val bitmap = narrow(picture)
            val bytes = ImageKodec.encodeJp2(bitmap, case.options)
            val tag = "${case.name}, $name, ${bytes.size} bytes"
            val ours = kodec(bytes, 8)
            val theirs = openjpeg(bytes, case.options.codestreamOnly)
            if (case.options.lossless) {
                compare("$tag: OpenJPEG", ours, theirs, 0, 0.0)
                compare("$tag: source", source(picture, 8), ours, 0, 0.0)
            } else {
                compare("$tag: OpenJPEG", ours, theirs, lossyAllowance, lossyMean)
            }
            files++
        }
        println("[#180] $files files decoded by both")
    }

    @Test
    fun losslessHoldsEveryDepthExactly() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for (bits in 1..16) for (channels in 1..4) {
            val picture = picture(23 + bits, 19 + channels, channels, bits, seed = bits * 7 + channels)
            val options = Jp2EncodeOptions(lossless = true, bitsPerSample = bits, levels = 3)
            val bytes = ImageKodec.encodeJp2Wide(picture, options)
            val tag = "lossless $bits bits, $channels channels"
            val ours = kodec(bytes, bits)
            compare("$tag: source", source(picture, bits), ours, 0, 0.0)
            compare("$tag: OpenJPEG", ours, openjpeg(bytes, false), 0, 0.0)
            if (bits >= 8) {
                // decode16 widens a deeper sample by repeating its bits, which the encoder rounds back.
                val again = ImageKodec.decode16(bytes)
                assertTrue(again.samples.contentEquals(picture.samples), "$tag: decode16 gives the bitmap back")
            }
        }
    }

    @Test
    fun lossyDeepSamplesMatchOpenjpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for (bits in listOf(10, 12, 16)) for (channels in listOf(1, 3)) {
            val picture = picture(71, 43, channels, bits, seed = bits)
            for (options in listOf(Jp2EncodeOptions(bitsPerSample = bits), Jp2EncodeOptions(bitsPerSample = bits, ratios = listOf(30.0, 8.0)))) {
                val bytes = ImageKodec.encodeJp2Wide(picture, options)
                val ours = kodec(bytes, bits)
                // OpenJPEG's floating point and the decoder's fixed point round a deep sample apart a
                // little more often than an 8-bit one.
                compare("lossy $bits bits, $channels channels, ${options.ratios}", ours, openjpeg(bytes, false), 2, 0.45)
            }
        }
    }

    @Test
    fun aReducedDecodeMatchesOpenjpegAtEveryLevel() {
        assumeTrue("OpenJPEG tools not installed", tools())
        val picture = picture(97, 61, 3, seed = 9)
        val bitmap = narrow(picture)
        val options = listOf(
            Jp2EncodeOptions(lossless = true),
            Jp2EncodeOptions(),
            Jp2EncodeOptions(ratios = listOf(20.0, 5.0), progression = Jp2Progression.RPCL, precinctSize = 32),
            Jp2EncodeOptions(lossless = true, tileWidth = 40, tileHeight = 32, levels = 3),
        )
        for (o in options) {
            val bytes = ImageKodec.encodeJp2(bitmap, o)
            for (r in 1..3) {
                val reduced = ImageKodec.decodeReduced(bytes, 1 shl r)
                val ours = Planes(reduced.width, reduced.height, IntArray(3) { 8 }, Array(3) { c -> IntArray(reduced.argb.size) { (reduced.argb[it] ushr (16 - 8 * c)) and 0xFF } })
                val theirs = openjpeg(bytes, false, r)
                if (o.lossless) compare("reduced by ${1 shl r}, $o", ours, theirs, 0, 0.0)
                else compare("reduced by ${1 shl r}, lossy", ours, theirs, lossyAllowance, lossyMean)
            }
        }
    }

    @Test
    fun opjDumpReadsTheParametersAskedFor() {
        assumeTrue("OpenJPEG tools not installed", tools())
        val bitmap = narrow(picture(97, 61, 3, seed = 10))
        val options = Jp2EncodeOptions(
            lossless = true, layers = 3, tileWidth = 64, tileHeight = 48, levels = 4, codeBlockWidth = 32, codeBlockHeight = 16,
            precinctSize = 64, progression = Jp2Progression.RPCL, bypass = true, resetContexts = true, terminateEachPass = true,
            verticalCausal = true, segmentationSymbols = true, sopMarkers = true, ephMarkers = true,
        )
        val file = temp(".jp2").apply { writeBytes(ImageKodec.encodeJp2(bitmap, options)) }
        val (code, dump) = run(Tools.require("opj_dump").path, "-i", file.path)
        assertEquals(0, code, dump)
        for (expected in listOf(
            "x1=97, y1=61", "numcomps=3", "tdx=64, tdy=48", "tw=2, th=2", "csty=0x7", "prg=0x2", "numlayers=3", "mct=1",
            "numresolutions=5", "cblkw=2^5", "cblkh=2^4", "cblksty=0x2f", "qmfbid=1", "(6,6) (6,6) (6,6) (6,6) (6,6)",
        )) assertTrue(dump.contains(expected), "opj_dump does not show \"$expected\":\n$dump")
        val lossy = temp(".j2k").apply { writeBytes(ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(codestreamOnly = true, progression = Jp2Progression.CPRL))) }
        val (code2, dump2) = run(Tools.require("opj_dump").path, "-i", lossy.path)
        assertEquals(0, code2, dump2)
        for (expected in listOf("prg=0x4", "qmfbid=0", "qntsty=2", "numresolutions=6")) {
            assertTrue(dump2.contains(expected), "opj_dump does not show \"$expected\":\n$dump2")
        }
    }

    @Test
    fun exiftoolReadsTheMetadataOfAJp2File() {
        assumeTrue("exiftool not installed", Tools.hasAll("exiftool"))
        val exif = tiffWithMake("KiteCam")
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Kite title</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val icc = File("/usr/share/color/icc/sRGB.icc").takeIf { it.isFile }?.readBytes()
        val metadata = ImageMetadata(exif = exif, xmp = xmp, icc = icc)
        val file = temp(".jp2").apply { writeBytes(ImageKodec.encodeJp2(narrow(picture(31, 17, 3)), Jp2EncodeOptions(), metadata)) }
        val (code, out) = run(Tools.require("exiftool").path, "-s", "-G1", file.path)
        assertEquals(0, code, out)
        assertTrue(!out.contains("Warning"), "exiftool warns:\n$out")
        assertTrue(Regex("""Make\s*:\s*KiteCam""").containsMatchIn(out), "exiftool reads no EXIF Make:\n$out")
        assertTrue(Regex("""Title\s*:\s*Kite title""").containsMatchIn(out), "exiftool reads no XMP title:\n$out")
        if (icc != null) assertTrue(out.contains("ICC_Profile") || out.contains("ICC-header"), "exiftool reads no ICC profile:\n$out")
        // And ImageKodec reads back what it wrote.
        val back = ImageKodec.readMetadata(file.readBytes())
        assertTrue(back.exif.contentEquals(exif))
        assertEquals(xmp, back.xmp)
        if (icc != null) assertTrue(back.icc.contentEquals(icc))
    }

    /** A big-endian EXIF block whose IFD0 holds a Make tag of [make]. */
    private fun tiffWithMake(make: String): ByteArray {
        val text = (make + "\u0000").encodeToByteArray()
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8); out.write(v) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        out.write('M'.code); out.write('M'.code); u16(42); u32(8)
        u16(1)
        u16(0x010F); u16(2); u32(text.size); u32(8 + 2 + 12 + 4)
        u32(0)
        out.write(text)
        return out.toByteArray()
    }
}
