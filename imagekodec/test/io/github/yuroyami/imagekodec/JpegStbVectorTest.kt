package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JpegStbVectors] decoded bit for bit as stb_image decodes them: restart intervals in baseline
 * and progressive files, a one-component file, CMYK and YCCK, and the 4:2:2, 4:1:1, 4:4:0 and 4x2
 * samplings, which [JpegDecoderTest]'s ffmpeg vectors do not cover (#72).
 */
class JpegStbVectorTest {

    @Test
    fun everyVectorMatchesStbExactly() {
        for (v in JpegStbVectors.all) {
            val bitmap = ImageKodec.decode(v.jpeg)
            assertEquals(v.width, bitmap.width, v.name)
            assertEquals(v.height, bitmap.height, v.name)
            for (y in 0 until v.height) for (x in 0 until v.width) {
                val e = (y * v.width + x) * 3
                val expected = argb(0xFF, v.rgb[e].toInt() and 0xFF, v.rgb[e + 1].toInt() and 0xFF, v.rgb[e + 2].toInt() and 0xFF)
                assertEquals(expected, bitmap[x, y], "${v.name} at ($x, $y)")
            }
        }
    }

    @Test
    fun theRestartVectorsHoldRestartMarkers() {
        // Without them the restart vectors would prove nothing about restarts.
        for (v in JpegStbVectors.all.filter { "restart" in it.name }) {
            val markers = (0 until v.jpeg.size - 1).count { v.jpeg[it] == 0xFF.toByte() && (v.jpeg[it + 1].toInt() and 0xFF) in 0xD0..0xD7 }
            assertTrue(markers >= 2, "${v.name} holds $markers restart markers")
        }
    }
}
