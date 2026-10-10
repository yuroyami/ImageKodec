package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CcittEolTest {
    @Test
    fun group3EolsAndFillBitsPreserveTiffPixels() {
        for (fill in listOf(false, true)) for (photometric in listOf(0, 1)) {
            val bitmap = ImageKodec.decode(faxTiff(group3EolStream(fill), 3, photometric,
                width = 7, t4Options = if (fill) 4 else 0))
            for (y in 0..1) for (x in 0..6) {
                val black = (x % 2 == 1) == (photometric == 0)
                assertEquals(gray(if (black) 0 else 255), bitmap[x, y], "$fill/$photometric at $x, $y")
            }
        }
    }

    @Test
    fun optionalEolRecognitionWorksWithEitherHint() {
        for (hint in listOf(false, true)) for (fill in listOf(false, true)) {
            assertContentEquals(byteArrayOf(0x54, 0x54), CcittFax.decode(group3EolStream(fill), 0,
                CcittOptions(7, 2, false, true, false, hint)))
        }
    }

    @Test
    fun modifiedHuffmanWithoutEolsKeepsItsBitsWithEitherHint() {
        val row = ("000111010".repeat(3) + "000111").padEnd(40, '0')
        for (hint in listOf(false, true)) {
            assertContentEquals(byteArrayOf(0x54, 0x54), CcittFax.decode(faxBits(row + row), 0,
                CcittOptions(7, 2, false, true, true, hint)))
        }
    }

    @Test
    fun unknownHeightStopsAtPaddingOrTheReturnToControlSequence() {
        for (fill in listOf(false, true)) {
            assertContentEquals(byteArrayOf(0x54, 0x54), CcittFax.decode(group3EolStream(fill), 0,
                CcittOptions(7, 0, false, true, false, true)))
        }
        val rtc = "000000000001".repeat(6)
        val framed = faxBits("000000000001" + "10011" + rtc)
        assertContentEquals(byteArrayOf(0), CcittFax.decode(framed, 0,
            CcittOptions(8, 0, true, true, false, true)))
    }

    @Test
    fun incompleteFaxStripsNeverBecomeBlankRows() {
        val mh = faxBits(("000111010".repeat(3) + "000111").padEnd(40, '0'))
        val g4 = faxBits("001000111010".repeat(4) + "11111111")
        for (bytes in listOf(
            faxTiff(mh, 2, width = 7),
            faxTiff(group3EolStream(false), 3, width = 7, height = 3),
            faxTiff(g4, 4, height = 3),
        )) assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        for (fill in listOf(false, true)) {
            val stream = group3EolStream(fill)
            for (cut in stream.indices) {
                val bytes = faxTiff(stream.copyOf(cut), 3, width = 7, t4Options = if (fill) 4 else 0)
                assertFailsWith<ImageDecodeException>("fill=$fill cut=$cut") { ImageKodec.decode(bytes) }
            }
        }
    }

    @Test
    fun invalidRowLengthsAndMidRowEolsNameADecodeFault() {
        val overshoot = faxTiff(faxBits("10011"), 2, width = 7, height = 1)
        val earlyEol = faxTiff(faxBits("000000000001000111000000000001"), 3, width = 7, height = 1)
        for (bytes in listOf(overshoot, earlyEol)) {
            assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        }
    }
}

/** T.4 white-1/black-1 pairs and a last white-1 run, preceded by an EOL per row. */
internal fun group3EolStream(fill: Boolean): ByteArray {
    val bits = StringBuilder()
    repeat(2) {
        if (fill) repeat((8 - (bits.length + 12) % 8) % 8) { bits.append('0') }
        bits.append("000000000001")
        bits.append("000111010".repeat(3)).append("000111")
    }
    return faxBits(bits.toString())
}
