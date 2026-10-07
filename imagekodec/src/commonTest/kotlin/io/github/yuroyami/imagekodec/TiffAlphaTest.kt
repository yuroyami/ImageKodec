package io.github.yuroyami.imagekodec

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TiffAlphaTest {
    @Test
    fun associatedRegressionAndStraightControl() {
        val bytes = Base64.decode("SUkqAAgAAAAKAAABBAABAAAAAQAAAAEBBAABAAAAAQAAAAIBAwAEAAAAhgAAAAMBAwABAAAAAQAAAAYBAwABAAAAAgAAABEBBAABAAAAjgAAABUBAwABAAAABAAAABYBBAABAAAAAQAAABcBBAABAAAABAAAAFIBAwABAAAAAQAAAAAAAAAIAAgACAAIAEAgEIA=")
        assertEquals(0x80804020.toInt(), ImageKodec.decode(bytes)[0, 0])
        bytes[126] = 2
        assertEquals(0x80402010.toInt(), ImageKodec.decode(bytes)[0, 0])
    }

    @Test
    fun associatedColorUsesNativeDepthAcrossGrayRgbAndPlanes() {
        for (bits in listOf(1, 2, 4, 8, 16)) for (photo in listOf(0, 1, 2)) {
            for (le in listOf(true, false)) for (planar in listOf(false, true)) {
                val bitmap = ImageKodec.decode(alphaTiff(bits, photo, le, planar))
                val max = (1 shl bits) - 1
                for (x in 0..5) {
                    val a = alphaSample(x, max)
                    fun color(c: Int): Int {
                        if (a == 0) return 0
                        val stored = (alphaColor(c, max).toLong() * a / max).toInt()
                        val straight = minOf(max.toLong(), (stored * max.toLong() + a / 2) / a).toInt()
                        val value = if (photo == 0) max - straight else straight
                        return if (bits == 16) value ushr 8 else value * 255 / max
                    }
                    val alpha = if (bits == 16) a ushr 8 else a * 255 / max
                    val expected = if (photo == 2) argb(alpha, color(0), color(1), color(2))
                        else argb(alpha, color(0), color(0), color(0))
                    assertEquals(expected, bitmap[x, 0], "$bits/$photo/$le/$planar at $x")
                }
            }
        }
    }

    @Test
    fun alphaDescriptorSelectsItsComponentAndUnspecifiedExtrasAreOpaque() {
        for (descriptor in listOf(1, 2)) {
            val bytes = alphaTiff(extras = intArrayOf(0, descriptor))
            val normal = alphaTiff(extras = intArrayOf(descriptor))
            assertTrue(ImageKodec.decode(bytes).argb.contentEquals(ImageKodec.decode(normal).argb))
            assertTrue(ImageKodec.probe(bytes).hasAlpha)
        }
        val opaque = alphaTiff(extras = intArrayOf(0))
        assertTrue(ImageKodec.decode(opaque).argb.all { it ushr 24 == 255 })
        assertTrue(!ImageKodec.probe(opaque).hasAlpha)
    }

    @Test
    fun alphaZeroIsCanonicalAndOutOfRangeColorsClamp() {
        val bytes = alphaTiff(bits = 16, overbright = true)
        val bitmap = ImageKodec.decode(bytes)
        assertEquals(0, bitmap[0, 0])
        assertEquals(0x00FFFFFF, bitmap[1, 0])
        assertEquals(0x3FFFFFFF, bitmap[2, 0])
    }

    @Test
    fun paletteColorsAreUnassociatedWithoutChangingTheirIndices() {
        for (descriptor in listOf(1, 2)) {
            val bitmap = ImageKodec.decode(alphaTiff(photo = 3, extras = intArrayOf(descriptor)))
            for (x in 0..5) {
                val a = alphaSample(x, 255)
                fun channel(c: Int): Int {
                    val value = paletteAlphaColor(x % 2, c)
                    return (if (descriptor == 2) value else if (a == 0) 0
                        else minOf(65535L, (value * 255L + a / 2) / a).toInt()) ushr 8
                }
                assertEquals(argb(a, channel(0), channel(1), channel(2)), bitmap[x, 0])
            }
        }
    }

    @Test
    fun malformedExtraSamplesHasNamedProbeAndDecodeErrors() {
        for (bytes in listOf(alphaTiff(extras = intArrayOf(1, 2)),
            alphaTiff(extras = intArrayOf(3)), alphaTiff(type = 1),
            alphaTiff(type = 4), alphaTiff(count = 0), alphaTiff(count = 2))) {
            val probe = assertFailsWith<ImageDecodeException> { ImageKodec.probe(bytes) }
            val decode = assertFailsWith<ImageDecodeException> { ImageKodec.decode(bytes) }
            assertTrue(probe.message.orEmpty().contains("ExtraSamples"))
            assertTrue(decode.message.orEmpty().contains("ExtraSamples"))
        }
    }
}

