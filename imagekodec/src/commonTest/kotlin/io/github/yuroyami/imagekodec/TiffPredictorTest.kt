package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertEquals

class TiffPredictorTest {
    @Test
    fun tiledRgbPredictorResetsAtEachPaddedTileRow() = checkLayouts(rgb = true, tiled = true)

    @Test
    fun tiledGrayPredictorResetsAtEachPaddedTileRow() = checkLayouts(rgb = false, tiled = true)

    @Test
    fun stripPredictorStillResetsAtEveryRow() = checkLayouts(rgb = true, tiled = false)

    private fun checkLayouts(rgb: Boolean, tiled: Boolean) {
        for (bits in listOf(8, 16)) for (le in listOf(true, false)) {
            for (planar in listOf(false, true)) for (compression in listOf(1, 8)) {
                val label = "$bits bits, le=$le, planar=$planar, compression=$compression"
                val bitmap = ImageKodec.decode(predictorTiff(bits, le, planar, compression, rgb, tiled))
                assertEquals(35, bitmap.width, label)
                assertEquals(19, bitmap.height, label)
                for (y in 0 until 19) for (x in 0 until 35) {
                    fun channel(c: Int) = predictorSample(x, y, c, bits) ushr (bits - 8)
                    val expected = if (rgb) argb(255, channel(0), channel(1), channel(2))
                        else gray(channel(0))
                    assertEquals(expected, bitmap[x, y], "$label at $x, $y")
                }
            }
        }
    }
}

private fun predictorSample(x: Int, y: Int, c: Int, bits: Int): Int =
    (x * 719 + y * 1139 + c * 11813 + 249) and ((1 shl bits) - 1)

/** TIFF 6.0 sections 14/15: the first sample of each tile row is absolute. */
internal fun predictorTiff(
    bits: Int = 8,
    le: Boolean = true,
    planar: Boolean = false,
    compression: Int = 8,
    rgb: Boolean = true,
    tiled: Boolean = true,
    predictor: Int = 2,
): ByteArray {
    val width = 35
    val height = 19
    val samples = if (rgb) 3 else 1
    val blockWidth = if (tiled) 16 else width
    val blockHeight = if (tiled) 16 else 8
    val across = (width + blockWidth - 1) / blockWidth
    val down = (height + blockHeight - 1) / blockHeight
    val planes = if (planar) samples else 1
    val perPixel = if (planar) 1 else samples
    val blocks = ArrayList<ByteArray>()
    for (p in 0 until planes) for (by in 0 until down) for (bx in 0 until across) {
        val rows = if (tiled) blockHeight else minOf(blockHeight, height - by * blockHeight)
        val raw = ByteArray(blockWidth * rows * perPixel * bits / 8)
        var at = 0
        for (y in 0 until rows) for (x in 0 until blockWidth) for (c in 0 until perPixel) {
            val channel = if (planar) p else c
            val sx = bx * blockWidth + x
            val sy = by * blockHeight + y
            val stored = predictorSample(sx, sy, channel, bits) -
                if (predictor == 1 || x == 0) 0 else predictorSample(sx - 1, sy, channel, bits)
            if (bits == 8) raw[at++] = stored.toByte() else {
                raw[at++] = (stored ushr if (le) 0 else 8).toByte()
                raw[at++] = (stored ushr if (le) 8 else 0).toByte()
            }
        }
        blocks.add(if (compression == 8) Zlib.compress(raw) else raw)
    }
    val offsets = IntArray(blocks.size)
    for (i in 1 until blocks.size) offsets[i] = offsets[i - 1] + blocks[i - 1].size
    data class Field(val tag: Int, val type: Int, val values: IntArray)
    fun short(tag: Int, vararg values: Int) = Field(tag, 3, values)
    fun long(tag: Int, vararg values: Int) = Field(tag, 4, values)
    val fields = mutableListOf(
        long(256, width), long(257, height), Field(258, 3, IntArray(samples) { bits }),
        short(259, compression), short(262, if (rgb) 2 else 1), short(277, samples),
        short(284, if (planar) 2 else 1), short(317, predictor),
    )
    val offsetTag = if (tiled) 324 else 273
    fields.add(Field(offsetTag, 4, offsets))
    fields.add(Field(if (tiled) 325 else 279, 4, IntArray(blocks.size) { blocks[it].size }))
    if (tiled) fields.addAll(listOf(long(322, blockWidth), long(323, blockHeight)))
    else fields.add(long(278, blockHeight))
    fields.sortBy { it.tag }
    fun size(f: Field) = f.values.size * if (f.type == 3) 2 else 4
    var pixelsAt = 8 + 2 + fields.size * 12 + 4
    val extra = HashMap<Int, Int>()
    for (f in fields) if (size(f) > 4) {
        extra[f.tag] = pixelsAt
        pixelsAt += size(f)
    }
    val out = ByteArray(pixelsAt + blocks.sumOf { it.size })
    var at = 0
    fun u16(value: Int) {
        out[at++] = (value ushr if (le) 0 else 8).toByte()
        out[at++] = (value ushr if (le) 8 else 0).toByte()
    }
    fun u32(value: Int) {
        u16(value ushr if (le) 0 else 16)
        u16(value ushr if (le) 16 else 0)
    }
    fun writeValues(f: Field) {
        for (value in f.values) {
            val v = if (f.tag == offsetTag) pixelsAt + value else value
            if (f.type == 3) u16(v) else u32(v)
        }
    }
    out[at++] = if (le) 'I'.code.toByte() else 'M'.code.toByte()
    out[at++] = out[0]
    u16(42); u32(8); u16(fields.size)
    for (f in fields) {
        u16(f.tag); u16(f.type); u32(f.values.size)
        val slot = at
        if (size(f) > 4) u32(extra.getValue(f.tag)) else writeValues(f)
        at = slot + 4
    }
    u32(0)
    for (f in fields) extra[f.tag]?.let { at = it; writeValues(f) }
    for (i in blocks.indices) blocks[i].copyInto(out, pixelsAt + offsets[i])
    return out
}
