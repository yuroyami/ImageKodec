package io.github.yuroyami.imagekodec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The TIFF writer's options (#184) read by libtiff and ImageIO. For every compression, layout and
 * kind of sample: `tiffinfo` must show the tags asked for, `tiff2rgba` must draw the page as
 * ImageKodec's decoder does (exactly, or within the JPEG bound below), `tiffcmp` must find a
 * compressed strip file equal, sample for sample, to the uncompressed one, and ImageIO's TIFF
 * reader must read the same samples. It also prints the sizes of fax against colour and of LZW
 * against Deflate, with libtiff's own files beside them. Skips cleanly without the tools.
 */
class TiffEncodeOptionsOracleTest {

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        val err = Thread { proc.errorStream.readBytes() }.apply { start() }
        proc.inputStream.copyTo(out)
        err.join()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        return proc.exitValue() to out.toByteArray()
    }

    private fun tools(): Boolean = Tools.hasAll("tiffinfo", "tiff2rgba", "tiffcmp", "tiffcp")

    private fun temp(ext: String) = File.createTempFile("kite-tiff-options", ext).apply { deleteOnExit() }

    private fun info(file: File): String {
        val (code, text) = run(Tools.require("tiffinfo").path, file.path)
        assertEquals(0, code, "tiffinfo failed on ${file.name}")
        return text.decodeToString()
    }

    /** libtiff's RGBA reading of [file], as ImageKodec reads the uncompressed file tiff2rgba writes. */
    private fun tiff2rgba(file: File): KiteBitmap {
        val out = temp(".tif")
        assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", file.path, out.path).first, "tiff2rgba failed on ${file.name}")
        return ImageKodec.decode(out.readBytes())
    }

    private fun imageIo(data: ByteArray): java.awt.image.BufferedImage =
        assertNotNull(ImageIO.read(ByteArrayInputStream(data)), "ImageIO has no reader for the file")

    private fun assertArgb(name: String, want: KiteBitmap, got: java.awt.image.BufferedImage) {
        assertEquals(want.width to want.height, got.width to got.height, "$name: size")
        for (y in 0 until want.height) for (x in 0 until want.width) {
            val w = want.argb[y * want.width + x]
            val g = got.getRGB(x, y)
            if (w != g) throw AssertionError("$name: ImageIO reads ($x, $y) as ${g.toUInt().toString(16)}, ImageKodec ${w.toUInt().toString(16)}")
        }
    }

    private fun worst(a: KiteBitmap, b: KiteBitmap): Int {
        var worst = 0
        for (i in a.argb.indices) for (s in intArrayOf(0, 8, 16, 24)) worst = maxOf(worst, kotlin.math.abs(((a.argb[i] ushr s) and 0xFF) - ((b.argb[i] ushr s) and 0xFF)))
        return worst
    }

    private val photo = PhotoScenes.portrait(150, 110).bitmap

    private fun palettePicture(): KiteBitmap {
        val palette = IntArray(13) { argb(255, (it * 97 + 3) and 0xFF, (it * 31 + 7) and 0xFF, (it * 13) and 0xFF) }
        return KiteBitmap(83, 45, IntArray(83 * 45) { i -> palette[((i % 83) / 7 + (i / 83) / 5) % 13] })
    }

    private val compressions = listOf(
        TiffCompression.None to "None", TiffCompression.PackBits to "PackBits", TiffCompression.Lzw to "LZW", TiffCompression.Deflate to "AdobeDeflate",
    )

    @Test
    fun libtiffAndImageIoReadEveryLosslessVariant() {
        assumeTrue("libtiff tools not installed", tools())
        val pictures = listOf(
            "rgb" to (EncoderFixtures.card(71, 53, alpha = false, gray = false) to TiffPhotometric.Auto),
            "rgba" to (EncoderFixtures.card(71, 53, alpha = true, gray = false, seed = 5) to TiffPhotometric.Rgb),
            "gray" to (EncoderFixtures.card(71, 53, alpha = false, gray = true) to TiffPhotometric.Gray),
            "gray alpha" to (EncoderFixtures.card(71, 53, alpha = true, gray = true) to TiffPhotometric.Gray),
            "palette" to (palettePicture() to TiffPhotometric.Palette),
        )
        val photometricNames = mapOf(TiffPhotometric.Auto to "RGB color", TiffPhotometric.Rgb to "RGB color", TiffPhotometric.Gray to "min-is-black", TiffPhotometric.Palette to "palette color")
        var checked = 0
        for ((pictureName, pair) in pictures) {
            val (src, photometric) = pair
            val plain = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.None, photometric))) }
            for ((compression, schemeName) in compressions) for (predictor in listOf(false, true)) {
                for (layout in listOf("strips", "rows", "tiles")) {
                    val options = when (layout) {
                        "strips" -> TiffEncodeOptions(compression, photometric, predictor)
                        "rows" -> TiffEncodeOptions(compression, photometric, predictor, rowsPerStrip = 5)
                        else -> TiffEncodeOptions(compression, photometric, predictor, tileWidth = 32, tileLength = 16)
                    }
                    val name = "$pictureName $options"
                    val data = ImageKodec.encodeTiff(src, options)
                    val file = temp(".tif").apply { writeBytes(data) }
                    val text = info(file)
                    assertTrue("Compression Scheme: $schemeName" in text, "$name:\n$text")
                    assertTrue(photometricNames.getValue(photometric) in text, "$name:\n$text")
                    val predicts = predictor && photometric != TiffPhotometric.Palette && (compression == TiffCompression.Lzw || compression == TiffCompression.Deflate)
                    assertEquals(predicts, "Predictor: horizontal differencing" in text, "$name:\n$text")
                    when (layout) {
                        "rows" -> assertTrue("Rows/Strip: 5" in text, "$name:\n$text")
                        "tiles" -> assertTrue("Tile Width: 32 Tile Length: 16" in text, "$name:\n$text")
                    }
                    val ours = ImageKodec.decode(data)
                    assertEquals(src.argb.toList(), ours.argb.toList(), "$name: ImageKodec")
                    // tiff2rgba premultiplies alpha, so it is held to the opaque pages.
                    if (!src.hasTransparency()) assertEquals(ours.argb.toList(), tiff2rgba(file).argb.toList(), "$name: tiff2rgba")
                    // tiffcmp reads scanlines, which a tiled file has none of.
                    if (layout != "tiles") assertEquals(0, run(Tools.require("tiffcmp").path, plain.path, file.path).first, "$name: tiffcmp against the uncompressed file")
                    val io = imageIo(data)
                    if (photometric == TiffPhotometric.Gray) {
                        // ImageIO's gray colour space is linear, so getRGB would convert; its samples are what is stored.
                        for (i in ours.argb.indices) {
                            val x = i % src.width
                            val y = i / src.width
                            assertEquals(ours.argb[i] and 0xFF, io.raster.getSample(x, y, 0), "$name: ImageIO's gray at ($x, $y)")
                            if (src.hasTransparency()) assertEquals(ours.argb[i] ushr 24, io.raster.getSample(x, y, 1), "$name: ImageIO's alpha at ($x, $y)")
                        }
                    } else {
                        assertArgb("$name: ImageIO", ours, io)
                    }
                    checked++
                }
            }
        }
        println("TIFF options oracle: $checked lossless variants read alike by libtiff, ImageIO and ImageKodec")
    }

    @Test
    fun libtiffAndImageIoReadBiLevelPages() {
        assumeTrue("libtiff tools not installed", tools())
        val pages = listOf(BilevelPages.text(1728, 300, 5), BilevelPages.noise(37, 23, 7), BilevelPages.edges[3])
        val all = listOf(TiffCompression.CcittG4 to "CCITT Group 4", TiffCompression.CcittG3 to "CCITT Group 3") + compressions
        for (page in pages) {
            val src = page.bitmap()
            val plain = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.None, TiffPhotometric.BiLevel))) }
            for ((compression, schemeName) in all) for (tiled in listOf(false, true)) {
                val options = if (tiled) {
                    TiffEncodeOptions(compression, TiffPhotometric.BiLevel, tileWidth = 256, tileLength = 64)
                } else TiffEncodeOptions(compression, TiffPhotometric.BiLevel, rowsPerStrip = 64)
                val name = "${page.name} $options"
                val data = ImageKodec.encodeTiff(src, options)
                val file = temp(".tif").apply { writeBytes(data) }
                val text = info(file)
                assertTrue("Compression Scheme: $schemeName" in text, "$name:\n$text")
                assertTrue("Photometric Interpretation: min-is-white" in text && "Bits/Sample: 1" in text, "$name:\n$text")
                if (compression == TiffCompression.CcittG3) assertTrue("Group 3 Options: (0 = 0x0)" in text, "$name:\n$text")
                assertEquals(src.argb.toList(), ImageKodec.decode(data).argb.toList(), "$name: ImageKodec")
                assertEquals(src.argb.toList(), tiff2rgba(file).argb.toList(), "$name: tiff2rgba")
                if (!tiled) assertEquals(0, run(Tools.require("tiffcmp").path, plain.path, file.path).first, "$name: tiffcmp")
                assertArgb("$name: ImageIO", src, imageIo(data))
            }
        }
    }

    @Test
    fun libtiffAndImageIoReadJpegPages() {
        assumeTrue("libtiff tools not installed", tools())
        val gray = KiteBitmap(photo.width, photo.height, IntArray(photo.argb.size) { val v = photo.argb[it] and 0xFF; argb(255, v, v, v) })
        for (quality in listOf(40, 90, 95)) for (tiled in listOf(false, true)) for (src in listOf(photo, gray)) {
            val options = if (tiled) {
                TiffEncodeOptions(TiffCompression.Jpeg, quality = quality, tileWidth = 64, tileLength = 32)
            } else TiffEncodeOptions(TiffCompression.Jpeg, quality = quality, rowsPerStrip = 32)
            val isGray = src === gray
            val name = "${if (isGray) "gray" else "colour"} $options"
            val data = ImageKodec.encodeTiff(src, options)
            val file = temp(".tif").apply { writeBytes(data) }
            val text = info(file)
            assertTrue("Compression Scheme: JPEG" in text && "JPEG Tables:" in text, "$name:\n$text")
            assertTrue((if (isGray) "min-is-black" else "YCbCr") in text, "$name:\n$text")
            if (!isGray) assertTrue("YCbCr Subsampling: ${if (quality <= 90) "2, 2" else "1, 1"}" in text, "$name:\n$text")
            val ours = ImageKodec.decode(data)
            // libjpeg and ImageKodec's decoder each round the inverse DCT their own way, and upsample
            // chroma and convert colour each their own way too.
            val libtiff = worst(ours, tiff2rgba(file))
            val bound = if (isGray) 1 else 3
            assertTrue(libtiff <= bound, "$name: tiff2rgba differs by $libtiff, bound $bound")
            val io = imageIo(data)
            // A gray page is held to ImageIO's samples, since getRGB converts from its linear gray.
            val ioBitmap = KiteBitmap(io.width, io.height, IntArray(io.width * io.height) {
                if (isGray) io.raster.getSample(it % io.width, it / io.width, 0).let { v -> argb(255, v, v, v) } else io.getRGB(it % io.width, it / io.width)
            })
            val imageIoWorst = worst(ours, ioBitmap)
            assertTrue(imageIoWorst <= maxOf(bound, 2), "$name: ImageIO differs by $imageIoWorst")
            println("TIFF JPEG oracle, $name: libtiff worst $libtiff, ImageIO worst $imageIoWorst")
        }
    }

    /** [options] without the predictor, which ImageIO's TIFF reader refuses on 16-bit samples. */
    private fun unpredicted(options: TiffEncodeOptions) = TiffEncodeOptions(
        options.compression, options.photometric, predictor = false, options.quality, options.rowsPerStrip, options.tileWidth, options.tileLength,
    )

    @Test
    fun libtiffAndImageIoReadCmykAndSixteenBitPages() {
        assumeTrue("libtiff tools not installed", tools())
        val random = BilevelPages.Random(9)
        val w = 45
        val h = 31
        val ink = ByteArray(w * h * 4) { random.next(256).toByte() }
        val ink16 = ShortArray(w * h * 4) { random.next(65536).toShort() }
        for ((compression, schemeName) in compressions) for (tiled in listOf(false, true)) {
            val options = if (tiled) TiffEncodeOptions(compression, tileWidth = 16, tileLength = 16) else TiffEncodeOptions(compression, rowsPerStrip = 4)
            for (wide in listOf(false, true)) {
                val name = "CMYK ${if (wide) 16 else 8}-bit $options"
                val data = if (wide) ImageKodec.encodeTiffCmyk(w, h, ink16, options) else ImageKodec.encodeTiffCmyk(w, h, ink, options)
                val file = temp(".tif").apply { writeBytes(data) }
                val text = info(file)
                assertTrue("Compression Scheme: $schemeName" in text && "Photometric Interpretation: separated" in text && "InkSet: 1" in text, "$name:\n$text")
                // libtiff's RGBA interface cannot handle 16-bit CMYK ("Sorry, can not handle image").
                if (!wide) assertEquals(ImageKodec.decode(data).argb.toList(), tiff2rgba(file).argb.toList(), "$name: tiff2rgba")
                if (!tiled) {
                    val none = TiffEncodeOptions(TiffCompression.None)
                    val plain = temp(".tif").apply { writeBytes(if (wide) ImageKodec.encodeTiffCmyk(w, h, ink16, none) else ImageKodec.encodeTiffCmyk(w, h, ink, none)) }
                    assertEquals(0, run(Tools.require("tiffcmp").path, plain.path, file.path).first, "$name: tiffcmp")
                }
                // ImageIO keeps the ink as it is stored. It refuses the predictor on 16-bit samples, so it reads them without.
                val raster = imageIo(if (wide) ImageKodec.encodeTiffCmyk(w, h, ink16, unpredicted(options)) else data).raster
                for (i in 0 until w * h) for (c in 0 until 4) {
                    val want = if (wide) ink16[i * 4 + c].toInt() and 0xFFFF else ink[i * 4 + c].toInt() and 0xFF
                    assertEquals(want, raster.getSample(i % w, i / w, c), "$name: ImageIO's pixel $i ink $c")
                }
            }
            for (channels in 1..4) {
                val src = EncoderFixtures.wide(w, h, channels)
                val name = "16-bit $channels channels $options"
                val data = ImageKodec.encodeTiff16(src, options)
                val file = temp(".tif").apply { writeBytes(data) }
                assertTrue("Bits/Sample: 16" in info(file), name)
                // tiff2rgba rounds 16 bits to 8 where ImageKodec keeps the high byte, so libtiff is
                // held to the uncompressed file instead, sample for sample.
                if (!tiled) {
                    val plain = temp(".tif").apply { writeBytes(ImageKodec.encodeTiff16(src, TiffEncodeOptions(TiffCompression.None))) }
                    assertEquals(0, run(Tools.require("tiffcmp").path, plain.path, file.path).first, "$name: tiffcmp")
                }
                val raster = imageIo(ImageKodec.encodeTiff16(src, unpredicted(options))).raster
                for (i in 0 until w * h) for (c in 0 until channels) {
                    assertEquals(src.samples[i * channels + c].toInt() and 0xFFFF, raster.getSample(i % w, i / w, c), "$name: ImageIO's pixel $i sample $c")
                }
            }
        }
    }

    @Test
    fun sizesAgainstDeflateAndLibtiff() {
        assumeTrue("libtiff tools not installed", tools())
        fun libtiff(src: ByteArray, codec: String, rows: Int = 64): Int {
            val input = temp(".tif").apply { writeBytes(src) }
            val out = temp(".tif")
            assertEquals(0, run(Tools.require("tiffcp").path, "-c", codec, "-r", "$rows", input.path, out.path).first, "tiffcp -c $codec")
            return out.readBytes().size
        }
        println("| page | ours G4 | libtiff G4 | ours RGB Deflate | G4 / Deflate |")
        println("|---|---|---|---|---|")
        for (page in listOf(BilevelPages.text(1728, 2200, 1), BilevelPages.text(1728, 400, 2), BilevelPages.noise(256, 256, 3))) {
            val src = page.bitmap()
            val g4 = ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.CcittG4, rowsPerStrip = 64)).size
            val reference = libtiff(ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.None, TiffPhotometric.BiLevel)), "g4")
            val deflate = ImageKodec.encodeTiff(src, TiffEncodeOptions(photometric = TiffPhotometric.Rgb)).size
            println("| ${page.name} | $g4 | $reference | $deflate | ${"%.4f".format(g4.toDouble() / deflate)} |")
            if (page.name.startsWith("text")) assertTrue(g4 * 2 < deflate, "${page.name}: G4 $g4 bytes against Deflate $deflate")
            assertTrue(g4 <= reference * 11 / 10 + 64, "${page.name}: G4 $g4 bytes against libtiff's $reference")
        }
        println("| scene | ours LZW | libtiff LZW | ours Deflate | libtiff Deflate | LZW / Deflate |")
        println("|---|---|---|---|---|---|")
        for (scene in PhotoScenes.all()) {
            val src = scene.bitmap
            val plain = ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.None))
            var lzw = 0
            var lzwTime = 0L
            var deflate = 0
            var deflateTime = 0L
            repeat(3) {
                val t0 = System.nanoTime()
                lzw = ImageKodec.encodeTiff(src, TiffEncodeOptions(TiffCompression.Lzw)).size
                val t1 = System.nanoTime()
                deflate = ImageKodec.encodeTiff(src).size
                val t2 = System.nanoTime()
                lzwTime = t1 - t0
                deflateTime = t2 - t1
            }
            // libtiff writes the same strips as ours: 8 KiB of samples each.
            val rows = 8192 / (src.width * 3)
            val libLzw = libtiff(plain, "lzw:2", rows)
            val libDeflate = libtiff(plain, "zip:2", rows)
            println(
                "| ${scene.name} | $lzw (${lzwTime / 1_000_000} ms) | $libLzw | $deflate (${deflateTime / 1_000_000} ms) | $libDeflate | " +
                    "${"%.3f".format(lzw.toDouble() / deflate)} |",
            )
            // The same predictor and LZW rules leave libtiff's files within a few bytes a strip of ours.
            assertTrue(lzw <= libLzw * 102 / 100 + 256, "${scene.name}: LZW $lzw bytes against libtiff's $libLzw")
            assertTrue(lzw < plain.size, "${scene.name}: LZW $lzw bytes against ${plain.size} uncompressed")
        }
    }
}
