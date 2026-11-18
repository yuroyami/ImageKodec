package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals

class PictureHashTest {

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun md5MatchesTheTestSuiteOfRfc1321() {
        // RFC 1321, appendix A.5.
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(PictureHash.md5("".encodeToByteArray())))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(PictureHash.md5("abc".encodeToByteArray())))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", hex(PictureHash.md5("message digest".encodeToByteArray())))
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            hex(PictureHash.md5("1234567890".repeat(8).encodeToByteArray())),
        )
    }

    @Test
    fun pictureDataPutsTheLowByteFirstAboveEightBits() {
        val samples = shortArrayOf(0x0123, 0x0345, 7, 7)
        assertEquals(listOf(0x23, 0x01, 0x45, 0x03), PictureHash.pictureData(samples, 0, 4, 2, 1, 10).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x23, 0x45), PictureHash.pictureData(samples, 0, 4, 2, 1, 8).map { it.toInt() and 0xFF })
    }

    @Test
    fun annexBSplitsWhatItJoins() {
        val units = listOf(byteArrayOf(0x40, 1, 2), byteArrayOf(0x42, 1, 0, 0, 5), byteArrayOf(0x26, 1, 9))
        val back = AnnexB.split(AnnexB.join(units))
        assertEquals(units.map { it.toList() }, back.map { it.toList() })
        val hvcC = AnnexB.hvcC(units.take(2), lengthSize = 2)
        assertEquals(units.take(2).map { it.toList() }, AnnexB.parameterSets(hvcC).map { it.toList() })
        assertEquals(2, AnnexB.lengthSize(hvcC))
        assertEquals(listOf(units[2].toList()), AnnexB.nalUnits(AnnexB.sample(units.drop(2), 2), 2).map { it.toList() })
    }
}
