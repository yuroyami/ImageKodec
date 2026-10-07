package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Old-style JPEG (compression 6, #8) in its two layouts, built from our own encoder's
 * output: the headers at JPEGInterchangeFormat with the scan in the strip, and bare
 * tables behind the table tags with only scan data in the strip. Both are one JPEG
 * stream once rebuilt, so they must decode to the same pixels. TiffOldJpegOracleTest
 * holds them to libtiff.
 */
class TiffOldJpegTest {

    private val width = 21
    private val height = 16

    private val source = KiteBitmap(width, height, IntArray(width * height) {
        val x = it % width
        val y = it / width
        argb(0xFF, x * 12, y * 15, ((x + y) * 6) and 0xFF)
    })

    // At this quality the encoder subsamples chroma 2 by 2, as the YCbCrSubSampling tag below says.
    private val jpeg = ImageKodec.encodeJpeg(source, quality = 85)

    private class Part(val marker: Int, val start: Int, val end: Int)

    /** The marker segments before the scan, and where the scan header starts. */
    private fun parts(): Pair<List<Part>, Int> {
        val list = ArrayList<Part>()
        var at = 2
        while (true) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA) return list to at
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            list += Part(marker, at, at + 2 + length)
            at += 2 + length
        }
    }

    private class Field(val tag: Int, val type: Int, val values: LongArray)

    private fun short(tag: Int, vararg v: Long) = Field(tag, 3, v)
    private fun long(tag: Int, vararg v: Long) = Field(tag, 4, v)

    /** A little-endian TIFF of [fields] and [blobs]; a LONG value -(i + 1) is blob i's offset. */
    private fun tiff(fields: List<Field>, blobs: List<ByteArray>): ByteArray {
        val sorted = fields.sortedBy { it.tag }
        fun size(f: Field) = f.values.size * if (f.type == 3) 2 else 4
        var at = 8 + 2 + sorted.size * 12 + 4
        val outside = HashMap<Int, Int>()
        for (f in sorted) if (size(f) > 4) { outside[f.tag] = at; at += size(f) }
        val blobAt = IntArray(blobs.size)
        for (i in blobs.indices) { blobAt[i] = at; at += blobs[i].size }
        val out = ByteArray(at)
        var p = 0
        fun u16(v: Int) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() }
        fun u32(v: Long) { u16(v.toInt()); u16((v ushr 16).toInt()) }
        fun put(f: Field) {
            for (v in f.values) if (f.type == 3) u16(v.toInt()) else u32(if (v < 0) blobAt[(-v - 1).toInt()].toLong() else v)
        }
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte(); p = 2; u16(42); u32(8)
        u16(sorted.size)
        for (f in sorted) {
            u16(f.tag); u16(f.type); u32(f.values.size.toLong())
            val slot = p
            if (size(f) > 4) u32(outside.getValue(f.tag).toLong()) else put(f)
            p = slot + 4
        }
        u32(0)
        for (f in sorted) outside[f.tag]?.let { p = it; put(f) }
        for (i in blobs.indices) blobs[i].copyInto(out, blobAt[i])
        return out
    }

    private fun fields(extra: List<Field>, subsampling: LongArray = longArrayOf(2, 2)) = listOf(
        long(256, width.toLong()), long(257, height.toLong()), short(258, 8, 8, 8), short(259, 6),
        short(262, 6), short(277, 3), short(530, *subsampling),
    ) + extra

    /** The headers of [stream] at JPEGInterchangeFormat, the scan header and data in the one strip. */
    internal fun interchangeFormat(stream: ByteArray = jpeg, subsampling: LongArray = longArrayOf(2, 2)): ByteArray {
        var sos = 2
        while ((stream[sos + 1].toInt() and 0xFF) != 0xDA) {
            sos += 2 + (((stream[sos + 2].toInt() and 0xFF) shl 8) or (stream[sos + 3].toInt() and 0xFF))
        }
        val header = stream.copyOf(sos)
        val strip = stream.copyOfRange(sos, stream.size)
        return tiff(fields(listOf(long(273, -2), long(279, strip.size.toLong()), long(513, -1),
            long(514, header.size.toLong())), subsampling), listOf(header, strip))
    }

    @Test
    fun theStreamsSamplingWinsOverTheTag() {
        // Above quality 90 the encoder keeps chroma at full resolution; the tag claims 2 by 2.
        val full = ImageKodec.encodeJpeg(source, quality = 95)
        val honest = ImageKodec.decode(interchangeFormat(full, longArrayOf(1, 1)))
        val claimed = ImageKodec.decode(interchangeFormat(full, longArrayOf(2, 2)))
        assertContentEquals(honest.argb, claimed.argb)
    }

    /** Bare tables behind the table tags, and only scan data in the strip. */
    private fun bareTables(): ByteArray {
        val (parts, sos) = parts()
        val q = ArrayList<ByteArray>()
        val dc = ArrayList<ByteArray>()
        val ac = ArrayList<ByteArray>()
        for (part in parts) {
            var at = part.start + 4
            when (part.marker) {
                0xDB -> while (at < part.end) { q += jpeg.copyOfRange(at + 1, at + 65); at += 65 }
                0xC4 -> while (at < part.end) {
                    val n = (0 until 16).sumOf { jpeg[at + 1 + it].toInt() and 0xFF }
                    val table = jpeg.copyOfRange(at + 1, at + 17 + n)
                    if ((jpeg[at].toInt() and 0xF0) == 0) dc += table else ac += table
                    at += 17 + n
                }
            }
        }
        val scanStart = sos + 2 + (((jpeg[sos + 2].toInt() and 0xFF) shl 8) or (jpeg[sos + 3].toInt() and 0xFF))
        val strip = jpeg.copyOfRange(scanStart, jpeg.size)
        // Luma has the first of each table and both chroma components the second, which the
        // tags say by repeating the second offset.
        fun offsets(first: Int) = longArrayOf(-(first + 1L), -(first + 2L), -(first + 2L))
        val blobs = q + dc + ac + listOf(strip)
        return tiff(fields(listOf(long(273, -(blobs.size.toLong())), long(279, strip.size.toLong()),
            long(519, *offsets(0)), long(520, *offsets(q.size)), long(521, *offsets(q.size + dc.size)))), blobs)
    }

    @Test
    fun bothLayoutsRebuildTheSameStream() {
        val fromHeaders = interchangeFormat()
        val fromTables = bareTables()
        assertTrue(ImageKodec.probe(fromHeaders).isDecodable)
        assertTrue(ImageKodec.probe(fromTables).isDecodable)
        val a = ImageKodec.decode(fromHeaders)
        val b = ImageKodec.decode(fromTables)
        assertContentEquals(a.argb, b.argb)
        // The samples go through the TIFF YCbCr path without upsampling, so they are near the
        // source rather than equal to our own JPEG decode.
        var total = 0L
        for (i in a.argb.indices) for (shift in intArrayOf(16, 8, 0)) {
            total += abs(((a.argb[i] ushr shift) and 0xFF) - ((source.argb[i] ushr shift) and 0xFF))
        }
        assertTrue(total.toDouble() / (a.argb.size * 3) < 4.0, "mean difference ${total.toDouble() / (a.argb.size * 3)}")
    }

    @Test
    fun whatLibtiffCannotReadEitherIsRefusedByName() {
        val base = interchangeFormat()
        fun with(tag: Int, type: Int, value: Int): ByteArray {
            val bytes = base.copyOf()
            val count = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            val fields = ArrayList<Field>()
            for (i in 0 until count) {
                val at = 10 + i * 12
                fun u(o: Int, n: Int) = (0 until n).fold(0L) { acc, k -> acc or ((bytes[at + o + k].toLong() and 0xFF) shl (8 * k)) }
                fields += Field(u(0, 2).toInt(), u(2, 2).toInt(), longArrayOf(u(8, 4)))
            }
            // Rewrite one inline value, or add the field when the file lacks it.
            val existing = (0 until count).firstOrNull { (bytes[10 + it * 12].toInt() and 0xFF) or ((bytes[11 + it * 12].toInt() and 0xFF) shl 8) == tag }
            if (existing != null) {
                val at = 10 + existing * 12 + 8
                bytes[at] = value.toByte(); bytes[at + 1] = (value ushr 8).toByte()
                return bytes
            }
            return tiff(fields(listOf(long(273, -2), long(279, jpeg.size.toLong()), long(513, -1), long(514, 2),
                Field(tag, type, longArrayOf(value.toLong())))), listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), jpeg))
        }
        for ((bytes, reason) in listOf(
            with(512, 3, 14) to "lossless",
            with(284, 3, 2) to "separate planes",
        )) {
            val info = ImageKodec.probe(bytes)
            assertFalse(info.isDecodable)
            assertTrue(reason in info.unsupportedReason.orEmpty(), "${info.unsupportedReason}")
            val error = assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
            assertTrue(reason in error.message.orEmpty(), "${error.message}")
        }
    }

    @Test
    fun aFileWithNoHeadersAndNoTablesIsDamaged() {
        val (_, sos) = parts()
        val strip = jpeg.copyOfRange(sos + 14, jpeg.size)
        val bytes = tiff(fields(listOf(long(273, -1), long(279, strip.size.toLong()))), listOf(strip))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
        assertEquals(ImageFormat.TIFF, ImageKodec.detect(bytes))
    }
}
