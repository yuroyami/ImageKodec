package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reduced JPEG decode against libjpeg-turbo's scaled decode, `djpeg -scale 1/r`, which
 * shrinks inside the inverse DCT the same way. The two differ only in rounding and in how
 * they upsample the chroma, so the check is a small tolerance, not bit-exactness. It skips
 * without `djpeg`.
 */
class JpegReducedOracleTest {

    /** A photo-like picture: smooth shading, fine texture and hard edges, [w] by [h]. */
    private fun picture(w: Int, h: Int, type: Int): BufferedImage {
        val random = Random(7)
        val img = BufferedImage(w, h, type)
        for (y in 0 until h) for (x in 0 until w) {
            val shade = (sin(x / 17.0) * 60 + sin(y / 23.0) * 50 + 128).toInt()
            val edge = if ((x / 40 + y / 30) % 2 == 0) 40 else -40
            val noise = random.nextInt(-12, 13)
            val r = (shade + edge + noise).coerceIn(0, 255)
            val g = (x * 255 / w + noise).coerceIn(0, 255)
            val b = (y * 255 / h - edge / 2).coerceIn(0, 255)
            img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return img
    }

    private fun jpeg(img: BufferedImage, progressive: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.9f
                if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** `djpeg -scale 1/[r]` of [jpeg], read back from its PNM output as ARGB. */
    private fun djpeg(jpeg: ByteArray, r: Int, dir: File): KiteBitmap {
        val input = File(dir, "in.jpg").apply { writeBytes(jpeg) }
        val output = File(dir, "out.pnm")
        val process = ProcessBuilder(Tools.require("djpeg").path, "-scale", "1/$r", "-pnm", "-outfile", output.path, input.path)
            .redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "djpeg failed: $log")
        return pnm(output.readBytes())
    }

    private fun pnm(bytes: ByteArray): KiteBitmap {
        var at = 0
        fun token(): String {
            while (bytes[at].toInt().toChar().isWhitespace()) at++
            val start = at
            while (!bytes[at].toInt().toChar().isWhitespace()) at++
            return String(bytes, start, at - start)
        }
        val magic = token()
        val w = token().toInt()
        val h = token().toInt()
        token() // maxval
        at++ // one white space before the samples
        val channels = if (magic == "P6") 3 else 1
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val o = at + i * channels
            val r = bytes[o].toInt() and 0xFF
            val g = if (channels == 3) bytes[o + 1].toInt() and 0xFF else r
            val b = if (channels == 3) bytes[o + 2].toInt() and 0xFF else r
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    @Test
    fun aReducedDecodeMatchesDjpegScale() {
        assumeTrue("djpeg is not installed", Tools.hasAll("djpeg"))
        val dir = Files.createTempDirectory("imagekodec-reduced").toFile()
        try {
            val cases = listOf(
                "colour 4:2:0" to jpeg(picture(203, 157, BufferedImage.TYPE_INT_RGB), progressive = false),
                "grey" to jpeg(picture(203, 157, BufferedImage.TYPE_BYTE_GRAY), progressive = false),
                "colour progressive" to jpeg(picture(203, 157, BufferedImage.TYPE_INT_RGB), progressive = true),
            )
            val report = StringBuilder()
            for ((name, bytes) in cases) {
                for (r in listOf(2, 4, 8)) {
                    val ours = ImageKodec.decodeReduced(bytes, r)
                    val theirs = djpeg(bytes, r, dir)
                    assertEquals(theirs.width, ours.width, "$name, 1/$r width")
                    assertEquals(theirs.height, ours.height, "$name, 1/$r height")
                    var total = 0L
                    var worst = 0
                    for (i in ours.argb.indices) for (shift in intArrayOf(16, 8, 0)) {
                        val d = abs(((ours.argb[i] shr shift) and 0xFF) - ((theirs.argb[i] shr shift) and 0xFF))
                        total += d
                        worst = maxOf(worst, d)
                    }
                    val mean = total.toDouble() / (ours.argb.size * 3)
                    report.appendLine("$name 1/$r: mean $mean worst $worst")
                    assertTrue(mean < MEAN_LIMIT && worst <= WORST_LIMIT, "$name, 1/$r: mean $mean, worst $worst\n$report")
                    // A grey eighth is the DC coefficient over 8 in both, so it matches exactly.
                    if (name == "grey" && r == 8) assertEquals(0, worst, "grey, 1/8 must match djpeg exactly")
                }
            }
            println(report)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        // Measured against libjpeg-turbo 3: a mean of 1.6 and a worst of 12 at most, from how the
        // reduced IDCT keeps coefficients and how the YCbCr conversion rounds.
        const val MEAN_LIMIT = 2.5
        const val WORST_LIMIT = 20
    }
}
