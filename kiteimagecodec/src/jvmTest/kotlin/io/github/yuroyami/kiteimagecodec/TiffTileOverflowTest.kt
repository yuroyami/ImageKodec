package io.github.yuroyami.kiteimagecodec

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A tile row is `width * samples * bits / 8` bytes. With 2^24 columns of 8 samples at
 * 16 bits that is 2^28 bytes, and the same product taken in Int wraps to a negative
 * number. Any file that triggers the wrap needs a tile of 256 MiB, so this lives in
 * jvmTest, where the heap allows it, and not in commonTest, which every target runs.
 */
class TiffTileOverflowTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun le32(v: Int) = le16(v) + le16(v ushr 16)

    /** A 1x1 little-endian TIFF whose only tile is 8 bytes long. Tags are (tag, type, value). */
    private fun tiledTiff(tileWidth: Int, samplesPerPixel: Int, bitsPerSample: Int): ByteArray {
        val fields = listOf(
            Triple(256, 3, 1), Triple(257, 3, 1), Triple(258, 3, bitsPerSample), Triple(259, 3, 1),
            Triple(262, 3, 2), Triple(277, 3, samplesPerPixel), Triple(322, 4, tileWidth),
            Triple(323, 4, 1), Triple(324, 4, 0), Triple(325, 4, 8),
        )
        val tileAt = 8 + 2 + fields.size * 12 + 4
        var out = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0) + le32(8) + le16(fields.size)
        for ((tag, type, value) in fields) {
            out += le16(tag) + le16(type) + le32(1) + le32(if (tag == 324) tileAt else value)
        }
        return out + le32(0) + ByteArray(8)
    }

    @Test
    fun aTileRowThatOverflowsAnIntIsNotANegativeArraySize() {
        // 2^24 * 8 * 16 = 2^31 bits: one past the largest Int.
        val failure = runCatching { KiteImageCodec.decode(tiledTiff(1 shl 24, 8, 16)) }.exceptionOrNull()
        assertTrue(failure == null || failure is ImageDecodeException, "expected a bitmap or a decode error, got $failure")
    }
}
