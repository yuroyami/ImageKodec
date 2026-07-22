package io.github.yuroyami.kiteimage

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
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

    private fun encodeJpeg(img: BufferedImage, quality: Float, progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
            if (progressive) progressiveMode = ImageWriteParam.MODE_DEFAULT
        }
        val out = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, null), param)
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
        val ours = KiteImage.decode(jpeg)
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

    @Test
    fun realWorldRestartMarkerFile() {
        // macOS ships a large baseline JPEG with DRI/RSTn restart markers. Skip
        // silently on machines that don't have it: commonTest still covers the
        // codec; this adds a real-camera-pipeline file with restarts.
        val f = File("/System/Library/CoreServices/DefaultBackground.jpg")
        if (!f.exists()) return
        val bytes = f.readBytes()
        // confirm it really has a DRI marker, else the test proves nothing
        var i = 2
        var hasDri = false
        while (i < bytes.size - 4) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) break
            val m = bytes[i + 1].toInt() and 0xFF
            if (m == 0xDD) { hasDri = true; break }
            if (m == 0xDA) break
            i += 2 + (((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF))
        }
        if (!hasDri) return
        assertCloseToImageIo(bytes, "DefaultBackground.jpg (restart intervals)")
    }
}
