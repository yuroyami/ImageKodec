package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** SIZ fields are addressed relative to its marker, in network byte order. */
internal fun jp2WithGeometry(vararg fields: Pair<Int, Long>): ByteArray {
    val bytes = hex(JP2)
    val siz = (0 until bytes.size - 1).first {
        bytes[it] == 0xff.toByte() && bytes[it + 1] == 0x51.toByte()
    }
    for ((offset, value) in fields) repeat(4) { i ->
        bytes[siz + offset + i] = (value ushr (24 - i * 8)).toByte()
    }
    return bytes
}

internal fun invalidJp2Geometries(): List<ByteArray> = listOf(
    jp2WithGeometry(30 to 0x007fffffL, 34 to 0xf8000000L),
    jp2WithGeometry(30 to 1L),
    jp2WithGeometry(34 to 1L),
    jp2WithGeometry(22 to 0L),
    jp2WithGeometry(26 to 0L),
    jp2WithGeometry(6 to 34L, 14 to 2L, 22 to 2L),
    jp2WithGeometry(10 to 26L, 18 to 2L, 26 to 2L),
    jp2WithGeometry(6 to 65536L, 10 to 1L, 22 to 1L, 26 to 1L),
)

class Jp2GeometryTest {
    @Test
    fun malformedTileGeometryIsRefusedBeforeProcessing() {
        val duration = measureTime {
            for (bytes in invalidJp2Geometries()) {
                val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
                assertTrue("tile" in error.message.orEmpty(), error.message.orEmpty())
                assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(bytes, 2) }
                assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
                assertNull(JpxDecoder.decode(bytes))
            }
        }
        assertTrue(duration < 1.seconds, "invalid grids took $duration")
    }

    @Test
    fun tileIndexMustBelongToTheGrid() {
        val bytes = hex(JP2)
        val sot = (0 until bytes.size - 1).first {
            bytes[it] == 0xff.toByte() && bytes[it + 1] == 0x90.toByte()
        }
        bytes[sot + 5] = 1 // one-tile grid, tile index 1
        val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue("tile index" in error.message.orEmpty(), error.message.orEmpty())
        assertNull(JpxDecoder.decode(bytes))
    }
}
