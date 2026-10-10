package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageFormatTest {

    @Test
    fun sniffsPng() {
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(hex("89504e470d0a1a0a00000000")))
    }

    @Test
    fun sniffsJpeg() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
    }

    @Test
    fun sniffsGif87And89() {
        assertEquals(ImageFormat.GIF, ImageFormat.sniff("GIF87a".encodeToByteArray()))
        assertEquals(ImageFormat.GIF, ImageFormat.sniff("GIF89a".encodeToByteArray()))
    }

    @Test
    fun sniffsBmp() {
        assertEquals(ImageFormat.BMP, ImageFormat.sniff("BM????".encodeToByteArray()))
    }

    @Test
    fun sniffsWebp() {
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff("RIFF....WEBP".encodeToByteArray()))
    }

    @Test
    fun sniffsTiffBothEndians() {
        assertEquals(ImageFormat.TIFF, ImageFormat.sniff(byteArrayOf(0x49, 0x49, 0x2A, 0x00)))
        assertEquals(ImageFormat.TIFF, ImageFormat.sniff(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)))
    }

    @Test
    fun rejectsUnknownAndShort() {
        assertNull(ImageFormat.sniff("not an image".encodeToByteArray()))
        assertNull(ImageFormat.sniff(ByteArray(0)))
        assertNull(ImageFormat.sniff(byteArrayOf(0x89.toByte())))   // too short for any magic
    }

    @Test
    fun gifRequiresFullVersionString() {
        // "GIF" alone (or a bad version) must not match.
        assertNull(ImageFormat.sniff("GIF".encodeToByteArray()))
        assertNull(ImageFormat.sniff("GIF88a".encodeToByteArray()))
    }
}
