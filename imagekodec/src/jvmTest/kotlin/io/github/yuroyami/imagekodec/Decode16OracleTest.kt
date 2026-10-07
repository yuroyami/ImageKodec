package io.github.yuroyami.imagekodec

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
 * 16-bit PNGs and TIFFs written by ImageMagick and libtiff, read through
 * [ImageKodec.decode16] and through ImageIO's 16-bit rasters, every sample exact (#10).
 * The sources are gradients finer than 256 steps, so the low byte of most samples is
 * not zero. Skips cleanly without the tools.
 */
class Decode16OracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(name: String, ext: String) =
        File.createTempFile("kite-16-$name", ext).apply { deleteOnExit() }

    private fun magick(name: String, ext: String, vararg args: String): File {
        val out = temp(name, ext)
        val (code, text) = run(Tools.require("magick").path, *args, out.path)
        assertEquals(0, code, "magick failed: $text")
        return out
    }

    /**
     * ImageIO cannot undo a 16-bit predictor, so a predicted file is held to an
     * uncompressed copy libtiff makes of it.
     */
    private fun unpredicted(file: File): File {
        val out = temp("copy", ".tif")
        val (code, text) = run(Tools.require("tiffcp").path, "-c", "none", file.path, out.path)
        assertEquals(0, code, "tiffcp failed: $text")
        return out
    }

    private fun compare(name: String, file: File, channels: Int, referenceFile: File = file) {
        val reference = assertNotNull(ImageIO.read(referenceFile), "$name: ImageIO could not read it")
        val raster = reference.raster
        assertEquals(channels, raster.numBands, "$name: ImageIO bands")
        assertTrue(raster.sampleModel.getSampleSize(0) == 16, "$name is not 16-bit")
        val bytes = file.readBytes()
        assertEquals(16, ImageKodec.probe(bytes).bitDepth, "$name probe depth")
        val ours = ImageKodec.decode16(bytes)
        assertEquals(reference.width, ours.width, "$name width")
        assertEquals(reference.height, ours.height, "$name height")
        assertEquals(channels, ours.channels, "$name channels")
        var lowBytes = 0
        for (y in 0 until reference.height) for (x in 0 until reference.width) for (c in 0 until channels) {
            val expected = raster.getSample(x, y, c)
            if (expected and 0xFF != 0) lowBytes++
            assertEquals(expected, ours[x, y, c], "$name ($x, $y) channel $c")
        }
        assertTrue(lowBytes > reference.width * reference.height / 2, "$name: too few samples use their low byte")
    }

    private val size = arrayOf("-size", "300x40")
    private val gray = arrayOf(*size, "gradient:black-white", "-depth", "16")
    private val rgb = arrayOf(*size, "gradient:#000010-#ffc0a0", "-type", "TrueColor", "-depth", "16")
    private val rgba = arrayOf(*rgb, "(", *size, "gradient:white-black", "-rotate", "180", ")",
        "-alpha", "off", "-compose", "CopyOpacity", "-composite", "-depth", "16")

    @Test
    fun imageMagickSixteenBitPngsMatchImageIo() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        compare("png-gray", magick("gray", ".png", *gray, "-define", "png:color-type=0"), 1)
        compare("png-rgb", magick("rgb", ".png", *rgb, "-define", "png:color-type=2"), 3)
        compare("png-rgba", magick("rgba", ".png", *rgba, "-define", "png:color-type=6"), 4)
        compare("png-interlaced", magick("adam7", ".png", *rgb, "-interlace", "PNG", "-define", "png:color-type=2"), 3)
    }

    @Test
    fun sixteenBitTiffsMatchImageIo() {
        assumeTrue("ImageMagick or libtiff not installed", Tools.hasAll("magick", "tiffcp"))
        val lzw = magick("gray", ".tif", *gray, "-compress", "LZW", "-define", "tiff:predictor=2")
        compare("tiff-gray-lzw", lzw, 1, unpredicted(lzw))
        val zip = magick("rgb", ".tif", *rgb, "-compress", "Zip", "-define", "tiff:predictor=2")
        compare("tiff-rgb-zip", zip, 3, unpredicted(zip))
        compare("tiff-rgba", magick("rgba", ".tif", *rgba, "-compress", "none", "-endian", "MSB"), 4)
        // Separate planes: tiffcp cannot change the planar layout of 16-bit samples, ImageMagick can.
        compare("tiff-planar", magick("planar", ".tif", *rgb, "-compress", "none", "-interlace", "Plane"), 3)
    }
}
