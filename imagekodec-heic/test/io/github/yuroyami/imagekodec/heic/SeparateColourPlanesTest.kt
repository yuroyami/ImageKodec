package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.PictureHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Three separate colour planes (separate_colour_plane_flag, #188) on every target: three 4:0:0 pictures from this
 * module's encoder, joined by [SeparateColourPlanes] into one stream, must decode to the three monochrome pictures,
 * plane for plane, and to the MD5s the encoder wrote for them. The oracle test does the same with x265 sequences of
 * P and B pictures, whose planes keep motion of their own.
 */
class SeparateColourPlanesTest {

    /** A 40x24 picture of 8 bits, a different pattern for each [seed]. */
    private fun gray(seed: Int): HeifPlanes {
        val w = 40
        val h = 24
        val p = ShortArray(w * h) { i ->
            val x = i % w
            val y = i / w
            ((x * (3 + seed) + y * (5 - seed) + ((x xor y) and 7) * 9 * seed) and 0xFF).toShort()
        }
        return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(p), intArrayOf(w), true)
    }

    private fun encode(seed: Int): List<ByteArray> =
        AnnexB.split(HevcEncoder.encode(gray(seed), true, null, HevcEncoderSettings(qp = 30, effort = 1, hash = true), null).annexB)

    private fun decode(units: List<ByteArray>): List<DecodedPicture> {
        val d = HevcDecoder()
        for (u in units) d.decodeNal(u, 0, u.size, null)
        return d.finish()
    }

    private fun md5(p: DecodedPicture, c: Int): ByteArray =
        PictureHash.md5(PictureHash.pictureData(p.planes[c], 0, p.strides[c], p.sps.width, p.sps.height, p.sps.planeBitDepth(c)))

    /** The three monochrome pictures, decoded, and the stream that joins them. */
    private val mono = (0..2).map { decode(encode(it)) }
    internal val joined = SeparateColourPlanes.join((0..2).map { encode(it) }, mono.map { list -> list.map { it.hash!!.values[0] } })

    @Test
    fun threePlanesDecodeAsTheirMonochromePictures() {
        val pictures = decode(joined)
        assertEquals(1, pictures.size)
        val p = pictures[0]
        assertTrue(p.sps.separateColourPlane && p.sps.chromaFormatIdc == 3 && p.sps.chromaArrayType == 0)
        assertEquals(3, p.planes.size)
        for (c in 0..2) {
            val m = mono[c][0]
            assertContentEquals(m.planes[0], p.planes[c], "colour plane $c")
            assertContentEquals(p.hash!!.values[c], md5(p, c), "the MD5 of colour plane $c")
        }
        // As a HEIF item: a 4:4:4 picture at full size, refused by nothing.
        val sets = joined.filter { ((it[0].toInt() shr 1) and 0x3F) in NalType.VPS..NalType.PPS }
        val hvcC = AnnexB.hvcC(sets, chromaFormat = 3)
        val sample = AnnexB.sample(joined.filter { NalType.isSlice((it[0].toInt() shr 1) and 0x3F) })
        val info = HevcCodec.inspect(hvcC, sample)
        assertNull(info.unsupportedReason)
        assertEquals(3, info.chromaFormat)
        val planes = HevcCodec.open(hvcC).decode(sample, null)!!
        assertEquals(0, planes.subX)
        assertEquals(0, planes.subY)
        for (c in 0..2) assertContentEquals(mono[c][0].toHeifPlanes().planes[0], planes.planes[c], "HEIF plane $c")
    }

    @Test
    fun aColourPlaneThatIsMissingIsADecodeError() {
        // Without the slices of the third plane, the picture holds no CTB of it.
        val third = joined.indexOfLast { NalType.isSlice((it[0].toInt() shr 1) and 0x3F) }
        val e = kotlin.runCatching { decode(joined.filterIndexed { i, _ -> i != third }) }.exceptionOrNull()
        assertTrue(e is io.github.yuroyami.imagekodec.ImageDecodeException, "got $e")
        assertTrue("colour plane 2" in e.message!!, e.message)
    }
}
