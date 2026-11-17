package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A HEVC image sequence with B pictures through `decodeAnimation` on every target (#157): the frames
 * come in output order, each the picture the decoded picture buffer outputs, converted to RGB by the
 * same path as a picture a test codec hands the core directly.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeicSequenceTest {

    /** The pictures of [HeicSequenceVector] in output order, as HevcDecoder's bumping process gives them. */
    private val outputOrder: List<HeifPlanes> = run {
        val d = HevcDecoder(keepOutput = true)
        for (u in HeicSequenceVector.parameterSets) d.decodeNal(u, 0, u.size, null)
        val units = HevcDecoderTest().interPictures.filter { ((it[0].toInt() shr 1) and 0x3F) !in 32..34 }
        for (u in units) d.decodeNal(u, 0, u.size, null)
        (d.takeOutput() + d.flush()).also { pictures -> assertEquals(listOf(0, 1, 2, 3), pictures.map { it.poc }) }.map { it.toHeifPlanes() }
    }

    /** A codec `tyuv` whose one-byte samples name a picture of [outputOrder]. */
    private inner class Replay : HeifCodec {
        override val itemType = "tyuv"
        override val sampleEntryTypes = setOf("tyuv")
        override val configurationType = "tyuC"

        override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo = HeifCodecInfo(48, 32, 8, 8, 1)

        override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes = outputOrder[sample[0].toInt()]
        }
    }

    @Test
    fun theFramesAreTheBufferOutputInPresentationOrder() {
        ImageKodecHeic.install()
        ImageKodec.registerHeifCodec(Replay())
        // The same pictures handed over in presentation order, with nothing to reorder.
        val reference = HeifBuilder("msf1", listOf("msf1")).apply {
            primary = null
            addTrack(1, "tyuv", 48, 32, ByteArray(0), List(4) { byteArrayOf(it.toByte()) }, handler = "vide")
        }.build()
        val expected = ImageKodec.decodeAnimation(reference).frames.map { it.bitmap.argb }
        for (entry in listOf("hvc1", "hev1")) for (handler in listOf("vide", "pict")) {
            val data = HeicSequenceVector.file(entry, handler)
            val info = ImageKodec.probe(data)
            assertEquals(ImageFormat.HEIC, info.format)
            assertTrue(info.isDecodable, info.unsupportedReason)
            assertEquals(4, info.frameCount)
            assertEquals(48 to 32, info.width to info.height)
            val animation = ImageKodec.decodeAnimation(data)
            assertEquals(4, animation.frames.size)
            for (k in 0 until 4) assertContentEquals(expected[k], animation.frames[k].bitmap.argb, "$entry $handler frame $k")
            assertEquals(List(4) { 100 }, animation.frames.map { it.delayMillis })
            assertEquals(1L, animation.loopCount)
            // The still is the first frame; a shorter decode stops early with the first frames.
            assertContentEquals(expected[0], ImageKodec.decode(data).argb)
            val two = ImageKodec.decodeAnimation(data, maxFrames = 2).frames.map { it.bitmap.argb }
            for (k in 0 until 2) assertContentEquals(expected[k], two[k])
        }
        // The pictures differ, so the order is checked.
        assertTrue(expected.zipWithNext().all { (a, b) -> !a.contentEquals(b) })
    }

    @Test
    fun cancellationRunsOnceAFrame() {
        ImageKodecHeic.install()
        var checks = 0
        ImageKodec.decodeAnimation(HeicSequenceVector.file(), cancellationCheck = { checks++ })
        assertEquals(3, checks)
    }
}
