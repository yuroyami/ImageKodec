package io.github.yuroyami.kiteimage

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GIF cross-check against a real encoder. ImageIO's GIF writer produces genuine
 * LZW: dictionary growth, code-width bumps, mid-stream clears on full tables -
 * exactly the paths the literal-only commonTest vectors deliberately avoid.
 */
class GifImageIoTest {

    /** ImageIO auto-quantizes RGB input; feeding indexed images keeps pixels exact. */
    private fun randomIndexed(w: Int, h: Int, seed: Long): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED)
        val rng = Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            img.setRGB(x, y, (0xFF shl 24) or rng.nextInt(1 shl 24))
        }
        return img
    }

    @Test
    fun staticGifRealLzwRoundTrip() {
        // 64x64 random = thousands of dictionary entries → several code-width bumps.
        for ((w, h) in listOf(1 to 1, 5 to 3, 64 to 64, 130 to 7)) {
            val img = randomIndexed(w, h, seed = 1000L * w + h)
            val out = ByteArrayOutputStream()
            check(ImageIO.write(img, "gif", out))
            val decoded = KiteImage.decode(out.toByteArray())
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
            for (y in 0 until h) for (x in 0 until w) {
                assertEquals(img.getRGB(x, y), decoded[x, y], "${w}x$h pixel ($x,$y)")
            }
        }
    }

    @Test
    fun solidColorGifMaxCompression() {
        // Long runs of one index: LZW's best case, exercises deep prefix chains.
        val img = BufferedImage(97, 41, BufferedImage.TYPE_BYTE_INDEXED)
        val g = img.createGraphics()
        g.color = java.awt.Color(0x33, 0x66, 0x99)
        g.fillRect(0, 0, 97, 41)
        g.dispose()
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "gif", out))
        val decoded = KiteImage.decode(out.toByteArray())
        for (y in 0 until 41) for (x in 0 until 97) {
            assertEquals(img.getRGB(x, y), decoded[x, y], "($x,$y)")
        }
    }

    @Test
    fun animatedGifWrittenByImageIo() {
        // Three full-rect frames, disposal=none, 20 cs delays, infinite loop.
        val frames = (0 until 3).map { randomIndexed(16, 16, seed = 7L + it) }

        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val bytes = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(bytes).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            for (frame in frames) {
                val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier(frame), null)
                val fmt = meta.nativeMetadataFormatName
                val root = meta.getAsTree(fmt) as IIOMetadataNode

                val gce = IIOMetadataNode("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", "20")
                    setAttribute("transparentColorIndex", "0")
                }
                root.appendChild(gce)

                val appExts = IIOMetadataNode("ApplicationExtensions")
                val appExt = IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(0x1, 0x0, 0x0)   // loop forever
                }
                appExts.appendChild(appExt)
                root.appendChild(appExts)

                meta.setFromTree(fmt, root)
                writer.writeToSequence(IIOImage(frame, null, meta), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()

        val anim = KiteImage.decodeAnimation(bytes.toByteArray())
        assertEquals(3, anim.frames.size)
        assertEquals(0, anim.loopCount)   // NETSCAPE 0 = forever
        for ((i, frame) in frames.withIndex()) {
            val decoded = anim.frames[i]
            assertEquals(200, decoded.delayMillis, "frame $i delay")
            for (y in 0 until 16) for (x in 0 until 16) {
                // Full-rect disposal-none frames: composited canvas == frame pixels.
                assertEquals(frame.getRGB(x, y), decoded.bitmap[x, y], "frame $i ($x,$y)")
            }
        }
    }
}
