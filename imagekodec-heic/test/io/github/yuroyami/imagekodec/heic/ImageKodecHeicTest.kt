package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.UnsupportedImageException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The module registers its codec, and the core hands it the `hvc1` items of a HEIC file (#139). */
class ImageKodecHeicTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The 24 by 20 x265 picture of HevcHeaderTest, its slice cut after the header. */
    private val parameterSets = listOf(
        hex("40010c01ffff0408000003009fa800000300001eba0240"),
        hex("4201010408000003009fa800000300001ea0c867de5baabcaf016e02201208000003000800000300c840"),
        hex("4401c173c089"),
    )
    private val slice = hex("2801af780a4152f9b1f1f4a9bc8b")

    private fun heic(): ByteArray {
        val hvcC = box("hvcC", AnnexB.hvcC(parameterSets)) to true
        val ispe = box("ispe", ByteArray(4) + be32(24) + be32(20)) to false
        return HeifBuilder("heic", listOf("mif1", "heic")).apply { add(1, "hvc1", AnnexB.sample(listOf(slice)), listOf(hvcC, ispe)) }.build()
    }

    @Test
    fun installTwiceThenProbe() {
        ImageKodecHeic.install()
        ImageKodecHeic.install()
        val info = ImageKodec.probe(heic())
        assertEquals(ImageFormat.HEIC, info.format)
        assertEquals(24 to 20, info.width to info.height)
        assertEquals(8, info.bitDepth)
        assertFalse(info.isDecodable)
        // The reason comes from this module's codec, not from the core's note that the module is missing.
        assertEquals(HevcSupport.DEBLOCKING, info.unsupportedReason)
        val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(heic()) }
        assertEquals(true, e.message?.contains("not implemented"), e.message)
    }
}
