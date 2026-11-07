package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.ByteArrayBuilder
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Output sizes near the largest array, where an Int capacity used to wrap to 0 and spin (#127). */
class InflateSizeTest {

    /** 16384 by 16384 RGBA at 16 bits inflates to 2,147,500,032 bytes, just past Int.MAX_VALUE. */
    private fun hugePng(): ByteArray =
        PNG_SIGNATURE +
            pngHeader(16384, 16384, bitDepth = 16, colorType = 6) +
            // Enough input that the expansion budget alone would let 2^28 pixels through.
            pngChunk("IDAT", Zlib.compress(ByteArray(1 shl 20))) +
            pngChunk("IEND", ByteArray(0))

    @Test
    fun aPngWhoseRowsCannotFitInOneArrayIsRefusedBeforeInflate() {
        val png = hugePng()
        val e = assertFailsWith<ImageDecodeException> { ImageKodec.decode(png) }
        assertTrue(e.message!!.contains("largest array"), "message: ${e.message}")
    }

    @Test
    fun theProbeAgreesThatSuchAPngCannotDecode() {
        val info = ImageKodec.probe(hugePng())
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("largest array"), "reason: ${info.unsupportedReason}")
    }

    @Test
    fun aStreamPastTheLimitFailsInsteadOfWrapping() {
        // No size hint, a ceiling above Int.MAX_VALUE: the buffer grows by doubling and
        // must stop at the ceiling rather than wrap. A ceiling of 1 MiB shows the bound.
        val stream = Zlib.compress(ByteArray(3 shl 20))
        assertFailsWith<Exception> { Zlib.decompress(stream, maximumSize = 1L shl 20, sizeHint = 0) }
        assertEquals(3 shl 20, Zlib.decompress(stream, maximumSize = Long.MAX_VALUE, sizeHint = 0).size)
    }

    @Test
    fun aBuilderWithNoCapacityGrows() {
        val b = ByteArrayBuilder(0)
        b.append(ByteArray(1000))
        assertEquals(1000, b.toByteArray().size)
    }
}
