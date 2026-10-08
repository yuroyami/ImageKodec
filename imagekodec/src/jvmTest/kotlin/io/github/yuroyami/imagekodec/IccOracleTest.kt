package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.color.Cicp
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conversion to sRGB (#108) against lcms2, through `tools/icc_lcms.c`: it builds profiles of
 * every matrix/TRC and gray kind (v2 and v4, `curv` gamma and tables, all five `para` types)
 * and converts samples through them to its built-in sRGB in floating point with no
 * optimization, in each intent. Every 8-bit sample must land within one level of lcms2's, and
 * every 16-bit sample within a few. Real profiles from `icc-profiles-free` ride along.
 */
class IccOracleTest {

    private fun run(vararg command: String, input: ByteArray? = null): ByteArray {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.PIPE).start()
        if (input != null) process.outputStream.use { it.write(input) } else process.outputStream.close()
        val out = process.inputStream.readBytes()
        val err = process.errorStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "${command.first()} failed: $err")
        return out
    }

    /** The lcms2 oracle, built once from `tools/icc_lcms.c`. */
    private val oracle: File? by lazy {
        val source = listOf(File("../tools/icc_lcms.c"), File("tools/icc_lcms.c")).firstOrNull { it.isFile } ?: return@lazy null
        val out = File.createTempFile("icc_lcms", ".bin").apply { deleteOnExit() }
        val process = ProcessBuilder(Tools.require("cc").path, "-O2", "-o", out.path, source.path, "-llcms2", "-lm")
            .redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        if (process.waitFor() != 0) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("could not build tools/icc_lcms.c against lcms2: $log")
            return@lazy null
        }
        out
    }

    /** Samples that reach every level of each channel, the corners of the cube, and random colours. */
    private fun samples(channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (v in 0..255) repeat(channels) { out.write(v) }
        if (channels >= 3) {
            for (v in 0..255 step 15) for (k in 0 until channels) for (c in 0 until channels) out.write(if (c == k) v else 0)
            for (corner in 0 until (1 shl channels)) for (c in 0 until channels) out.write(if ((corner shr c) and 1 == 1) 255 else 0)
            val random = Random(108)
            repeat(3000) { for (c in 0 until channels) out.write(random.nextInt(256)) }
        }
        return out.toByteArray()
    }

    private fun lcms(profile: File, intent: Int, channels: Int, samples: ByteArray): FloatArray {
        val bytes = run(oracle!!.path, "convert", profile.path, "$intent", "$channels", input = samples)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun check(name: String, icc: ByteArray, file: File, channels: Int) {
        if (channels == 4) return checkInk(name, icc, file)
        val samples = samples(channels)
        val count = samples.size / channels
        val argb = IntArray(count) { i ->
            val r = samples[i * channels].toInt() and 0xFF
            val g = if (channels == 3) samples[i * 3 + 1].toInt() and 0xFF else r
            val b = if (channels == 3) samples[i * 3 + 2].toInt() and 0xFF else r
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = KiteBitmap(count, 1, argb)
        val wide = KiteBitmap16(count, 1, channels, ShortArray(count * channels) { ((samples[it].toInt() and 0xFF) * 257).toShort() })
        val profile = ColorProfile(icc = icc)
        for ((code, intent) in RenderingIntent.entries.withIndex()) {
            val reference = lcms(file, code, channels, samples)
            assertEquals(count * 3, reference.size, "$name $intent")
            val ours = ImageKodec.convertToSrgb(bitmap, profile, intent)
            val ours16 = ImageKodec.convertToSrgb(wide, profile, intent)
            var worst = 0
            var worst16 = 0
            for (i in 0 until count) for (c in 0 until 3) {
                val want = reference[i * 3 + c].toDouble().coerceIn(0.0, 1.0)
                val got = (ours.argb[i] shr (16 - 8 * c)) and 0xFF
                worst = maxOf(worst, abs(got - Math.round(want * 255).toInt()))
                val got16 = ours16.samples[i * ours16.channels + (if (ours16.channels < 3) 0 else c)].toInt() and 0xFFFF
                worst16 = maxOf(worst16, abs(got16 - Math.round(want * 65535).toInt()))
            }
            assertTrue(worst <= 1, "$name, $intent: an 8-bit sample is $worst levels from lcms2's")
            assertTrue(worst16 <= 4, "$name, $intent: a 16-bit sample is $worst16 levels from lcms2's")
        }
    }

    private fun lcms16(profile: File, intent: Int, channels: Int, samples: ShortArray): FloatArray {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (v in samples) bytes.putShort(v)
        val out = run(oracle!!.path, "convert", profile.path, "$intent", "$channels", "2", input = bytes.array())
        val buffer = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** The worst distance of [rgb] (opaque ARGB) from lcms2's [reference], in 8-bit levels. */
    private fun worst8(rgb: IntArray, reference: FloatArray): Int {
        var worst = 0
        for (i in rgb.indices) for (c in 0 until 3) {
            val want = Math.round(reference[i * 3 + c].toDouble().coerceIn(0.0, 1.0) * 255).toInt()
            worst = maxOf(worst, abs(((rgb[i] shr (16 - 8 * c)) and 0xFF) - want))
        }
        return worst
    }

    /** The worst distance of [bitmap]'s colour from lcms2's [reference], in 16-bit levels. */
    private fun worst16(bitmap: KiteBitmap16, reference: FloatArray): Int {
        var worst = 0
        for (i in 0 until bitmap.width * bitmap.height) for (c in 0 until 3) {
            val want = Math.round(reference[i * 3 + c].toDouble().coerceIn(0.0, 1.0) * 65535).toInt()
            worst = maxOf(worst, abs((bitmap.samples[i * bitmap.channels + c].toInt() and 0xFFFF) - want))
        }
        return worst
    }

    /** CMYK ink through the profile's tables, as lcms2's TYPE_CMYK_8 and TYPE_CMYK_16 take it. */
    private fun checkInk(name: String, icc: ByteArray, file: File) {
        val samples = samples(4)
        val count = samples.size / 4
        val random = Random(4)
        val wide = ShortArray(samples.size) { random.nextInt(65536).toShort() }
        val profile = ColorProfile(icc = icc)
        for ((code, intent) in RenderingIntent.entries.withIndex()) {
            val ours = ImageKodec.convertCmykToSrgb(count, 1, samples, profile, intent)
            assertTrue(ours != null, "$name, $intent: no CMYK conversion")
            val worst = worst8(ours.argb, lcms(file, code, 4, samples))
            assertTrue(worst <= 1, "$name, $intent: an 8-bit sample is $worst levels from lcms2's")
            val ours16 = ImageKodec.convertCmykToSrgb(count, 1, wide, profile, intent)!!
            val worst16 = worst16(ours16, lcms16(file, code, 4, wide))
            assertTrue(worst16 <= 4, "$name, $intent: a 16-bit sample is $worst16 levels from lcms2's")
        }
        // An RGB bitmap does not go through a CMYK profile.
        val bitmap = KiteBitmap(1, 1, intArrayOf(0xFF336699.toInt()))
        assertTrue(ImageKodec.convertToSrgb(bitmap, profile) === bitmap)
    }

    @Test
    fun lutProfilesMatchLcms2() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val dir = kotlin.io.path.createTempDirectory("imagekodec-icc").toFile()
        try {
            for (kind in listOf("rgb-lut8-v2", "rgb-xyz-v2", "rgb-mab-v4", "cmyk-v2", "cmyk-v4", "cmyk-tiny-v2", "cmyk-tiny-v4")) {
                val file = File(dir, "$kind.icc").apply { writeBytes(run(oracle!!.path, "make", kind)) }
                check(kind, file.readBytes(), file, if (kind.startsWith("cmyk")) 4 else 3)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The profiles of [kinds], made by the oracle in a temporary folder, to [body]. */
    private fun withProfiles(vararg kinds: String, body: (Map<String, File>) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("imagekodec-icc").toFile()
        try {
            body(kinds.associateWith { kind -> File(dir, "$kind.icc").apply { writeBytes(run(oracle!!.path, "make", kind)) } })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cmykTiffsConvertThroughTheirProfile() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val random = Random(51)
        val w = 37
        val h = 23
        val ink = IntArray(w * h * 4) { random.nextInt(256) }
        val ink16 = IntArray(w * h * 4) { random.nextInt(65536) }
        withProfiles("cmyk-v2", "cmyk-v4") { profiles ->
            for ((kind, file) in profiles) {
                val icc = file.readBytes()
                val tiff = CmykTiffs.tiff(w, h, ink, icc = icc)
                val tiff16 = CmykTiffs.tiff(w, h, ink16, bits = 16, icc = icc)
                assertEquals("CMYK", ImageKodec.probe(tiff).colorProfile?.iccColorSpace)
                for ((code, intent) in RenderingIntent.entries.withIndex()) {
                    val target = ColorTarget.Srgb(intent)
                    val ours = ImageKodec.decode(tiff, colorTarget = target)
                    val worst = worst8(ours.argb, lcms(file, code, 4, ByteArray(ink.size) { ink[it].toByte() }))
                    assertTrue(worst <= 1, "$kind, $intent: an 8-bit TIFF sample is $worst levels from lcms2's")
                    val ours16 = ImageKodec.decode16(tiff16, colorTarget = target)
                    assertEquals(3, ours16.channels)
                    val worst16 = worst16(ours16, lcms16(file, code, 4, ShortArray(ink16.size) { ink16[it].toShort() }))
                    assertTrue(worst16 <= 4, "$kind, $intent: a 16-bit TIFF sample is $worst16 levels from lcms2's")
                    // Every entry point converts the same way, separate planes too.
                    assertContentEquals(ours.argb, ImageKodec.decodePage(tiff, 0, colorTarget = target).argb)
                    assertContentEquals(ours.argb, ImageKodec.decodeAnimation(tiff, colorTarget = target).frames[0].bitmap.argb)
                    val planar = CmykTiffs.tiff(w, h, CmykTiffs.planes(ink, 4), planar = true, icc = icc)
                    assertContentEquals(ours.argb, ImageKodec.decode(planar, colorTarget = target).argb)
                }
                // Alpha rides along, and a reduced decode reduces the ink, then converts.
                val withAlpha = IntArray(w * h * 5) { i -> if (i % 5 == 4) 255 - (i / 5) % 256 else ink[(i / 5) * 4 + i % 5] }
                val alpha = ImageKodec.decode(CmykTiffs.tiff(w, h, withAlpha, extraSamples = 2, icc = icc), colorTarget = ColorTarget.Srgb())
                val opaque = ImageKodec.decode(tiff, colorTarget = ColorTarget.Srgb())
                for (i in 0 until w * h) {
                    assertEquals((255 - i % 256) shl 24 or (opaque.argb[i] and 0xFFFFFF), alpha.argb[i], "$kind, alpha at pixel $i")
                }
                val reduced = ImageKodec.decodeReduced(tiff, 4, colorTarget = ColorTarget.Srgb())
                assertEquals((w + 3) / 4, reduced.width)
                val block = ByteArray(4) { c -> ((0 until 4).sumOf { y -> (0 until 4).sumOf { x -> ink[(y * w + x) * 4 + c] } } + 8).div(16).toByte() }
                assertEquals(ImageKodec.convertCmykToSrgb(1, 1, block, ColorProfile(icc = icc))!!.argb[0], reduced.argb[0], "$kind, reduced")
            }
        }
    }

    /** [jpeg] with [icc] in APP2 ICC_PROFILE segments after its SOI, as many as it needs. */
    private fun withIccApp2(jpeg: ByteArray, icc: ByteArray): ByteArray {
        val parts = icc.toList().chunked(65000)
        val segments = ByteArrayOutputStream()
        for ((k, part) in parts.withIndex()) {
            val body = "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0, (k + 1).toByte(), parts.size.toByte()) + part.toByteArray()
            segments.write(byteArrayOf(0xFF.toByte(), 0xE2.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()))
            segments.write(body)
        }
        return jpeg.copyOfRange(0, 2) + segments.toByteArray() + jpeg.copyOfRange(2, jpeg.size)
    }

    /** ImageMagick's reading of a CMYK JPEG's ink, libjpeg's YCCK conversion and Adobe's inversion undone: 0 is no ink. */
    private fun magickInk(jpeg: ByteArray, dir: File): ByteArray {
        val input = File(dir, "in.jpg").apply { writeBytes(jpeg) }
        return run(Tools.require("magick").path, input.path, "-depth", "8", "cmyk:-")
    }

    @Test
    fun cmykJpegsConvertThroughTheirProfile() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        assumeTrue("ImageMagick is not installed", Tools.hasAll("magick"))
        withProfiles("cmyk-v2", "cmyk-v4") { profiles ->
            val dir = profiles.values.first().parentFile
            for ((kind, file) in profiles) {
                for ((name, plain) in listOf(
                    "transform 0" to JpegCmykFixtures.blocksAdobeCmyk,
                    "transform 2" to JpegCmykFixtures.blocksAdobeYcck,
                    "no marker" to JpegCmykFixtures.blocksBare,
                    "split" to JpegCmykFixtures.splitAdobeCmyk,
                )) {
                    val jpeg = withIccApp2(plain, file.readBytes())
                    assertEquals("CMYK", ImageKodec.probe(jpeg).colorProfile?.iccColorSpace, "$kind, $name")
                    val ink = magickInk(jpeg, dir)
                    for ((code, intent) in RenderingIntent.entries.withIndex()) {
                        val target = ColorTarget.Srgb(intent)
                        val ours = ImageKodec.decode(jpeg, colorTarget = target)
                        assertEquals(ink.size / 4, ours.argb.size)
                        // libjpeg and stb round the YCCK conversion and the IDCT their own ways, a level apart.
                        val worst = worst8(ours.argb, lcms(file, code, 4, ink))
                        assertTrue(worst <= 2, "$kind, $name, $intent: a sample is $worst levels from lcms2's")
                        val wide = ImageKodec.decode16(jpeg, colorTarget = target).toBitmap().argb
                        for (i in wide.indices) for (c in 0 until 3) {
                            assertTrue(abs(((wide[i] shr (8 * c)) and 0xFF) - ((ours.argb[i] shr (8 * c)) and 0xFF)) <= 1, "$kind, $name, decode16 at $i")
                        }
                    }
                    // Without a target, the profile changes nothing.
                    assertContentEquals(ImageKodec.decode(plain).argb, ImageKodec.decode(jpeg).argb)
                }
            }
        }
    }

    /** One of PNG's SDR declarations and the lcms2 profile of its primaries and curve. */
    private class Declared(val name: String, val chunks: List<ByteArray>, val primaries: DoubleArray, val gamma: Double)

    /**
     * PNG's `gAMA`, `cHRM` and SDR `cICP` against lcms2 converting through a v4 profile it
     * builds of the same primaries, white and curve (cmsCreateRGBProfile), at 8 and 16 bits,
     * unsaved: saving rounds its colorants to s15Fixed16, which moves a dark channel next to a
     * bright one by a dozen 16-bit levels. A `gAMA` is the inverse of its exponent times
     * 100000, so the profile takes the exponent the chunk holds.
     */
    @Test
    fun pngColorChunksConvertAsLcms2ProfilesOfThem() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val bt709 = doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290)
        val cases = listOf(
            Declared("gAMA 1/2.2", listOf(PngColorFiles.gama(45455)), bt709, 100_000.0 / 45455),
            Declared("gAMA 1", listOf(PngColorFiles.gama(100_000)), bt709, 1.0),
            Declared("cHRM Adobe RGB", listOf(PngColorFiles.chrm(0.3127, 0.3290, 0.64, 0.33, 0.21, 0.71, 0.15, 0.06)),
                doubleArrayOf(0.64, 0.33, 0.21, 0.71, 0.15, 0.06, 0.3127, 0.3290), 0.0),
            Declared("gAMA and cHRM ProPhoto", listOf(PngColorFiles.gama(55556), PngColorFiles.chrm(0.3457, 0.3585, 0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001)),
                doubleArrayOf(0.7347, 0.2653, 0.1596, 0.8404, 0.0366, 0.0001, 0.3457, 0.3585), 100_000.0 / 55556),
            Declared("cICP Display P3", listOf(PngColorFiles.cicp(12, 13)), doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290), 0.0),
            Declared("cICP BT.2020 with BT.1886", listOf(PngColorFiles.cicp(9, 1)), doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046, 0.3127, 0.3290), 2.4),
            Declared("cICP BT.470 M, gamma 2.2", listOf(PngColorFiles.cicp(4, 4)), doubleArrayOf(0.67, 0.33, 0.21, 0.71, 0.14, 0.08, 0.310, 0.316), 2.2),
            Declared("cICP EBU 3213, gamma 2.8", listOf(PngColorFiles.cicp(22, 5)), doubleArrayOf(0.630, 0.340, 0.295, 0.605, 0.155, 0.077, 0.3127, 0.3290), 2.8),
            Declared("cICP BT.709, linear", listOf(PngColorFiles.cicp(1, 8)), bt709, 1.0),
        )
        val samples = samples(3)
        val count = samples.size / 3
        val random = Random(9)
        val wide = ShortArray(count * 3) { random.nextInt(65536).toShort() }
        fun lcmsRgb(case: Declared, bytes: Int, input: ByteArray): FloatArray {
            val args = case.primaries.map { "%.10f".format(it) } + "%.10f".format(case.gamma)
            val out = run(oracle!!.path, "convert-rgb", *args.toTypedArray(), "1", "$bytes", input = input)
            val buffer = ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val wideBytes = ByteBuffer.allocate(wide.size * 2).order(ByteOrder.nativeOrder()).also { b -> wide.forEach { b.putShort(it) } }.array()
        run {
            for (case in cases) {
                val png = PngColorFiles.png(count, 1, 3, 8, IntArray(samples.size) { samples[it].toInt() and 0xFF }, *case.chunks.toTypedArray())
                val ours = ImageKodec.decode(png, colorTarget = ColorTarget.Srgb())
                val worst = worst8(ours.argb, lcmsRgb(case, 1, samples))
                assertTrue(worst <= 1, "${case.name}: an 8-bit sample is $worst levels from lcms2's")
                val png16 = PngColorFiles.png(count, 1, 3, 16, IntArray(wide.size) { wide[it].toInt() and 0xFFFF }, *case.chunks.toTypedArray())
                val reference16 = lcmsRgb(case, 2, wideBytes)
                val ours16 = ImageKodec.decode16(png16, colorTarget = ColorTarget.Srgb())
                val worst16 = worst16(ours16, reference16)
                assertTrue(worst16 <= 4, "${case.name}: a 16-bit sample is $worst16 levels from lcms2's")
                // An 8-bit decode of the 16-bit file converts at 16 bits, then narrows.
                assertContentEquals(ours16.toBitmap().argb, ImageKodec.decode(png16, colorTarget = ColorTarget.Srgb()).argb, case.name)
            }
        }
    }

    /**
     * The light PQ and HLG put out, against zimg (ffmpeg's `zscale` to linear light): PQ's EOTF
     * over 10000 cd/m², colour by colour, and HLG through BT.2100's OOTF over its 1000 cd/m²
     * display. zimg raises each HLG channel to 1.2 on its own, where BT.2100 and libplacebo
     * weigh the pixel by its luminance, so the two agree on grays only, which is what HLG is
     * checked on here; `PngColorChunksTest` holds its colours to BT.2100's equation.
     */
    @Test
    fun hdrTransfersMatchZimg() {
        assumeTrue("ffmpeg is not installed", Tools.hasAll("ffmpeg"))
        val random = Random(2100)
        val n = 4096 + 2048
        val coded = Array(n) { i -> if (i < 4096) DoubleArray(3) { (i * 16 + 8) / 65535.0 } else DoubleArray(3) { random.nextInt(65536) / 65535.0 } }
        val bt2020 = Cicp.primaries(9)!!
        for ((name, transfer, scale) in listOf<Triple<String, Cicp.Transfer, Double>>(
            Triple("smpte2084", Cicp.Transfer.Pq, 10000.0),
            Triple("arib-std-b67", Cicp.Transfer.Hlg, 1000.0),
        )) {
            // gbrp planes: green, blue, red.
            val planes = ByteBuffer.allocate(n * 6).order(ByteOrder.LITTLE_ENDIAN)
            for (plane in intArrayOf(1, 2, 0)) for (v in coded) planes.putShort(Math.round(v[plane] * 65535).toInt().toShort())
            val out = run(
                Tools.require("ffmpeg").path, "-v", "error", "-f", "rawvideo", "-pix_fmt", "gbrp16le", "-s", "${n}x1", "-i", "-",
                "-vf", "zscale=transferin=$name:transfer=linear:primariesin=2020:primaries=2020:rangein=full:range=full:npl=${scale.toInt()},format=gbrpf32le",
                "-f", "rawvideo", "-", input = planes.array(),
            )
            val floats = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            assertEquals(n * 3, floats.remaining(), name)
            val light = Cicp.light(bt2020, transfer)
            var worst = 0.0
            for (i in 0 until if (transfer == Cicp.Transfer.Hlg) 4096 else n) {
                val ours = light(coded[i])
                for ((k, plane) in intArrayOf(1, 2, 0).withIndex()) {
                    val theirs = floats.get(k * n + i).toDouble()
                    worst = maxOf(worst, abs(ours[plane] / scale - theirs) / maxOf(theirs, 1e-3))
                }
            }
            assertTrue(worst < 2e-3, "$name: light is ${worst * 100}% from zimg's")
        }
    }

    /**
     * An HDR AVIF from avifenc: its `nclx` PQ code points and `clli` light levels reach the
     * probe, and a decode to sRGB tone-maps them exactly as [ImageKodec.convertToSrgb] does the
     * same samples with the probe's profile, which `PngColorChunksTest` holds to BT.2100.
     */
    @Test
    fun anHdrAvifToneMapsThroughItsCodePointsAndLightLevels() {
        assumeTrue("avifenc is not installed", Tools.hasAll("avifenc"))
        val random = Random(2390)
        val w = 32
        val h = 16
        val samples = IntArray(w * h * 3) { random.nextInt(65536) }
        val dir = kotlin.io.path.createTempDirectory("imagekodec-hdr").toFile()
        try {
            val png = File(dir, "in.png").apply { writeBytes(PngColorFiles.png(w, h, 3, 16, samples)) }
            val avif = File(dir, "out.avif")
            run(Tools.require("avifenc").path, "-l", "-d", "10", "--cicp", "9/16/0", "-r", "full", "--clli", "4000,400", png.path, avif.path)
            val bytes = avif.readBytes()
            val profile = ImageKodec.probe(bytes).colorProfile!!
            assertContentEquals(intArrayOf(9, 16, 0, 1), profile.cicp)
            assertContentEquals(intArrayOf(40_000_000, 4_000_000), profile.contentLight)
            val stored = ImageKodec.decode16(bytes)
            val converted = ImageKodec.decode16(bytes, colorTarget = ColorTarget.Srgb())
            assertContentEquals(ImageKodec.convertToSrgb(stored, profile).samples, converted.samples)
            assertTrue(!converted.samples.contentEquals(stored.samples), "PQ came back unconverted")
            // A brighter declared peak compresses highlights harder.
            val dimmer = ImageKodec.convertToSrgb(stored, ColorProfile(cicp = profile.cicp, contentLight = intArrayOf(10_000_000, 0)))
            assertTrue(!dimmer.samples.contentEquals(converted.samples), "the content light level changed nothing")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun matrixAndGrayProfilesMatchLcms2() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val dir = kotlin.io.path.createTempDirectory("imagekodec-icc").toFile()
        try {
            val kinds = listOf(
                "p3-v4", "adobe-v2", "prophoto-v2", "rec2020-v4", "table-v2",
                "para0-v4", "para1-v4", "para2-v4", "para3-v4", "para4-v4", "gray-v2", "gray-v4",
            )
            for (kind in kinds) {
                val file = File(dir, "$kind.icc").apply { writeBytes(run(oracle!!.path, "make", kind)) }
                check(kind, file.readBytes(), file, if (kind.startsWith("gray")) 1 else 3)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun realProfilesMatchLcms2() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val dir = File("/usr/share/color/icc")
        val names = listOf("sRGB.icc", "compatibleWithAdobeRGB1998.icc", "LStar-RGB.icc", "Gray.icc")
        val present = names.map { File(dir, it) }.filter { it.isFile }
        if (System.getenv(Tools.REQUIRE_ENV) == "1") assertEquals(names.size, present.size, "icc-profiles-free is not installed")
        assumeTrue("icc-profiles-free is not installed", present.isNotEmpty())
        for (file in present) {
            val icc = file.readBytes()
            check(file.name, icc, file, if (ColorProfile(icc = icc).iccColorSpace == "GRAY") 1 else 3)
        }
    }

    /** [png] with an iCCP chunk of [icc] after its IHDR. */
    private fun withIccp(png: ByteArray, icc: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(icc)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        val body = "test".encodeToByteArray() + byteArrayOf(0, 0) + compressed.toByteArray()
        val chunk = ByteArrayOutputStream()
        chunk.write(ByteBuffer.allocate(4).putInt(body.size).array())
        val typed = "iCCP".encodeToByteArray() + body
        chunk.write(typed)
        val crc = CRC32().apply { update(typed) }.value.toInt()
        chunk.write(ByteBuffer.allocate(4).putInt(crc).array())
        val ihdrEnd = 8 + 8 + 13 + 4
        return png.copyOfRange(0, ihdrEnd) + chunk.toByteArray() + png.copyOfRange(ihdrEnd, png.size)
    }

    @Test
    fun aDecodeAskedForSrgbConvertsThroughTheFilesProfile() {
        assumeTrue("cc or lcms2 is not installed", Tools.hasAll("cc") && oracle != null)
        val icc = run(oracle!!.path, "make", "p3-v4")
        val random = Random(5)
        val source = KiteBitmap(17, 9, IntArray(17 * 9) { (0xFF shl 24) or random.nextInt(0x1000000) })
        val png = withIccp(ImageKodec.encodePng(source), icc)
        assertEquals("RGB", ImageKodec.probe(png).colorProfile?.iccColorSpace)
        assertContentEquals(source.argb, ImageKodec.decode(png).argb)
        val converted = ImageKodec.decode(png, colorTarget = ColorTarget.Srgb())
        assertContentEquals(ImageKodec.convertToSrgb(source, ColorProfile(icc = icc)).argb, converted.argb)
        assertTrue(!converted.argb.contentEquals(source.argb), "Display P3 colours came back unconverted")
        // Every entry point converts the same way.
        assertContentEquals(converted.argb, ImageKodec.decodePage(png, 0, colorTarget = ColorTarget.Srgb()).argb)
        assertContentEquals(converted.argb, ImageKodec.decodeAnimation(png, colorTarget = ColorTarget.Srgb()).frames[0].bitmap.argb)
        // decode16 converts at 16 bits, whose high byte may round the other way.
        val wide = ImageKodec.decode16(png, colorTarget = ColorTarget.Srgb()).toBitmap().argb
        for (i in wide.indices) for (c in 0 until 3) {
            assertTrue(abs(((wide[i] shr (8 * c)) and 0xFF) - ((converted.argb[i] shr (8 * c)) and 0xFF)) <= 1, "decode16 at pixel $i")
        }
    }
}
