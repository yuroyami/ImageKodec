package io.github.yuroyami.imagekodec.codec

/**
 * The directories of an EXIF block (Exif 2.32, 4.6), and of the TIFF and JPEG XR files that keep
 * the same directories in their own structure: IFD0, and the EXIF, GPS and Interoperability
 * directories it points to.
 *
 * A JPEG, PNG or WebP file holds an EXIF block as it is. A TIFF or JPEG XR file has no place for
 * one: its tags join the file's own directory, and the EXIF and GPS directories hang from it at
 * offsets of the file. So [read] takes the directories out of either, every value turned little
 * endian, and [serialize] lays them out again at an offset, which [block] uses to give a
 * standalone EXIF block and the TIFF and JPEG XR writers to place them in a file.
 *
 * Each value is copied as its type says; a value of type UNDEFINED is copied as it is. A maker
 * note that holds offsets of its own therefore points to the wrong place once moved, as it does
 * after any editor that moves it.
 */
internal object ExifDirectories {

    const val EXIF_IFD = 0x8769
    const val GPS_IFD = 0x8825
    const val INTEROP_IFD = 0xA005

    private const val LONG = 4

    /** Bytes of an element of each TIFF type (TIFF 6.0, section 2; 13 is the IFD type of TN1); 0 for none known. */
    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

    /**
     * An entry of a directory: its [value] in little-endian order, or, for a pointer to a
     * directory, the fields of that directory in [sub].
     */
    class Field(val tag: Int, val type: Int, val count: Int, val value: ByteArray, val sub: List<Field>? = null)

    /**
     * The tags of IFD0 that describe the photo rather than the samples of a file (Exif 2.32,
     * Table 4, and the text tags of T.832 Table A.4): what a TIFF file and an EXIF block share.
     * DocumentName, ImageDescription, Make, Model, Orientation, XResolution, YResolution,
     * PageName, ResolutionUnit, Software, DateTime, Artist, HostComputer, WhitePoint,
     * PrimaryChromaticities and Copyright.
     */
    val DESCRIPTIVE: Set<Int> = setOf(269, 270, 271, 272, 274, 282, 283, 285, 296, 305, 306, 315, 316, 318, 319, 33432)

