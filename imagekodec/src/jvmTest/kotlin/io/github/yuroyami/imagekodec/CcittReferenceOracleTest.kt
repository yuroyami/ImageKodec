package io.github.yuroyami.imagekodec

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CcittReferenceOracleTest {
    @Test
    fun aScannedPageWidthDecodesToItsSource() {
        // A band of a 600 dpi letter page: 5100 columns of serif text with scanner speckle, so rows hold
        // runs past the 2560 makeup code, short runs and every coding mode, written whole bytes at a
        // time where the runs are long (#105).
        val page = BufferedImage(5100, 400, BufferedImage.TYPE_BYTE_BINARY)
        val graphics = page.createGraphics()
        graphics.color = java.awt.Color.WHITE
        graphics.fillRect(0, 0, page.width, page.height)
        graphics.color = java.awt.Color.BLACK
        graphics.font = java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 92)
        val random = Random(105)
        for (line in 0 until 3) {
            graphics.drawString((0 until 12).joinToString(" ") { "word${random.nextInt(1000)}" }, 600, 110 + line * 130)
        }
        repeat(4000) { graphics.fillRect(random.nextInt(page.width), random.nextInt(page.height), 1 + random.nextInt(3), 1 + random.nextInt(2)) }
        graphics.dispose()
        val encoded = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("TIFF").next()
        try {
            ImageIO.createImageOutputStream(encoded).use { output ->
                writer.output = output
                val params = writer.defaultWriteParam
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionType = "CCITT T.6"
                writer.write(null, IIOImage(page, null, null), params)
            }
        } finally { writer.dispose() }
        val bitmap = ImageKodec.decode(encoded.toByteArray())
        assertEquals(page.width, bitmap.width)
        assertEquals(page.height, bitmap.height)
        for (y in 0 until page.height) for (x in 0 until page.width) {
            if (page.getRGB(x, y) != bitmap[x, y]) assertEquals(page.getRGB(x, y), bitmap[x, y], "at $x,$y")
        }
    }

    @Test
    fun imageIoWrittenChangingRunsAgreeWithLibtiff() {
        assumeTrue("libtiff not installed", Tools.hasAll("tiff2rgba"))
        for (width in listOf(7, 13, 64, 1024, 8192)) {
            val image = BufferedImage(width, 32, BufferedImage.TYPE_BYTE_BINARY)
            val random = Random(61)
            for (y in 0 until image.height) for (x in 0 until width) {
                val shift = (y % 7)-3
                val black = when (y % 8) {
                    0 -> (x and 1) != 0
                    1 -> ((x-shift) and 1) != 0
                    2 -> x >= width/3 + shift && x < 2*width/3 + shift
                    3 -> x == 0 || x == width-1
                    4 -> false
                    5 -> true
                    6 -> (x+shift).mod(13) < 6
                    else -> random.nextBoolean()
                }
                image.setRGB(x,y,if (black) 0xFF000000.toInt() else -1)
            }
            val encoded = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("TIFF").next()
            try {
                ImageIO.createImageOutputStream(encoded).use { output ->
                    writer.output = output
                    val params = writer.defaultWriteParam
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionType = "CCITT T.6"
                    writer.write(null,IIOImage(image,null,null),params)
                }
            } finally { writer.dispose() }
            val bytes = encoded.toByteArray()
            val input = File.createTempFile("imagekodec-reference-fax", ".tif").apply { deleteOnExit();writeBytes(bytes) }
            val output = File.createTempFile("imagekodec-reference-fax-rgba", ".tif").apply { deleteOnExit() }
            val log = File.createTempFile("imagekodec-reference-fax", ".log").apply { deleteOnExit() }
            val process = ProcessBuilder(Tools.require("tiff2rgba").path,"-c","none",input.path,output.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(30,TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished,"libtiff timed out")
            assertEquals(0,process.exitValue(),log.readText())
            val bitmap = ImageKodec.decode(bytes)
            // ImageIO's G4 reader misreads a trailing edge in the 7-column
            // fixture. libtiff independently decodes the ImageIO-written data
            // and is compared against both the source page and this decoder.
            val reference = assertNotNull(ImageIO.read(output))
            for (y in 0 until image.height) for (x in 0 until width) {
                val expected = image.getRGB(x,y)
                assertEquals(expected, reference.getRGB(x,y),"libtiff/$width at $x,$y")
                assertEquals(expected, bitmap[x,y],"ImageKodec/$width at $x,$y")
            }
        }
    }
}
