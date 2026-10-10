package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.UnsupportedImageException

/**
 * The boxes of a JP2 header that say what the codestream's components mean (T.800, Annex I.5.3):
 * the colour specification, the palette and its component mapping, and the channel definitions.
 * [JpxDecoder] and the probe both read them here, so the two agree on alpha and on what is refused.
 */
internal object Jp2Color {

    /** A colour specification box (I.5.3.3): [enumerated] for method 1, else the ICC profile's data colour space. */
    class ColorSpec(val method: Int, val enumerated: Int, val iccSpace: String?, val icc: ByteArray? = null)

    /**
     * A palette box (I.5.3.4): [values] holds each column's entries, already shifted to the unsigned range,
     * and [depth] each column's bit depth.
     */
    class Palette(val entries: Int, val depth: IntArray, val values: Array<IntArray>)

    /** One component mapping (I.5.3.5): component [component] directly, or through palette column [column]. */
    class Mapping(val component: Int, val palette: Boolean, val column: Int)

    /** One channel definition (I.5.3.6): channel [channel] is of [type] and belongs to [association]. */
    class Definition(val channel: Int, val type: Int, val association: Int)

    class Boxes(
        /** Where the first contiguous codestream box's contents lie in the file. */
        val codestreamStart: Int,
        val codestreamEnd: Int,
        val colors: List<ColorSpec>,
        val palette: Palette?,
        val mappings: List<Mapping>?,
        val definitions: List<Definition>?,
    )

    /** How [JpxDecoder] turns decoded samples into the colours of the result. */
    enum class Space(val channels: Int) { GRAY(1), RGB(3), SYCC(3), ESYCC(3), CMYK(4), CMY(3) }

    /** A channel: a component used directly ([column] -1) or through a palette column. */
    class Channel(val component: Int, val column: Int)

    /** The colour channels in the order of [space], and the opacity channel if there is one. */
    class Plan(val space: Space, val colors: List<Channel>, val alpha: Channel?, val premultiplied: Boolean)

    // ---- boxes ---------------------------------------------------------------------------------

