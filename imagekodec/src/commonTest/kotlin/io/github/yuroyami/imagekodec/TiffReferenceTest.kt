package io.github.yuroyami.imagekodec

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TiffReferenceTest {
    @Test
    fun reportedRationalDeclarationDecodes() {
        val bytes = Base64.decode("SUkqAAgAAAALAAABBAABAAAAAQAAAAEBBAABAAAAAQAAAAIBAwADAAAAkgAAAAMBAwABAAAAAQAAAAYBAwABAAAABgAAABEBBAABAAAAyAAAABUBAwABAAAAAwAAABYBBAABAAAAAQAAABcBBAABAAAAAwAAABICAwACAAAAAQABABQCBQAGAAAAmAAAAAAAAAAIAAgACAAAAAAAAQAAAP8AAAABAAAAgAAAAAEAAAD/AAAAAQAAAIAAAAABAAAA/wAAAAEAAABkgIA=")
        assertTrue(ImageKodec.probe(bytes).isDecodable)
        assertEquals(0xFF646464.toInt(), ImageKodec.decode(bytes)[0, 0])
    }

    @Test
    fun declaredDefaultsMatchAbsentTagAcrossByteOrdersAndPlanes() {
        for (le in listOf(true, false)) for (planar in listOf(false, true)) {
            val bytes = referenceTiff(le = le, planar = planar)
            val expected = ImageKodec.decode(bytes)
            for (den in listOf(1L, 16843009L)) {
                val refs = defaultReferences(true, 8, den)
                val declared = withReferences(bytes, refs)
                assertTrue(ImageKodec.probe(declared).isDecodable)
                assertTrue(expected.argb.contentEquals(ImageKodec.decode(declared).argb), "$le/$planar/$den")
            }
            assertEquals(0xFF057CE4.toInt(), expected[1, 0])
            assertEquals(0xFFFFA81A.toInt(), expected[2, 0])
        }
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) for (planar in listOf(false, true)) {
            val bytes = predictorTiff(bits, le, planar)
            val declared = withReferences(bytes, defaultReferences(false, bits, if (bits == 16) 65537 else 16843009))
            assertTrue(ImageKodec.decode(bytes).argb.contentEquals(ImageKodec.decode(declared).argb))
        }
    }

    @Test
    fun limitedAndFractionalRangesRetainChromaPrecision() {
        val limited = longArrayOf(15,1,235,1,128,1,240,1,128,1,240,1)
        val expected = intArrayOf(0xFF010101.toInt(), 0xFF007DF3.toInt(), 0xFFFFB212.toInt(),
            0xFFFFFFFF.toInt(), 0xFF008800.toInt(), 0xFFFF7EFF.toInt())
        for (le in listOf(true, false)) for (planar in listOf(false, true)) {
            val bitmap = ImageKodec.decode(withReferences(referenceTiff(le = le, planar = planar), limited))
            for (x in expected.indices) assertEquals(expected[x], bitmap[x, 0], "$le/$planar at $x")
        }
        val fractional = longArrayOf(1,2,511,2,257,2,511,2,257,2,511,2)
        val bitmap = ImageKodec.decode(withReferences(referenceTiff(), fractional))
        assertEquals(0xFF0F100F.toInt(), bitmap[0, 0])
    }

    @Test
    fun rgbRangesUseNativeSamplesAndPreserveZeroAssociatedAlpha() {
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) for (planar in listOf(false, true)) {
            val max = (1 shl bits) - 1
            val refs = longArrayOf(max.toLong(),1,0,1,0,1,max.toLong(),1,0,1,max.toLong(),1)
            val bytes = referenceTiff(ycbcr = false, bits = bits, le = le, planar = planar)
            val bitmap = ImageKodec.decode(withReferences(bytes, refs))
            for (x in 0..5) {
                val codes = referenceCodes[x]
                assertEquals(argb(255, 255 - codes[0], codes[1], codes[2]), bitmap[x, 0])
            }
            val alpha = ImageKodec.decode(withReferences(alphaTiff(bits, le = le, planar = planar), refs))
            assertEquals(0, alpha[0, 0])
        }
    }

    @Test
    fun narrowUnsignedRangesDoNotOverflowOrPrematurelyClamp() {
        val m = 0xFFFFFFFFL
        // At code 1, (1 - (m-1)/m) / (m/(m-1) - (m-1)/m)
        // is just below 1/2, so a 255-wide range rounds to 127.
        val refs = LongArray(12) { i -> when (i % 4) { 0,3 -> m-1; else -> m } }
        val bytes = referenceTiff(ycbcr = false).also { out ->
            val offset = referenceFieldValues(out, 273)[0].toInt()
            out.fill(1, offset, offset + 18)
        }
        assertEquals(0xFF7F7F7F.toInt(), ImageKodec.decode(withReferences(bytes, refs))[0, 0])
        for (c in 0..2) {
            val at = c * 4
            val a = refs[at]; val b = refs[at+1]
            refs[at] = refs[at+2]; refs[at+1] = refs[at+3]; refs[at+2] = a; refs[at+3] = b
        }
        assertEquals(0xFF808080.toInt(), ImageKodec.decode(withReferences(bytes, refs))[0, 0])
        val wideDivisor = LongArray(12) { i -> when (i % 4) { 0 -> 0; else -> m } }
        assertEquals(0xFFFFFFFF.toInt(), ImageKodec.decode(withReferences(bytes, wideDivisor))[0, 0])
    }

    @Test
    fun malformedReferencesHaveNamedProbeAndDecodeErrors() {
        for (le in listOf(true, false)) {
            val source = referenceTiff(le = le)
            val defaults = defaultReferences(true)
            val good = withReferences(source, defaults)
            val bad = mutableListOf(withReferences(source, defaults, type = 3),
                withReferences(source, defaults, count = 5), good.copyOf(good.size - 1),
                withReferences(source, defaults.copyOf().also { it[1] = 0 }),
                withReferences(source, defaults.copyOf().also { it[2] = 0 }))
            bad += good.copyOf().also { bytes ->
                val entry = referenceFieldEntry(bytes, 532)
                referencePut(bytes, entry + 8, 0xFFFFFFFFL, 4)
            }
            for (bytes in bad) {
                assertTrue(assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
                    .message.orEmpty().contains("ReferenceBlackWhite"))
                assertTrue(assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
                    .message.orEmpty().contains("ReferenceBlackWhite"))
            }
        }
    }
}

