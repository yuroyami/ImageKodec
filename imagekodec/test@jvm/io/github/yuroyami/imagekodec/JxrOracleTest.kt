package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxr.JxrContainer
import io.github.yuroyami.imagekodec.codec.jxr.JxrDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * JPEG XR files (#125) written by `JxrEncApp` of Microsoft's jxrlib, read through [JxrDecoder] and
 * compared with `JxrDecApp` of the same library, sample for sample, in every pixel format the
 * issue names: lossless and lossy, spatial and frequency order, the three overlap modes, every
 * internal colour format, tiles, and planar and interleaved alpha.
 *
 * The decoder is a port of jxrlib, so every sample has to be equal, lossy files included, in the
 * pixel format's own samples: integers, signed fixed point, and the bits of halves and floats.
 * The 8-bit and 16-bit pictures the facade gives are checked against the same reading: for the
 * linear formats, `JxrDecApp`'s conversion to 24-bit RGB, within a level for the two roundings.
 * Skips cleanly without `JxrEncApp` and `JxrDecApp` (Debian's libjxr-tools).
 */
class JxrOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(300, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(ext: String) = File.createTempFile("kite-jxr", ext).apply { deleteOnExit() }

    private fun tools(): Boolean = Tools.hasAll("JxrEncApp", "JxrDecApp")

    /** An image of [w] by [h] with gradients, hard edges and a block of noise, as values from 0 to 1 per channel. */
    private fun scene(w: Int, h: Int, seed: Int): Array<DoubleArray> {
        val rng = java.util.Random(seed.toLong())
        return Array(4) { c ->
            DoubleArray(w * h) { i ->
                val x = i % w
                val y = i / w
                when {
                    x in w / 5 until w / 2 && y in h / 4 until h / 2 -> rng.nextDouble()
                    c == 3 -> ((x * 3 + y * 11) % 256) / 255.0
                    x > w * 3 / 4 -> if ((x / 3 + y / 4) % 2 == 0) 0.9 else 0.1
                    else -> ((x * (5 + 2 * c) + y * (7 - 2 * c) + 40 * c) % 256) / 255.0
                }
            }
        }
    }

    /** An uncompressed little-endian TIFF of one strip, as JxrEncApp reads one. */
    private fun tiff(w: Int, h: Int, spp: Int, bits: Int, sampleFormat: Int, alpha: Boolean, sample: (Int, Int) -> Long): ByteArray {
        val bytesPer = bits / 8
        val data = ByteBuffer.allocate(w * h * spp * bytesPer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until w * h) {
            for (c in 0 until spp) {
                val v = sample(i, c)
                when (bytesPer) {
                    1 -> data.put(v.toByte())
                    2 -> data.putShort(v.toShort())
                    else -> data.putInt(v.toInt())
                }
            }
        }
        class Entry(val tag: Int, val type: Int, val values: List<Int>)
        val entries = mutableListOf(
            Entry(256, 4, listOf(w)), Entry(257, 4, listOf(h)), Entry(258, 3, List(spp) { bits }),
            Entry(259, 3, listOf(1)), Entry(262, 3, listOf(if (spp >= 3) 2 else 1)), Entry(273, 4, listOf(0)),
            Entry(277, 3, listOf(spp)), Entry(278, 4, listOf(h)), Entry(279, 4, listOf(data.capacity())), Entry(284, 3, listOf(1)),
        )
        if (alpha) entries += Entry(338, 3, listOf(2))
        entries += Entry(339, 3, List(spp) { sampleFormat })
        val extraAt = 8 + 2 + 12 * entries.size + 4
        val extra = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = ByteBuffer.allocate(2 + 12 * entries.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(entries.size.toShort())
        val extraValues = entries.sumOf { e -> if (e.values.size * (if (e.type == 3) 2 else 4) > 4) e.values.size * 2 else 0 }
        val dataAt = extraAt + extraValues
        for (e in entries) {
            ifd.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.values.size)
            val size = e.values.size * if (e.type == 3) 2 else 4
            if (size > 4) {
                ifd.putInt(extraAt + extra.position())
                for (v in e.values) extra.putShort(v.toShort())
            } else if (e.tag == 273) {
                ifd.putInt(dataAt)
            } else if (e.type == 3) {
                ifd.putShort(e.values[0].toShort()).putShort(if (e.values.size > 1) e.values[1].toShort() else 0)
            } else {
                ifd.putInt(e.values[0])
            }
        }
        ifd.putInt(0)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0))
        out.write(ifd.array())
        out.write(extra.array(), 0, extra.position())
        out.write(data.array())
        return out.toByteArray()
    }

    /** The samples of a TIFF that JxrDecApp wrote: one strip, uncompressed, little endian. */
    private class Tiff(val width: Int, val height: Int, val spp: Int, val samples: IntArray)

    private fun readTiff(bytes: ByteArray): Tiff {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt()
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val at = ifd + 2 + 12 * i
            val tag = b.getShort(at).toInt() and 0xFFFF
            if (tag !in setOf(256, 257, 258, 273, 277, 339)) continue
            val type = b.getShort(at + 2).toInt()
            val count = b.getInt(at + 4)
            val size = if (type == 3) 2 else 4
            val base = if (count * size > 4) b.getInt(at + 8) else at + 8
            tags[tag] = IntArray(count) { k -> if (type == 3) b.getShort(base + 2 * k).toInt() and 0xFFFF else b.getInt(base + 4 * k) }
        }
        val w = tags.getValue(256)[0]
        val h = tags.getValue(257)[0]
        val spp = tags.getValue(277)[0]
        val bits = tags.getValue(258)[0]
        val sf = tags[339]?.get(0) ?: 1
        val start = tags.getValue(273)[0]
        // JxrDecApp writes a packed pixel of 5, 6 or 10 bits a channel as one 16-bit or 32-bit value.
        if (bits == 5 || bits == 6) return Tiff(w, h, 1, IntArray(w * h) { i -> b.getShort(start + 2 * i).toInt() and 0xFFFF })
        if (bits == 10) return Tiff(w, h, 1, IntArray(w * h) { i -> b.getInt(start + 4 * i) })
        val samples = IntArray(w * h * spp) { i ->
            when (bits) {
                8 -> bytes[start + i].toInt() and 0xFF
                16 -> b.getShort(start + 2 * i).toInt().let { if (sf == 2) it else it and 0xFFFF }
                else -> b.getInt(start + 4 * i)
            }
        }
        return Tiff(w, h, spp, samples)
    }

    /** A source format of JxrEncApp: its `-c` number, the TIFF it reads and how many channels it has. */
    private class Format(val name: String, val code: Int, val spp: Int, val bits: Int, val sampleFormat: Int, val value: (Double, Boolean) -> Long)

    private val u8 = { v: Double, _: Boolean -> (v * 255).roundToInt().toLong() }
    private val u16 = { v: Double, _: Boolean -> (v * 65535).roundToInt().toLong() }
    private val fixed16 = { v: Double, a: Boolean -> ((if (a) v else v * 1.5 - 0.1) * 8192).roundToInt().toLong() }
    private val half = { v: Double, a: Boolean -> toHalf(if (a) v else v * 1.5 - 0.1).toLong() }
    private val float32 = { v: Double, a: Boolean -> (if (a) v else v * 1.5 - 0.1).toFloat().toRawBits().toLong() }

    private val formats = listOf(
        Format("24bppRGB", 9, 3, 8, 1, u8),
        Format("24bppBGR", 0, 3, 8, 1, u8),
        Format("32bppBGR", 34, 3, 8, 1, u8),
        Format("8bppGray", 2, 1, 8, 1, u8),
        Format("32bppBGRA", 22, 4, 8, 1, u8),
        Format("48bppRGB", 10, 3, 16, 1, u16),
        Format("64bppRGBA", 23, 4, 16, 1, u16),
        Format("16bppGray", 3, 1, 16, 1, u16),
        Format("48bppRGBFixedPoint", 11, 3, 16, 2, fixed16),
        Format("64bppRGBAFixedPoint", 24, 4, 16, 2, fixed16),
        Format("16bppGrayFixedPoint", 4, 1, 16, 2, fixed16),
        Format("48bppRGBHalf", 12, 3, 16, 3, half),
        Format("64bppRGBAHalf", 25, 4, 16, 3, half),
        Format("16bppGrayHalf", 5, 1, 16, 3, half),
        Format("128bppRGBAFloat", 28, 4, 32, 3, float32),
        Format("16bppRGB555", 29, 3, 8, 1, u8),
        Format("16bppRGB565", 30, 3, 8, 1, u8),
        Format("32bppRGB101010", 31, 3, 16, 1, u16),
    )

    private fun toHalf(v: Double): Int {
        val bits = v.toFloat().toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val e = ((bits ushr 23) and 0xFF) - 127 + 15
        if (e <= 0) return sign
        val m = bits and 0x7FFFFF
        val rounded = (e shl 10) + ((m + 0x1000) shr 13)
        return sign or minOf(rounded, 0x7BFF)
    }

    /** Convert_Half_To_Float of JXRGluePFC.c, which reads a denormal as 0. */
    private fun halfValue(h: Int): Double {
        val e = (h shr 10) and 0x1F
        if (e == 0) return 0.0
        val v = (1 + (h and 0x3FF) / 1024.0) * 2.0.pow(e - 15)
        return if (h and 0x8000 != 0) -v else v
    }

    /** Convert_Float_To_U8 and Convert_AlphaFloat_To_U8 of JXRGluePFC.c. */
    private fun toU8(f: Double, alpha: Boolean): Int = when {
        f <= 0 -> 0
        f >= 1 -> 255
        alpha -> (255 * f + 0.5).toInt()
        f <= 0.0031308 -> (255 * f * 12.92 + 0.5).toInt()
        else -> (255 * (1.055 * f.pow(1 / 2.4) - 0.055) + 0.5).toInt()
    }

    private fun source(f: Format, w: Int, h: Int, seed: Int): File {
        val s = scene(w, h, seed)
        val file = temp(".tif")
        file.writeBytes(tiff(w, h, f.spp, f.bits, f.sampleFormat, f.spp == 4) { i, c -> f.value(s[if (f.spp == 1) 1 else c][i], c == 3) })
        return file
    }

    private fun encode(src: File, f: Format, options: List<String>): ByteArray {
        val out = temp(".jxr")
        val (code, text) = run(Tools.require("JxrEncApp").path, "-i", src.path, "-o", out.path, "-c", f.code.toString(), *options.toTypedArray())
        assertTrue(code == 0 && out.length() > 0, "JxrEncApp -c ${f.code} ${options.joinToString(" ")} failed: $text")
        return out.readBytes()
    }

    private fun reference(jxr: ByteArray, vararg options: String): Tiff {
        val input = temp(".jxr").apply { writeBytes(jxr) }
        val out = temp(".tif")
        val (code, text) = run(Tools.require("JxrDecApp").path, "-i", input.path, "-o", out.path, *options)
        assertTrue(code == 0, "JxrDecApp failed: $text")
        return readTiff(out.readBytes())
    }

    /** The samples of [jxr] in the order JxrDecApp writes them to a TIFF: gray, or red, green, blue and alpha. */
    private fun ours(jxr: ByteArray): Pair<Int, IntArray> = ours(JxrDecoder.decodeRaw(jxr))

    private fun ours(d: JxrDecoder.Decoded): Pair<Int, IntArray> {
        val pf = d.format
        val r = d.raster
        val s = r.samples
        val name = pf.name
        if (name.endsWith("555") || name.endsWith("565") || name.endsWith("101010")) return 1 to s.copyOf()
        val color = if (pf.colorFormat == 0) 1 else 3
        val channels = color + if (d.alpha) 1 else 0
        val out = IntArray(r.width * r.height * channels)
        for (i in 0 until r.width * r.height) {
            for (c in 0 until channels) {
                val slot = if (c < color && pf.bgr) 2 - c else c
                out[i * channels + c] = s[i * r.slots + slot]
            }
        }
        return channels to out
    }

    private class Case(val name: String, val options: List<String>, val lossless: Boolean)

    private val options = listOf(
        Case("lossless", listOf("-q", "1"), true),
        Case("lossless spatial", listOf("-q", "1", "-f"), true),
        Case("lossless tiles", listOf("-q", "1", "-U", "2", "3"), true),
        Case("q0.9", listOf("-q", "0.9"), false),
        Case("q0.4", listOf("-q", "0.4"), false),
        Case("q30 spatial", listOf("-q", "30", "-f"), false),
    )

    /** The coding options every one of which 24bppRGB goes through. */
    private val rgbOptions = options + listOf(
        Case("overlap 0", listOf("-q", "0.8", "-l", "0"), false),
        Case("overlap 1", listOf("-q", "0.8", "-l", "1"), false),
        Case("overlap 2", listOf("-q", "0.8", "-l", "2"), false),
        Case("overlap 2 lossless", listOf("-q", "1", "-l", "2"), true),
        Case("Y only", listOf("-q", "0.8", "-d", "0"), false),
        Case("4:2:0", listOf("-q", "0.8", "-d", "1"), false),
        Case("4:2:2", listOf("-q", "0.8", "-d", "2"), false),
        Case("4:4:4", listOf("-q", "0.8", "-d", "3"), false),
        Case("4:2:0 overlap 2 spatial", listOf("-q", "0.5", "-d", "1", "-l", "2", "-f"), false),
        Case("4:2:2 overlap 2 tiles", listOf("-q", "0.5", "-d", "2", "-l", "2", "-U", "2", "2"), false),
        Case("4:2:0 lossless", listOf("-q", "1", "-d", "1"), true),
        Case("4:2:2 lossless tiles", listOf("-q", "1", "-d", "2", "-U", "3", "2"), true),
        Case("uneven tiles", listOf("-q", "0.7", "-V", "1", "3", "-H", "2", "1"), false),
        Case("tiles overlap 2 spatial", listOf("-q", "0.7", "-l", "2", "-f", "-U", "3", "3"), false),
        Case("sequential", listOf("-q", "0.7", "-p"), false),
        Case("coarse", listOf("-q", "120"), false),
        Case("quantizer 2", listOf("-q", "2"), false),
    )

    private var compared = 0L
    private var files = 0

    private fun check(
        f: Format, case: Case, w: Int = 83, h: Int = 61, extra: List<String> = emptyList(),
        patch: (ByteArray) -> Unit = {},
    ) {
        val jxr = encode(source(f, w, h, f.code * 31 + case.name.length), f, case.options + extra)
        patch(jxr)
        val want = reference(jxr)
        val (channels, got) = ours(jxr)
        val label = "${f.name} ${case.name} ${extra.joinToString(" ")}"
        assertEquals(want.spp, channels, "$label: channels")
        assertEquals(want.samples.size, got.size, "$label: samples")
        var worst = 0
        var first = -1
        for (i in got.indices) {
            val d = abs(got[i].toLong() - want.samples[i].toLong())
            if (d != 0L && first < 0) first = i
            if (d > worst) worst = minOf(d, Int.MAX_VALUE.toLong()).toInt()
        }
        if (worst != 0) {
            val px = first / channels
            fail("$label: sample ${first % channels} of pixel (${px % w}, ${px / w}) is ${got[first]}, JxrDecApp ${want.samples[first]}; worst difference $worst")
        }
        compared += got.size
        files++
    }

    @Test
    fun everyPixelFormatMatchesJxrDecApp() {
        assumeTrue(tools())
        for (f in formats) for (case in options) check(f, case)
        // Interleaved alpha, which JxrEncApp writes only when asked; planar is its default.
        for (f in formats.filter { it.spp == 4 }) {
            check(f, options[0], extra = listOf("-a", "3"))
            check(f, options[3], extra = listOf("-a", "3"))
            check(f, options[5], extra = listOf("-a", "3"))
        }
        println("JxrOracleTest: $files files, $compared samples equal to JxrDecApp's")
    }

    @Test
    fun everyCodingOptionMatchesJxrDecApp() {
        assumeTrue(tools())
        val f = formats.first()
        for (case in rgbOptions) check(f, case)
        // Sizes that end inside a macroblock, and one of a single macroblock.
        // jxrlib's thumbnail of a gray codestream in BGR is black, from a slot it never wrote; the facade fills the gray.
        val gray = encode(source(formats[1], 83, 61, 9), formats[1], listOf("-q", "0.8", "-d", "0"))
        assertTrue(JxrDecoder.decodeRawReduced(gray, 2)!!.raster.samples.all { it == 0 })
        val full = ImageKodec.decode(gray)
        val half = ImageKodec.decodeReduced(gray, 2)
        for (y in 0 until half.height) for (x in 0 until half.width) {
            val d = abs(((half[x, y] shr 8) and 0xFF) - ((full[2 * x, 2 * y] shr 8) and 0xFF))
            assertTrue(d <= 3, "the gray thumbnail at ($x, $y) is $d levels from the full decode")
        }
        for ((w, h) in listOf(16 to 16, 1 to 1, 17 to 33, 130 to 9)) {
            check(f, options[0], w, h)
            check(f, options[4], w, h)
        }
        println("JxrOracleTest: $files files, $compared samples equal to JxrDecApp's")
    }

    private var thumbnails = 0
    private var thumbnailSamples = 0L
    private var fellBack = 0

    /** Each reduction of [jxr] against `JxrDecApp -T`, sample for sample, at every size jxrlib keeps. */
    private fun checkReduced(jxr: ByteArray, label: String) {
        val info = ImageKodec.probe(jxr)
        for (t in 1..3) {
            val reduction = 1 shl t
            val d = JxrDecoder.decodeRawReduced(jxr, reduction)
            if (d == null) {
                // jxrlib gives another size than decodeReduced promises, so the facade averages a full decode.
                assertEquals(0, JxrDecoder.thumbnailScale(info.width, info.height, reduction), label)
                fellBack++
                continue
            }
            val want = reference(jxr, "-T", t.toString())
            val (channels, got) = ours(d)
            val l = "$label at 1/$reduction"
            assertEquals(want.width, d.raster.width, "$l: width")
            assertEquals(want.height, d.raster.height, "$l: height")
            assertEquals(want.spp, channels, "$l: channels")
            for (i in got.indices) {
                if (got[i] != want.samples[i]) {
                    val px = i / channels
                    fail("$l: sample ${i % channels} of pixel (${px % want.width}, ${px / want.width}) is ${got[i]}, JxrDecApp ${want.samples[i]}")
                }
            }
            val bitmap = ImageKodec.decodeReduced(jxr, reduction)
            assertEquals(d.raster.width, bitmap.width, l)
            assertEquals(d.raster.height, bitmap.height, l)
            thumbnails++
            thumbnailSamples += got.size
        }
    }

    @Test
    fun reducedDecodesMatchJxrDecAppThumbnails() {
        assumeTrue(tools())
        val cases = listOf(
            Case("lossless", listOf("-q", "1"), true),
            Case("q0.8", listOf("-q", "0.8"), false),
            Case("q0.4 spatial", listOf("-q", "0.4", "-f"), false),
            Case("tiles", listOf("-q", "0.7", "-U", "2", "3"), false),
        )
        for (f in formats) for (case in cases) {
            val jxr = encode(source(f, 83, 61, f.code + case.name.length), f, case.options)
            checkReduced(jxr, "${f.name} ${case.name}")
        }
        for (f in formats.filter { it.spp == 4 }) {
            checkReduced(encode(source(f, 83, 61, 3), f, listOf("-q", "0.8", "-a", "3")), "${f.name} interleaved")
        }
        val rgb = formats[0]
        val bgr = formats[1]
        for (case in rgbOptions) {
            checkReduced(encode(source(rgb, 83, 61, case.name.length), rgb, case.options), "24bppRGB ${case.name}")
            checkReduced(encode(source(bgr, 83, 61, case.name.length), bgr, case.options), "24bppBGR ${case.name}")
        }
        for ((w, h) in listOf(16 to 16, 1 to 1, 17 to 33, 130 to 9, 100 to 8, 9 to 70, 64 to 48, 40 to 23)) {
            for (opts in listOf(listOf("-q", "1"), listOf("-q", "0.5", "-d", "1", "-l", "2"), listOf("-q", "0.5", "-d", "2"), listOf("-q", "0.6", "-f", "-l", "0"))) {
                // JxrEncApp refuses two levels of overlap with subsampled chroma under two macroblocks wide.
                if (w <= 16 && "-l" in opts && opts[opts.indexOf("-l") + 1] == "2") continue
                checkReduced(encode(source(rgb, w, h, w + h), rgb, opts), "24bppRGB ${w}x$h ${opts.joinToString(" ")}")
            }
        }
        // A subsampled image under 8 wide, whose quarter thumbnail jxrlib cannot make, still reduces:
        // the facade decodes it in full and averages.
        for (opts in listOf(listOf("-q", "0.8", "-d", "1"), listOf("-q", "0.8", "-d", "2"))) {
            val narrow = encode(source(rgb, 6, 40, 6), rgb, opts)
            val full = ImageKodec.decode(narrow)
            val quarter = ImageKodec.decodeReduced(narrow, 4)
            assertEquals(2, quarter.width, "6x40 ${opts.joinToString(" ")}")
            assertEquals(10, quarter.height, "6x40 ${opts.joinToString(" ")}")
            assertEquals(6, full.width)
        }
        for (version in listOf(0, 9)) {
            for (opts in listOf(listOf("-q", "0.6", "-l", "2", "-U", "3", "2"), listOf("-q", "0.5", "-d", "1", "-l", "1", "-U", "2", "2", "-f"))) {
                val jxr = encode(source(rgb, 83, 61, version), rgb, opts)
                subVersion(jxr, version)
                checkReduced(jxr, "24bppRGB subversion $version ${opts.joinToString(" ")}")
            }
        }
        println("JxrOracleTest: $thumbnails thumbnails, $thumbnailSamples samples equal to JxrDecApp -T; $fellBack reductions that jxrlib sizes otherwise")
    }

    /**
     * Sets the codec subversion of every codestream of [jxr] to [subVersion]. JxrEncApp writes only
     * subversion 1, but the same bits read as HD Photo's subversion 0, with its own transform, or as
     * subversion 9, with hard tile edges, and JxrDecApp reads the altered file the same way.
     */
    private fun subVersion(jxr: ByteArray, subVersion: Int) {
        val c = JxrContainer.read(jxr)
        for (at in listOf(c.imageOffset, c.alphaOffset)) {
            if (at == 0) continue
            jxr[at + 8] = ((jxr[at + 8].toInt() and 0xF0) or subVersion).toByte()
        }
    }

    @Test
    fun otherSubversionsMatchJxrDecApp() {
        assumeTrue(tools())
        val cases = listOf(
            Case("lossless", listOf("-q", "1"), true),
            Case("overlap 1", listOf("-q", "0.8", "-l", "1"), false),
            Case("overlap 2", listOf("-q", "0.6", "-l", "2"), false),
            Case("4:2:0 overlap 2", listOf("-q", "0.5", "-d", "1", "-l", "2"), false),
            Case("4:2:2 overlap 2", listOf("-q", "0.5", "-d", "2", "-l", "2"), false),
            Case("4:2:0 overlap 1", listOf("-q", "0.5", "-d", "1", "-l", "1"), false),
            Case("4:2:2 overlap 1", listOf("-q", "0.5", "-d", "2", "-l", "1"), false),
        )
        val tiles = listOf(listOf(), listOf("-U", "3", "2"), listOf("-V", "1", "2", "-H", "2", "1"), listOf("-U", "2", "3", "-f"))
        for (version in listOf(0, 9)) {
            for (case in cases) for (t in tiles) check(formats[0], case, extra = t) { subVersion(it, version) }
            check(formats[4], cases[2], extra = listOf("-U", "2", "2", "-a", "3")) { subVersion(it, version) }
            check(formats[4], cases[2], extra = listOf("-U", "2", "2")) { subVersion(it, version) }
        }
        println("JxrOracleTest: $files files, $compared samples equal to JxrDecApp's")
    }

    @Test
    fun premultipliedAlphaIsDivided() {
        assumeTrue(tools())
        // JxrEncApp writes no premultiplied file; one of 32bppBGRA relabelled 32bppPBGRA holds the same samples.
        val f = formats[4]
        for (case in listOf(options[0], options[3])) {
            check(f, case) { relabel(it, 0x10) }
            val jxr = encode(source(f, 40, 30, 5), f, case.options)
            relabel(jxr, 0x10)
            val (_, native) = ours(jxr)
            val bitmap = ImageKodec.decode(jxr)
            for (i in bitmap.argb.indices) {
                val a = native[4 * i + 3]
                assertEquals(a, bitmap.argb[i] ushr 24)
                for (c in 0 until 3) {
                    val want = if (a == 0) 0 else minOf(255, (native[4 * i + c] * 255 + a / 2) / a)
                    assertEquals(want, (bitmap.argb[i] shr (16 - 8 * c)) and 0xFF, "pixel $i channel $c")
                }
            }
        }
    }

    /** Puts pixel format [last] of the 6fddc324 family in place of the file's own. */
    private fun relabel(jxr: ByteArray, last: Int) {
        val b = ByteBuffer.wrap(jxr).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = b.getInt(4)
        for (i in 0 until b.getShort(ifd).toInt()) {
            val at = ifd + 2 + 12 * i
            if (b.getShort(at).toInt() and 0xFFFF == 0xBC01) jxr[b.getInt(at + 8) + 15] = last.toByte()
        }
    }

    @Test
    fun facadeAgreesWithTheSamples() {
        assumeTrue(tools())
        for (f in formats) {
            val jxr = encode(source(f, 40, 30, 7), f, listOf("-q", "0.8"))
            val info = ImageKodec.probe(jxr)
            assertEquals(ImageFormat.JXR, info.format)
            assertEquals(40, info.width)
            assertEquals(30, info.height)
            assertEquals(f.spp == 4, info.hasAlpha, f.name)
            assertTrue(info.isDecodable, f.name)
            val bitmap = ImageKodec.decode(jxr)
            val wide = ImageKodec.decode16(jxr)
            assertEquals(wide.toBitmap().argb.toList(), bitmap.argb.toList(), "${f.name}: decode16 then toBitmap is decode")
            val (channels, native) = ours(jxr)
            val linear = f.sampleFormat != 1
            // JxrDecApp cannot convert these itself: asked for 24-bit RGB it decodes a 48-bit format into
            // a 24-bit buffer. Its samples go through its converter's arithmetic (Convert_Float_To_U8 of
            // JXRGluePFC.c) restated here.
            val ref = if (linear) reference(jxr) else null
            var worst = 0
            for (i in bitmap.argb.indices) {
                val p = bitmap.argb[i]
                val argb = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, p ushr 24)
                if (ref != null) {
                    for (c in 0 until ref.spp) {
                        val raw = ref.samples[i * ref.spp + c]
                        val v = when (f.sampleFormat) {
                            2 -> raw / 8192.0
                            else -> if (f.bits == 16) halfValue(raw) else Float.fromBits(raw).toDouble()
                        }
                        val alpha = ref.spp == 4 && c == 3
                        val want = if (alpha) toU8(v, alpha = true) else toU8(v, alpha = false)
                        val got = if (ref.spp == 1) argb[0] else argb[c]
                        worst = maxOf(worst, abs(got - want))
                    }
                } else if (channels == 1 && f.spp == 3) {
                    // A packed pixel widens each field by repeating its bits.
                    val v = native[i]
                    val fields = when {
                        f.name.endsWith("555") -> intArrayOf((v shr 10) and 31, (v shr 5) and 31, v and 31).map { (it shl 3) or (it shr 2) }
                        f.name.endsWith("565") -> listOf(((v shr 11) and 31).let { (it shl 3) or (it shr 2) }, ((v shr 5) and 63).let { (it shl 2) or (it shr 4) }, (v and 31).let { (it shl 3) or (it shr 2) })
                        else -> intArrayOf((v shr 20) and 1023, (v shr 10) and 1023, v and 1023).map { it shr 2 }
                    }
                    for (c in 0 until 3) worst = maxOf(worst, abs(argb[c] - fields[c]))
                } else {
                    for (c in 0 until channels) {
                        val want = if (f.bits == 16) native[i * channels + c] ushr 8 else native[i * channels + c]
                        val got = if (channels == 1) argb[0] else argb[c]
                        worst = maxOf(worst, abs(got - want))
                    }
                }
            }
            val code = if (linear) "jxrlib's conversion of JxrDecApp's samples" else "the samples"
            assertTrue(if (linear) worst <= 1 else worst == 0, "${f.name}: decode is $worst levels from $code")
        }
    }
}