    private fun u16(d: ByteArray, p: Int): Int = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)

    private fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or
            ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)

    private fun damaged(what: String): Nothing = throw ImageDecodeException("JPEG 2000: $what")

    /**
     * The colour boxes of the JP2 file [data], and where its codestream lies. A raw codestream, which
     * starts with SOC, has none of the boxes. Null when a JP2 file holds no codestream box.
     */
    fun parse(data: ByteArray): Boxes? {
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0x4F) {
            return Boxes(0, data.size, emptyList(), null, null, null)
        }
        var start = -1
        var end = -1
        val colors = ArrayList<ColorSpec>()
        var palette: Palette? = null
        var mappings: List<Mapping>? = null
        var definitions: List<Definition>? = null

        fun walk(from: Int, to: Int) {
            var p = from
            while (p + 8 <= to) {
                var len = u32(data, p)
                val type = u32(data, p + 4)
                var body = p + 8
                if (len == 1L) {
                    if (p + 16 > to) return
                    len = (u32(data, p + 8) shl 32) or u32(data, p + 12)
                    body = p + 16
                }
                val boxEnd = if (len == 0L) to else (p + len).coerceAtMost(to.toLong()).toInt()
                if (len != 0L && len < body - p || boxEnd < body) return
                when (type) {
                    0x6A703263L -> if (start < 0) { start = body; end = boxEnd }              // jp2c
                    0x6A703268L -> walk(body, boxEnd)                                         // jp2h
                    0x636F6C72L -> readColor(data, body, boxEnd)?.let { colors.add(it) }      // colr
                    0x70636C72L -> if (palette == null) palette = readPalette(data, body, boxEnd) // pclr
                    0x636D6170L -> if (mappings == null) mappings = readMappings(data, body, boxEnd) // cmap
                    0x63646566L -> if (definitions == null) definitions = readDefinitions(data, body, boxEnd) // cdef
                    else -> {}
                }
                if (len == 0L) break
                p = boxEnd
            }
        }
        walk(0, data.size)
        if (start < 0) return null
        return Boxes(start, end, colors, palette, mappings, definitions)
    }

    private fun readColor(d: ByteArray, from: Int, to: Int): ColorSpec? {
        if (to - from < 3) return null
        val method = d[from].toInt() and 0xFF
        return when (method) {
            1 -> if (to - from >= 7) ColorSpec(1, u32(d, from + 3).toInt(), null) else null
            // ICC: a profile's header holds its data colour space at offset 16.
            2, 3 -> ColorSpec(
                method, -1,
                if (to - from >= 3 + 20) d.copyOfRange(from + 3 + 16, from + 3 + 20).decodeToString() else "",
                d.copyOfRange(from + 3, to),
            )
            else -> ColorSpec(method, -1, null)
        }
    }

    private fun readPalette(d: ByteArray, from: Int, to: Int): Palette {
        if (to - from < 3) damaged("pclr box cut off")
        val entries = u16(d, from)
        val columns = d[from + 2].toInt() and 0xFF
        if (entries !in 1..1024 || columns == 0) damaged("pclr box with $entries entries in $columns columns")
        if (from + 3 + columns > to) damaged("pclr box cut off")
        val depth = IntArray(columns) { (d[from + 3 + it].toInt() and 0x7F) + 1 }
        val signed = BooleanArray(columns) { (d[from + 3 + it].toInt() and 0x80) != 0 }
        if (depth.any { it > 38 }) damaged("pclr column deeper than 38 bits")
        val values = Array(columns) { IntArray(entries) }
        var p = from + 3 + columns
        for (e in 0 until entries) for (c in 0 until columns) {
            val bytes = (depth[c] + 7) / 8
            if (p + bytes > to) damaged("pclr box cut off")
            var v = 0L
            for (i in 0 until bytes) v = (v shl 8) or (d[p + i].toLong() and 0xFF)
            p += bytes
            // Signed entries move to the unsigned range, as signed components do; then keep 16 bits at most.
            if (signed[c]) v = (v + (1L shl (depth[c] - 1))) and ((1L shl depth[c]) - 1)
            values[c][e] = (if (depth[c] > 16) v shr (depth[c] - 16) else v).toInt()
        }
        for (c in 0 until columns) if (depth[c] > 16) depth[c] = 16
        return Palette(entries, depth, values)
    }

    private fun readMappings(d: ByteArray, from: Int, to: Int): List<Mapping> =
        List((to - from) / 4) { i ->
            val p = from + 4 * i
            Mapping(u16(d, p), (d[p + 2].toInt() and 0xFF) == 1, d[p + 3].toInt() and 0xFF)
        }

    private fun readDefinitions(d: ByteArray, from: Int, to: Int): List<Definition> {
        if (to - from < 2) damaged("cdef box cut off")
        val n = u16(d, from)
        if (from + 2 + 6 * n > to) damaged("cdef box cut off")
        return List(n) { i ->
            val p = from + 2 + 6 * i
            Definition(u16(d, p), u16(d, p + 2), u16(d, p + 4))
        }
    }

    // ---- interpretation ------------------------------------------------------------------------

    /** The first colour specification this decoder can apply, or why none can be. */
    private fun space(boxes: Boxes, channels: Int): Space {
        if (boxes.colors.isEmpty()) return if (channels >= 3) Space.RGB else Space.GRAY
        for (c in boxes.colors) spaceOf(c)?.let { return it }
        throw UnsupportedImageException(describe(boxes.colors.first()))
    }

    private fun spaceOf(c: ColorSpec): Space? = when (c.method) {
        1 -> when (c.enumerated) {
            16 -> Space.RGB
            17, 0, 15 -> Space.GRAY      // greyscale, and bi-level, whose samples are already 0 or 1
            18 -> Space.SYCC
            24 -> Space.ESYCC
            12 -> Space.CMYK
            11 -> Space.CMY
            else -> null
        }
        // The profile cannot be applied without a colour engine (#16), so its samples pass as they are.
        2, 3 -> when (c.iccSpace) {
            "GRAY" -> Space.GRAY
            "RGB " -> Space.RGB
            "CMYK" -> Space.CMYK
            else -> null
        }
        else -> null
    }

    private val ENUMERATED_NAMES = mapOf(
        1 to "YCbCr(1)", 3 to "YCbCr(2)", 4 to "YCbCr(3)", 9 to "PhotoYCC", 13 to "YCCK", 14 to "CIELab",
        19 to "CIEJab", 20 to "e-sRGB", 21 to "ROMM-RGB", 22 to "YPbPr(1125/60)", 23 to "YPbPr(1250/50)",
    )

    private fun describe(c: ColorSpec): String = when (c.method) {
        1 -> "JPEG 2000 with the enumerated colour space ${c.enumerated}" +
            (ENUMERATED_NAMES[c.enumerated]?.let { " ($it)" } ?: "")
        2, 3 -> "JPEG 2000 with an ICC profile of colour space '${c.iccSpace.orEmpty().trim()}'"
        else -> "JPEG 2000 with colour method ${c.method}"
    }

    /** Why this decoder refuses [boxes]' colour interpretation, or null when it applies it. */
    fun unsupported(boxes: Boxes): String? {
        if (boxes.colors.isEmpty() || boxes.colors.any { spaceOf(it) != null }) return null
        return describe(boxes.colors.first())
    }

    /**
     * How the [components] decoded components become colours and opacity: the palette mapping, then the
     * channel definitions, then the colour space, in the order of I.5.3. Throws [ImageDecodeException]
     * for a mapping that points past what the file holds, and [UnsupportedImageException] for a colour
     * space this decoder does not convert.
     */
    fun plan(boxes: Boxes, components: Int): Plan {
        val palette = boxes.palette
        val channels: List<Channel> = when {
            palette != null -> {
                val mappings = boxes.mappings ?: damaged("pclr box without a cmap box")
                mappings.map { m ->
                    if (m.component >= components) damaged("cmap maps component ${m.component} of $components")
                    if (m.palette && m.column >= palette.values.size) damaged("cmap maps palette column ${m.column} of ${palette.values.size}")
                    Channel(m.component, if (m.palette) m.column else -1)
                }
            }
            boxes.mappings != null && boxes.mappings.any { it.palette } -> damaged("cmap box without a pclr box")
            else -> List(components) { Channel(it, -1) }
        }
        val space = space(boxes, channels.size)
        val definitions = boxes.definitions
        var alpha: Channel? = null
        var premultiplied = false
        val colors = arrayOfNulls<Channel>(space.channels)
        if (definitions == null) {
            for (k in 0 until minOf(space.channels, channels.size)) colors[k] = channels[k]
            // One channel beyond the colours, with nothing to say what it is: opacity, as OpenJPEG's
            // writers and Pillow read a gray-and-alpha or RGBA codestream, which has no boxes (#82).
            if (channels.size == space.channels + 1) alpha = channels.last()
        } else {
            val described = BooleanArray(channels.size)
            for (d in definitions) {
                if (d.channel >= channels.size) damaged("cdef describes channel ${d.channel} of ${channels.size}")
                described[d.channel] = true
                when (d.type) {
                    0 -> if (d.association in 1..space.channels) colors[d.association - 1] = channels[d.channel]
                    1, 2 -> if ((d.association == 0 || d.association == 65535) && alpha == null) {
                        alpha = channels[d.channel]
                        premultiplied = d.type == 2
                    }
                    else -> {}
                }
            }
            // A colour the definitions leave open takes the first channel they do not describe.
            var next = 0
            for (k in colors.indices) {
                if (colors[k] != null) continue
                while (next < channels.size && described[next]) next++
                if (next < channels.size) colors[k] = channels[next++]
            }
        }
        val present = colors.takeWhile { it != null }.filterNotNull()
        if (present.size < space.channels) {
            // Fewer channels than the colour space has: what is there reads as gray, as OpenJPEG falls back.
            if (present.isEmpty()) damaged("no channel for the first colour")
            return Plan(Space.GRAY, present.take(1), alpha, premultiplied)
        }
        return Plan(space, present, alpha, premultiplied)
    }
}
