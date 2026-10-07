package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The spatial progressions with more than one precinct per resolution and chroma subsampled
 * 2x2, losslessly: OpenJPEG 2.5 wrote each from the same 48 by 32 planar raw source with
 * `opj_compress -F 48,32,3,8,u@1x1:2x2:2x2 -n 3 -b 4,4 -c [8,8],[8,8],[8,8] -p <order>`, so each
 * must decode to the source exactly. Pairing precincts by ordinal put packets in the wrong
 * precincts in all three orders (#56).
 */
class Jp2ProgressionTest {

    private val rpcl: ByteArray = Base64.decode(
        "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" +
        "qz6nJM4H/9k=",
    )

    private val pcrl: ByteArray = Base64.decode(
        "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" +
        "qz6nJM4H/9k=",
    )

    private val cprl: ByteArray = Base64.decode(
        "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" +
        "UqzTPdw6/9k=",
    )

    /** The source: luma (5x + 9y) mod 256, and each chroma plane at half size (13x xor 7y xor 90c) mod 256. */
    private fun source(x: Int, y: Int, c: Int): Int =
        if (c == 0) (x * 5 + y * 9) % 256 else ((x / 2 * 13) xor (y / 2 * 7) xor (c * 90)) % 256

    @Test
    fun everySpatialOrderDecodesTheSourceExactly() {
        for ((name, bytes) in listOf("RPCL" to rpcl, "PCRL" to pcrl, "CPRL" to cprl)) {
            val r = JpxDecoder.decodeForFacade(bytes, 1)
            assertEquals(48, r.width, name)
            assertEquals(32, r.height, name)
            for (y in 0 until 32) for (x in 0 until 48) for (c in 0 until 3) {
                assertEquals(source(x, y, c), r.pixelBytes[(y * 48 + x) * 3 + c].toInt() and 0xFF, "$name at ($x, $y), component $c")
            }
        }
    }
}
