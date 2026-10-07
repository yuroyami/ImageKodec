package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TiffFillOrderTest {
    @Test
    fun reportedLeastSignificantBitFixtureStartsWithWhite() {
        val bytes = Base64.decode("SUkqAAgAAAAKAAABBAABAAAACAAAAAEBBAABAAAAAQAAAAIBAwABAAAAAQAAAAMBAwABAAAAAQAAAAYBAwABAAAAAQAAAAoBAwABAAAAAgAAABEBBAABAAAAhgAAABUBAwABAAAAAQAAABYBBAABAAAAAQAAABcBBAABAAAAAQAAAAAAAAAB")
        val bitmap = ImageKodec.decode(bytes)
        for (x in 0..7) assertEquals(gray(if (x == 0) 255 else 0), bitmap[x, 0])
    }

    @Test
    fun packedSamplesAgreeAcrossBothFillOrdersAndByteCodecs() {
        for (bits in listOf(1, 2, 4)) for (compression in listOf(1, 5, 8, 32946, 32773)) {
            for (order in listOf(1, 2)) {
                val bitmap = ImageKodec.decode(withFillOrder(packedFillTiff(bits, compression), order))
                for (y in 0..2) for (x in 0..12) {
                    val value = ((x * 3 + y * 5) and ((1 shl bits) - 1)) * 255 / ((1 shl bits) - 1)
                    assertEquals(gray(value), bitmap[x, y], "$bits/$compression/$order at $x, $y")
                }
            }
        }
    }

    @Test
    fun byteOrderPredictorAndTilePlaneBoundariesAreIndependentOfFillOrder() {
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) {
            for (planar in listOf(false, true)) for (tiled in listOf(false, true)) {
                for (compression in listOf(1, 8)) {
                    val bytes = predictorTiff(bits, le, planar, compression, tiled = tiled)
                    val expected = ImageKodec.decode(bytes)
                    for (order in listOf(1, 2)) {
                        val actual = ImageKodec.decode(withFillOrder(bytes, order))
                        assertContentEquals(expected.argb, actual.argb, "$bits/$le/$planar/$tiled/$compression/$order")
                    }
                }
            }
        }
    }

    @Test
    fun faxCodesAreNormalizedBeforeParsing() {
        val mhRow = "000111010".repeat(4).padEnd(40, '0')
        val streams = listOf(
            2 to faxBits(mhRow + mhRow),
            3 to group3EolStream(true),
            4 to faxBits("001000111010".repeat(4) + "11111111"),
        )
        for ((compression, stream) in streams) for (photo in listOf(0, 1)) {
            val bytes = faxTiff(stream, compression, photo, width = if (compression == 3) 7 else 8, t4Options = 4)
            val expected = ImageKodec.decode(bytes)
            for (order in listOf(1, 2)) {
                assertContentEquals(expected.argb, ImageKodec.decode(withFillOrder(bytes, order)).argb,
                    "$compression/$photo/$order")
            }
        }
    }

    @Test
    fun malformedFillOrderHasNamedProbeAndDecodeErrors() {
        val base = packedFillTiff(1)
        for ((order, type, count) in listOf(
            Triple(0, 3, 1), Triple(3, 3, 1), Triple(65535, 3, 1),
            Triple(1, 1, 1), Triple(1, 4, 1), Triple(1, 3, 0), Triple(1, 3, 2),
        )) {
            val bytes = withFillOrder(base, order, type, count)
            val probe = assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
            val decode = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
            assertTrue(probe.message.orEmpty().contains("FillOrder"))
            assertTrue(decode.message.orEmpty().contains("FillOrder"))
        }
    }
}

internal fun packedFillTiff(bits: Int, compression: Int = 1): ByteArray {
    val raw = faxBits((0..2).joinToString("") { y ->
        (0..12).joinToString("") { x ->
            ((x * 3 + y * 5) and ((1 shl bits) - 1)).toString(2).padStart(bits, '0')
        }.let { it.padEnd((it.length + 7) / 8 * 8, '0') }
    })
    val payload = when (compression) {
        5 -> faxBits((listOf(256) + raw.map { it.toInt() and 255 } + 257)
            .joinToString("") { it.toString(2).padStart(9, '0') })
        8, 32946 -> Zlib.compress(raw)
        32773 -> byteArrayOf((raw.size - 1).toByte()) + raw
        else -> raw
    }
    return faxTiff(payload, compression, photometric = 1, width = 13, height = 3).also { bytes ->
        for (i in 0 until (bytes[8].toInt() and 255)) {
            val at = 10 + i * 12
            if ((bytes[at].toInt() and 255) == 2 && bytes[at + 1].toInt() == 1) bytes[at + 8] = bits.toByte()
        }
    }
}

/** Append an IFD so existing offsets stay absolute, then change only the stored bit order. */
internal fun withFillOrder(input: ByteArray, order: Int, type: Int = 3, count: Int = 1): ByteArray {
    val le = input[0] == 'I'.code.toByte()
    fun u16(at: Int) = if (le) (input[at].toInt() and 255) or ((input[at + 1].toInt() and 255) shl 8)
        else ((input[at].toInt() and 255) shl 8) or (input[at + 1].toInt() and 255)
    fun u32(at: Int) = if (le) u16(at) or (u16(at + 2) shl 16) else (u16(at) shl 16) or u16(at + 2)
    val ifd = u32(4)
    val entries = (0 until u16(ifd)).map { ifd + 2 + it * 12 }
    fun values(tag: Int): IntArray {
        val at = entries.first { u16(it) == tag }
        val n = u32(at + 4)
        val short = u16(at + 2) == 3
        val size = if (short) 2 else 4
        val base = if (size * n <= 4) at + 8 else u32(at + 8)
        return IntArray(n) { if (short) u16(base + it * size) else u32(base + it * size) }
    }
    val output = input.copyOf(input.size + 2 + (entries.size + 1) * 12 + 4)
    fun put16(at: Int, value: Int) {
        output[at] = (value ushr if (le) 0 else 8).toByte()
        output[at + 1] = (value ushr if (le) 8 else 0).toByte()
    }
    fun put32(at: Int, value: Int) {
        put16(at, value ushr if (le) 0 else 16)
        put16(at + 2, value ushr if (le) 16 else 0)
    }
    val newIfd = input.size
    put32(4, newIfd); put16(newIfd, entries.size + 1)
    var at = newIfd + 2
    for (entry in (entries + -1).sortedBy { if (it < 0) 266 else u16(it) }) {
        if (entry >= 0) input.copyInto(output, at, entry, entry + 12) else {
            put16(at, 266); put16(at + 2, type); put32(at + 4, count)
            if (type == 4) put32(at + 8, order) else put16(at + 8, order)
        }
        at += 12
    }
    if (order == 2) {
        val tiled = entries.any { u16(it) == 324 }
        val offsets = values(if (tiled) 324 else 273)
        val counts = values(if (tiled) 325 else 279)
        for (i in offsets.indices) for (p in offsets[i] until offsets[i] + counts[i]) {
            output[p] = (input[p].toInt() and 255).toString(2).padStart(8, '0').reversed().toInt(2).toByte()
        }
    }
    return output
}
