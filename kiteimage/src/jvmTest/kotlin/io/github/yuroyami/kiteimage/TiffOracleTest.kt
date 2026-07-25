package io.github.yuroyami.kiteimage

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tiled, planar and 16-bit TIFFs, produced by libtiff's own `tiffcp` and by
 * ImageMagick, then read two ways: through [KiteImage] and through ImageIO's
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
        f.writeBytes(KiteImage.encodePng(KiteBitmap(w, h, px)))
        return f
    }

    /** Decode [tiff] both ways and require agreement within [tolerance] per channel. */
    private fun compare(name: String, tiff: File, tolerance: Int = 0) {
        val reference = assertNotNull(ImageIO.read(tiff), "$name: ImageIO could not read the fixture")
        val ours = KiteImage.decode(tiff.readBytes())

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

        val info = KiteImage.probe(tiff.readBytes())
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
        val ours = KiteImage.decode(grey.readBytes())
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
