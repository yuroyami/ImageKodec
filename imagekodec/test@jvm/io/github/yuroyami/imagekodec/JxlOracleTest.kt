package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.jxl.JxlDecoder
import io.github.yuroyami.imagekodec.codec.jxl.JxlFrameHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JPEG XL files (#42) written by libjxl's cjxl, read through [ImageKodec] and compared with
 * libjxl's djxl: both coding modes over a range of efforts and distances, each coding tool
 * cjxl turns on by request, 16-bit samples, recompressed JPEGs and animations.
 *
 * A lossless file has to read exactly. For a lossy file djxl is asked for 16 bits, because it
 * dithers its 8-bit output. A file of 8 bits then has to give the 8-bit level nearest to
 * djxl's sample, and a file of 16 bits has to stay within a few 65535ths of it.
 *
 * The decoder follows libjxl 0.11, and those bounds are for that djxl. An older one, such as
 * the 0.7.0 of Ubuntu 24.04, has other command-line options and other rounding, so it is
 * given only the plain cases, at 8 bits, with a bound of two levels, and no 16-bit file
 * with alpha, which its PNG writer refuses.
 * Skips cleanly without cjxl, djxl and cjpeg.
 */
class JxlOracleTest {

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

    private fun temp(ext: String) = File.createTempFile("kite-jxl", ext).apply { deleteOnExit() }

    private fun tools(): Boolean = Tools.hasAll("cjxl", "djxl", "cjpeg")

    /** True when the installed libjxl is 0.11 or newer, the version this decoder follows. */
    private val current: Boolean by lazy {
        val text = run(Tools.require("cjxl").path, "--version").second
        val match = Regex("""v(\d+)\.(\d+)""").find(text) ?: return@lazy false
        val (major, minor) = match.destructured
        major.toInt() > 0 || minor.toInt() >= 11
    }

    /** One way to call cjxl. [plain] marks a case that an old cjxl accepts and an old djxl reads alike. */
    private class Case(
        val name: String,
        val options: List<String>,
        val plain: Boolean = false,
        /** What the frame headers of the file must show, so the case fails when cjxl leaves its tool off. */
        val uses: ((List<JxlFrameHeader>) -> Boolean)? = null,
    )

    private fun case(name: String, vararg options: String, plain: Boolean = false, uses: ((List<JxlFrameHeader>) -> Boolean)? = null) =
        Case(name, options.toList(), plain, uses)

    /** [source] encoded with [options], or null when an old cjxl does not take them. */
    private fun encode(source: File, options: List<String>): ByteArray? {
        val out = temp(".jxl")
        val (code, text) = run(Tools.require("cjxl").path, source.path, out.path, *options.toTypedArray())
        if (code != 0) {
            assertTrue(!current, "cjxl ${options.joinToString(" ")} failed: $text")
            return null
        }
        return out.readBytes()
    }

    /** djxl's reading of [jxl]: at 16 bits from the djxl this decoder follows, at the file's depth from an older one. */
    private fun reference(jxl: ByteArray, vararg options: String): KiteBitmap16 {
        val input = temp(".jxl").apply { writeBytes(jxl) }
        val out = temp(".png")
        val depth = if (current) arrayOf("--bits_per_sample=16") else emptyArray()
        val (code, text) = run(Tools.require("djxl").path, input.path, out.path, *depth, *options)
        assertEquals(0, code, "djxl could not read the file: $text")
        return ImageKodec.decode16(out.readBytes())
    }

    /**
     * Holds this decoder's reading of [jxl] to djxl's, each sample within [tolerance] 65535ths.
     * With [premultiplied] the colour is compared multiplied by its alpha: djxl gives a
     * premultiplied file's colour as stored, and this decoder divides it by alpha. A colour
     * above its alpha is no premultiplied colour and divides to more than white, so it is passed over.
     */
    private fun check(name: String, jxl: ByteArray, tolerance: Int, premultiplied: Boolean = false, vararg djxlOptions: String) {
        val want = reference(jxl, *djxlOptions)
        val got = ImageKodec.decode16(jxl, applyOrientation = true)
        assertEquals(Triple(want.width, want.height, want.channels), Triple(got.width, got.height, got.channels), "$name shape")
        val ch = got.channels
        var worst = 0
        var at = 0
        var compared = 0
        for (i in want.samples.indices) {
            var g = got.samples[i].toInt() and 0xFFFF
            val w = want.samples[i].toInt() and 0xFFFF
            if (premultiplied && i % ch != ch - 1) {
                val a = got.samples[i - i % ch + ch - 1].toInt() and 0xFFFF
                if (w > a) continue
                g = ((g.toLong() * a + 32767) / 65535).toInt()
            }
            compared++
            val d = abs(g - w)
            if (d > worst) {
                worst = d
                at = i
            }
        }
        assertTrue(worst <= tolerance, "$name differs from djxl by $worst of 65535 at pixel ${at / ch}, channel ${at % ch}")
        assertTrue(compared * 2 >= want.samples.size, "$name: only $compared of ${want.samples.size} samples were compared")
        assertContentEquals(
            ImageKodec.decode(jxl, applyOrientation = true).argb, got.toBitmap().argb,
            "$name: decode16 does not narrow to decode",
        )
        val info = ImageKodec.probe(jxl)
        assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
        assertEquals(want.width to want.height, info.displayWidth to info.displayHeight, "$name probed size")
        assertEquals(want.hasAlpha, info.hasAlpha, "$name probed alpha")
    }

    /** The bound for a lossy file whose samples have [bits] bits. */
    private fun lossyBound(bits: Int): Int = when {
        !current -> 2 * 257
        // The nearest 8-bit level is at most half a level away; the rest is the bound of a 16-bit file.
        bits <= 8 -> 128 + 48
        else -> 48
    }

    /** Runs [cases] on [source] and gives how many were checked. */
    private fun runCases(label: String, source: File, base: List<String>, cases: List<Case>, tolerance: Int, checkTools: Boolean): Int {
        var ran = 0
        for (c in cases) {
            if (!current && !c.plain) continue
            val jxl = encode(source, base + c.options) ?: continue
            if (checkTools && current && c.uses != null) {
                assertTrue(c.uses.invoke(JxlDecoder.frameHeaders(jxl)), "$label ${c.name}: cjxl left out what the case is there for")
            }
            check("$label ${c.name}", jxl, tolerance)
            ran++
        }
        return ran
    }

    /** A card of edges, flat areas and a little grain, which the lossless tools take apart well. */
    private fun card(w: Int, h: Int, alpha: Boolean, gray: Boolean): KiteBitmap {
        var seed = 12345
        fun rnd(): Int {
            seed = seed * 1103515245 + 12345
            return (seed ushr 16) and 0xFF
        }
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var r = (x * 255 / w + (rnd() and 7)) and 0xFF
            var g = (y * 255 / h + (rnd() and 3)) and 0xFF
            var b = ((x + y) * 2 + (if ((x / 16 + y / 16) % 2 == 0) 60 else 0)) and 0xFF
            if (x in 40..90 && y in 30..70) {
                r = 200
                g = 30
                b = 30
            }
            if (gray) {
                g = r
                b = r
            }
            val inside = (x - w / 2) * (x - w / 2) + (y - h / 2) * (y - h / 2) < h * h / 5
            argb(if (!alpha || inside) 255 else x * 255 / w, r, g, b)
        })
    }

    /** A picture of waves, ramps, soft noise and a few hard edges, as a photograph has. */
    private fun photo(w: Int, h: Int, alpha: Boolean, gray: Boolean): KiteBitmap {
        val random = kotlin.random.Random(7)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val n = random.nextInt(-6, 7)
            val wave = (40 * kotlin.math.sin(x / 9.0) * kotlin.math.cos(y / 13.0)).toInt()
            val edge = if ((x / 37 + y / 29) % 3 == 0) 50 else 0
            var r = (x * 255 / w + n + edge + wave).coerceIn(0, 255)
            var g = (y * 255 / h - n + wave / 2).coerceIn(0, 255)
            var b = (((x + 2 * y) / 3) % 256 + n).coerceIn(0, 255)
            if ((x - 60) * (x - 60) + (y - 50) * (y - 50) < 900) {
                r = 230
                g = 220
                b = 40
            }
            if (gray) {
                g = r
                b = r
            }
            argb(if (!alpha || y < h / 3) 255 else x * 255 / w, r, g, b)
        })
    }

    /** A white page with one small glyph repeated over it, which cjxl stores once as a patch. */
    private fun page(w: Int, h: Int): KiteBitmap {
        val glyph = listOf("01110", "10001", "10111", "10001", "01110")
        val argb = IntArray(w * h) { -1 }
        for (top in 4 until h - 8 step 9) for (left in 4 until w - 8 step 8) {
            for (j in glyph.indices) for (i in 0 until 5) if (glyph[j][i] == '1') argb[(top + j) * w + left + i] = 0xFF000000.toInt()
        }
        return KiteBitmap(w, h, argb)
    }

    /** [bitmap] as a file every cjxl reads: a PGM when [gray], or else a PNG. */
    private fun write(bitmap: KiteBitmap, gray: Boolean = false): File {
        if (!gray) return temp(".png").apply { writeBytes(ImageKodec.encodePng(bitmap)) }
        val out = ByteArrayOutputStream()
        out.write("P5\n${bitmap.width} ${bitmap.height}\n255\n".toByteArray())
        for (p in bitmap.argb) out.write(p and 0xFF)
        return temp(".pgm").apply { writeBytes(out.toByteArray()) }
    }

    /** A 16-bit picture with [channels] channels as a PNG: gray, gray with alpha, RGB or RGBA. */
    private fun deep(w: Int, h: Int, channels: Int): File {
        val random = kotlin.random.Random(11)
        val raw = ByteArrayOutputStream()
        fun put(v: Int) {
            val c = v.coerceIn(0, 65535)
            raw.write(c ushr 8)
            raw.write(c)
        }
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                val n = random.nextInt(-900, 901)
                val wave = (9000 * kotlin.math.sin(x / 9.0) * kotlin.math.cos(y / 13.0)).toInt()
                val edge = if ((x / 37 + y / 29) % 3 == 0) 12000 else 0
                val inDisc = (x - 60) * (x - 60) + (y - 50) * (y - 50) < 900
                put(if (inDisc) 60000 else x * 65535 / w + n + edge + wave)
                if (channels >= 3) {
                    put(y * 65535 / h - n + wave / 2)
                    put(((x + 2 * y) * 97) % 65536 + n)
                }
                if (channels % 2 == 0) put(if (y < h / 3) 65535 else x * 65535 / w)
            }
        }
        val packed = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
        val out = ByteArrayOutputStream()
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun chunk(type: String, body: ByteArray) {
            val crc = CRC32().apply {
                update(type.toByteArray())
                update(body)
            }
            out.write(be32(body.size))
            out.write(type.toByteArray())
            out.write(body)
            out.write(be32(crc.value.toInt()))
        }
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val colorType = intArrayOf(0, 4, 2, 6)[channels - 1]
        chunk("IHDR", be32(w) + be32(h) + byteArrayOf(16, colorType.toByte(), 0, 0, 0))
        chunk("IDAT", packed.toByteArray())
        chunk("IEND", ByteArray(0))
        return temp(".png").apply { writeBytes(out.toByteArray()) }
    }

    private val losslessCases = listOf(
        case("effort 1", "-e", "1", plain = true),
        case("effort 2", "-e", "2"),
        case("effort 3", "-e", "3", plain = true),
        case("effort 4", "-e", "4"),
        case("effort 7", "-e", "7", plain = true),
        case("effort 9", "-e", "9"),
        case("small groups", "-e", "5", "-g", "0"),
        case("large groups", "-e", "5", "-g", "3"),
        case("squeeze", "-e", "5", "-R", "1"),
        case("palette", "-e", "7", "--modular_palette_colors=70000"),
        case("delta palette", "-e", "7", "--modular_lossy_palette", "--modular_palette_colors=0"),
        case("earlier channels", "-e", "7", "-E", "3"),
        case("gradient predictor", "-e", "5", "-P", "5"),
        case("weighted predictor", "-e", "5", "-P", "6"),
        case("average predictor", "-e", "5", "-P", "13"),
        case("no colour transform", "-e", "5", "-C", "0"),
        case("colour transform 10", "-e", "5", "-C", "10"),
        case("colour transform 25", "-e", "5", "-C", "25"),
        case("progressive", "-e", "5", "-p"),
    )

    @Test
    fun losslessFilesReadExactly() {
        assumeTrue(tools())
        val lossless = listOf("-d", "0")
        var ran = runCases("rgb", write(card(300, 270, alpha = false, gray = false)), lossless, losslessCases, 0, checkTools = true)
        ran += runCases("rgba", write(card(300, 270, alpha = true, gray = false)), lossless, losslessCases, 0, checkTools = false)
        ran += runCases("gray", write(card(300, 270, alpha = false, gray = true), gray = true), lossless, losslessCases, 0, checkTools = false)
        ran += runCases("small", write(card(37, 21, alpha = true, gray = false)), lossless, losslessCases.take(6), 0, checkTools = false)
        // A page of repeated glyphs: the lossless mode stores the glyph once as well.
        ran += runCases(
            "page", write(page(96, 96)), lossless,
            listOf(case("patches", "--patches=1", uses = { f -> f.any { it.hasPatches } })), 0, checkTools = true,
        )
        assertTrue(ran >= if (current) 4 * 19 - 13 + 1 else 9, "only $ran lossless cases ran")
    }

    private val lossyCases = listOf(
        case("distance 1", "-d", "1", plain = true, uses = { f -> !f.last().modular && f.last().colorTransform == JxlFrameHeader.XYB }),
        case("distance 0.5", "-d", "0.5", plain = true),
        case("distance 3", "-d", "3", plain = true),
        case("distance 8", "-d", "8", plain = true),
        case("distance 15", "-d", "15"),
        case("effort 1", "-d", "1", "-e", "1"),
        case("effort 3", "-d", "1", "-e", "3", plain = true),
        case("effort 4", "-d", "1", "-e", "4", plain = true),
        case("effort 9", "-d", "2", "-e", "9"),
        case("passes", "-d", "1", "-p", plain = true, uses = { f -> f.last().numPasses > 1 }),
        case("LF frame", "-d", "1", "--progressive_dc=1", uses = { f -> f.first().type == JxlFrameHeader.LF_FRAME && f.last().usesLfFrame }),
        case("quantized passes", "-d", "1", "--qprogressive_ac", uses = { f -> f.last().numPasses > 1 }),
        case("no edge filter", "-d", "2", "--epf=0", uses = { f -> f.last().loopFilter.epfIters == 0 }),
        case("edge filter 1", "-d", "2", "--epf=1", uses = { f -> f.last().loopFilter.epfIters == 1 }),
        case("edge filter 3", "-d", "2", "--epf=3", uses = { f -> f.last().loopFilter.epfIters == 3 }),
        case("no gaborish", "-d", "2", "--gaborish=0", uses = { f -> !f.last().loopFilter.gab }),
        case("noise", "-d", "2", "--noise=1", uses = { f -> f.last().hasNoise }),
        case("photon noise", "-d", "2", "--photon_noise_iso=3200", uses = { f -> f.last().hasNoise }),
        case("dots", "-d", "2", "--dots=1"),
        case("patches", "-d", "2", "--patches=1"),
        case("half size", "-d", "1", "--resampling=2", uses = { f -> f.last().upsampling == 2 }),
        case("quarter size", "-d", "1", "--resampling=4", uses = { f -> f.last().upsampling == 4 }),
        case("eighth size", "-d", "1", "--resampling=8", uses = { f -> f.last().upsampling == 8 }),
        case("half size alpha", "-d", "1", "--ec_resampling=2"),
        case("faster decoding", "-d", "1", "--faster_decoding=4"),
        case("modular", "-d", "1", "-m", "1", plain = true, uses = { f -> f.last().modular && f.last().colorTransform == JxlFrameHeader.XYB }),
        case("modular distance 4", "-d", "4", "-m", "1"),
        case("modular progressive", "-d", "1", "-m", "1", "-p"),
    )

    @Test
    fun lossyFilesReadAsDjxlReadsThem() {
        assumeTrue(tools())
        val bound = lossyBound(8)
        val few = lossyCases.filter { it.name in setOf("distance 1", "distance 8", "passes", "LF frame", "edge filter 3", "noise", "half size", "modular") }
        var ran = runCases("rgb", write(photo(300, 270, alpha = false, gray = false)), emptyList(), lossyCases, bound, checkTools = true)
        ran += runCases("rgba", write(photo(300, 270, alpha = true, gray = false)), emptyList(), lossyCases, bound, checkTools = false)
        ran += runCases("gray", write(photo(300, 270, alpha = false, gray = true), gray = true), emptyList(), lossyCases, bound, checkTools = false)
        ran += runCases("small", write(photo(37, 21, alpha = false, gray = false)), emptyList(), lossyCases, bound, checkTools = false)
        ran += runCases("tiny", write(photo(5, 3, alpha = false, gray = false)), emptyList(), few, bound, checkTools = false)
        // Wide enough for several groups in a row, and large enough for the largest transforms.
        ran += runCases("wide", write(photo(2200, 70, alpha = false, gray = false)), emptyList(), few, bound, checkTools = false)
        ran += runCases("large", write(photo(700, 600, alpha = false, gray = false)), emptyList(), few, bound, checkTools = false)
        ran += runCases(
            "page", write(page(128, 128)), emptyList(),
            listOf(case("patches", "-d", "1", "--patches=1", uses = { f -> f.first().type == JxlFrameHeader.REFERENCE_ONLY && f.last().hasPatches })),
            bound, checkTools = true,
        )
        assertTrue(ran >= if (current) 4 * 28 + 3 * 8 + 1 else 30, "only $ran lossy cases ran")
    }

    @Test
    fun sixteenBitFilesKeepTheirPrecision() {
        assumeTrue(tools())
        val exact = listOf(case("lossless", "-d", "0", plain = true), case("lossless effort 9", "-d", "0", "-e", "9"))
        val lossy = lossyCases.filter {
            it.name in setOf(
                "distance 1", "distance 0.5", "distance 8", "effort 3", "effort 9", "passes", "LF frame", "no edge filter", "edge filter 3",
                "no gaborish", "noise", "photon noise", "half size", "quarter size", "half size alpha", "modular", "modular progressive",
            )
        } + listOf(
            case("two LF frames", "-d", "1", "--progressive_dc=2", uses = { f -> f.count { it.type == JxlFrameHeader.LF_FRAME } == 2 }),
            case("half size with noise", "-d", "1", "--resampling=2", "--photon_noise_iso=6400"),
            case("quarter size alpha", "-d", "1", "--ec_resampling=4", "--resampling=2"),
            case("modular with noise", "-d", "1", "-m", "1", "--photon_noise_iso=6400"),
            case("large groups", "-d", "1", "-g", "3"),
        )
        var ran = 0
        for ((label, channels) in listOf("rgb" to 3, "rgba" to 4, "gray" to 1, "gray with alpha" to 2)) {
            // djxl before 0.11 cannot write a 16-bit picture with alpha to PNG.
            if (!current && channels % 2 == 0) continue
            val source = deep(if (channels == 2) 130 else 300, if (channels == 2) 90 else 270, channels)
            ran += runCases("16-bit $label", source, emptyList(), exact, 0, checkTools = false)
            ran += runCases("16-bit $label", source, emptyList(), lossy, lossyBound(16), checkTools = channels == 3)
        }
        assertTrue(ran >= if (current) 4 * 24 else 2 * 6, "only $ran 16-bit cases ran")
        if (!current) return
        // cjxl marks the alpha as premultiplied and stores the colour as it is given.
        val premultiplied = encode(deep(300, 270, 4), listOf("-d", "1", "--premultiply=1"))!!
        check("16-bit premultiplied", premultiplied, 3 * 257, premultiplied = true)
    }

    /** A PNM file of random blocks and ramps whose samples run to [max]: gray, or RGB with [colour]. */
    private fun shallow(w: Int, h: Int, max: Int, colour: Boolean): File {
        val random = kotlin.random.Random(max)
        val raw = ByteArrayOutputStream()
        raw.write("${if (colour) "P6" else "P5"}\n$w $h\n$max\n".toByteArray())
        for (y in 0 until h) for (x in 0 until w) repeat(if (colour) 3 else 1) { c ->
            val v = if (x / 16 % 2 == 0) (x + y * (c + 1)) * max / (w + h * 3) else random.nextInt(max + 1)
            if (max > 255) raw.write(v ushr 8)
            raw.write(v)
        }
        return temp(if (colour) ".ppm" else ".pgm").apply { writeBytes(raw.toByteArray()) }
    }

    @Test
    fun samplesOfOtherDepthsReadAsDjxlReadsThem() {
        // cjxl before 0.11 reads a PNM file wrong when its samples are not of 8 or 16 bits.
        assumeTrue(tools() && current)
        for ((bits, colour) in listOf(1 to false, 2 to false, 5 to true, 7 to false, 10 to true, 12 to false)) {
            val source = shallow(120, 90, (1 shl bits) - 1, colour)
            val name = "$bits-bit ${if (colour) "rgb" else "gray"}"
            // A file of 8 bits or fewer is read at 8 bits, so a level that 8 bits cannot hold is half a level
            // away. Above 8 bits a sample repeats its high bits below, which is within 2 of djxl's scaling.
            val lossless = if (bits > 8) 2 else if (255 % ((1 shl bits) - 1) == 0) 0 else 128
            check("$name lossless", encode(source, listOf("-d", "0"))!!, lossless)
            check("$name lossy", encode(source, listOf("-d", "1"))!!, lossyBound(bits) + lossless)
        }
    }

    @Test
    fun recompressedJpegsReadAsDjxlReadsThem() {
        assumeTrue(tools())
        val bitmap = photo(301, 203, alpha = false, gray = false)
        val ppm = ByteArrayOutputStream()
        ppm.write("P6\n${bitmap.width} ${bitmap.height}\n255\n".toByteArray())
        for (p in bitmap.argb) {
            ppm.write(p ushr 16)
            ppm.write(p ushr 8)
            ppm.write(p)
        }
        val source = temp(".ppm").apply { writeBytes(ppm.toByteArray()) }
        val kinds = listOf(
            "4:4:4" to listOf("-sample", "1x1"),
            "4:2:0" to listOf("-sample", "2x2"),
            "4:2:2" to listOf("-sample", "2x1"),
            "4:4:0" to listOf("-sample", "1x2"),
            "gray" to listOf("-grayscale"),
            "progressive" to listOf("-progressive"),
            "quality 30" to listOf("-quality", "30"),
            "RGB" to listOf("-rgb"),
        )
        for ((name, options) in kinds) {
            val jpeg = temp(".jpg")
            val (code, text) = run(Tools.require("cjpeg").path, *options.toTypedArray(), "-outfile", jpeg.path, source.path)
            assertEquals(0, code, "cjpeg failed: $text")
            // Without a distance cjxl keeps a JPEG's own coefficients.
            val jxl = encode(jpeg, emptyList()) ?: error("cjxl could not recompress the $name JPEG")
            val frame = JxlDecoder.frameHeaders(jxl).single()
            assertTrue(!frame.modular && frame.colorTransform != JxlFrameHeader.XYB, "JPEG $name was not recompressed")
            if (name == "4:4:4" || name == "gray" || name == "RGB") assertTrue(frame.is444) else if (name != "progressive" && name != "quality 30") assertTrue(!frame.is444)
            check("JPEG $name", jxl, lossyBound(8))
            // The picture is also the JPEG's own, on average. A JPEG decoder clips Y, Cb and Cr after the inverse
            // transform and libjxl clips only the RGB they give, so at a hard edge single samples are far apart.
            val direct = ImageKodec.decode(jpeg.readBytes())
            val ours = ImageKodec.decode(jxl)
            var sum = 0L
            for (i in ours.argb.indices) for (shift in intArrayOf(0, 8, 16)) {
                sum += abs(((ours.argb[i] ushr shift) and 255) - ((direct.argb[i] ushr shift) and 255))
            }
            val mean = sum.toDouble() / (ours.argb.size * 3)
            assertTrue(mean <= 3.0, "JPEG $name is $mean away on average from the JPEG decoder's reading")
        }
    }

    @Test
    fun animationsPlayAsDjxlPlaysThem() {
        assumeTrue(tools())
        // An old djxl does not write every frame of an animation.
        assumeTrue(current)
        val frames = List(6) { f ->
            val bitmap = photo(160, 120, alpha = f % 2 == 1, gray = false)
            for (y in 20 until 60) for (x in 10 + f * 20 until 50 + f * 20) bitmap.argb[y * 160 + x] = argb(255, 40 * f, 255 - 40 * f, 90)
            KiteFrame(bitmap, delayMillis = 40 + 70 * f, delayRawCentiseconds = 4 + 7 * f)
        }
        val animation = KiteAnimation(160, 120, frames, loopCount = 3)
        val sources = listOf(
            "GIF" to temp(".gif").apply { writeBytes(ImageKodec.encodeGif(animation)) },
            "APNG" to temp(".png").apply { writeBytes(ImageKodec.encodePng(animation)) },
        )
        val cases = listOf(
            case("lossless", "-d", "0"),
            case("lossless effort 7", "-d", "0", "-e", "7"),
            case("distance 1", "-d", "1"),
            case("distance 3 effort 3", "-d", "3", "-e", "3"),
            case("modular", "-d", "1", "-m", "1"),
            case("patches", "-d", "1", "--patches=1"),
        )
        for ((kind, source) in sources) for (c in cases) {
            val name = "$kind ${c.name}"
            val jxl = encode(source, c.options)!!
            val input = temp(".jxl").apply { writeBytes(jxl) }
            val out = temp(".apng")
            val (code, text) = run(Tools.require("djxl").path, input.path, out.path)
            assertEquals(0, code, "djxl could not read the $name animation: $text")
            val want = ImageKodec.decodeAnimation(out.readBytes())
            val got = ImageKodec.decodeAnimation(jxl)
            assertEquals(6, got.frames.size, "$name frames")
            assertEquals(want.frames.size, got.frames.size, "$name frames")
            assertEquals(6, ImageKodec.probe(jxl).frameCount, "$name probed frames")
            // cjxl keeps neither a GIF's nor an APNG's play count, so the file plays for ever.
            assertEquals(0L, got.loopCount, "$name loops")
            assertEquals(want.loopCount, got.loopCount, "$name loops")
            assertEquals(got.loopCount, ImageKodec.probe(jxl).loopCount, "$name probed loops")
            assertEquals(frames.map { it.delayMillis }, got.frames.map { it.delayMillis }, "$name delays")
            assertEquals(want.frames.map { it.delayMillis }, got.frames.map { it.delayMillis }, "$name delays")
            // djxl dithers the 8 bits it writes into an APNG, so even a lossless frame that blends by alpha may sit one level away.
            for (i in got.frames.indices) {
                val a = got.frames[i].bitmap.argb
                val b = want.frames[i].bitmap.argb
                var worst = 0
                for (k in a.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
                    worst = maxOf(worst, abs(((a[k] ushr shift) and 255) - ((b[k] ushr shift) and 255)))
                }
                assertTrue(worst <= 1, "$name frame $i is $worst away from djxl's")
            }
            assertContentEquals(got.frames[0].bitmap.argb, ImageKodec.decode(jxl).argb, "$name: the still is not the first frame")
            assertEquals(2, ImageKodec.decodeAnimation(jxl, maxFrames = 2).frames.size)
        }
    }

    /**
     * The conformance files of the JPEG XL project and libjxl's own test files, for a checkout
     * that has them: `reference/REFERENCES.md` says where they come from. They are not committed,
     * so this skips everywhere else, CI included.
     */
    @Test
    fun theConformanceFilesReadAsDjxlReadsThem() {
        val roots = listOf("jxl-conformance/testcases", "jxl-testdata/jxl").map { File("../reference/$it") }
        assumeTrue(roots.all { it.isDirectory })
        assumeTrue(Tools.find("cjxl") != null && Tools.find("djxl") != null && Tools.find("jxlinfo") != null && current)
        var ran = 0
        for (root in roots) for (file in root.walkTopDown().filter { it.extension == "jxl" }.sortedBy { it.path }) {
            val name = file.path.removePrefix("../reference/")
            val data = file.readBytes()
            val described = run(Tools.require("jxlinfo").path, "-v", file.path).second
            val premultiplied = described.contains("alpha_premultiplied: 1")
            // A lossy file with only an ICC profile: this decoder gives sRGB, and djxl the profile unless it is told.
            val space = when {
                !described.contains("ICC profile") || !described.contains(", lossy,") -> emptyArray()
                described.contains("Grayscale") -> arrayOf("--color_space=Gra_D65_Rel_SRG")
                else -> arrayOf("--color_space=RGB_D65_SRG_Rel_SRG")
            }
            val bits = ImageKodec.probe(data).bitDepth
            check(name, data, if (premultiplied) 3 * 257 else lossyBound(bits), premultiplied, *space)
            ran++
        }
        assertTrue(ran >= 40, "only $ran conformance files were found")
    }

    @Test
    fun theFixturesReadAsTheInstalledDjxlReadsThem() {
        assumeTrue(tools())
        // The fixtures hold what djxl 0.11.1 read. A newer djxl that reads them another way shows here first.
        assumeTrue(current)
        val exact = JxlFixtures.hashes.keys
        for ((name, data) in JxlFixtures.all) {
            if (name == "animation") continue
            val bits = ImageKodec.probe(data).bitDepth
            // A 10-bit sample widens to 16 bits by repeating its high bits here and by scaling in djxl.
            val tolerance = if (name in exact) (if (bits in 9..15) 1 else 0) else lossyBound(bits)
            check("fixture $name", data, tolerance)
        }
    }
}
