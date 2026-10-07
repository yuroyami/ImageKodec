package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CcittMixedTest {
    private val eol = "000000000001"
    private fun options(rows: Int = 6, endOfBlock: Boolean = false, endOfLine: Boolean = true,
        aligned: Boolean = false, blackIs1: Boolean = true) =
        CcittOptions(8, rows, endOfBlock, blackIs1, aligned, endOfLine)

    // T.4 run words and V0 modes, independent of the decoder's lookup tables.
    private val lines = listOf(
        "1" + "000111010".repeat(4), "0" + "1".repeat(8),
        "1" + "00110101000101", "0" + "11", "1" + "10011", "0" + "1",
    )
    internal fun stream(framed: Boolean = true, aligned: Boolean = false, fill: Int = 0,
        rtc: Boolean = false): ByteArray {
        val bits = StringBuilder()
        for (line in lines) {
            if (aligned) repeat((8 - bits.length % 8) % 8) { bits.append('0') }
            if (framed) { repeat(fill) { bits.append('0') }; bits.append(eol) }
            bits.append(line)
        }
        if (rtc) {
            if (aligned) repeat((8 - bits.length % 8) % 8) { bits.append('0') }
            bits.append((eol + "1").repeat(6))
        }
        return faxBits(bits.toString())
    }
    private val expected = byteArrayOf(0x55, 0x55, 0xff.toByte(), 0xff.toByte(), 0, 0)

    @Test
    fun reportVectorDecodesTwoWhiteRows() {
        for (k in listOf(1, 2, 4, Int.MAX_VALUE)) for (hint in listOf(false, true)) {
            assertContentEquals(byteArrayOf(0, 0), CcittFax.decode(hex("001cc005"), k,
                options(2, endOfBlock = true, endOfLine = hint)))
        }
    }

    @Test
    fun oneDimensionalRowsResetTheReferenceForFollowingTwoDimensionalRows() {
        for (k in listOf(1, 2, 4, Int.MAX_VALUE)) {
            assertContentEquals(expected, CcittFax.decode(stream(), k, options()))
        }
    }

    @Test
    fun modeTagsWithoutEolsAreAllowedWhenTheHintIsFalse() {
        assertContentEquals(expected, CcittFax.decode(stream(framed = false), 2, options(endOfLine = false)))
        assertFailsWith<ImageDecodeException> { CcittFax.decode(stream(framed = false), 2, options()) }
    }

    @Test
    fun rowByteAlignmentAndLongEolFillPreserveModeTags() {
        for (framed in listOf(false, true)) for (aligned in listOf(false, true)) {
            for (fill in if (framed) listOf(0, 5, 129) else listOf(0)) {
                assertContentEquals(expected, CcittFax.decode(stream(framed, aligned, fill), 2,
                    options(endOfLine = framed, aligned = aligned)), "$framed/$aligned/$fill")
            }
        }
    }

    @Test
    fun polarityInvertsPixelsAndKeepsPaddingZero() {
        val inverted = expected.map { (it.toInt() xor 255).toByte() }.toByteArray()
        assertContentEquals(inverted, CcittFax.decode(stream(), 2, options(blackIs1 = false)))
        val narrow = faxBits(eol + "1" + "000111010".repeat(3) + "000111" + eol + "0" + "1".repeat(7))
        for (polarity in listOf(false, true)) {
            val v = if (polarity) 0x54 else 0xaa
            assertContentEquals(byteArrayOf(v.toByte(), v.toByte()), CcittFax.decode(narrow, 2,
                CcittOptions(7, 2, false, polarity, false, true)))
        }
    }

    @Test
    fun unknownHeightStopsAtBytePaddingOrMixedReturnToControl() {
        for (rtc in listOf(false, true)) for (aligned in listOf(false, true)) {
            assertContentEquals(expected, CcittFax.decode(stream(aligned = aligned, rtc = rtc), 2,
                options(0, endOfBlock = rtc, aligned = aligned)))
        }
    }

    @Test
    fun endOfBlockOverridesTheRowHintButAFixedRowCountStopsWhenItIsFalse() {
        assertContentEquals(expected, CcittFax.decode(stream(rtc = true), 2, options(99, endOfBlock = true)))
        assertContentEquals(expected, CcittFax.decode(stream(rtc = true), 2, options(1, endOfBlock = true)))
        assertContentEquals(byteArrayOf(0x55), CcittFax.decode(stream(), 2, options(1)))
    }

    @Test
    fun incompleteRowsAndMissingModeTagsThrow() {
        val bytes = stream()
        for (cut in bytes.indices) {
            assertFailsWith<ImageDecodeException>("cut=$cut") { CcittFax.decode(bytes.copyOf(cut), 2, options()) }
        }
        assertFailsWith<ImageDecodeException> { CcittFax.decode(faxBits(eol + "1" + "000111"), 2, options(1)) }
        assertFailsWith<ImageDecodeException> { CcittFax.decode(faxBits(eol), 2, options(1)) }
    }

    @Test
    fun invalidRunLengthsAndUncompressedEscapesAreTypedFaults() {
        for (line in listOf("1" + "10100", "0" + "00110011000101")) {
            assertFailsWith<ImageDecodeException> { CcittFax.decode(faxBits(eol + line), 2, options(1)) }
        }
        assertFailsWith<UnsupportedImageException> { CcittFax.decode(faxBits(eol + "0" + "0000001"), 2, options(1)) }
    }

    @Test
    fun positiveModeChecksCallerGeometryBeforeAllocating() {
        for (columns in listOf(-1, 0, (1 shl 24) + 1)) {
            assertFailsWith<ImageDecodeException> { CcittFax.decode(byteArrayOf(), 2, options().copy(columns = columns)) }
        }
        assertFailsWith<ImageDecodeException> { CcittFax.decode(byteArrayOf(), 2, options().copy(rows = -1)) }
    }

    @Test
    fun mixedTiffOptionsUseTheSameDecoderAndProbePolicy() {
        for (photo in listOf(0, 1)) for (fill in listOf(false, true)) {
            val bytes = faxTiff(stream(fill = if (fill) 3 else 0), 3, photo, height = 6,
                t4Options = if (fill) 5 else 1)
            assertTrue(ImageKodec.probe(bytes).isDecodable)
            val image = ImageKodec.decode(bytes)
            for (y in 0 until 6) for (x in 0 until 8) {
                val blackRun = ((expected[y].toInt() ushr (7 - x)) and 1) != 0
                assertEquals(gray(if (blackRun == (photo == 0)) 0 else 255), image[x, y], "$photo/$fill/$x,$y")
            }
        }
    }

    @Test
    fun uncompressedTiffModeRemainsANamedUnsupportedFeature() {
        for (t4 in listOf(2, 3)) {
            val bytes = faxTiff(stream(), 3, height = 6, t4Options = t4)
            val info = ImageKodec.probe(bytes)
            assertTrue(!info.isDecodable)
            assertTrue(info.unsupportedReason.orEmpty().contains("uncompressed mode"))
            val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
            assertTrue(e.message.orEmpty().contains("uncompressed mode"))
        }
    }

    @Test
    fun anIncompleteReturnToControlCannotAddOrHideRows() {
        for (words in 1..5) {
            val data = stream() + faxBits((eol + "1").repeat(words))
            assertFailsWith<ImageDecodeException>("RTC words=$words") {
                CcittFax.decode(data, 2, options(0, endOfBlock = true))
            }
        }
    }

    @Test
    fun directMixedMutationsCannotEscapeTheTypedFailureContract() {
        val random = Random(60)
        val base = stream()
        repeat(2000) { iteration ->
            val data = base.copyOf(random.nextInt(base.size + 1))
            if (data.isNotEmpty()) repeat(random.nextInt(1, 5)) {
                val at = random.nextInt(data.size)
                data[at] = (data[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
            try { CcittFax.decode(data, 2, options()) }
            catch (_: ImageDecodeException) { }
            catch (e: Throwable) { throw AssertionError("mixed mutation $iteration raised ${e::class.simpleName}", e) }
        }
    }
}
