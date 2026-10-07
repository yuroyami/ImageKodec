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
