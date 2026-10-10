package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnimationLoopCountTest {
    private fun gif(extension: ByteArray = byteArrayOf()): ByteArray {
        fun frame(index: Int) = byteArrayOf(
            0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0,
            2, 2, if (index == 0) 0x44 else 0x4c, 1, 0,
        )
        return "GIF89a".encodeToByteArray() +
            byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, -1, 0, 0, 0, 0, -1) +
            extension + frame(0) + frame(1) + byteArrayOf(0x3b)
    }

    private fun application(name: String, vararg blocks: ByteArray): ByteArray =
        byteArrayOf(0x21, 0xff.toByte(), name.length.toByte()) + name.encodeToByteArray() +
            blocks.fold(byteArrayOf()) { all, block -> all + byteArrayOf(block.size.toByte()) + block } +
            byteArrayOf(0)

    private fun repetition(count: Int) = byteArrayOf(1, count.toByte(), (count ushr 8).toByte())

    @Test
    fun gifRepetitionsIncludeTheInitialPlayInThePublicCount() {
        for (name in listOf("NETSCAPE2.0", "ANIMEXTS1.0")) {
            for ((wire, plays) in listOf(0 to 0L, 1 to 2L, 2 to 3L, 65_535 to 65_536L)) {
                val bytes = gif(application(name, repetition(wire)))
                assertEquals(plays, ImageKodec.decodeAnimation(bytes).loopCount)
                assertEquals(plays, ImageKodec.probe(bytes).loopCount)
            }
        }
        assertEquals(1L, ImageKodec.decodeAnimation(gif()).loopCount)
        assertEquals(1L, ImageKodec.probe(gif()).loopCount)
    }

    @Test
    fun gifBufferingAndUnknownExtensionsCannotChangeTheCount() {
        val buffering = byteArrayOf(2, 0xe8.toByte(), 3, 0, 0)
        for (name in listOf("NETSCAPE2.0", "NETSCAPEbad", "ANIMEXTSbad")) {
            val bytes = gif(application(name, buffering))
            assertEquals(1L, ImageKodec.decodeAnimation(bytes).loopCount)
            assertEquals(1L, ImageKodec.probe(bytes).loopCount)
        }
        val bytes = gif(application("NETSCAPE2.0", buffering, repetition(1), buffering))
        assertEquals(2L, ImageKodec.decodeAnimation(bytes).loopCount)
        assertEquals(2L, ImageKodec.probe(bytes).loopCount)
        val wrongSize = gif(application("NETSCAPE2.0", repetition(1) + byteArrayOf(0)))
        assertEquals(1L, ImageKodec.decodeAnimation(wrongSize).loopCount)
        assertEquals(1L, ImageKodec.probe(wrongSize).loopCount)
    }

    @Test
    fun gifEncodingWritesRepeatsAndOmitsTheExtensionForOnePlay() {
        val frames = ImageKodec.decodeAnimation(gif()).frames
        for ((plays, animation) in listOf(
            0 to KiteAnimation(1, 1, frames, 0),
            1 to KiteAnimation(1, 1, frames, 1),
            2 to KiteAnimation(1, 1, frames, 2),
            3 to KiteAnimation(1, 1, frames, 3),
            65_536 to KiteAnimation(1, 1, frames, 65_536),
        )) {
            val encoded = ImageKodec.encodeGif(animation)
            val name = "NETSCAPE2.0".encodeToByteArray()
            val at = (0..encoded.size - name.size).firstOrNull { i ->
                name.indices.all { j -> encoded[i + j] == name[j] }
            }
            if (plays == 1) {
                assertEquals(null, at)
            } else {
                assertTrue(at != null)
                assertEquals(3, encoded[at + 11].toInt())
                assertEquals(1, encoded[at + 12].toInt())
                val repeats = (encoded[at + 13].toInt() and 255) or
                    ((encoded[at + 14].toInt() and 255) shl 8)
                assertEquals(if (plays == 0) 0 else plays - 1, repeats)
            }
            assertEquals(plays.toLong(), ImageKodec.decodeAnimation(encoded).loopCount)
            assertEquals(plays.toLong(), ImageKodec.probe(encoded).loopCount)
        }
    }

    @Test
    fun gifEncodingRefusesCountsItsWireFormatCannotPreserve() {
        val frames = ImageKodec.decodeAnimation(gif()).frames
        for (plays in listOf(65_537L, 4_294_967_295L, Long.MAX_VALUE)) {
            val error = assertFailsWith<IllegalArgumentException> {
                ImageKodec.encodeGif(KiteAnimation(1, 1, frames, plays))
            }
            assertTrue(error.message.orEmpty().contains("play count"))
        }
        assertFailsWith<IllegalArgumentException> { KiteAnimation(1, 1, frames, -1) }
    }

    @Test
    fun aSingleFrameGifPreservesItsPlayCount() {
        val frames = ImageKodec.decodeAnimation(gif()).frames.take(1)
        for (plays in listOf(0L, 1L, 2L, 65_536L)) {
            val encoded = ImageKodec.encodeGif(KiteAnimation(1, 1, frames, plays))
            assertEquals(plays, ImageKodec.decodeAnimation(encoded).loopCount)
            assertEquals(plays, ImageKodec.probe(encoded).loopCount)
        }
    }

    // The complete two-frame Pillow file reported in #59, with independent zlib streams.
    private fun apng(plays: Long): ByteArray {
        val bytes = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACGFjVEwAAAAC/////y02s5MAAAAaZmNUTAAAAAAAAAABAAAAAQAAAAAAAAAAAAoAZAAAb+bn6wAAAA1JREFUeJxj+M/A8B8ABQAB/4mZPR0AAAAaZmNUTAAAAAEAAAABAAAAAQAAAAAAAAAAAAoAZAAA9JUNPwAAABFmZEFUAAAAAnicY2Bg+P8fAAMCAf/1e6XXAAAAAElFTkSuQmCC")
        for (i in 0..3) bytes[45 + i] = (plays ushr (24 - i * 8)).toByte()
        val crc = Crc32().apply { update(bytes.copyOfRange(37, 49)) }.value()
        for (i in 0..3) bytes[49 + i] = (crc ushr (24 - i * 8)).toByte()
        return bytes
    }

    @Test
    fun apngPlayCountsPreserveTheFullUnsignedField() {
        for (plays in listOf(0L, 1L, 2_147_483_647L, 2_147_483_648L, 4_294_967_295L)) {
            val bytes = apng(plays)
            val decoded = ImageKodec.decodeAnimation(bytes)
            assertEquals(2, decoded.frames.size)
            assertEquals(plays, decoded.loopCount)
            assertEquals(plays, ImageKodec.probe(bytes).loopCount)
        }
    }
}
