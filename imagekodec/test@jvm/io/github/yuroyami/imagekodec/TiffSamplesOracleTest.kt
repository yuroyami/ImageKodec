package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TIFF samples of other depths and formats, and JPEG planes, against the readers that take them
 * (#189). ImageMagick writes unsigned samples of 5 to 32 bits, signed ones and floats of 16, 32 and
 * 64 bits, and reads them back at 16 bits through libtiff; `tiffcp` adds both predictors, which
 * libtiff undoes; ImageIO reads binary32 and binary64. libtiff's `tiff2rgba` reads JPEG YCbCr in
 * separate planes without subsampling, and ImageIO reads the subsampled planes without converting
 * them.
 */
class TiffSamplesOracleTest {

    private fun run(vararg args: String): Pair<Int, ByteArray> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(false).start()
        val out = ByteArrayOutputStream()
        val reader = Thread { proc.inputStream.copyTo(out) }.apply { start() }
        proc.errorStream.readBytes()
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ByteArray(0)
        }
        reader.join()
        return proc.exitValue() to out.toByteArray()
    }

    private fun temp(name: String, ext: String) = File.createTempFile("kite-samples-$name", ext).apply { deleteOnExit() }

    private val magick get() = Tools.require("magick").path

    private fun source(gray: Boolean): File {
        val scene = PhotoScenes.foliage(61, 47).bitmap
        val px = if (!gray) scene.argb else IntArray(scene.argb.size) { i ->
            val p = scene.argb[i]
            val v = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 4 + (p and 0xFF)) / 8
            argb(255, v, v, v)
        }
        return temp("src", ".png").apply { writeBytes(ImageKodec.encodePng(KiteBitmap(scene.width, scene.height, px))) }
    }

    /** ImageMagick's reading of [file] at 16 bits, big-endian, in [channels] channels. */
    private fun magick16(file: File, channels: Int): IntArray {
        val (code, raw) = run(magick, file.path, "-depth", "16", "-endian", "MSB", (if (channels == 1) "gray:-" else "rgb:-"))
        assertEquals(0, code, "ImageMagick could not read ${file.name}")
        return IntArray(raw.size / 2) { ((raw[2 * it].toInt() and 0xFF) shl 8) or (raw[2 * it + 1].toInt() and 0xFF) }
    }

    private class Worst(var value: Int = 0, var count: Int = 0)

    private fun compare16(name: String, theirs: IntArray, ours: KiteBitmap16, bound: Int): String {
        assertEquals(theirs.size, ours.samples.size, "$name sample count")
        var worst = 0
        var differing = 0
        for (i in theirs.indices) {
            val d = abs(theirs[i] - (ours.samples[i].toInt() and 0xFFFF))
            if (d > 0) differing++
            worst = maxOf(worst, d)
        }
        val report = "$name: worst ${worst} of 65535, $differing of ${theirs.size} samples differ"
        assertTrue(worst <= bound, report)
        return report
    }

    @Test
    fun imageMagickDepthsSignedAndFloatsMatchItsOwnReading() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        val reports = ArrayList<String>()
        val rgb = source(gray = false)
        val gray = source(gray = true)
        val cases = listOf(
            Triple("unsigned", 5, false), Triple("unsigned", 10, false), Triple("unsigned", 12, false),
            Triple("unsigned", 14, false), Triple("unsigned", 24, false), Triple("unsigned", 32, false),
            Triple("unsigned", 12, true), Triple("unsigned", 6, true),
            Triple("floating-point", 16, false), Triple("floating-point", 32, false), Triple("floating-point", 64, false),
            Triple("floating-point", 32, true),
            // ImageMagick 6 moves signed gray up by half its range at 16 bits alone, so only that depth is held to it.
            // ImageMagick 7 does not move it (Ubuntu 26.04's 7.1.2 differs by 32768 in every sample), so there
            // its reading is printed beside ours and not held.
            Triple("signed", 16, true),
        )
        val magick6 = run(magick, "-version").second.decodeToString().contains("ImageMagick 6.")
        for ((kind, depth, isGray) in cases) for (endian in listOf("LSB", "MSB")) {
            val file = temp("$kind-$depth", ".tif")
            val args = mutableListOf(magick, (if (isGray) gray else rgb).path, "-type", if (isGray) "Grayscale" else "TrueColor",
                "-depth", "$depth", "-endian", endian, "-compress", "none")
            if (kind != "unsigned") args += listOf("-define", "quantum:format=$kind")
            args += file.path
            assertEquals(0, run(*args.toTypedArray()).first, "ImageMagick could not write $kind $depth")
            val bytes = file.readBytes()
            assertTrue(ImageKodec.probe(bytes).isDecodable, "$kind $depth: ${ImageKodec.probe(bytes).unsupportedReason}")
            val channels = if (isGray) 1 else 3
            val name = "$kind $depth-bit ${if (isGray) "gray" else "RGB"} $endian"
            if (kind == "signed" && !magick6) {
                val theirs = magick16(file, channels)
                val ours = ImageKodec.decode16(bytes).samples
                val moved = theirs.indices.maxOf { abs(((theirs[it] xor 0x8000) - (ours[it].toInt() and 0xFFFF))) }
                reports += "$name: not held to ImageMagick 7; worst ${theirs.indices.maxOf { abs(theirs[it] - (ours[it].toInt() and 0xFFFF)) }}, " +
                    "worst $moved with its samples moved by half the range"
                continue
            }
            reports += compare16(name, magick16(file, channels), ImageKodec.decode16(bytes), 0)
        }
        println(reports.joinToString("\n"))
    }

    @Test
    fun imageIoReadsFloatsAsDecodeDoes() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        for (depth in listOf(32, 64)) {
            val file = temp("float$depth", ".tif")
            assertEquals(0, run(magick, source(false).path, "-type", "TrueColor", "-depth", "$depth", "-define",
                "quantum:format=floating-point", "-compress", "none", file.path).first)
            val reference = ImageIO.read(file)
            val ours = ImageKodec.decode(file.readBytes())
            var worst = 0
            for (y in 0 until ours.height) for (x in 0 until ours.width) for (shift in intArrayOf(16, 8, 0)) {
                worst = maxOf(worst, abs(((reference.getRGB(x, y) shr shift) and 0xFF) - ((ours[x, y] shr shift) and 0xFF)))
            }
            println("binary$depth against ImageIO: worst $worst")
            // ImageIO rounds to 8 bits where decode takes the high byte of the 16-bit sample.
            assertTrue(worst <= 1, "binary$depth against ImageIO: worst $worst")
        }
    }

    @Test
    fun libtiffsPredictorsMatchTheUncompressedFile() {
        assumeTrue("ImageMagick or libtiff tools not installed", Tools.hasAll("magick", "tiffcp"))
        val reports = ArrayList<String>()
        for ((kind, depth, predictor) in listOf(
            Triple("floating-point", 16, 3), Triple("floating-point", 32, 3), Triple("floating-point", 64, 3),
            Triple("floating-point", 32, 2), Triple("floating-point", 64, 2), Triple("unsigned", 32, 2),
        )) for (codec in listOf("lzw", "zip")) for (endian in listOf("LSB", "MSB")) {
            val plain = temp("plain", ".tif")
            val args = mutableListOf(magick, source(false).path, "-type", "TrueColor", "-depth", "$depth", "-endian", endian, "-compress", "none")
            if (kind != "unsigned") args += listOf("-define", "quantum:format=$kind")
            assertEquals(0, run(*(args + plain.path).toTypedArray()).first)
            val coded = temp("coded", ".tif")
            assertEquals(0, run(Tools.require("tiffcp").path, "-c", "$codec:$predictor", plain.path, coded.path).first)
            val name = "$kind $depth-bit, $codec with predictor $predictor, $endian"
            val bytes = coded.readBytes()
            assertTrue(ImageKodec.probe(bytes).isDecodable, name)
            reports += compare16(name, IntArray(0).let { _ ->
                val s = ImageKodec.decode16(plain.readBytes()).samples
                IntArray(s.size) { s[it].toInt() and 0xFFFF }
            }, ImageKodec.decode16(bytes), 0)
            reports += compare16("$name against ImageMagick", magick16(coded, 3), ImageKodec.decode16(bytes), 0)
        }
        println(reports.joinToString("\n"))
    }

    private fun ycbcr(y: Int, cb: Int, cr: Int) = TiffSamplesTest().ycbcr(y, cb, cr)

    @Test
    fun jpegYcbcrInSeparatePlanesMatchesLibtiffAndImageIo() {
        assumeTrue("libtiff tools not installed", Tools.hasAll("tiff2rgba"))
        val reports = ArrayList<String>()
        // ImageIO places a chroma strip or tile as if it were full size, so it checks the subsampled planes of one strip alone.
        for ((sub, strips, tile) in listOf(Triple(1, 1, 0), Triple(1, 3, 0), Triple(1, 1, 16), Triple(2, 1, 0))) {
            val f = TiffSamplesTest().separateJpeg(sub, strips, tile)
            val file = temp("separate", ".tif").apply { writeBytes(f.bytes) }
            val ours = ImageKodec.decode(f.bytes)
            val theirs: IntArray
            val reader: String
            if (sub == 1) {
                // libtiff converts unsubsampled separate YCbCr itself (putseparate8bitYCbCr11tile).
                val out = temp("rgba", ".tif")
                assertEquals(0, run(Tools.require("tiff2rgba").path, "-c", "none", file.path, out.path).first)
                theirs = ImageKodec.decode(out.readBytes()).argb
                reader = "tiff2rgba"
            } else {
                // libtiff's RGBA interface refuses subsampled separate YCbCr. ImageIO hands on the planes
                // unconverted, each chroma plane at its own size in the corner of a full-size band, so
                // its samples are taken where TIFF 6.0 section 21 places them and converted here.
                val raster = ImageIO.read(file).raster
                theirs = IntArray(f.width * f.height) { i ->
                    val x = i % f.width
                    val y = i / f.width
                    ycbcr(raster.getSample(x, y, 0), raster.getSample(x / sub, y / sub, 1), raster.getSample(x / sub, y / sub, 2))
                }
                reader = "ImageIO"
            }
            var total = 0L
            var worst = 0
            for (i in theirs.indices) for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((theirs[i] shr shift) and 0xFF) - ((ours.argb[i] shr shift) and 0xFF))
                total += d
                worst = maxOf(worst, d)
            }
            val mean = total.toDouble() / (theirs.size * 3)
            val report = "separate ${sub}x$sub, $strips strip(s), tile $tile, against $reader: mean $mean, worst $worst"
            // The two read the JPEG planes with different inverse DCTs (libjpeg's islow, and
            // ImageIO's, against this library's), which moves single samples by a few levels.
            assertTrue(mean <= 0.6 && worst <= 6, report)
            reports += report
        }
        println(reports.joinToString("\n"))
    }
}
