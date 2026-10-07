package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Jp2DiagnosticsTest {
    private fun marker(data: ByteArray, value: Int): Int {
        for (i in 0 until data.size - 1) {
            if ((data[i].toInt() and 255) == value ushr 8 && (data[i + 1].toInt() and 255) == value and 255) return i
        }
        error("missing test marker ${value.toString(16)}")
    }

    private fun stream(): ByteArray {
        val jp2 = hex(JP2)
        return jp2.copyOfRange(marker(jp2, 0xff4f), jp2.size)
    }

    private fun error(data: ByteArray, vararg names: String): ImageDecodeException {
        val ex = assertFailsWith<ImageDecodeException> { ImageKodec.decode(data) }
        for (name in names) assertTrue(ex.message.orEmpty().contains(name), "expected $name in ${ex.message}")
        assertNull(JpxDecoder.decode(data))
        assertNull(JpxDecoder.decode(data, 2))
        return ex
    }

    @Test
    fun missingMandatoryMarkersHaveDistinctDiagnostics() {
        val siz = error(byteArrayOf(0xff.toByte(), 0x4f, 0xff.toByte(), 0xd9.toByte()), "missing SIZ")
        fun renamed(value: Int): ByteArray = stream().also { it[marker(it, value) + 1] = 0x55 }
        val cod = error(renamed(0xff52), "missing COD")
        val qcd = error(renamed(0xff5c), "missing QCD")
        assertEquals(3, setOf(siz.message, cod.message, qcd.message).size)
    }

    @Test
    fun shortSegmentsCannotBorrowFieldsFromTheNextMarker() {
        for ((value, name) in listOf(0xff51 to "SIZ", 0xff52 to "COD", 0xff5c to "QCD")) {
            val data = stream()
            val at = marker(data, value)
            data[at + 2] = 0; data[at + 3] = 2
            error(data, name, "cut off", "byte")
            data[at + 3] = 1
            error(data, name, "length 1", "byte")
        }
    }

    @Test
    fun truncatedSegmentsAndLostMarkerSyncNameTheirLocation() {
        val data = stream()
        val cod = marker(data, 0xff52)
        error(data.copyOf(cod + 5), "COD", "cut off", "byte")
        val lost = data.copyOf().also { it[cod] = 0x12 }
        error(lost, "marker", "1252", "byte")
    }

    @Test
    fun malformedQuantizationNamesTheTableAndField() {
        val badStyle = stream().also { it[marker(it, 0xff5c) + 4] = 31 }
        error(badStyle, "QCD", "quantization style 31")
        val empty = stream().also { val at = marker(it, 0xff5c); it[at + 3] = 3; it[at + 4] = 0 }
        error(empty, "QCD", "empty quantization")
        val partial = stream().also { val at = marker(it, 0xff5c); it[at + 3] = 4; it[at + 4] = 2 }
        error(partial, "QCD", "cut off")
    }

    @Test
    fun unsupportedMarkersKeepTypedFeatureNames() {
        for ((value, name) in listOf(0xff5e to "region of interest", 0xff5f to "progression order change",
            0xff60 to "packed packet headers", 0xff61 to "packed packet headers")) {
            val cs = stream(); val at = marker(cs, 0xff90)
            val segment = byteArrayOf((value ushr 8).toByte(), value.toByte(), 0, 4, 0, 0)
            val ex = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(cs.copyOfRange(0, at) + segment + cs.copyOfRange(at, cs.size)) }
            assertTrue(ex.message.orEmpty().contains(name), ex.message)
            assertTrue(ex.message.orEmpty().contains("byte"), ex.message)
        }
    }

    @Test
    fun codingLimitsNameTheirFieldsAndUnsupportedStylesAreTyped() {
        val data = stream(); val cod = marker(data, 0xff52)
        error(data.copyOf().also { it[cod + 9] = 33 }, "COD", "decomposition", "33")
        error(data.copyOf().also { it[cod + 6] = 0; it[cod + 7] = 0 }, "COD", "layers", "0")
        error(data.copyOf().also { it[cod + 10] = 10 }, "COD", "code-block")
        val style = data.copyOf().also { it[cod + 12] = 1 }
        val ex = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(style) }
        assertTrue(ex.message.orEmpty().startsWith(assertNotNull(ImageKodec.probe(style).unsupportedReason)), ex.message)
    }

    @Test
    fun aHeaderWithoutTileDataFailsBeforeAllocatingOutput() {
        val data = stream()
        error(data.copyOf(marker(data, 0xff90)), "missing", "SOT")
        val sod = marker(data, 0xff93)
        error(data.copyOf(sod), "missing", "SOD")
    }

    private fun withSegment(value: Int, payload: ByteArray): ByteArray {
        val cs = stream(); val at = marker(cs, 0xff90)
        val n = payload.size + 2
        val segment = byteArrayOf((value ushr 8).toByte(), value.toByte(), (n ushr 8).toByte(), n.toByte()) + payload
        return cs.copyOfRange(0, at) + segment + cs.copyOfRange(at, cs.size)
    }

    @Test
    fun componentOverridesNameTheirSegmentAndComponent() {
        error(withSegment(0xff53, byteArrayOf(0, 0)), "COC", "cut off")
        error(withSegment(0xff5d, byteArrayOf(0, 31)), "QCC", "quantization style 31")
        for ((value, name) in listOf(0xff53 to "COC", 0xff5d to "QCC")) {
            error(withSegment(value, byteArrayOf(3)), name, "component index 3")
        }
    }

    @Test
    fun aValidComponentOverrideKeepsTheReferencePixels() {
        val cs = stream(); val at = marker(cs, 0xff52)
        val len = ((cs[at + 2].toInt() and 255) shl 8) or (cs[at + 3].toInt() and 255)
        val coc = byteArrayOf(0, (cs[at + 4].toInt() and 1).toByte()) + cs.copyOfRange(at + 9, at + 2 + len)
        val expected = ImageKodec.decode(cs)
        val actual = ImageKodec.decode(withSegment(0xff53, coc))
        assertEquals(expected.width to expected.height, actual.width to actual.height)
        kotlin.test.assertContentEquals(expected.argb, actual.argb)
    }
}

internal fun jp2ShortCodSeed(): ByteArray {
    val data = hex(JP2)
    val soc = data.indices.first { it + 1 < data.size && data[it] == 0xff.toByte() && data[it + 1] == 0x4f.toByte() }
    val cs = data.copyOfRange(soc, data.size)
    val cod = cs.indices.first { it + 1 < cs.size && cs[it] == 0xff.toByte() && cs[it + 1] == 0x52.toByte() }
    cs[cod + 2] = 0; cs[cod + 3] = 2
    return cs
}
