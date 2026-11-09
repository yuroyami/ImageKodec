package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Motion Photo's video is found and removed, and the still decodes to the same pixels (#131). */
class MotionPhotoTest {

    private val bitmap = KiteBitmap(8, 8, IntArray(64) { 0xFF000000.toInt() or (it * 0x030507) })

    /** Bytes that stand for an MP4 video of [n] bytes: an ftyp box, then filler. */
    private fun video(n: Int) = ByteArray(n) { (it * 31).toByte() }.also {
        byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70).copyInto(it)
    }

    private fun googleXmp(videoLength: Int, gainMapLength: Int? = null): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:Container="http://ns.google.com/photos/1.0/container/" """ +
            """xmlns:Item="http://ns.google.com/photos/1.0/container/item/" GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" """ +
            """GCamera:MotionPhotoPresentationTimestampUs="500000"><Container:Directory><rdf:Seq>""" +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>""" +
            (gainMapLength?.let { """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="$it"/></rdf:li>""" } ?: "") +
            """<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>""" +
            """</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test
    fun a_google_motion_photo_loses_its_video_and_keeps_its_pixels() {
        val clip = video(5000)
        val still = ImageKodec.encodeJpeg(bitmap, 90, ImageMetadata(xmp = googleXmp(clip.size)))
        val photo = still + clip
        val info = assertNotNull(ImageKodec.motionPhoto(photo))
        assertEquals(MotionPhotoInfo.Format.GOOGLE_MOTION_PHOTO, info.format)
        assertEquals(still.size, info.videoOffset)
        assertEquals(clip.size, info.videoLength)

        val cut = ImageKodec.removeMotion(photo)
        assertNull(ImageKodec.motionPhoto(cut), "the still photo still has a video")
        assertContentEquals(ImageKodec.decode(photo).argb, ImageKodec.decode(cut).argb)
        assertTrue(cut.size < still.size + 100, "the still photo is ${cut.size} bytes for a ${still.size}-byte JPEG")
        val xmp = assertNotNull(ImageKodec.readMetadata(cut).xmp)
        assertTrue("GCamera:MotionPhoto=\"0\"" in xmp, xmp)
        assertFalse("video/mp4" in xmp, "the video item stays in the directory")
        assertTrue("Item:Semantic=\"Primary\"" in xmp)
    }

    @Test
    fun a_gain_map_and_its_bytes_stay() {
        val clip = video(3000)
        val gainMap = ImageKodec.encodeJpeg(KiteBitmap(4, 4, IntArray(16) { 0xFF808080.toInt() }), 80)
        val still = ImageKodec.encodeJpeg(bitmap, 90, ImageMetadata(xmp = googleXmp(clip.size, gainMap.size)))
        val cut = ImageKodec.removeMotion(still + gainMap + clip)
        val xmp = assertNotNull(ImageKodec.readMetadata(cut).xmp)
        assertTrue("Item:Semantic=\"GainMap\"" in xmp, "the gain map item went")
        assertContentEquals(gainMap, cut.copyOfRange(cut.size - gainMap.size, cut.size), "the gain map's bytes went")
    }

    @Test
    fun the_older_micro_video_format_is_found_too() {
        val clip = video(2000)
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" """ +
            """GCamera:MicroVideoOffset="${clip.size}" GCamera:MicroVideoPresentationTimestampUs="0"/></rdf:RDF></x:xmpmeta>"""
        val photo = ImageKodec.encodeJpeg(bitmap, 90, ImageMetadata(xmp = xmp)) + clip
        assertEquals(MotionPhotoInfo.Format.GOOGLE_MICRO_VIDEO, ImageKodec.motionPhoto(photo)?.format)
        val cut = ImageKodec.removeMotion(photo)
        assertFalse("MicroVideo" in ImageKodec.readMetadata(cut).xmp.orEmpty())
        assertNull(ImageKodec.motionPhoto(cut))
    }

    @Test
    fun a_samsung_trailer_is_cut_at_the_end_of_the_jpeg() {
        val still = ImageKodec.encodeJpeg(bitmap, 90)
        val clip = video(1500)
        val photo = still + "MotionPhoto_Data".encodeToByteArray() + clip
        val info = assertNotNull(ImageKodec.motionPhoto(photo))
        assertEquals(MotionPhotoInfo.Format.SAMSUNG, info.format)
        assertEquals(clip.size, info.videoLength)
        assertContentEquals(still, ImageKodec.removeMotion(photo))
    }

    @Test
    fun a_photo_without_a_video_comes_back_as_it_is() {
        val still = ImageKodec.encodeJpeg(bitmap, 90)
        assertNull(ImageKodec.motionPhoto(still))
        assertContentEquals(still, ImageKodec.removeMotion(still))
    }
}
