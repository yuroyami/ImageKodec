package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTJ2K (#187) against OpenJPEG 2.5, the one installed decoder of ITU-T T.814 that reads it as the
 * standard does: every file the HT block encoder writes, over sizes, depths, channel counts, tiles,
 * levels, code-block sizes, precincts, lossless and lossy, decodes in `opj_decompress` and in
 * ImageKodec to the same samples, at full size and at every reduction; a lossless one holds its
 * source exactly. OpenJPEG reads the HT blocks the decoder reads, so this holds the encoder and the
 * decoder each to an independent implementation: a wrong table, context, stream or pass breaks
 * the agreement.
 */
class Jp2HtOracleTest {

    private fun tools(): Boolean = Tools.hasAll("opj_decompress", "opj_dump")

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -1 to "timed out" }
        return proc.exitValue() to out.toString()
    }

    private fun temp(suffix: String): File = File.createTempFile("kite-htj2k", suffix).apply { deleteOnExit() }

    /** Planar components, each at its own precision: what OpenJPEG decodes. */
    private class Planes(val width: Int, val height: Int, val samples: Array<IntArray>)

    /** [bytes] decoded by `opj_decompress` with [reduce] levels dropped, as planar raw samples. */
    private fun openjpeg(bytes: ByteArray, codestream: Boolean, reduce: Int = 0): Planes {
        val input = temp(if (codestream) ".j2k" else ".jp2").apply { writeBytes(bytes) }
        val raw = temp(".rawl")
        val args = mutableListOf(Tools.require("opj_decompress").path, "-i", input.path, "-o", raw.path)
        if (reduce > 0) args += listOf("-r", "$reduce")
        val (code, log) = run(*args.toTypedArray())
        assertEquals(0, code, "opj_decompress failed: $log")
        val comps = Regex("""Component \d+ characteristics: (\d+)x(\d+)x(\d+)""").findAll(log).map { it.groupValues }.toList()
        assertTrue(comps.isNotEmpty(), "no component lines in: $log")
        val w = comps[0][1].toInt()
        val h = comps[0][2].toInt()
        val data = raw.readBytes()
        var at = 0
        val samples = Array(comps.size) { c ->
            val precision = comps[c][3].toInt()
            IntArray(w * h) {
                if (precision <= 8) data[at++].toInt() and 0xFF
                else (data[at++].toInt() and 0xFF) or ((data[at++].toInt() and 0xFF) shl 8)
            }
        }
        return Planes(w, h, samples)
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
        return Planes(wide.width, wide.height, samples)
    }

    /** [bytes] decoded by ImageKodec with each side divided by [factor], as [channels] 8-bit planes. */
    private fun kodecReduced(bytes: ByteArray, factor: Int, channels: Int): Planes {
        val reduced = ImageKodec.decodeReduced(bytes, factor)
        val samples = Array(channels) { c ->
            IntArray(reduced.argb.size) { i ->
                val p = reduced.argb[i]
                when {
                    channels <= 2 && c == 0 -> p and 0xFF
                    c == channels - 1 && channels % 2 == 0 -> p ushr 24
                    else -> (p ushr (16 - 8 * c)) and 0xFF
                }
            }
        }
        return Planes(reduced.width, reduced.height, samples)
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
        return Planes(bitmap.width, bitmap.height, samples)
    }

    /** The worst and mean differences of [actual] from [expected], after checking they are within [tolerance] and [maxMean]. */
    private fun compare(tag: String, expected: Planes, actual: Planes, tolerance: Int, maxMean: Double): Pair<Int, Double> {
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
        assertTrue(worst <= tolerance, "$tag: a sample differs by $worst, more than $tolerance")
        assertTrue(mean <= maxMean, "$tag: the mean difference $mean is more than $maxMean")
        return worst to mean
    }

    /** A picture of [channels] channels at [bits] bits: gradients, edges and noise, alpha with holes. */
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

    private class Case(val name: String, val options: Jp2EncodeOptions)

    /** The 9/7 allowance of Jp2EncodeOracleTest: OpenJPEG's floating point against the decoder's fixed point. */
    private val lossyAllowance = 1
    private val lossyMean = 0.05

    private fun cases(): List<Case> {
        val list = ArrayList<Case>()
        for (lossless in listOf(false, true)) {
            fun o(
                levels: Int = 5, tileW: Int = 0, tileH: Int = 0, cbw: Int = 64, cbh: Int = 64, precinct: Int = 0,
                progression: Jp2Progression = Jp2Progression.LRCP, effort: Int = 2, vsc: Boolean = false,
                codestream: Boolean = false, quality: Int = 75, ratios: List<Double> = emptyList(),
            ) = Jp2EncodeOptions(
                quality = quality, lossless = lossless, ratios = ratios, codestreamOnly = codestream, tileWidth = tileW,
                tileHeight = tileH, levels = levels, codeBlockWidth = cbw, codeBlockHeight = cbh, precinctSize = precinct,
                progression = progression, effort = effort, verticalCausal = vsc, highThroughput = true,
            )
            val tag = if (lossless) "lossless" else "lossy"
            list += Case("$tag default", o())
            list += Case("$tag levels 0", o(levels = 0))
            list += Case("$tag levels 1", o(levels = 1))
            list += Case("$tag tiles 40x24", o(tileW = 40, tileH = 24, levels = 3))
            list += Case("$tag code-blocks 32x16", o(cbw = 32, cbh = 16))
            list += Case("$tag code-blocks 4x4", o(cbw = 4, cbh = 4, levels = 2))
            list += Case("$tag code-blocks 128x32", o(cbw = 128, cbh = 32))
            list += Case("$tag code-blocks 1024x4", o(cbw = 1024, cbh = 4, levels = 1))
            list += Case("$tag code-blocks 4x1024", o(cbw = 4, cbh = 1024, levels = 1))
            list += Case("$tag vertical causal", o(vsc = true, cbw = 16, cbh = 16))
            list += Case("$tag codestream", o(codestream = true))
            list += Case("$tag effort 1", o(effort = 1))
            list += Case("$tag effort 3", o(effort = 3))
            list += Case("$tag RPCL precincts 32", o(progression = Jp2Progression.RPCL, precinct = 32, cbw = 16, cbh = 16))
            list += Case("$tag tiles and PCRL", o(tileW = 33, tileH = 29, precinct = 16, progression = Jp2Progression.PCRL, levels = 2))
            if (!lossless) {
                list += Case("lossy quality 20", o(quality = 20))
                list += Case("lossy quality 50", o(quality = 50))
                list += Case("lossy quality 100", o(quality = 100))
                list += Case("lossy ratio 40", o(ratios = listOf(40.0)))
                list += Case("lossy ratio 8", o(ratios = listOf(8.0)))
            }
        }
        return list
    }

    private val pictures = listOf(
        "gray 61x47" to picture(61, 47, 1, seed = 2),
        "gray+alpha 37x29" to picture(37, 29, 2, seed = 3),
        "rgb 97x61" to picture(97, 61, 3, seed = 4),
        "rgba 53x41" to picture(53, 41, 4, seed = 5),
        "rgb 1x1" to picture(1, 1, 3, seed = 6),
        "gray 1x40" to picture(1, 40, 1, seed = 7),
        "rgb 70x1" to picture(70, 1, 3, seed = 8),
    )

    @Test
    fun openjpegDecodesEveryHtFileToTheSamplesOfTheDecoder() {
        assumeTrue("OpenJPEG tools not installed", tools())
        var files = 0
        var reductions = 0
        var worst = 0
        for (case in cases()) for ((name, picture) in pictures) {
            val bytes = ImageKodec.encodeJp2(picture.toBitmap(), case.options)
            val tag = "${case.name}, $name, ${bytes.size} bytes"
            val ours = kodec(bytes, 8)
            val theirs = openjpeg(bytes, case.options.codestreamOnly)
            if (case.options.lossless) {
                compare("$tag: OpenJPEG", ours, theirs, 0, 0.0)
                compare("$tag: source", source(picture, 8), ours, 0, 0.0)
            } else {
                worst = maxOf(worst, compare("$tag: OpenJPEG", ours, theirs, lossyAllowance, lossyMean).first)
            }
            files++
            // Every reduction the levels of the smallest tile allow.
            val levels = minOf(case.options.levels, 3)
            for (r in 1..levels) {
                if (picture.width < (1 shl r) || picture.height < (1 shl r)) break
                if (case.options.tileWidth != 0 && (case.options.tileWidth < (1 shl levels) || case.options.tileHeight < (1 shl levels))) break
                val b = openjpeg(bytes, case.options.codestreamOnly, r)
                val a = kodecReduced(bytes, 1 shl r, b.samples.size)
                val (tol, mean) = if (case.options.lossless) 0 to 0.0 else lossyAllowance to lossyMean
                compare("$tag, reduced by ${1 shl r}", b, a, tol, mean)
                reductions++
            }
        }
        println("[#187] $files HT files and $reductions reductions decoded by both; worst lossy difference $worst")
    }

    @Test
    fun losslessHtHoldsEveryDepthExactly() {
        assumeTrue("OpenJPEG tools not installed", tools())
        var files = 0
        for (bits in 1..16) for (channels in 1..4) {
            val picture = picture(23 + bits, 19 + channels, channels, bits, seed = bits * 7 + channels)
            val options = Jp2EncodeOptions(lossless = true, bitsPerSample = bits, levels = 3, highThroughput = true)
            val bytes = ImageKodec.encodeJp2Wide(picture, options)
            val tag = "HT lossless $bits bits, $channels channels"
            val ours = kodec(bytes, bits)
            compare("$tag: source", source(picture, bits), ours, 0, 0.0)
            compare("$tag: OpenJPEG", ours, openjpeg(bytes, false), 0, 0.0)
            files++
        }
        println("[#187] $files lossless HT files of 1 to 16 bits equal their source in both decoders")
    }

    @Test
    fun lossyHtDeepSamplesMatchOpenjpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        for (bits in listOf(10, 12, 16)) for (channels in listOf(1, 3)) {
            val picture = picture(71, 43, channels, bits, seed = bits)
            for (quality in listOf(40, 90)) {
                val options = Jp2EncodeOptions(bitsPerSample = bits, quality = quality, highThroughput = true)
                val bytes = ImageKodec.encodeJp2Wide(picture, options)
                val (worst, mean) = compare("HT lossy $bits bits, $channels channels, quality $quality", kodec(bytes, bits), openjpeg(bytes, false), 2, 0.45)
                println("[#187] HT lossy $bits bits, $channels channels, quality $quality: worst=$worst mean=${"%.4f".format(mean)}")
            }
        }
    }

    @Test
    fun theCommittedFixturesDecodeAlikeInOpenjpeg() {
        assumeTrue("OpenJPEG tools not installed", tools())
        compare("rgbaLossless", openjpeg(Jp2HtFixtures.rgbaLossless, false), kodec(Jp2HtFixtures.rgbaLossless, 8), 0, 0.0)
        compare("gray12", openjpeg(Jp2HtFixtures.gray12, false), kodec(Jp2HtFixtures.gray12, 12), 0, 0.0)
        val (worst, mean) = compare("rgbLossy", openjpeg(Jp2HtFixtures.rgbLossy, true), kodec(Jp2HtFixtures.rgbLossy, 8), lossyAllowance, lossyMean)
        println("[#187] committed lossy fixture against OpenJPEG: worst=$worst mean=${"%.4f".format(mean)}")
    }

    @Test
    fun opjDumpReadsTheHtCodeBlockStyleAndTheFileIsJph() {
        assumeTrue("OpenJPEG tools not installed", tools())
        val bitmap = picture(97, 61, 3, seed = 10).toBitmap()
        val bytes = ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, verticalCausal = true, highThroughput = true))
        assertEquals("jph ", bytes.copyOfRange(20, 24).decodeToString(), "the ftyp brand")
        val file = temp(".jp2").apply { writeBytes(bytes) }
        val (code, dump) = run(Tools.require("opj_dump").path, "-i", file.path)
        assertEquals(0, code, dump)
        assertTrue(dump.contains("cblksty=0x48"), "opj_dump does not show the HT style:\n$dump")
        val info = ImageKodec.probe(bytes)
        assertEquals(ImageFormat.JP2, info.format)
        assertTrue(info.isDecodable, "probe: ${info.unsupportedReason}")
    }
}
