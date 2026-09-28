package io.github.yuroyami.kiteimage

import io.github.yuroyami.kiteimage.codec.Jbig2Decoder
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JBIG2 data carries no size in the PDF, so the caller passes the width and height, and every
 * segment header states sizes of its own. None of them may be trusted.
 */
class Jbig2DecoderTest {

    /** One segment: number 0, the [type], no referred-to segments, page 1, then [data]. */
    private fun segment(type: Int, data: ByteArray) =
        beBytes(0) + byteArrayOf(type.toByte(), 0, 1) + beBytes(data.size) + data

    /** A page information segment (type 48) for a [width] by [height] page. */
    private fun page(width: Int, height: Int) =
        segment(48, beBytes(width) + beBytes(height) + beBytes(0) + beBytes(0) + byteArrayOf(0, 0, 0))

    @Test
    fun aBlankPageDecodes() {
        assertNotNull(Jbig2Decoder.decode(page(8, 8), null, 8, 8))
    }

    @Test
    fun aSizeThatIsNotPositiveOrPastTheCeilingIsNull() {
        val page = page(8, 8)
        assertNull(Jbig2Decoder.decode(page, null, 0, 8))
        assertNull(Jbig2Decoder.decode(page, null, 8, 0))
        assertNull(Jbig2Decoder.decode(page, null, 8, -1))
        assertNull(Jbig2Decoder.decode(page, null, 1 shl 20, 1 shl 20))
    }

    @Test
    fun aPageInformationSegmentThatClaimsMoreThanTheCeilingIsNull() {
        // 2^20 by 2^10 is 2^30 pixels, one byte each: a gigabyte for a 30-byte stream.
        assertNull(Jbig2Decoder.decode(page(1 shl 20, 1 shl 10), null, 8, 8))
    }

    @Test
    fun aSegmentHeaderCutOffInsideItsReferenceListIsNull() {
        // The flags byte says five referred-to segments, and the data ends before the page number. An
        // unchecked reader indexes past the array, which is a trap on webassembly, not an exception.
        val header = byteArrayOf(0, 0, 0, 0, 0x30, 0xA0.toByte(), 0, 0, 0, 0, 0)
        assertNull(Jbig2Decoder.decode(header, null, 8, 8))
    }
}
