package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CcittReferenceTest {
    private fun decode(bits: String, width: Int, rows: Int, blackIs1: Boolean = true): ByteArray =
        CcittFax.decode(faxBits(bits), -1, CcittOptions(width, rows, false, blackIs1, false, false))

    @Test
    fun horizontalAndVerticalAlternatingRowsAgreeAtWideWidths() {
        for (width in listOf(2, 8, 64, 4096, 65536)) for (blackIs1 in listOf(true, false)) {
            val bits = "001000111010".repeat(width / 2) + "1".repeat(width)
            val row = ByteArray((width+7)/8) { if (blackIs1) 0x55 else 0xAA.toByte() }
            if (width == 2) row[0] = if (blackIs1) 0x40 else 0x80.toByte()
            assertContentEquals(row + row, decode(bits, width, 2, blackIs1))
        }
    }

    @Test
    fun everyVerticalOffsetKeepsBothReferenceColors() {
        val modes = listOf("0000010", "000010", "010", "1", "011", "000011", "0000011")
        for ((i,mode) in modes.withIndex()) {
            val offset = i-3
            // Reference: white 4, black 4. Coding: white 4+offset, black to edge.
            assertContentEquals(byteArrayOf(0x0F, ((1 shl (4-offset))-1).toByte()),
                decode("0011011011" + mode + "1", 8, 2))
            // Reference starts black: H(W0,B4), H(W4,B0). V0 changes at column 0.
            val black = ((0xFF shl (4-offset)) and 255).toByte()
            assertContentEquals(byteArrayOf(0xF0.toByte(), black),
                decode(("001" + "00110101" + "011" + "001" + "1011" + "0000110111") + "1" + mode + "1", 8, 2))
        }
    }

    @Test
    fun verticalModesCannotMoveBackwardsOrPastTheEdge() {
        for (bits in listOf("011", "0011011011" + "0000011" + "0000010")) {
            val error = assertFailsWith<ImageDecodeException> { decode(bits, 8, 2) }
            assertTrue(error.message.orEmpty().contains("vertical"))
        }
    }

    @Test
    fun passModeReusesTheNextReferencePair() {
        // Reference: W2 B2 W2 B2. Two pass codes produce an all-white coding row.
        assertContentEquals(byteArrayOf(0x33, 0), decode("001011111".repeat(2) + "00010001", 8, 2))
    }
}
