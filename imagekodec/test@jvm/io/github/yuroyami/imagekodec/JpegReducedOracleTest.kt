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

    /** One-pixel gray stripes, or a one-pixel checkerboard, [w] by [w]: all detail, no average. */
    private fun fineDetail(w: Int, checkerboard: Boolean): BufferedImage {
        val img = BufferedImage(w, w, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until w) for (x in 0 until w) {
            img.raster.setSample(x, y, 0, 255 * ((if (checkerboard) x + y else x) % 2))
        }
        return img
    }

    private fun jpeg(img: BufferedImage, progressive: Boolean, quality: Float = 0.9f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
                if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** [img] as a binary PPM, the input `cjpeg` reads. */
    private fun ppm(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("P6\n${img.width} ${img.height}\n255\n".encodeToByteArray())
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            out.write((p shr 16) and 0xFF); out.write((p shr 8) and 0xFF); out.write(p and 0xFF)
        }
        return out.toByteArray()
    }

    /** libjpeg-turbo's `cjpeg` of [img] at quality 90 with the luma sampled [sampling], such as 4x2. */
    private fun cjpeg(img: BufferedImage, sampling: String, dir: File): ByteArray {
        val input = File(dir, "in.ppm").apply { writeBytes(ppm(img)) }
        val output = File(dir, "cjpeg.jpg")
        val process = ProcessBuilder(
            Tools.require("cjpeg").path, "-quality", "90", "-sample", "$sampling,1x1,1x1", "-outfile", output.path, input.path,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.readBytes().decodeToString()
        assertEquals(0, process.waitFor(), "cjpeg failed: $log")
        return output.readBytes()
    }

    /** `djpeg -scale 1/[r]` of [jpeg], read back from its PNM output as ARGB. At 1 it is the full decode. */
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
            compare(
                listOf(
                    "colour 4:2:0" to jpeg(picture(203, 157, BufferedImage.TYPE_INT_RGB), progressive = false),
                    "grey" to jpeg(picture(203, 157, BufferedImage.TYPE_BYTE_GRAY), progressive = false),
                    "colour progressive" to jpeg(picture(203, 157, BufferedImage.TYPE_INT_RGB), progressive = true),
                ),
                dir,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * All detail and no average: each reduced pixel is the mean of whole periods, which is a
     * tie at 127.5 for the checkerboard, so either rounding is right there. Keeping only the
     * low frequencies of each block left stripes here, 33 levels off djpeg (#62).
     */
    @Test
    fun fineDetailMatchesDjpegScale() {
        assumeTrue("djpeg is not installed", Tools.hasAll("djpeg"))
        val dir = Files.createTempDirectory("imagekodec-reduced").toFile()
        try {
            compare(
                listOf(
                    "stripes" to jpeg(fineDetail(64, checkerboard = false), progressive = false, quality = 1f),
                    "checkerboard" to jpeg(fineDetail(64, checkerboard = true), progressive = false, quality = 1f),
                ),
                dir,
                meanLimit = 0.5,
                worstLimit = 1,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Luma sampled 4x2, 2x1, 1x2, 4x1 and 2x4 against chroma 1x1, checked against the truth: djpeg's
     * own full decode, averaged over each reduced pixel. libjpeg-turbo gives a chroma plane one IDCT
     * size for both directions, so it reduces the finer direction too far and upsamples it back;
     * this decoder sizes each direction apart, as libjpeg 9 does. It must come out at least as
     * close to the truth as `djpeg -scale` at every reduction, and close outright at a quarter and
     * an eighth, where no upsampling is left (#63).
     */
    @Test
    fun unequalSamplingRatiosStayCloserToTheFullDecodeThanDjpegScale() {
        assumeTrue("cjpeg and djpeg are not installed", Tools.hasAll("cjpeg", "djpeg"))
        val dir = Files.createTempDirectory("imagekodec-reduced").toFile()
        try {
            val img = picture(203, 157, BufferedImage.TYPE_INT_RGB)
            val report = StringBuilder()
            for (sampling in listOf("4x2", "2x1", "1x2", "4x1", "2x4")) {
                val bytes = cjpeg(img, sampling, dir)
                val full = djpeg(bytes, 1, dir)
                for (r in listOf(2, 4, 8)) {
                    val truth = full.reducedBy(r)
                    val (ourMean, ourWorst) = difference(ImageKodec.decodeReduced(bytes, r), truth)
                    val (theirMean, theirWorst) = difference(djpeg(bytes, r, dir), truth)
                    report.appendLine("luma $sampling 1/$r: ours mean $ourMean worst $ourWorst, djpeg -scale mean $theirMean worst $theirWorst")
                    assertTrue(ourMean <= theirMean + 0.05, "luma $sampling, 1/$r is further from the truth than djpeg -scale\n$report")
                    if (r >= 4) assertTrue(ourMean <= 1.0 && ourWorst <= 14, "luma $sampling, 1/$r: mean $ourMean, worst $ourWorst\n$report")
                }
            }
            println(report)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The mean and the worst channel difference between [a] and [b], which have one size. */
    private fun difference(a: KiteBitmap, b: KiteBitmap): Pair<Double, Int> {
        assertEquals(b.width, a.width)
        assertEquals(b.height, a.height)
        var total = 0L
        var worst = 0
        for (i in a.argb.indices) for (shift in intArrayOf(16, 8, 0)) {
            val d = abs(((a.argb[i] shr shift) and 0xFF) - ((b.argb[i] shr shift) and 0xFF))
            total += d
            worst = maxOf(worst, d)
        }
        return total.toDouble() / (a.argb.size * 3) to worst
    }

    private fun compare(cases: List<Pair<String, ByteArray>>, dir: File, meanLimit: Double = MEAN_LIMIT, worstLimit: Int = WORST_LIMIT) {
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
                assertTrue(mean <= meanLimit && worst <= worstLimit, "$name, 1/$r: mean $mean, worst $worst\n$report")
                // A grey eighth is the DC coefficient over 8 in both, so it matches exactly.
                if (name == "grey" && r == 8) assertEquals(0, worst, "grey, 1/8 must match djpeg exactly")
            }
        }
        println(report)
    }

    private companion object {
        // Measured against libjpeg-turbo 2.1: a mean of 0.06 and a worst of 3 at most, from the
        // rounding of the reduced IDCT and of the YCbCr conversion.
        const val MEAN_LIMIT = 0.25
        const val WORST_LIMIT = 4
    }
}
