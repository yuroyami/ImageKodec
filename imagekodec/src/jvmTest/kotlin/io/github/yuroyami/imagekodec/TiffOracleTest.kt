package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.plugins.tiff.TIFFDirectory
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tiled, planar and 16-bit TIFFs, produced by libtiff's own `tiffcp` and by
 * ImageMagick, then read two ways: through [ImageKodec] and through ImageIO's
 * independent TIFF reader. The layouts here are the ones no hand-written vector
 * really proves, because the point of them is how a real writer lays bytes out
 * across tile and plane boundaries.
 *
 * Skips cleanly when the tools are absent.
 */
class TiffOracleTest {

    private val magick get() = Tools.require("magick")
    private val tiffcp get() = Tools.require("tiffcp")

    private fun tools(): Boolean = Tools.hasAll("magick", "tiffcp")

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    private fun temp(name: String, ext: String) =
        File.createTempFile("kite-tiff-$name", ext).apply { deleteOnExit() }

    /** A deterministic true-colour source, written with our own PNG encoder. */
    private fun sourcePng(w: Int = 71, h: Int = 53): File {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            px[y * w + x] = argb(
                0xFF,
                (x * 255 / (w - 1)),
                (y * 255 / (h - 1)),
                if ((x / 9 + y / 7) % 2 == 0) 40 else 215,
            )
        }
        val f = temp("src", ".png")
        f.writeBytes(ImageKodec.encodePng(KiteBitmap(w, h, px)))
        return f
    }

    /** Decode [tiff] both ways and require agreement within [tolerance] per channel. */
    private fun compare(name: String, tiff: File, tolerance: Int = 0, referenceTiff: File = tiff) {
        val reference = assertNotNull(ImageIO.read(referenceTiff), "$name: ImageIO could not read the fixture")
        val ours = ImageKodec.decode(tiff.readBytes())

        assertEquals(reference.width, ours.width, "$name width")
        assertEquals(reference.height, ours.height, "$name height")
        var worst = 0
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val a = reference.getRGB(x, y)
            val b = ours[x, y]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                if (d > worst) worst = d
                assertTrue(d <= tolerance, "$name pixel ($x, $y) channel $shift differs by $d")
            }
        }

        val info = ImageKodec.probe(tiff.readBytes())
        assertEquals(ours.width, info.width, "$name probe width")
        assertEquals(ours.height, info.height, "$name probe height")
    }

    private fun baseTiff(name: String): File {
        val src = sourcePng()
        val out = temp(name, ".tif")
        assertEquals(
            0,
            run(magick.path, src.path, "-type", "TrueColor", "-depth", "8", "-compress", "none", out.path),
            "magick failed",
        )
        return out
    }

    @Test
    fun imageMagickSampleFormatsHaveMatchingProbeAndDecodeRefusals() {
        assumeTrue("TIFF tools not installed", tools())
        for ((kind, format) in listOf("signed" to 2, "floating-point" to 3)) {
            for (endian in listOf("LSB", "MSB")) {
                val file = temp("sample-format-$format-$endian", ".tif")
                assertEquals(0, run(magick.path, sourcePng().path, "-type", "TrueColor", "-depth", "16",
                    "-define", "quantum:format=$kind", "-endian", endian, "-compress", "none", file.path))
                val reader = ImageIO.getImageReadersByFormatName("TIFF").next()
                ImageIO.createImageInputStream(file).use { input ->
                    reader.input = input
                    val directory = TIFFDirectory.createFromMetadata(reader.getImageMetadata(0))
                    val field = assertNotNull(directory.getTIFFField(339))
                    assertEquals(3, field.count)
                    for (i in 0 until field.count) assertEquals(format, field.getAsInt(i))
                }
                reader.dispose()
                val bytes = file.readBytes()
                assertFalse(ImageKodec.probe(bytes).isDecodable)
                assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
            }
        }
    }

    @Test
    fun shortStripsAndRecoverableCountsMatchLibtiff() {
        assumeTrue("TIFF tools not installed", tools())
        val input = temp("short", ".tif")
        val output = temp("short-copy", ".tif")
        for (bytes in listOf(tiffBlock(1, ByteArray(16), count = 32),
            tiffBlock(32773, byteArrayOf(15) + ByteArray(16)))) {
            input.writeBytes(bytes)
            assertTrue(run(tiffcp.path, input.path, output.path) != 0)
            assertTrue(run(magick.path, input.path, temp("short", ".png").path) != 0)
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        }
        for (count in listOf(0, 1, 16)) {
            input.writeBytes(tiffBlock(1, ByteArray(32) { (it * 7).toByte() }, count))
            assertEquals(0, run(tiffcp.path, input.path, output.path))
            val repaired = ImageKodec.decode(output.readBytes())
            val ours = ImageKodec.decode(input.readBytes())
            for (i in ours.argb.indices) assertEquals(repaired.argb[i], ours.argb[i])
        }
    }

    @Test
    fun subsampledYcbcrTilesMatchLibtiff() {
        assumeTrue("TIFF tools not installed", tools())
        assumeTrue("tiff2rgba not installed", Tools.hasAll("tiff2rgba"))
        for ((h, v) in listOf(2 to 1, 2 to 2, 4 to 1, 4 to 2, 4 to 4)) {
            val input = temp("ycbcr-$h-$v", ".tif")
            val decoded = temp("ycbcr-$h-$v", ".tif")
            input.writeBytes(TiffExtendedTest().tiledYcbcr(h, v))
            assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", input.path, decoded.path))
            val reference = assertNotNull(ImageIO.read(decoded))
            val ours = ImageKodec.decode(input.readBytes())
            for (y in 0 until 17) for (x in 0 until 19) {
                assertEquals(reference.getRGB(x, y), ours[x, y], "$h x $v at $x, $y")
            }
        }
    }

    @Test
    fun stripBaselineStillAgrees() {
        assumeTrue("TIFF tools not installed", tools())
        compare("strips", baseTiff("strips"))
    }

    @Test
    fun tiledFilesReassembleExactly() {
        assumeTrue("TIFF tools not installed", tools())
        // 32x32 tiles over a 71x53 image: every edge tile is padded, which is
        // exactly where a naive reassembly goes wrong.
        val out = temp("tiled", ".tif")
        assertEquals(0, run(tiffcp.path, "-t", "-w", "32", "-l", "32", baseTiff("t0").path, out.path))
        compare("tiled", out)
    }

    @Test
    fun tinyTilesReassembleExactly() {
        assumeTrue("TIFF tools not installed", tools())
        val out = temp("tiled16", ".tif")
        assertEquals(0, run(tiffcp.path, "-t", "-w", "16", "-l", "16", baseTiff("t1").path, out.path))
        compare("tiled16", out)
    }

    @Test
    fun separatePlanesAgree() {
        assumeTrue("TIFF tools not installed", tools())
        val out = temp("planar", ".tif")
        assertEquals(0, run(tiffcp.path, "-p", "separate", baseTiff("p0").path, out.path))
        compare("planar", out)
    }

    @Test
    fun tiledAndPlanarTogetherAgree() {
        assumeTrue("TIFF tools not installed", tools())
        val out = temp("tiledplanar", ".tif")
        assertEquals(
            0,
            run(tiffcp.path, "-t", "-w", "16", "-l", "16", "-p", "separate", baseTiff("tp").path, out.path),
        )
        compare("tiledplanar", out)
    }

    @Test
    fun sixteenBitAgreesOnTheHighByte() {
        assumeTrue("TIFF tools not installed", tools())
        val src = sourcePng()
        val out = temp("d16", ".tif")
        assertEquals(
            0,
            run(magick.path, src.path, "-type", "TrueColor", "-depth", "16", "-compress", "none", out.path),
        )
        // We narrow 16-bit samples to their high byte, ImageIO keeps 16 and its
        // getRGB rounds; a one-count difference is the two rules disagreeing, not
        // a decode error.
        compare("d16", out, tolerance = 1)
    }

    @Test
    fun compressedTilesAgree() {
        assumeTrue("TIFF tools not installed", tools())
        for (codec in listOf("lzw", "zip", "packbits")) {
            val out = temp("tiled-$codec", ".tif")
            assertEquals(
                0,
                run(tiffcp.path, "-c", codec, "-t", "-w", "32", "-l", "32", baseTiff("c-$codec").path, out.path),
                "tiffcp -c $codec failed",
            )
            compare("tiled-$codec", out)
        }
    }

    @Test
    fun predictorWithCompressionAgrees() {
        assumeTrue("TIFF tools not installed", tools())
        for (codec in listOf("lzw:2", "zip:2")) {
            val out = temp("pred-${codec.first()}", ".tif")
            assertEquals(
                0,
                run(tiffcp.path, "-c", codec, baseTiff("pr-${codec.first()}").path, out.path),
                "tiffcp -c $codec failed",
            )
            compare("predictor-$codec", out)
        }
    }

    @Test
    fun tiledPredictorWithCompressionAgrees() {
        assumeTrue("TIFF tools not installed", tools())
        for (bits in listOf(8, 16)) for (planar in listOf(false, true)) {
            // tiffcp cannot convert 16-bit chunky/separate layouts. Supply the
            // source in its desired layout so it only encodes compression/prediction.
            val base = temp("predictor-base-$bits-$planar", ".tif")
            base.writeBytes(predictorTiff(bits = bits, planar = planar, compression = 1, predictor = 1))
            for (codec in listOf("lzw:2", "zip:2")) {
                val out = temp("tiled-predictor-$bits-${codec.first()}-$planar", ".tif")
                assertEquals(0, run(tiffcp.path, "-c", codec, "-t",
                    "-w", "32", "-l", "32", base.path, out.path), "$bits/$planar/$codec")
                comparePredictor("tiled-predictor-$bits-$codec-$planar", out, bits)
            }
        }
    }

    @Test
    fun commonPredictorVectorsAgreeWithIndependentReader() {
        assumeTrue("TIFF tools not installed", tools())
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) for (planar in listOf(false, true)) {
            val file = temp("predictor-vector-$bits-$le-$planar", ".tif")
            file.writeBytes(predictorTiff(bits = bits, le = le, planar = planar))
            comparePredictor("predictor-vector-$bits-$le-$planar", file, bits)
        }
    }

    private fun comparePredictor(name: String, tiff: File, bits: Int) {
        // ImageIO refuses 16-bit prediction. Read libtiff's decoded RGBA output
        // instead, including separate 16-bit planes without a layout conversion.
        assumeTrue("tiff2rgba not installed", Tools.hasAll("tiff2rgba"))
        val decoded = temp("predictor-decoded", ".tif")
        assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", tiff.path, decoded.path))
        compare(name, tiff, tolerance = if (bits == 16) 1 else 0, referenceTiff = decoded)
    }

    @Test
    fun greyscaleAgreesOnTheStoredSamples() {
        assumeTrue("TIFF tools not installed", tools())
        val src = sourcePng()
        val grey = temp("grey", ".tif")
        assertEquals(0, run(magick.path, src.path, "-colorspace", "Gray", "-depth", "8", "-compress", "none", grey.path))

        // Compared against the raster, not getRGB: ImageIO treats a grey TIFF as
        // *linear* grey and colour-manages it into sRGB on the way out, which
        // shifts dark values by tens of counts. The stored sample is the fact
        // both decoders can agree on.
        val reference = assertNotNull(ImageIO.read(grey))
        val ours = ImageKodec.decode(grey.readBytes())
        assertEquals(reference.width, ours.width)
        assertEquals(reference.height, ours.height)
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val stored = reference.raster.getSample(x, y, 0)
            assertEquals(stored, (ours[x, y] shr 16) and 0xFF, "grey ($x, $y)")
        }
    }

    @Test
    fun bilevelAgrees() {
        assumeTrue("TIFF tools not installed", tools())
        val src = sourcePng()
        val mono = temp("mono", ".tif")
        assertEquals(
            0,
            run(magick.path, src.path, "-monochrome", "-depth", "1", "-compress", "none", mono.path),
        )
        compare("mono", mono)
    }
}
