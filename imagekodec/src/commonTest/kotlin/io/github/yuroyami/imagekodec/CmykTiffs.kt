package io.github.yuroyami.imagekodec

/**
 * Uncompressed CMYK TIFFs written field by field, for the photometric 5 tests: little endian,
 * one strip, the samples chunky or in four (or five) separate planes.
 */
internal object CmykTiffs {

    /**
     * A [width] by [height] TIFF of [samples], each [bits] (8 or 16) wide and in TIFF's own
     * order (pixel by pixel, or plane by plane with [planar]), 16-bit ones little endian.
     * [extraSamples] adds one ExtraSamples value and a fifth sample a pixel; [inkSet],
     * [inks] and [icc] add those fields.
     */
    fun tiff(
        width: Int,
        height: Int,
        samples: IntArray,
        bits: Int = 8,
        extraSamples: Int? = null,
        planar: Boolean = false,
        inkSet: Int? = null,
        inks: Int? = null,
        icc: ByteArray? = null,
    ): ByteArray {
        val spp = if (extraSamples == null) 4 else 5
        require(samples.size == width * height * spp)
        val pixels = ByteArray(samples.size * bits / 8)
        for (i in samples.indices) {
            if (bits == 8) {
                pixels[i] = samples[i].toByte()
            } else {
                pixels[2 * i] = samples[i].toByte()
                pixels[2 * i + 1] = (samples[i] ushr 8).toByte()
            }
        }
        val planeBytes = pixels.size / spp
        // Each field: tag, type, values. Type 3 SHORT, 4 LONG, 7 UNDEFINED.
        class Field(val tag: Int, val type: Int, val values: IntArray)
        val fields = buildList {
            add(Field(256, 4, intArrayOf(width)))
            add(Field(257, 4, intArrayOf(height)))
            add(Field(258, 3, IntArray(spp) { bits }))
            add(Field(259, 3, intArrayOf(1)))
            add(Field(262, 3, intArrayOf(5)))
            // Offsets relative to the pixels; fixed below.
            add(Field(273, 4, if (planar) IntArray(spp) { it * planeBytes } else intArrayOf(0)))
            add(Field(277, 3, intArrayOf(spp)))
            add(Field(278, 4, intArrayOf(height)))
            add(Field(279, 4, if (planar) IntArray(spp) { planeBytes } else intArrayOf(pixels.size)))
            add(Field(284, 3, intArrayOf(if (planar) 2 else 1)))
            if (inkSet != null) add(Field(332, 3, intArrayOf(inkSet)))
            if (inks != null) add(Field(334, 3, intArrayOf(inks)))
            if (extraSamples != null) add(Field(338, 3, intArrayOf(extraSamples)))
            if (icc != null) add(Field(34675, 7, IntArray(icc.size) { icc[it].toInt() and 0xFF }))
        }
        fun size(f: Field) = f.values.size * when (f.type) { 3 -> 2; 4 -> 4; else -> 1 }
        val ifdBytes = 2 + fields.size * 12 + 4
        var overflowAt = 8 + ifdBytes
        val overflow = HashMap<Int, Int>()
        for (f in fields) if (size(f) > 4) {
            overflow[f.tag] = overflowAt
            overflowAt += size(f)
        }
        val pixelsAt = overflowAt
        val out = ByteArray(pixelsAt + pixels.size)
        var at = 0
        fun u8(v: Int) { out[at++] = v.toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u16(v); u16(v ushr 16) }
        fun value(f: Field, v: Int) = if (f.tag == 273) pixelsAt + v else v
        fun write(f: Field) {
            for (v in f.values) when (f.type) {
                3 -> u16(value(f, v))
                4 -> u32(value(f, v))
                else -> u8(v)
            }
        }
        u8('I'.code); u8('I'.code); u16(42); u32(8)
        u16(fields.size)
        for (f in fields) {
            u16(f.tag); u16(f.type); u32(f.values.size)
            val slot = at
            if (size(f) > 4) u32(overflow.getValue(f.tag)) else write(f)
            at = slot + 4
        }
        u32(0)
        for (f in fields) if (size(f) > 4) {
            at = overflow.getValue(f.tag)
            write(f)
        }
        pixels.copyInto(out, pixelsAt)
        return out
    }

    /** [chunky], four or five samples a pixel, as separate planes. */
    fun planes(chunky: IntArray, spp: Int): IntArray {
        val n = chunky.size / spp
        return IntArray(chunky.size) { chunky[(it % n) * spp + it / n] }
    }
}
