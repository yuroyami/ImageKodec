package io.github.yuroyami.imagekodec

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ImageMagick's BMP reader hands a BI_JPEG or BI_PNG pixel array to its JPEG or PNG reader, from
 * bfOffBits to the end of the file, and reports the embedded image's size. These BMPs decode here
 * to what ImageMagick reads from them, and are refused where it refuses them (#17).
 */
class BmpEmbeddedOracleTest {

    private fun sample(w: Int, h: Int, alpha: Boolean) = KiteBitmap(w, h, IntArray(w * h) { i ->
        argb(if (alpha && i % 3 == 0) 0x80 else 0xFF, i * 7, i * 13, i * 29)
    })

    /** ImageMagick's decode of [bytes] as 8-bit RGBA with its size, or null when it refuses the file. */
    private fun magick(bytes: ByteArray, label: String): Triple<Int, Int, ByteArray>? {
        val input = File.createTempFile("imagekodec-bmp", ".bmp").apply { deleteOnExit(); writeBytes(bytes) }
        val output = File.createTempFile("imagekodec-bmp", ".rgba").apply { deleteOnExit() }
        val log = File.createTempFile("imagekodec-bmp", ".log").apply { deleteOnExit() }
        val process = ProcessBuilder(Tools.require("magick").path, input.path, "-format", "%w %h\\n", "-write", "info:-",
            "-depth", "8", "rgba:${output.path}").redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "$label: ImageMagick timed out")
        if (process.exitValue() != 0) return null
        val (w, h) = log.readLines().first { it.matches(Regex("\\d+ \\d+")) }.split(" ").map { it.toInt() }
        return Triple(w, h, output.readBytes())
    }

    private fun rgba(bitmap: KiteBitmap): ByteArray = ByteArray(bitmap.argb.size * 4).also { out ->
        for ((i, p) in bitmap.argb.withIndex()) {
            out[i * 4] = (p ushr 16).toByte()
            out[i * 4 + 1] = (p ushr 8).toByte()
            out[i * 4 + 2] = p.toByte()
            out[i * 4 + 3] = (p ushr 24).toByte()
        }
    }

    @Test
    fun embeddedFilesDecodeAsImageMagickReadsThem() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        val png = ImageKodec.encodePng(sample(9, 5, alpha = true))
        val jpeg = ImageKodec.encodeJpeg(sample(17, 11, alpha = false), quality = 80)
        for ((label, bmp, inner) in listOf(
            Triple("BI_PNG", embeddedBmp(5, png, width = 3, height = 2), png),
            Triple("BI_PNG with no size", embeddedBmp(5, png, size = 0), png),
            Triple("BI_JPEG", embeddedBmp(4, jpeg, width = 3, height = 2), jpeg),
        )) {
            val reference = magick(bmp, label) ?: error("$label: ImageMagick refused the file")
            val ours = ImageKodec.decode(bmp)
            assertEquals(reference.first, ours.width, "$label width")
            assertEquals(reference.second, ours.height, "$label height")
            // ImageMagick reads the embedded file as it reads that file alone, and so does this library.
            assertContentEquals(magick(inner, label)!!.third, reference.third, "$label against ImageMagick's own decode of the payload")
            assertContentEquals(ImageKodec.decode(inner).argb, ours.argb, label)
            // A PNG decodes losslessly, so the two readers agree on every byte.
            if (inner === png) assertContentEquals(reference.third, rgba(ours), "$label pixels")
        }
    }

    @Test
    fun whatImageMagickRefusesIsRefused() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        val png = ImageKodec.encodePng(sample(4, 4, alpha = false))
        val jpeg = ImageKodec.encodeJpeg(sample(4, 4, alpha = false), quality = 80)
        for ((label, bmp) in listOf(
            "a BMP inside a BMP" to embeddedBmp(5, ImageKodec.encodeBmp(sample(4, 4, alpha = false))),
            "JPEG declared as PNG" to embeddedBmp(5, jpeg),
        )) {
            assertEquals(null, magick(bmp, label), "$label: ImageMagick read it")
            assertNotEquals(null, runCatching { ImageKodec.decode(bmp) }.exceptionOrNull() as? ImageDecodeException, label)
        }
        assertNotEquals(null, magick(embeddedBmp(5, png), "control"))
    }
}
