package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file that names one item many times, through nested derived images, costs that item's work once (#166). */
@OptIn(ExperimentalHeifCodecApi::class)
class DerivedWalkTest {

    @AfterTest
    fun forgetTheCodec() = HeifCodecs.reset()

    /** One gray sample per item: `cnt1` items decode to a 1 by 1 picture of their one byte, and count their decodes. */
    private class CountingCodec : HeifCodec {
        var decodes = 0
        override val itemType = "cnt1"
        override val sampleEntryTypes = setOf("cnt1")
        override val configurationType = "cntC"
        override fun inspect(configuration: ByteArray?, data: ByteArray?) = HeifCodecInfo(1, 1, 8, 8, chromaFormat = 0)
        override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
                decodes++
                return HeifPlanes(1, 1, 8, 8, 0, 0, arrayOf(shortArrayOf((sample[0].toInt() and 0xFF).toShort())), intArrayOf(1), true)
            }
        }
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false

    /**
     * [levels] nested grids, each [side] by [side] copies of the one below it, over one `cnt1` item.
     * Without a memo the walk visits side^(2 levels) items.
     */
    private fun nested(side: Int, levels: Int) = HeifBuilder().apply {
        add(100, "cnt1", byteArrayOf(77), listOf(box("cntC", ByteArray(1)) to true, ispe(1, 1)), hidden = true)
        var below = 100L
        var size = 1
        for (level in 1..levels) {
            size *= side
            val id = level
            add(id, "grid", byteArrayOf(0, 0, (side - 1).toByte(), (side - 1).toByte()) + be16(size) + be16(size), listOf(ispe(size, size)), hidden = level < levels)
            references += Triple("dimg", id.toLong(), LongArray(side * side) { below })
            below = id.toLong()
        }
        primary = levels
    }.build()

    @Test
    fun aWideNestOfOneItemProbesQuickly() {
        ImageKodec.registerHeifCodec(CountingCodec())
        // 256 references a level, 4 levels: 4.3 billion visits without the memo. The canvas is past
        // the size limit, so the probe may refuse it, but it must say so in time.
        val file = nested(16, 4)
        withDeadline("probe of a wide nest", 5_000) {
            try {
                ImageKodec.probe(file)
            } catch (_: ImageDecodeException) {
            }
        }
    }

    @Test
    fun aDeepNestDecodesItsOneItemOnce() {
        val codec = CountingCodec()
        ImageKodec.registerHeifCodec(codec)
        // Two by two, 8 levels: 65,536 decodes without the memo.
        val file = nested(2, 8)
        withDeadline("decode of a deep nest", 5_000) {
            val bitmap = ImageKodec.decode(file)
            assertEquals(256, bitmap.width)
            assertTrue(bitmap.argb.all { it == 0xFF4D4D4D.toInt() })
        }
        assertEquals(1, codec.decodes)
    }
}
