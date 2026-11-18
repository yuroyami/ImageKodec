package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Deflate
import io.github.yuroyami.imagekodec.internal.flate.Inflate
import io.github.yuroyami.imagekodec.internal.flate.InflateError
import io.github.yuroyami.imagekodec.internal.flate.InflateException
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The zlib trailer (#160), the output limit (#161), fixed blocks (#162) and the encoder's memory (#163). */
class ZlibTrailerTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun aWrongOrMissingAdler32IsRefused() {
        // "a", as zlib 1.2.12 writes it, then with its checksum zeroed, then without it.
        assertContentEquals("a".encodeToByteArray(), Zlib.decompress(hex("789c4b040000620062"), maximumSize = 1))
        val wrong = assertFailsWith<InflateException> { Zlib.decompress(hex("789c4b040000000000"), maximumSize = 1) }
        assertEquals(InflateError.INCORRECT_DATA_CHECK, wrong.error)
        val missing = assertFailsWith<InflateException> { Zlib.decompress(hex("789c4b0400"), maximumSize = 1) }
        assertEquals(InflateError.MISSING_DATA_CHECK, missing.error)
        // PNG's chunks carry their own CRC, so it reads past the trailer, as the png crate does.
        assertContentEquals("a".encodeToByteArray(), Zlib.decompress(hex("789c4b040000000000"), maximumSize = 1, checkAdler = false))
    }

    @Test
    fun theTrailerIsFoundAfterAStoredAndADynamicBlock() {
        val data = ByteArray(70_000) { (it * 7 % 251).toByte() } + Random(3).nextBytes(5_000)
        assertContentEquals(data, Zlib.decompress(Zlib.compress(data), maximumSize = data.size.toLong()))
    }

    @Test
    fun theOutputLimitHoldsBelowTheFirstBuffer() {
        val twenty = hex("78da6360c0040000140001")   // 20 zero bytes
        val twelve = hex("78da6360400000000c0001")   // 12 zero bytes
        for ((input, hint) in listOf(twenty to 0, twelve to 10, twenty to 20)) {
            val e = assertFailsWith<InflateException> { Zlib.decompress(input, maximumSize = 10, sizeHint = hint) }
            assertEquals(InflateError.INFLATED_DATA_TOO_LARGE, e.error)
        }
        assertEquals(20, Zlib.decompress(twenty, maximumSize = 20, sizeHint = 0).size)
    }

    @Test
    fun aMegabyteOfEmptyFixedBlocksInflatesQuickly() {
        val unit = hex("0208208000")                 // four empty fixed blocks, none final
        val input = ByteArray(209_715 * 5 + 2)
        repeat(209_715) { unit.copyInto(input, it * 5) }
        input[209_715 * 5] = 3                       // one final empty fixed block
        withDeadline("838,861 empty fixed blocks", 2_000) { assertEquals(0, Inflate.inflate(input).size) }
    }

    @Test
    fun anInputLargerThanOneSegmentRoundTrips() {
        // More than one segment of tokens: random bytes give a literal each.
        val data = Random(11).nextBytes((1 shl 20) + 123_457) + ByteArray(300_000) + "ImageKodec".repeat(20_000).encodeToByteArray()
        assertContentEquals(data, Inflate.inflate(Deflate.encode(data)))
    }
}
