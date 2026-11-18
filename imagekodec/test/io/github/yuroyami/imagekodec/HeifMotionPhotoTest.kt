package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The video of a HEIC or AVIF Motion Photo, and namespace prefixes in any Motion Photo (#155). */
class HeifMotionPhotoTest {

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false

    /** A small MP4: an ftyp box and an empty mdat, enough to be told apart. */
    private val video = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isommp41".encodeToByteArray()) + box("mdat", ByteArray(40) { it.toByte() })

    /** The fields under the prefix `XCam`, which the XMP binds to the camera namespace. */
    private val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
        """<rdf:Description xmlns:XCam="http://ns.google.com/photos/1.0/camera/" XCam:MotionPhoto="1" XCam:MotionPhotoVersion="1"/>""" +
        """</rdf:RDF></x:xmpmeta>"""

    /** The AV1 data and av1C payload of a real still, so that an AVIF probes. */
    private val av1: Pair<ByteArray, ByteArray> = run {
        val c = AvifContainer.parse(AvifFixtures.plain)
        val item = c.items[c.primary!!]!!
        c.itemData(item) to item.property<AvifProperty.Av1Config>()!!.payload
    }

    private fun still(major: String, brands: List<String>, type: String) = HeifBuilder(major, brands).apply {
        if (type == "hvc1") {
            add(1, type, ByteArray(4), listOf(box("hvcC", AnnexB.hvcC(emptyList())) to true, ispe(16, 16)))
        } else {
            add(1, type, av1.first, listOf(box("av1C", av1.second) to true, ispe(24, 16)))
        }
        add(2, "mime", xmp.encodeToByteArray(), emptyList(), hidden = true, contentType = "application/rdf+xml")
        references += Triple("cdsc", 2L, longArrayOf(1))
    }.build()

    @Test
    fun theMpvdBoxOfAHeicOrAnAvifIsTheVideo() {
        for ((major, brands, type) in listOf(Triple("heic", listOf("mif1", "heic"), "hvc1"), Triple("avif", listOf("mif1", "avif"), "av01"))) {
            val photo = still(major, brands, type)
            val file = photo + box("mpvd", video)
            val info = assertNotNull(ImageKodec.motionPhoto(file), major)
            assertEquals(MotionPhotoInfo.Format.GOOGLE_MOTION_PHOTO, info.format)
            assertEquals(photo.size + 8, info.videoOffset)
            assertContentEquals(video, file.copyOfRange(info.videoOffset, info.videoOffset + info.videoLength))
            val removed = ImageKodec.removeMotion(file)
            assertContentEquals(photo, removed)
            assertNull(ImageKodec.motionPhoto(removed))
            assertEquals(ImageKodec.probe(file).width, ImageKodec.probe(removed).width)
        }
    }

    @Test
    fun aVideoBoxThatOtherBoxesFollowIsNotCutOut() {
        val file = still("heic", listOf("mif1", "heic"), "hvc1") + box("mpvd", video) + box("free", ByteArray(4))
        assertNotNull(ImageKodec.motionPhoto(file))
        assertFailsWith<ImageDecodeException> { ImageKodec.removeMotion(file) }
    }

    @Test
    fun withoutTheXmpFlagThereIsNoVideo() {
        val photo = HeifBuilder("heic", listOf("mif1", "heic")).apply {
            add(1, "hvc1", ByteArray(4), listOf(box("hvcC", AnnexB.hvcC(emptyList())) to true, ispe(16, 16)))
        }.build()
        assertNull(ImageKodec.motionPhoto(photo + box("mpvd", video)))
    }

    @Test
    fun aJpegNamesItsFieldsUnderTheCurrentPrefix() {
        // Motion Photo 1.0 binds the camera namespace to `Camera`, where older files used `GCamera`.
        val jpegXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:Camera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" Camera:MotionPhoto="1"><Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="${video.size}"/></rdf:li>""" +
            """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val photo = ImageKodec.encodeJpeg(KiteBitmap(8, 8, IntArray(64) { 0xFF808080.toInt() }), 90, ImageMetadata(xmp = jpegXmp))
        val file = photo + video
        val info = assertNotNull(ImageKodec.motionPhoto(file))
        assertEquals(photo.size, info.videoOffset)
        assertEquals(video.size, info.videoLength)
        val still = ImageKodec.removeMotion(file)
        assertNull(ImageKodec.motionPhoto(still))
    }
}
