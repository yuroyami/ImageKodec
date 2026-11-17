package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The core recognises a HEIC file and describes it from its container alone (#138). */
class HeicRecognitionTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private fun hvcC(depth: Int) = box("hvcC", AnnexB.hvcC(emptyList(), chromaFormat = 1, bitDepthLuma = depth, bitDepthChroma = depth)) to true

    /** One `hvc1` item of 64 by 48 at [depth] bits, with no `pixi`, under the given brands. */
    private fun heic(major: String = "heic", brands: List<String> = listOf("mif1", "heic"), depth: Int = 10) =
        HeifBuilder(major, brands).apply { add(1, "hvc1", byteArrayOf(0, 0, 0, 2, 0x26, 1), listOf(hvcC(depth), ispe(64, 48))) }.build()

    @Test
    fun everyHevcBrandIsHeic() {
        for (brand in listOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs")) {
            assertEquals(ImageFormat.HEIC, ImageFormat.sniff(heic(major = brand, brands = listOf("mif1"))), brand)
            assertEquals(ImageFormat.HEIC, ImageFormat.sniff(heic(major = "mif1", brands = listOf("mif1", brand))), "compatible $brand")
        }
    }

    @Test
    fun aFileThatNamesOnlyHeifIsClassifiedByItsPrimaryItem() {
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(heic(major = "mif1", brands = listOf("mif1", "miaf"))))
        // Both families named: the primary item decides.
        assertEquals(ImageFormat.HEIC, ImageFormat.sniff(heic(major = "mif1", brands = listOf("mif1", "avif", "heic"))))
        // A brand of neither family and no HEIF brand is no image this library knows.
        assertNull(ImageFormat.sniff(heic(major = "isom", brands = listOf("isom", "mp41"))))
    }

    @Test
    fun probeDescribesTheFileAndNamesTheModule() {
        val info = ImageKodec.probe(heic())
        assertEquals(ImageFormat.HEIC, info.format)
        assertEquals(64 to 48, info.width to info.height)
        assertEquals(10, info.bitDepth, "the depth comes from hvcC, as the file has no pixi")
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("imagekodec-heic"), info.unsupportedReason)
        val e = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(heic()) }
        assertTrue(e.message!!.startsWith("HEIC"), e.message)
    }

    @Test
    fun layeredHevcIsNamed() {
        val file = HeifBuilder("heim", listOf("mif1", "heim")).apply {
            add(1, "lhv1", byteArrayOf(0), listOf(ispe(64, 48)))
        }.build()
        val info = ImageKodec.probe(file)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("lhv1"), info.unsupportedReason)
    }
}
