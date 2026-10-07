package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The libjpeg-written four-component files of [JpegCmykFixtures] against ImageMagick, which
 * reads every CMYK JPEG with Adobe's inverted convention, marker or not. It converts with
 * floating point and we with stb's integer multiply, so a channel may differ by one. Skips
 * without ImageMagick.
 */
class JpegCmykOracleTest {

    private fun magick(jpeg: ByteArray, dir: File): ByteArray {
        val input = File(dir, "in.jpg").apply { writeBytes(jpeg) }
        val process = ProcessBuilder(Tools.require("magick").path, input.path, "-colorspace", "sRGB", "-depth", "8", "rgb:-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val rgb = process.inputStream.readBytes()
        assertEquals(0, process.waitFor(), "ImageMagick failed")
        return rgb
    }

    @Test
    fun fourComponentFilesMatchImageMagick() {
        assumeTrue("ImageMagick is not installed", Tools.hasAll("magick"))
        val dir = Files.createTempDirectory("imagekodec-cmyk").toFile()
        try {
            val cases = listOf(
                "transform 0" to JpegCmykFixtures.blocksAdobeCmyk,
                "transform 2" to JpegCmykFixtures.blocksAdobeYcck,
                "no marker" to JpegCmykFixtures.blocksBare,
                "split, transform 0" to JpegCmykFixtures.splitAdobeCmyk,
                "split, no marker" to JpegCmykFixtures.splitBare,
            )
            for ((name, jpeg) in cases) {
                val ours = ImageKodec.decode(jpeg)
                val theirs = magick(jpeg, dir)
                assertEquals(ours.width * ours.height * 3, theirs.size, "$name: size")
                for (i in ours.argb.indices) for (c in 0 until 3) {
                    val a = (ours.argb[i] shr (16 - 8 * c)) and 0xFF
                    val b = theirs[i * 3 + c].toInt() and 0xFF
                    assertTrue(abs(a - b) <= 1, "$name at pixel $i, channel $c: $a against ImageMagick's $b")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
