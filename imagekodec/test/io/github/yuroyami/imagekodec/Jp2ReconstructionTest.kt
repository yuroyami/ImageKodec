package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Native FFmpeg -pred 1 -layer_rates 4; OpenJPEG reconstructs each source exactly. */
class Jp2ReconstructionTest {
    internal fun encoded(size: Int = 16): ByteArray = hex(streams.getValue(size))

    @Test fun smallPartialBitplanesPreserveExactSamples() = checkExact(16)
    @Test fun mediumPartialBitplanesPreserveExactSamples() = checkExact(32)
    @Test fun largePartialBitplanesPreserveExactSamples() = checkExact(64)

    private fun checkExact(size: Int) {
        val result = assertNotNull(JpxDecoder.decode(encoded(size)))
        assertEquals(size, result.width); assertEquals(size, result.height)
        val expected = ByteArray(size * size) { i ->
            ((i % size) * 181 / (size - 1) + (i / size) * 71 / (size - 1)).toByte()
        }
        assertContentEquals(expected, result.pixelBytes, "size=$size")
    }

    private val streams = mapOf(
        16 to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020001ff5c00162040484850484850484850484850484850484850ff64001100014c61766336322e31312e313030ff90000a00000000004c0001ff93cf9810087f0000cfb00a3e6020016f02c7cc0e1f28400cfa170b3c608fc3e50907c8142218addf037fb0737fc1f18681f08c36a18455279f3da63427e122ffd9",
        32 to "ff4fff510029000000000020000000200000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020001ff5c00162040484850484850484850484850484850484850ff64001100014c61766336322e31312e313030ff90000a0000000000610001ff93cf9810083f00cfa4147d2080018f02dfc7d20e1f30300cfa150b3c62c3e70b07c4102218adf137037fb103c1f20881f08e36a18539180951af3da63427a11cf7c0f80580e9005fa7c7327f9dac29598b3e5e3bffd9",
        64 to "ff4fff510029000000000040000000400000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020001ff5c00162040484850484850484850484850484850484850ff64001100014c61766336322e31312e313030ff90000a00000000008f0001ff93cfa410081fcfa4147cc08001bf02bfc7cc0e1f18300cfa1f0b3c63c3e70b07c6102218ade5ba037fb41fc1f20b81f00836a1852f4f15d694718c7f3da6342fc0f84b80e1005fa7c733bf790536c8addf9dac295be029bd1e9bd1e40784f08022181749eeb55fa7c724b4ecdd22181749eeb55fa7c724b4ecdd9dac293f9dac293fffd9",
    )
}
