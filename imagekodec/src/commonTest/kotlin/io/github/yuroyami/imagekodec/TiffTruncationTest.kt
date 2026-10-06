package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** One grayscale strip, or one 16x16 tile, with data immediately after the IFD. */
internal fun tiffBlock(compression: Int, pixels: ByteArray, count: Int? = pixels.size, tiled: Boolean = false): ByteArray {
    val fields = mutableListOf(
        Triple(256, 4, if (tiled) 16 else 8), Triple(257, 4, if (tiled) 16 else 4),
        Triple(258, 3, 8), Triple(259, 3, compression), Triple(262, 3, 1), Triple(277, 3, 1),
    )
    if (tiled) {
        fields += Triple(322, 4, 16); fields += Triple(323, 4, 16)
    } else fields += Triple(278, 4, 4)
    val offsetTag = if (tiled) 324 else 273
    fields += Triple(offsetTag, 4, 0)
    if (count != null) fields += Triple(if (tiled) 325 else 279, 4, count)
    fields.sortBy { it.first }
    val pixelsAt = 8 + 2 + fields.size * 12 + 4
    val bytes = ByteArray(pixelsAt + pixels.size)
    fun put(at: Int, value: Int, length: Int) { repeat(length) { bytes[at + it] = (value ushr (it * 8)).toByte() } }
    bytes[0] = 'I'.code.toByte(); bytes[1] = 'I'.code.toByte()
    put(2, 42, 2); put(4, 8, 4); put(8, fields.size, 2)
    fields.forEachIndexed { i, (tag, type, value) ->
        val at = 10 + i * 12
        put(at, tag, 2); put(at + 2, type, 2); put(at + 4, 1, 4)
        put(at + 8, if (tag == offsetTag) pixelsAt else value, 4)
    }
    pixels.copyInto(bytes, pixelsAt)
    return bytes
}

class TiffTruncationTest {
    @Test
    fun uncompressedBlocksNeedAllGeometryBytes() {
        for (tiled in listOf(false, true)) {
            val size = if (tiled) 256 else 32
            for (present in listOf(0, size / 2, size - 1)) {
                val error = assertFailsWith<ImageDecodeException> {
                    ImageKodec.decode(tiffBlock(1, ByteArray(present), count = size, tiled = tiled))
                }
                assertTrue("block" in error.message.orEmpty(), error.message.orEmpty())
            }
        }
    }

    @Test
    fun uncompressedByteCountsCanBeRecoveredWhenBytesExist() {
        for (tiled in listOf(false, true)) {
            val size = if (tiled) 256 else 32
            for (count in listOf(null, 0, 1, size / 2, size, size + 100)) {
                val bitmap = ImageKodec.decode(tiffBlock(1, ByteArray(size) { it.toByte() }, count, tiled))
                for (i in 0 until size) assertEquals(gray(i and 255), bitmap.argb[i])
            }
        }
    }

    @Test
    fun packBitsMustFillTheBlock() {
        for (tiled in listOf(false, true)) {
            val shortPackets = listOf(ByteArray(0), byteArrayOf(-128), byteArrayOf(3, 1, 2, 3, 4),
                byteArrayOf(-15, 42), byteArrayOf(3, 1, 2))
            for (payload in shortPackets) assertFailsWith<ImageDecodeException> {
                ImageKodec.decode(tiffBlock(32773, payload, tiled = tiled))
            }
        }
    }

    @Test
    fun compressedEarlyEndCannotPadTheBlock() {
        for (tiled in listOf(false, true)) {
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(tiffBlock(8, Zlib.compress(ByteArray(4)), tiled = tiled)) }
            // Nine-bit MSB-first codes: clear (256), end-of-information (257).
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(tiffBlock(5, hex("804040"), tiled = tiled)) }
        }
    }
}