internal val referenceCodes: Array<IntArray> = arrayOf(intArrayOf(16,128,128), intArrayOf(100,200,60),
    intArrayOf(200,30,220), intArrayOf(235,128,128), intArrayOf(0,0,0), intArrayOf(255,255,255))

internal fun defaultReferences(ycbcr: Boolean, bits: Int = 8, denominator: Long = 1): LongArray =
    LongArray(12) { i -> when (i % 4) {
        0 -> if (ycbcr && i >= 4) 128 * denominator else 0
        2 -> ((1 shl bits) - 1) * denominator
        else -> denominator
    } }

internal fun referenceTiff(ycbcr: Boolean = true, bits: Int = 8, le: Boolean = true, planar: Boolean = false): ByteArray {
    var bytes = withReferenceField(alphaTiff(bits, le = le, planar = planar, extras = intArrayOf()),
        338, 0, 0, byteArrayOf())
    val offsets = referenceFieldValues(bytes, 273)
    for (x in 0..5) for (c in 0..2) {
        val at = offsets[if (planar) c else 0].toInt() + (if (planar) x else x * 3 + c) * bits / 8
        referencePut(bytes, at, referenceCodes[x][c] * (if (bits == 16) 257L else 1L), bits / 8)
    }
    if (ycbcr) {
        bytes = withReferenceField(bytes, 262, 3, 1, byteArrayOf(if (le) 6 else 0, if (le) 0 else 6))
        bytes = withReferenceField(bytes, 530, 3, 2, byteArrayOf(if (le) 1 else 0, if (le) 0 else 1,
            if (le) 1 else 0, if (le) 0 else 1))
    }
    return bytes
}

internal fun referenceRead(bytes: ByteArray, at: Int, size: Int): Long {
    val le = bytes[0] == 'I'.code.toByte()
    var value = 0L
    for (i in 0 until size) value = value or ((bytes[at+i].toLong() and 255) shl if (le) i*8 else (size-1-i)*8)
    return value
}

internal fun referencePut(bytes: ByteArray, at: Int, value: Long, size: Int) {
    val le = bytes[0] == 'I'.code.toByte()
    for (i in 0 until size) bytes[at+i] = (value ushr if (le) i*8 else (size-1-i)*8).toByte()
}

internal fun referenceFieldEntry(bytes: ByteArray, tag: Int): Int {
    val ifd = referenceRead(bytes, 4, 4).toInt()
    return (0 until referenceRead(bytes, ifd, 2).toInt()).map { ifd + 2 + it * 12 }
        .first { referenceRead(bytes, it, 2) == tag.toLong() }
}

internal fun referenceFieldValues(bytes: ByteArray, tag: Int): LongArray {
    val entry = referenceFieldEntry(bytes, tag)
    val type = referenceRead(bytes, entry+2, 2).toInt()
    val size = if (type == 3) 2 else 4
    val count = referenceRead(bytes, entry+4, 4).toInt()
    val at = if (count * size > 4) referenceRead(bytes, entry+8, 4).toInt() else entry+8
    return LongArray(count) { referenceRead(bytes, at + it*size, size) }
}

internal fun withReferences(bytes: ByteArray, pairs: LongArray, type: Int = 5, count: Int = 6): ByteArray {
    val payload = ByteArray(pairs.size * 4)
    for (i in pairs.indices) for (j in 0..3) payload[i*4+j] =
        (pairs[i] ushr if (bytes[0] == 'I'.code.toByte()) j*8 else (3-j)*8).toByte()
    return withReferenceField(bytes, 532, type, count, payload)
}

private fun withReferenceField(bytes: ByteArray, tag: Int, type: Int, count: Int, payload: ByteArray): ByteArray {
    val old = referenceRead(bytes, 4, 4).toInt()
    val entries = (0 until referenceRead(bytes, old, 2).toInt()).map { old+2+it*12 }
        .filter { referenceRead(bytes, it, 2) != tag.toLong() }
    val tags = (entries.map { referenceRead(bytes, it, 2).toInt() } + if (type == 0) emptyList() else listOf(tag)).sorted()
    val ifd = (bytes.size + 1) and -2
    val payloadAt = ifd + 2 + tags.size * 12 + 4
    val out = bytes.copyOf(payloadAt + if (payload.size > 4) payload.size else 0)
    referencePut(out, 4, ifd.toLong(), 4)
    referencePut(out, ifd, tags.size.toLong(), 2)
    for ((i, t) in tags.withIndex()) {
        val at = ifd+2+i*12
        if (t != tag) {
            val from = entries.first { referenceRead(bytes, it, 2) == t.toLong() }
            bytes.copyInto(out, at, from, from+12)
        } else {
            referencePut(out, at, tag.toLong(), 2); referencePut(out, at+2, type.toLong(), 2)
            referencePut(out, at+4, count.toLong(), 4)
            if (payload.size <= 4) payload.copyInto(out, at+8) else {
                referencePut(out, at+8, payloadAt.toLong(), 4); payload.copyInto(out, payloadAt)
            }
        }
    }
    return out
}