private fun alphaSample(x: Int, max: Int): Int = intArrayOf(0, 1, max / 4, max / 2, max - 1, max)[x]
private fun alphaColor(c: Int, max: Int): Int = intArrayOf(max, max / 2, max / 3)[c]
private fun paletteAlphaColor(index: Int, c: Int): Int = when (c) {
    0 -> if (index == 0) 16384 else 8192
    1 -> if (index == 0) 8192 else 16384
    else -> 0
}

internal fun alphaTiff(
    bits: Int = 8,
    photo: Int = 2,
    le: Boolean = true,
    planar: Boolean = false,
    extras: IntArray = intArrayOf(1),
    type: Int = 3,
    count: Int = extras.size,
    overbright: Boolean = false,
): ByteArray {
    val colors = if (photo == 2) 3 else 1
    val spp = colors + extras.size
    val alphaAt = extras.indexOfFirst { it == 1 || it == 2 }.let { if (it < 0) colors else colors + it }
    val associated = extras.getOrNull(alphaAt - colors) == 1
    val max = (1 shl bits) - 1
    fun stored(x: Int, c: Int): Int {
        val a = alphaSample(x, max)
        return when {
            c == alphaAt -> a
            c >= colors -> max
            photo == 3 -> x % 2
            overbright -> max
            associated -> (alphaColor(c, max).toLong() * a / max).toInt()
            else -> alphaColor(c, max)
        }
    }
    fun encode(samples: List<Int>): ByteArray = if (bits == 16) ByteArray(samples.size * 2) { i ->
        (samples[i / 2] ushr if ((i % 2 == 0) == le) 0 else 8).toByte()
    } else faxBits(samples.joinToString("") { it.toString(2).padStart(bits, '0') })
    val blocks = if (planar) (0 until spp).map { c -> encode((0..5).map { stored(it, c) }) }
        else listOf(encode((0..5).flatMap { x -> (0 until spp).map { stored(x, it) } }))
    data class Field(val tag: Int, val type: Int, val values: IntArray, val count: Int = values.size)
    val offsets = IntArray(blocks.size)
    for (i in 1 until blocks.size) offsets[i] = offsets[i - 1] + blocks[i - 1].size
    val fields = mutableListOf(
        Field(256, 4, intArrayOf(6)), Field(257, 4, intArrayOf(1)), Field(258, 3, IntArray(spp) { bits }),
        Field(259, 3, intArrayOf(1)), Field(262, 3, intArrayOf(photo)), Field(273, 4, offsets),
        Field(277, 3, intArrayOf(spp)), Field(278, 4, intArrayOf(1)),
        Field(279, 4, IntArray(blocks.size) { blocks[it].size }),
        Field(284, 3, intArrayOf(if (planar) 2 else 1)), Field(338, type, extras, count),
    )
    if (photo == 3) fields.add(Field(320, 3, IntArray(3 * (1 shl bits)) { i ->
        paletteAlphaColor(i % (1 shl bits), i / (1 shl bits))
    }))
    fields.sortBy { it.tag }
    fun size(f: Field) = f.values.size * if (f.type == 1) 1 else if (f.type == 3) 2 else 4
    var pixelsAt = 8 + 2 + fields.size * 12 + 4
    val extraAt = HashMap<Int, Int>()
    for (f in fields) if (size(f) > 4) { extraAt[f.tag] = pixelsAt; pixelsAt += size(f) }
    val out = ByteArray(pixelsAt + blocks.sumOf { it.size })
    var at = 0
    fun u16(v: Int) {
        out[at++] = (v ushr if (le) 0 else 8).toByte(); out[at++] = (v ushr if (le) 8 else 0).toByte()
    }
    fun u32(v: Int) { u16(v ushr if (le) 0 else 16); u16(v ushr if (le) 16 else 0) }
    fun write(f: Field) {
        for (value in f.values) {
            val v = if (f.tag == 273) pixelsAt + value else value
            when (f.type) { 1 -> out[at++] = v.toByte(); 3 -> u16(v); else -> u32(v) }
        }
    }
    out[at++] = if (le) 'I'.code.toByte() else 'M'.code.toByte(); out[at++] = out[0]
    u16(42); u32(8); u16(fields.size)
    for (f in fields) {
        u16(f.tag); u16(f.type); u32(f.count)
        val slot = at
        if (size(f) > 4) u32(extraAt.getValue(f.tag)) else write(f)
        at = slot + 4
    }
    u32(0)
    for (f in fields) extraAt[f.tag]?.let { at = it; write(f) }
    for (i in blocks.indices) blocks[i].copyInto(out, pixelsAt + offsets[i])
    return out
}
