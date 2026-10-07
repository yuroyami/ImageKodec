package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JPEG strips and tiles as Technical Note 2 lays them out (compression 7, #8):
 * the tables every block shares in JPEGTables, each block an abbreviated stream
 * with its own frame and scan. The blocks here are our own encoder's output split
 * that way, so the expected pixels are exactly what our JPEG decoder gives the
 * same streams.
 */
class TiffJpegTest {

    private fun sample(width: Int, height: Int, seed: Int) = KiteBitmap(width, height, IntArray(width * height) {
        val x = it % width
        val y = it / width
        argb(0xFF, (x * 37 + seed) and 0xFF, (y * 23 + seed * 3) and 0xFF, ((x xor y) * 11) and 0xFF)
    })

    /** [jpeg] split into its tables (DQT and DHT, between SOI and EOI) and the rest, which starts with SOI. */
    private fun split(jpeg: ByteArray): Pair<ByteArray, ByteArray> {
        val tables = ArrayList<Byte>()
        val rest = ArrayList<Byte>()
        tables += jpeg[0]; tables += jpeg[1]
        rest += jpeg[0]; rest += jpeg[1]
        var at = 2
        while (at < jpeg.size) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                for (i in at until jpeg.size) rest += jpeg[i]
                break
            }
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            val target = if (marker == 0xDB || marker == 0xC4) tables else rest
            for (i in at until at + 2 + length) target += jpeg[i]
            at += 2 + length
        }
        tables += 0xFF.toByte(); tables += 0xD9.toByte()
        return tables.toByteArray() to rest.toByteArray()
    }

    private class Field(val tag: Int, val type: Int, val values: IntArray = IntArray(0), val bytes: ByteArray? = null)

    /**
     * A little-endian TIFF whose blocks are [blocks], and whose JPEGTables are [tables].
     * Offsets and byte counts of the blocks are filled in.
     */
    private fun tiff(fields: List<Field>, blocks: List<ByteArray>, tables: ByteArray?, tiled: Boolean = false): ByteArray {
        val all = fields + listOfNotNull(
            Field(if (tiled) 324 else 273, 4, IntArray(blocks.size)),
            Field(if (tiled) 325 else 279, 4, IntArray(blocks.size) { blocks[it].size }),
            tables?.let { Field(347, 7, bytes = it) },
        )
        val sorted = all.sortedBy { it.tag }
        fun size(f: Field) = f.bytes?.size ?: (f.values.size * if (f.type == 3) 2 else 4)
        var at = 8 + 2 + sorted.size * 12 + 4
        val outside = HashMap<Int, Int>()
        for (f in sorted) if (size(f) > 4) { outside[f.tag] = at; at += size(f) }
        val blockAt = IntArray(blocks.size)
        for (i in blocks.indices) { blockAt[i] = at; at += blocks[i].size }
        val out = ByteArray(at)
        var p = 0
        fun u16(v: Int) { out[p++] = v.toByte(); out[p++] = (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        fun put(f: Field) {
            val values = if (f.tag == 273 || f.tag == 324) blockAt else f.values
            f.bytes?.let { b -> b.copyInto(out, p); p += b.size; return }
            for (v in values) if (f.type == 3) u16(v) else u32(v)
        }
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte(); p = 2; u16(42); u32(8)
        u16(sorted.size)
        for (f in sorted) {
            u16(f.tag); u16(f.type); u32(f.bytes?.size ?: f.values.size)
            val slot = p
            if (size(f) > 4) u32(outside.getValue(f.tag)) else put(f)
            p = slot + 4
        }
        u32(0)
        for (f in sorted) outside[f.tag]?.let { p = it; put(f) }
        for (i in blocks.indices) blocks[i].copyInto(out, blockAt[i])
        return out
    }

    private fun short(tag: Int, vararg v: Int) = Field(tag, 3, v)
    private fun long(tag: Int, vararg v: Int) = Field(tag, 4, v)

    private fun rgbFields(width: Int, height: Int, photometric: Int, extra: List<Field> = emptyList()) = listOf(
        long(256, width), long(257, height), short(258, 8, 8, 8), short(259, 7),
        short(262, photometric), short(277, 3),
    ) + extra

    /** Two YCbCr strips sharing JPEGTables, the last cut short: a seed for the fuzz suite. */
    internal fun seed(): ByteArray {
        val (tables, first) = split(ImageKodec.encodeJpeg(sample(9, 8, 1), quality = 60))
        val (_, second) = split(ImageKodec.encodeJpeg(sample(9, 8, 7), quality = 60))
        return tiff(rgbFields(9, 13, 6, listOf(long(278, 8), short(530, 2, 2))), listOf(first, second), tables)
    }

    @Test
    fun ycbcrStripsShareTheirTables() {
        val top = ImageKodec.encodeJpeg(sample(21, 16, 1), quality = 80)
        val bottom = ImageKodec.encodeJpeg(sample(21, 16, 9), quality = 80)
        val (tables, first) = split(top)
        val (sameTables, second) = split(bottom)
        assertContentEquals(tables, sameTables, "one quality, one set of tables")
        // The last strip is coded at the full strip height and cut to the image's 27 rows.
        val bytes = tiff(rgbFields(21, 27, 6, listOf(long(278, 16), short(530, 2, 2))), listOf(first, second), tables)

        val info = ImageKodec.probe(bytes)
        assertTrue(info.isDecodable, "${info.unsupportedReason}")
        val decoded = ImageKodec.decode(bytes)
        val expected = ImageKodec.decode(top).argb + ImageKodec.decode(bottom).argb.copyOf(21 * 11)
        assertContentEquals(expected, decoded.argb)
    }

    @Test
    fun rgbSamplesPassThroughWithoutConversion() {
        // Stored as RGB, the JPEG's three components are the color itself.
        val jpeg = ImageKodec.encodeJpeg(sample(13, 9, 4), quality = 95)
        val (tables, strip) = split(jpeg)
        val bytes = tiff(rgbFields(13, 9, 2, listOf(long(278, 9))), listOf(strip), tables)
        val samples = ImageKodec.decodeJpegComponents(jpeg)
        val decoded = ImageKodec.decode(bytes)
        for (i in decoded.argb.indices) {
            val expected = argb(0xFF, samples[i % 13, i / 13, 0], samples[i % 13, i / 13, 1], samples[i % 13, i / 13, 2])
            assertEquals(expected, decoded.argb[i], "pixel $i")
        }
    }

    @Test
    fun tilesAreCutAtTheImageEdge() {
        // 24 by 20 in tiles of 16: four tiles, each a whole JPEG with its own tables.
        val tiles = (0 until 4).map { sample(16, 16, it * 5) }
        val streams = tiles.map { ImageKodec.encodeJpeg(it, quality = 90) }
        val bytes = tiff(
            rgbFields(24, 20, 6, listOf(long(322, 16), long(323, 16), short(530, 2, 2))),
            streams, tables = null, tiled = true,
        )
        val decoded = ImageKodec.decode(bytes)
        val parts = streams.map { ImageKodec.decode(it) }
        for (y in 0 until 20) for (x in 0 until 24) {
            val tile = parts[(y / 16) * 2 + x / 16]
            assertEquals(tile[x % 16, y % 16], decoded[x, y], "pixel ($x, $y)")
        }
    }

    @Test
    fun theStreamIsReadAsItIsWhateverFillOrderSays() {
        val jpeg = ImageKodec.encodeJpeg(sample(8, 8, 2), quality = 90)
        val bytes = tiff(rgbFields(8, 8, 6, listOf(short(266, 2), long(278, 8))), listOf(jpeg), tables = null)
        assertContentEquals(ImageKodec.decode(jpeg).argb, ImageKodec.decode(bytes).argb)
    }

    @Test
    fun aBlockThatDoesNotFitItsPlaceIsRefused() {
        val jpeg = ImageKodec.encodeJpeg(sample(8, 8, 2), quality = 90)
        // A strip of 10 rows cannot come from an 8-row JPEG, and gray has one sample, not three.
        val short = tiff(rgbFields(8, 10, 6, listOf(long(278, 10))), listOf(jpeg), tables = null)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(short) }
        val gray = tiff(listOf(long(256, 8), long(257, 8), short(258, 8), short(259, 7), short(262, 1),
            short(277, 1), long(278, 8)), listOf(jpeg), tables = null)
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(gray) }
    }

    @Test
    fun sixteenBitJpegIsRefusedByNameInProbeAndDecode() {
        val jpeg = ImageKodec.encodeJpeg(sample(8, 8, 2), quality = 90)
        val bytes = tiff(listOf(long(256, 8), long(257, 8), short(258, 16, 16, 16), short(259, 7),
            short(262, 2), short(277, 3), long(278, 8)), listOf(jpeg), tables = null)
        val info = ImageKodec.probe(bytes)
        assertFalse(info.isDecodable)
        assertTrue("JPEG" in info.unsupportedReason.orEmpty(), "${info.unsupportedReason}")
        assertFailsWith<UnsupportedImageException> { ImageKodec.decode(bytes) }
    }
}
