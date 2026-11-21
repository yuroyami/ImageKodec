package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A codec from outside the core plugs into the HEIF reader through [ImageKodec.registerHeifCodec] (#137). */
@OptIn(ExperimentalHeifCodecApi::class)
class HeifCodecTest {

    @AfterTest
    fun forgetTheFakeCodec() = HeifCodecs.reset()

    /**
     * An invented item type `fake` configured by `fakC`, whose payload gives the size as two
     * 16-bit numbers, and whose data is one gray level. It decodes to 4:4:4 planes of that level.
     */
    private object FakeCodec : HeifCodec {
        override val itemType = "fake"
        override val sampleEntryTypes = setOf("fake")
        override val configurationType = "fakC"

        private fun size(c: ByteArray?): Pair<Int, Int> {
            val b = c ?: throw ImageDecodeException("no fakC")
            return (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)) to (((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        }

        override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
            val (w, h) = size(configuration)
            return HeifCodecInfo(w, h, 8, 8, chromaFormat = 3, colorPrimaries = 1, transferCharacteristics = 13, matrixCoefficients = 6, fullRange = true)
        }

        override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
                cancellationCheck?.invoke()
                val (w, h) = size(configuration)
                val y = ShortArray(w * h) { (sample[0].toInt() and 0xFF).toShort() }
                val c = ShortArray(w * h) { 128 }
                return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(y, c, c.copyOf()), intArrayOf(w, w, w), fullRange = true)
            }
        }
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private fun fakC(w: Int, h: Int) = box("fakC", be16(w) + be16(h)) to true

    private fun single(level: Int) = HeifBuilder().apply {
        add(1, "fake", byteArrayOf(level.toByte()), listOf(ispe(6, 4), fakC(6, 4)))
    }.build()

    private fun grid() = HeifBuilder().apply {
        add(1, "grid", byteArrayOf(0, 0, 1, 1, 0, 8, 0, 8), listOf(ispe(8, 8)))
        for ((k, level) in listOf(10, 20, 30, 40).withIndex()) {
            add(2 + k, "fake", byteArrayOf(level.toByte()), listOf(ispe(4, 4), fakC(4, 4)), hidden = true)
        }
        references += Triple("dimg", 1L, longArrayOf(2, 3, 4, 5))
    }.build()

    @Test
    fun anItemTypeWithoutACodecIsReportedAndRefused() {
        val info = ImageKodec.probe(single(77))
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("fake"), info.unsupportedReason)
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(single(77)) }
    }

    @Test
    fun aRegisteredCodecDecodesAnItem() {
        ImageKodec.registerHeifCodec(FakeCodec)
        ImageKodec.registerHeifCodec(FakeCodec)
        val info = ImageKodec.probe(single(77))
        assertTrue(info.isDecodable, info.unsupportedReason)
        assertEquals(6, info.width)
        assertEquals(4, info.height)
        val bitmap = ImageKodec.decode(single(77))
        assertContentEquals(IntArray(24) { 0xFF4D4D4D.toInt() }, bitmap.argb)
    }

    @Test
    fun aGridOfRegisteredItemsJoinsItsTiles() {
        ImageKodec.registerHeifCodec(FakeCodec)
        val bitmap = ImageKodec.decode(grid())
        assertEquals(8, bitmap.width)
        assertEquals(8, bitmap.height)
        for (y in 0 until 8) for (x in 0 until 8) {
            val level = listOf(10, 20, 30, 40)[(y / 4) * 2 + x / 4]
            assertEquals(0xFF000000.toInt() or (level * 0x010101), bitmap[x, y], "pixel $x, $y")
        }
    }

    @Test
    fun aCodecSeesTheCancellationCheck() {
        ImageKodec.registerHeifCodec(FakeCodec)
        var calls = 0
        ImageKodec.decode(grid(), cancellationCheck = { calls++ })
        assertTrue(calls >= 4, "the check ran $calls times")
    }

    @Test
    fun aNameThatIsNotAFourCharacterCodeIsRefused() {
        val bad = object : HeifCodec by FakeCodec {
            override val itemType = "hevc1"
        }
        assertFailsWith<IllegalArgumentException> { ImageKodec.registerHeifCodec(bad) }
    }
}
