package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Jbig2UnsupportedTest {
    private fun segment(number: Int, type: Int, body: ByteArray, length: Int = body.size, page: Int = 1): ByteArray =
        beBytes(number) + byteArrayOf(type.toByte(),0,page.toByte()) + beBytes(length) + body

    private fun page(): ByteArray = segment(0,48,
        beBytes(8) + beBytes(2) + beBytes(0) + beBytes(0) + byteArrayOf(0,0,0))

    private fun mmr(): ByteArray = segment(1,38,
        beBytes(8) + beBytes(2) + beBytes(0) + beBytes(0) + byteArrayOf(0,1) +
            faxBits("001000111010".repeat(4) + "11111111"))

    private fun dictionary(flags: Int, newSymbols: Int, coded: ByteArray = byteArrayOf(), global: Boolean = false): ByteArray {
        val at = if (flags and 1 == 0) byteArrayOf(3,-1,-3,-1,2,-2,-2,-2) else byteArrayOf()
        val refinementAt = if (flags and 2 != 0 && flags and 0x1000 == 0) byteArrayOf(0,-1,-1,-1) else byteArrayOf()
        return segment(2,0,byteArrayOf((flags ushr 8).toByte(),flags.toByte()) + at + refinementAt +
            beBytes(0) + beBytes(newSymbols) + coded, page = if (global) 0 else 1)
    }

    @Test
    fun unknownLengthCannotExposeABlankOrPartialPage() {
        val unknown = segment(3,38,byteArrayOf(),length = -1)
        assertNull(Jbig2Decoder.decode(page() + unknown,null,8,2))
        assertNull(Jbig2Decoder.decode(page() + mmr() + unknown,null,8,2))
    }

    @Test
    fun huffmanRefinementDeclarationRefusesTheEntirePage() {
        assertNull(Jbig2Decoder.decode(page() + dictionary(3,0),null,8,2))
        assertNull(Jbig2Decoder.decode(page(),dictionary(3,0,global = true),8,2))
    }

    @Test
    fun multiInstanceAggregatePrefixIsRefused() {
        // jbig2enc's Apache-2.0 arithmetic encoder at d0dfca46216c98f11312a9c9f15615ed490cd7b3:
        // IADH=1, IADW=1, IAAI=2, then finalization. This prefix declares two
        // instances; refusal must happen before an unsupported aggregate body.
        assertNull(Jbig2Decoder.decode(page() + dictionary(0x1002,1,hex("94efffac")),null,8,2))
    }

    @Test
    fun supportedBlankAndEmptyDictionaryControlsDecode() {
        val white = byteArrayOf(-1,-1)
        assertContentEquals(white,assertNotNull(Jbig2Decoder.decode(page(),null,8,2)))
        for (flags in listOf(1,0x1002)) {
            assertContentEquals(white,assertNotNull(Jbig2Decoder.decode(page() + dictionary(flags,0),null,8,2)))
        }
    }

    @Test
    fun supportedMmrRegionKeepsItsPixels() {
        assertContentEquals(byteArrayOf(0xAA.toByte(),0xAA.toByte()),
            assertNotNull(Jbig2Decoder.decode(page() + mmr(),null,8,2)))
    }
}
