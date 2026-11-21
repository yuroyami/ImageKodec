package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TIFF strips compressed with Zstandard (50000) and with LZMA2 in an xz stream (34925). The
 * payloads come from the `zstd` and `xz` command-line tools, so the decoder reads another encoder's bytes.
 */
class TiffZstdLzmaTest {

    // An 8 x 4 gray strip: (x % 4) * 60 + y / 2.
    private val pixels = ByteArray(32) { ((it % 4) * 60 + it / 16).toByte() }

    // `zstd -19`, one compressed block.
    private val zstd = hex("28b52ffd24208d000048003c78b4013d79b5010200c0d0b84b04d82fbf81")

    // `xz --check=none`, one compressed LZMA2 chunk.
    private val xz = hex(
        "fd377a585a000000ff12d94104c01720210116000000000000000000b5e85d64e0001f000f5d0000100b7a4995ff7c" +
            "7288fa4a960a00000000012b208a68986d06729e7a010000000000595a",
    )

    @Test
    fun zstdStripDecodes() = assertGray(tiffBlock(50000, zstd))

    @Test
    fun lzmaStripDecodes() = assertGray(tiffBlock(34925, xz))

    @Test
    fun probeCallsBothDecodable() {
        assertTrue(ImageKodec.probe(tiffBlock(50000, zstd)).isDecodable)
        assertTrue(ImageKodec.probe(tiffBlock(34925, xz)).isDecodable)
    }

    @Test
    fun damagedStreamsFailAsDecodeErrors() {
        for ((compression, payload) in listOf(50000 to zstd, 34925 to xz)) {
            val cut = payload.copyOf(payload.size - 6)
            assertFailsWith<ImageDecodeException>("compression $compression") {
                ImageKodec.decode(tiffBlock(compression, cut))
            }
        }
    }

    @Test
    fun streamsLongerThanTheStripFail() {
        // 64 bytes of output for a 32-byte strip: `zstd -19` of the pattern written twice.
        val twice = hex("28b52ffd24409d000048003c78b4013d79b5000300433502700477894a3797c9")
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(tiffBlock(50000, twice)) }
    }

    private fun assertGray(tiff: ByteArray) {
        val bitmap = ImageKodec.decode(tiff)
        assertEquals(8, bitmap.width)
        assertEquals(4, bitmap.height)
        for (i in pixels.indices) {
            val v = pixels[i].toInt() and 0xFF
            assertEquals(argb(0xFF, v, v, v), bitmap.argb[i], "pixel $i")
        }
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