    /** IFD0 of [exif], a TIFF structure as an APP1 `Exif` segment holds after its 6-byte signature, or null when it is not one. */
    fun parseBlock(exif: ByteArray): List<Field>? {
        if (exif.size < 8) return null
        val little = when {
            exif[0] == 'I'.code.toByte() && exif[1] == 'I'.code.toByte() -> true
            exif[0] == 'M'.code.toByte() && exif[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val r = Reader(exif, little)
        if (r.u16(2) != 42) return null
        return read(exif, little, r.u32(4), 0)
    }

    /**
     * The fields of the directory at [ifd] of the TIFF structure [data] in byte order [little],
     * or null when the directory lies outside it. IFD0 ([depth] 0) points to the EXIF and GPS
     * directories, and the EXIF directory to the Interoperability one; each comes in its
     * pointer's [Field.sub]. An entry of an unknown type, or whose value lies outside [data], is
     * left out, and so is a pointer to a directory outside it, and a second pointer of a kind.
     * Values may overlap in a hostile file, so all those copied together stop at twice the size of
     * [data]: what comes after is left out.
     */
    fun read(data: ByteArray, little: Boolean, ifd: Long, depth: Int): List<Field>? =
        read(Reader(data, little), ifd, depth, LongArray(1) { 2L * data.size + 4096 })

    private fun read(r: Reader, ifd: Long, depth: Int, budget: LongArray): List<Field>? {
        val data = r.data
        val little = r.little
        if (!r.fits(ifd, 2)) return null
        val n = r.u16(ifd.toInt())
        if (!r.fits(ifd + 2, 12L * n)) return emptyList()
        val fields = ArrayList<Field>(n)
        val pointers = HashSet<Int>()
        for (i in 0 until n) {
            val p = ifd.toInt() + 2 + 12 * i
            val tag = r.u16(p)
            val type = r.u16(p + 2)
            val count = r.u32(p + 4)
            val size = TYPE_SIZE.getOrNull(type) ?: 0
            if (size == 0 || count <= 0 || count > data.size) continue
            val pointer = count == 1L && when (depth) {
                0 -> tag == EXIF_IFD || tag == GPS_IFD
                1 -> tag == INTEROP_IFD
                else -> false
            }
            if (pointer && size == 4) {
                if (tag in pointers) continue
                pointers += tag
                val sub = read(r, r.u32(p + 8), depth + 1, budget) ?: continue
                fields += Field(tag, LONG, 1, ByteArray(4), sub)
                continue
            }
            val length = count * size
            val from = if (length <= 4) p + 8L else r.u32(p + 8)
            if (!r.fits(from, length)) continue
            budget[0] -= length
            if (budget[0] < 0) break
            val bytes = ByteArray(length.toInt())
            // Each element little endian: a rational is two 4-byte halves, and bytes stay as they are.
            val unit = if (type == 5 || type == 10) 4 else size
            for (k in 0 until length.toInt() step unit) {
                for (b in 0 until unit) bytes[k + b] = data[(from + k + if (little || unit == 1) b else unit - 1 - b).toInt()]
            }
            fields += Field(tag, type, count.toInt(), bytes)
        }
        return fields
    }

    /**
     * [fields] as a little-endian directory standing at offset [base] of its file: the entries
     * sorted by tag, then each value longer than 4 bytes at an even offset, then each directory a
     * pointer leads to, laid out the same way.
     */
    fun serialize(fields: List<Field>, base: Int): ByteArray {
        val items = fields.sortedBy { it.tag }
        var at = base + 2 + 12 * items.size + 4
        val valueAt = IntArray(items.size)
        for ((i, f) in items.withIndex()) {
            if (f.sub == null && f.value.size > 4) {
                valueAt[i] = at
                at += f.value.size + (f.value.size and 1)
            }
        }
        val subs = arrayOfNulls<ByteArray>(items.size)
        for ((i, f) in items.withIndex()) {
            val sub = f.sub ?: continue
            valueAt[i] = at
            subs[i] = serialize(sub, at).also { at += it.size }
        }
        val out = ByteArray(at - base)
        fun put16(p: Int, v: Int) {
            out[p] = v.toByte()
            out[p + 1] = (v shr 8).toByte()
        }
        fun put32(p: Int, v: Int) {
            put16(p, v)
            put16(p + 2, v ushr 16)
        }
        put16(0, items.size)
        for ((i, f) in items.withIndex()) {
            val p = 2 + 12 * i
            put16(p, f.tag)
            put16(p + 2, f.type)
            put32(p + 4, f.count)
            val sub = subs[i]
            when {
                sub != null -> {
                    put32(p + 8, valueAt[i])
                    sub.copyInto(out, valueAt[i] - base)
                }
                f.value.size > 4 -> {
                    put32(p + 8, valueAt[i])
                    f.value.copyInto(out, valueAt[i] - base)
                }
                else -> f.value.copyInto(out, p + 8)
            }
        }
        put32(2 + 12 * items.size, 0)
        return out
    }

    /** A standalone little-endian EXIF block of IFD0 [fields]: the TIFF header, then the directories from offset 8. */
    fun block(fields: List<Field>): ByteArray =
        byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0) + serialize(fields, 8)

    /** The value of a SHORT field of one value, or null. */
    fun short(field: Field?): Int? =
        if (field != null && field.type == 3 && field.count == 1) (field.value[0].toInt() and 0xFF) or ((field.value[1].toInt() and 0xFF) shl 8) else null

    private class Reader(val data: ByteArray, val little: Boolean) {
        fun u16(at: Int): Int = if (little) (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        else ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

        fun u32(at: Int): Long = if (little) u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        else (u16(at).toLong() shl 16) or u16(at + 2).toLong()

        fun fits(at: Long, n: Long) = at >= 0 && n >= 0 && at + n <= data.size
    }
}
