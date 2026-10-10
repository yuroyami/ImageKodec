package io.github.yuroyami.imagekodec

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriteParam
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-decoder check: ImageIO (a libjpeg-family implementation with a
 * *different* IDCT) writes and reads; our decode must land within JPEG's
 * usual inter-decoder wobble. The commonTest vectors pin bit-exactness against
 * stb; this suite pins "agrees with everyone else within tolerance".
 */
class JpegImageIoTest {

    private fun photoish(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            // smooth waves: compresses like a photo, no random noise
            val r = (128 + 127 * kotlin.math.sin(x / 7.0)).toInt().coerceIn(0, 255)
            val g = (128 + 127 * kotlin.math.sin(y / 5.0 + 1.0)).toInt().coerceIn(0, 255)
            val b = (128 + 127 * kotlin.math.sin((x + y) / 9.0 + 2.0)).toInt().coerceIn(0, 255)
            img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return img
    }

    /**
     * [lumaSampling] sets the luma's horizontal and vertical factors; ImageIO's own default is 2x2,
     * 4:2:0. [restartInterval] above 0 writes a DRI marker, a restart every that many MCUs.
     */
    private fun encodeJpeg(
        img: BufferedImage,
        quality: Float,
        progressive: Boolean = false,
        lumaSampling: Pair<Int, Int>? = null,
        restartInterval: Int = 0,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
            if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
        }
        val metadata = if (lumaSampling == null && restartInterval == 0) null else {
            val format = "javax_imageio_jpeg_image_1.0"
            writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param).apply {
                val tree = getAsTree(format) as IIOMetadataNode
                lumaSampling?.let { (h, v) ->
                    val luma = tree.getElementsByTagName("componentSpec").item(0) as IIOMetadataNode
                    luma.setAttribute("HsamplingFactor", "$h")
                    luma.setAttribute("VsamplingFactor", "$v")
                }
                if (restartInterval > 0) {
                    val sequence = tree.getElementsByTagName("markerSequence").item(0) as IIOMetadataNode
                    val dri = IIOMetadataNode("dri").apply { setAttribute("interval", "$restartInterval") }
                    sequence.insertBefore(dri, sequence.firstChild)
                }
                setFromTree(format, tree)
            }
        }
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, metadata), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    /**
     * Tolerances cover legitimate inter-decoder variance: stb's IDCT vs
     * libjpeg's, and centered (JFIF) vs co-sited chroma upsampling phase: a
     * few counts at hard edges, worst on tiny single-MCU images. Real decoder
     * bugs show up as means in the tens (see the gray color-management note
     * below), not single digits.
     */
    private fun assertCloseToImageIo(jpeg: ByteArray, name: String, maxDiff: Int = 6, maxMean: Double = 1.5) {
        val reference = ImageIO.read(jpeg.inputStream())!!
        val ours = ImageKodec.decode(jpeg)
        assertEquals(reference.width, ours.width, "$name width")
        assertEquals(reference.height, ours.height, "$name height")
        val gray = reference.type == BufferedImage.TYPE_BYTE_GRAY
        var worst = 0
        var sum = 0L
        var n = 0L
        for (y in 0 until reference.height) for (x in 0 until reference.width) {
            val a = ours[x, y]
            if (gray) {
                // getRGB() on TYPE_BYTE_GRAY routes through Java2D color
                // management (linear-gray → sRGB gamma) and reports values ~50
                // counts off the raw samples. Compare the raw raster instead.
                val e = reference.raster.getSample(x, y, 0)
                val d = abs(e - (a and 0xFF))
                if (d > worst) worst = d
                sum += d
                n++
            } else {
                val e = reference.getRGB(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF))
                    if (d > worst) worst = d
                    sum += d
                    n++
                }
            }
        }
        val mean = sum.toDouble() / n
        assertTrue(worst <= maxDiff, "$name: max channel diff $worst > $maxDiff (mean ${"%.3f".format(mean)})")
        assertTrue(mean <= maxMean, "$name: mean channel diff ${"%.3f".format(mean)} > $maxMean")
    }

    @Test
    fun quantizationSlotReuseAgreesWithImageIo() {
        for (progressive in listOf(false, true)) {
            assertCloseToImageIo(quantizationJpeg(progressive), "table reuse progressive=$progressive", maxDiff = 2, maxMean = 1.0)
        }
    }

    @Test
    fun qualitySweepAgreesWithImageIo() {
        val img = photoish(64, 48)
        for (q in floatArrayOf(0.3f, 0.75f, 0.95f)) {
            assertCloseToImageIo(encodeJpeg(img, q), "q=$q")
        }
    }

    @Test
    fun oddSizesAgree() {
        for ((w, h) in listOf(1 to 1, 3 to 3, 15 to 17, 33 to 9)) {
            assertCloseToImageIo(encodeJpeg(photoish(w, h), 0.85f), "${w}x$h")
        }
    }

    @Test
    fun horizontalSubsamplingAgrees() {
        for ((w, h) in listOf(64 to 48, 33 to 9, 2 to 2, 1 to 5)) {
            assertCloseToImageIo(encodeJpeg(photoish(w, h), 0.85f, lumaSampling = 2 to 1), "4:2:2 ${w}x$h")
        }
    }

    /**
     * A sharp red border on gray, in 4:2:2, at an even and an odd width. The pixel next to
     * the edge takes the chroma of the nearer sample, the red one: stb weights the farther
     * gray one and was 88 levels off here (#69).
     */
    @Test
    fun horizontalSubsamplingKeepsTheRightEdge() {
        for (w in listOf(66, 65)) {
            val img = BufferedImage(w, 8, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 8) for (x in 0 until w) img.setRGB(x, y, if (x >= 64) 0xFF0000 else 0x808080)
            val jpeg = encodeJpeg(img, 1f, lumaSampling = 2 to 1)
            assertCloseToImageIo(jpeg, "4:2:2 edge, width $w", maxDiff = 6, maxMean = 1.0)
            val reference = ImageIO.read(jpeg.inputStream())!!
            val ours = ImageKodec.decode(jpeg)
            for (y in 0 until 8) for (x in 62 until w) {
                val e = reference.getRGB(x, y)
                val a = ours[x, y]
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF))
                    assertTrue(d <= 3, "width $w at ($x, $y): ${"%08x".format(a)} against ${"%08x".format(e)}")
                }
            }
        }
    }

    @Test
    fun grayscaleJpegAgrees() {
        val img = BufferedImage(40, 25, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until 25) for (x in 0 until 40) {
            val v = (x * 6 + y * 3) and 0xFF
            img.raster.setSample(x, y, 0, v)
        }
        assertCloseToImageIo(encodeJpeg(img, 0.9f), "gray")
    }

    @Test
    fun progressiveQualitySweepAgreesWithImageIo() {
        val img = photoish(64, 48)
        for (q in floatArrayOf(0.3f, 0.75f, 0.95f)) {
            assertCloseToImageIo(encodeJpeg(img, q, progressive = true), "progressive q=$q")
        }
    }

    /**
     * Restart intervals from 1 to more than the image holds, in 4:2:0, 4:2:2 and 4:4:4, baseline
     * and progressive. ImageIO writes the DRI marker and the RSTn markers, so the file proves
     * something only if it holds them, which the test checks first. This replaces a test that
     * read a file only macOS ships and passed without asserting anywhere else (#72).
     */
    @Test
    fun restartIntervalsAgree() {
        val img = photoish(77, 53)
        for (sampling in listOf(2 to 2, 2 to 1, 1 to 1)) for (progressive in listOf(false, true)) {
            for (interval in listOf(1, 2, 7, 100)) {
                val name = "luma ${sampling.first}x${sampling.second}, progressive $progressive, restart every $interval"
                val jpeg = encodeJpeg(img, 0.85f, progressive = progressive, lumaSampling = sampling, restartInterval = interval)
                val restarts = (0 until jpeg.size - 1).count { jpeg[it] == 0xFF.toByte() && (jpeg[it + 1].toInt() and 0xFF) in 0xD0..0xD7 }
                assertTrue(restarts > 0 || interval == 100, "$name: no restart marker in the file")
                assertCloseToImageIo(jpeg, name)
            }
        }
    }
}
