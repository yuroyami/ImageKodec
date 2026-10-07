package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jp2HeaderProbeTest {
    private fun marker(data: ByteArray, value: Int): Int = data.indices.first {
        it + 1 < data.size && (data[it].toInt() and 255) == value ushr 8 && (data[it + 1].toInt() and 255) == value and 255
    }

    private fun stream(): ByteArray = hex(JP2).let { it.copyOfRange(marker(it, 0xff4f), it.size) }
    private fun boxed(cs: ByteArray): ByteArray {
        val base = hex(JP2); val start = marker(base, 0xff4f)
        put32(base, start - 8, cs.size + 8)
        return base.copyOfRange(0, start) + cs
    }
    private fun u16(data: ByteArray, at: Int): Int = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
    private fun put32(data: ByteArray, at: Int, value: Int) {
        for (i in 0..3) data[at + i] = (value ushr (24 - 8 * i)).toByte()
    }
    private fun payload(value: Int): ByteArray = stream().let {
        val at = marker(it, value); it.copyOfRange(at + 4, at + 2 + u16(it, at + 2))
    }
    private fun segment(value: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf((value ushr 8).toByte(), value.toByte(), (len ushr 8).toByte(), len.toByte()) + payload
    }
    private fun withHeader(value: Int, payload: ByteArray, tile: Boolean): ByteArray {
        val cs = stream(); val added = segment(value, payload)
        val at = marker(cs, if (tile) 0xff93 else 0xff90)
        if (tile) {
            val sot = marker(cs, 0xff90)
            val psot = (0..3).fold(0) { n, i -> (n shl 8) or (cs[sot + 6 + i].toInt() and 255) }
            put32(cs, sot + 6, psot + added.size)
        }
        return cs.copyOfRange(0, at) + added + cs.copyOfRange(at, cs.size)
    }

    internal fun tileOverrideSeed(): ByteArray = withHeader(0xff52, payload(0xff52), true)
    private fun refused(data: ByteArray, field: String) {
        val info = ImageKodec.probe(data)
        assertFalse(info.isDecodable, "probe accepted $field")
        val reason = info.unsupportedReason.orEmpty()
        assertTrue(reason.contains(field), reason)
        val ex = assertFailsWith<ImageDecodeException> { ImageKodec.decode(data) }
        assertTrue(ex.message.orEmpty().contains(reason), "${ex.message} differs from $reason")
    }

    @Test
    fun mainCodingParameterRefusalsAgree() {
        for ((offset, value, field) in listOf(
            Triple(4, 128, "Scod"), Triple(5, 5, "progression"),
            Triple(6, 0, "layers"), Triple(8, 2, "component transform"),
            Triple(9, 33, "decomposition"), Triple(10, 0x14, "code-block"),
            Triple(13, 2, "wavelet transform"),
        )) {
            val data = stream(); val at = marker(data, 0xff52)
            data[at + offset] = value.toByte()
            if (offset == 6) data[at + 7] = 0
            refused(data, field)
            refused(boxed(data), field)
        }
        val layered = stream(); val at = marker(layered, 0xff52)
        layered[at + 6] = 3; layered[at + 7] = 0xe9.toByte()
        refused(layered, "layers")
    }

    @Test
    fun tileCodingParametersAndUnsupportedMarkersAgree() {
        for ((offset, value, field) in listOf(Triple(1, 5, "progression"),
            Triple(5, 33, "decomposition"), Triple(8, 1, "code-block"))) {
            refused(withHeader(0xff52, payload(0xff52).also { it[offset] = value.toByte() }, true), field)
        }
        for ((value, field) in listOf(0xff5e to "region of interest", 0xff5f to "progression order change",
            0xff60 to "packed packet headers", 0xff61 to "packed packet headers")) {
            refused(withHeader(value, byteArrayOf(0, 0), true), field)
        }
    }

    @Test
    fun componentCodingOverridesInBothHeaderScopesAgree() {
        val cod = payload(0xff52)
        val coc = byteArrayOf(0, (cod[0].toInt() and 1).toByte()) + cod.copyOfRange(5, cod.size)
        for (tile in listOf(false, true)) {
            for ((offset, value, field) in listOf(Triple(1, 2, "Scoc"), Triple(2, 33, "decomposition"),
                Triple(3, 0x14, "code-block"), Triple(5, 1, "code-block"), Triple(6, 2, "wavelet transform"))) {
                refused(withHeader(0xff53, coc.copyOf().also { it[offset] = value.toByte() }, tile), field)
            }
        }
    }

    @Test
    fun invalidPrecinctExponentsAreVisibleInBothHeaderScopes() {
        val cod = payload(0xff52).also { it[0] = (it[0].toInt() or 1).toByte() }
        val precincts = ByteArray((cod[5].toInt() and 255) + 1) { 0x44 }
        precincts[1] = 0x40
        for (tile in listOf(false, true)) refused(withHeader(0xff52, cod + precincts, tile), "precinct")
    }

    @Test
    fun invalidComponentIndicesAreVisibleInBothHeaderScopes() {
        val cod = payload(0xff52)
        val coc = byteArrayOf(3, (cod[0].toInt() and 1).toByte()) + cod.copyOfRange(5, cod.size)
        val qcc = byteArrayOf(3) + payload(0xff5c)
        for (tile in listOf(false, true)) {
            refused(withHeader(0xff53, coc, tile), "component index")
            refused(withHeader(0xff5d, qcc, tile), "component index")
        }
    }

    @Test
    fun quantizationTablesInBothHeaderScopesAgree() {
        for (tile in listOf(false, true)) {
            for ((table, field) in listOf(byteArrayOf(31) to "quantization style",
                byteArrayOf(0) to "empty quantization", byteArrayOf(2, 0) to "cut off",
                byteArrayOf(1, 0, 0, 0, 0) to "scalar-derived")) {
                refused(withHeader(0xff5c, table, tile), field)
                refused(withHeader(0xff5d, byteArrayOf(0) + table, tile), field)
            }
        }
    }

    @Test
    fun laterTilePartHeadersAreInspected() {
        val cs = stream(); val eoc = marker(cs, 0xffd9); val sot = marker(cs, 0xff90)
        cs[sot + 11] = 2
        val cod = segment(0xff52, payload(0xff52).also { it[8] = 1 })
        val part = byteArrayOf(0xff.toByte(), 0x90.toByte(), 0, 10, 0, 0, 0, 0, 0, 0, 1, 2) + cod +
            byteArrayOf(0xff.toByte(), 0x93.toByte())
        put32(part, 6, part.size)
        refused(cs.copyOfRange(0, eoc) + part + cs.copyOfRange(eoc, cs.size), "code-block")
    }

    @Test
    fun packetBytesAreSkippedInsteadOfInterpretedAsMarkers() {
        val cs = stream(); val sot = marker(cs, 0xff90); val eoc = marker(cs, 0xffd9)
        val fakeHeader = segment(0xff52, payload(0xff52).also { it[8] = 1 })
        val psot = (0..3).fold(0) { n, i -> (n shl 8) or (cs[sot + 6 + i].toInt() and 255) }
        put32(cs, sot + 6, psot + fakeHeader.size)
        val modified = cs.copyOfRange(0, eoc) + fakeHeader + cs.copyOfRange(eoc, cs.size)
        assertTrue(ImageKodec.probe(modified).isDecodable)
        assertContentEquals(ImageKodec.decode(stream()).argb, ImageKodec.decode(modified).argb)
    }

    @Test
    fun aShortSegmentDoesNotBorrowAnUnsupportedStyleFromOutsideItsBounds() {
        val cs = stream(); val at = marker(cs, 0xff52)
        cs[at + 3] = 2; cs[at + 12] = 1
        assertTrue(ImageKodec.probe(cs).isDecodable)
        val ex = assertFailsWith<ImageDecodeException> { ImageKodec.decode(cs) }
        assertTrue(ex.message.orEmpty().contains("COD header cut off"), ex.message)
    }

    @Test
    fun aTruncatedCodestreamCannotBorrowFieldsFromTheNextBox() {
        val cs = stream(); val cod = marker(cs, 0xff52)
        val free = byteArrayOf(0, 0, 0, 16, 0x66, 0x72, 0x65, 0x65) + ByteArray(8) { 1 }
        val bytes = boxed(cs.copyOf(cod + 5)) + free
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        val ex = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertTrue(ex.message.orEmpty().contains("COD header cut off"), ex.message)
    }

    @Test
    fun validOverridesKeepTheReferencePixelsAndDecodableStatus() {
        val original = ImageKodec.decode(stream())
        val cod = payload(0xff52)
        val coc = byteArrayOf(0, (cod[0].toInt() and 1).toByte()) + cod.copyOfRange(5, cod.size)
        val quant = payload(0xff5c)
        for (tile in listOf(false, true)) {
            for ((value, payload) in listOf(0xff52 to cod, 0xff53 to coc, 0xff5c to quant, 0xff5d to (byteArrayOf(0) + quant))) {
                val data = withHeader(value, payload, tile)
                val info = ImageKodec.probe(data)
                val decoded = ImageKodec.decode(data)
                assertTrue(info.isDecodable, info.unsupportedReason)
                assertEquals(decoded.width to decoded.height, info.width to info.height)
                assertContentEquals(original.argb, decoded.argb)
            }
        }
    }

    @Test
    fun allEncodedGuardBitCountsRemainDeclaredSupported() {
        for (guard in 0..7) {
            val cs = stream(); val at = marker(cs, 0xff5c) + 4
            cs[at] = ((cs[at].toInt() and 31) or (guard shl 5)).toByte()
            val info = ImageKodec.probe(cs)
            assertTrue(info.isDecodable, "guard=$guard: ${info.unsupportedReason}")
        }
    }
}
