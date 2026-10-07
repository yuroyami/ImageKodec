package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals

class TiffFaxTest {
    @Test
    fun faxRunColorsFollowTheTiffPhotometricTag() {
        // T.4: four white-1/black-1 pairs, then byte padding for Modified Huffman.
        val mhRow = "000111010".repeat(4).padEnd(40, '0')
        // T.6: horizontal white-1/black-1 pairs, then an identical reference row.
        val g4 = "001000111010".repeat(4) + "11111111"
        for ((compression, stream) in listOf(2 to (mhRow + mhRow), 4 to g4)) {
            for (photometric in listOf(0, 1)) {
                val bitmap = ImageKodec.decode(faxTiff(faxBits(stream), compression, photometric))
                for (y in 0..1) for (x in 0..7) {
                    val black = (x % 2 == 1) == (photometric == 0)
                    assertEquals(gray(if (black) 0 else 255), bitmap[x, y], "$compression/$photometric at $x, $y")
                }
            }
        }
    }
}

internal fun faxBits(bits: String): ByteArray = ByteArray((bits.length + 7) / 8) { byte ->
    var value = 0
    for (i in 0..7) {
        value = value shl 1
        if (byte * 8 + i < bits.length && bits[byte * 8 + i] == '1') value++
    }
    value.toByte()
}

internal fun faxTiff(
    compressed: ByteArray,
    compression: Int,
    photometric: Int = 0,
    width: Int = 8,
    height: Int = 2,
    t4Options: Int = 0,
): ByteArray {
    val fields = mutableListOf(
        intArrayOf(256, 4, width), intArrayOf(257, 4, height), intArrayOf(258, 3, 1),
        intArrayOf(259, 3, compression), intArrayOf(262, 3, photometric),
        intArrayOf(273, 4, 0), intArrayOf(277, 3, 1), intArrayOf(278, 4, height),
        intArrayOf(279, 4, compressed.size),
    )
    if (compression == 3) fields.add(intArrayOf(292, 4, t4Options))
    fields.sortBy { it[0] }
    val pixelsAt = 8 + 2 + fields.size * 12 + 4
    val out = ByteArray(pixelsAt + compressed.size)
    var at = 0
    fun u16(value: Int) { out[at++] = value.toByte(); out[at++] = (value ushr 8).toByte() }
    fun u32(value: Int) { u16(value); u16(value ushr 16) }
    out[at++] = 'I'.code.toByte(); out[at++] = 'I'.code.toByte(); u16(42); u32(8)
    u16(fields.size)
    for ((tag, type, value) in fields) {
        u16(tag); u16(type); u32(1)
        u32(if (tag == 273) pixelsAt else value)
    }
    u32(0)
    compressed.copyInto(out, pixelsAt)
    return out
}
