package io.github.yuroyami.imagekodec.codec

import kotlin.math.max
import kotlin.math.pow

/**
 * The tags of an Apple MakerNote that an Apple HDR gain map needs (#153): 33 (0x0021) and 48
 * (0x0030), from the EXIF of an iPhone photo. The MakerNote is tag 0x927C of the EXIF IFD; Apple's
 * starts with `Apple iOS\0`, two bytes of version and its byte order, and its IFD follows at byte 14,
 * with offsets from the start of the MakerNote, as ExifTool's `Apple.pm` reads it.
 *
 * Like [Exif], it is total: a damaged block gives no value, never an exception.
 */
internal object AppleMakerNote {

    /** Tags [wanted] of the Apple MakerNote in the EXIF block [tiff], which starts at its TIFF header, each read as a number. */
    fun tags(tiff: ByteArray, wanted: Set<Int>): Map<Int, Double> {
        val ifd0 = Ifd.header(tiff, 0) ?: return emptyMap()
        val exifIfd = ifd0.entries(ifd0.first).firstOrNull { it.tag == 0x8769 }?.let { ifd0.u32(it.at + 8) } ?: return emptyMap()
        val note = ifd0.entries(exifIfd).firstOrNull { it.tag == 0x927C && it.type == 7 } ?: return emptyMap()
        val count = ifd0.u32(note.at + 4) ?: return emptyMap()
        val start = (if (count <= 4) (note.at + 8).toLong() else ifd0.u32(note.at + 8) ?: return emptyMap())
        if (start < 0 || count < 16 || start > tiff.size - count) return emptyMap()
        val s = start.toInt()
        if (CharArray(10) { (tiff[s + it].toInt() and 0xFF).toChar() }.concatToString() != "Apple iOS\u0000") return emptyMap()
        val note2 = Ifd.order(tiff, s, s + 12, (s + count).toInt()) ?: return emptyMap()
        val out = HashMap<Int, Double>()
        for (e in note2.entries(14)) if (e.tag in wanted) note2.number(e)?.let { out[e.tag] = it }
        return out
    }

    /**
     * The headroom of an Apple HDR gain map from MakerNote tags 33 and 48, as Apple's "Applying Apple
     * HDR effect to your photos" (developer.apple.com/documentation/appkit/applying-apple-hdr-effect-to-your-photos)
     * gives it: a number of stops by one of four lines, and 2 to the power of that, never below 1.
     */
    fun headroom(maker33: Double, maker48: Double): Double {
        val stops = if (maker33 < 1.0) {
            if (maker48 <= 0.01) -20.0 * maker48 + 1.8 else -0.101 * maker48 + 1.601
        } else {
            if (maker48 <= 0.01) -70.0 * maker48 + 3.0 else -0.303 * maker48 + 2.303
        }
        return 2.0.pow(max(stops, 0.0))
    }

    private class Entry(val tag: Int, val type: Int, val at: Int)

    /** An IFD reader over [data] from [base], where offsets count, to [end]; [first] is the offset of the first IFD. */
    private class Ifd(val data: ByteArray, val base: Int, val end: Int, val little: Boolean, val first: Long) {

        fun u16(at: Int): Int? {
            if (at < base || at > end - 2) return null
            val a = data[at].toInt() and 0xFF
            val b = data[at + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(at: Int): Long? {
            val lo = u16(if (little) at else at + 2) ?: return null
            val hi = u16(if (little) at + 2 else at) ?: return null
            return lo.toLong() or (hi.toLong() shl 16)
        }

        /** The entries of the IFD at [offset] from [base]. */
        fun entries(offset: Long): List<Entry> {
            if (offset < 0 || offset > end - base - 2) return emptyList()
            val at = base + offset.toInt()
            val count = u16(at) ?: return emptyList()
            // A sane IFD has a few dozen entries; the MakerNote of an iPhone photo has about fifty.
            if (count > 512) return emptyList()
            return (0 until count).mapNotNull { i ->
                val e = at + 2 + i * 12
                if (e > end - 12) return@mapNotNull null
                Entry(u16(e)!!, u16(e + 2)!!, e)
            }
        }

        /** The one value of [e] as a number, for a SHORT, LONG, RATIONAL, SSHORT, SLONG, SRATIONAL, FLOAT or DOUBLE. */
        fun number(e: Entry): Double? {
            if (u32(e.at + 4) != 1L) return null
            val size = when (e.type) {
                3, 8 -> 2
                4, 9, 11 -> 4
                5, 10, 12 -> 8
                else -> return null
            }
            val at = if (size <= 4) e.at + 8 else base + (u32(e.at + 8)?.takeIf { it <= end - base - size } ?: return null).toInt()
            return when (e.type) {
                3 -> u16(at)?.toDouble()
                8 -> u16(at)?.toShort()?.toDouble()
                4 -> u32(at)?.toDouble()
                9 -> u32(at)?.toInt()?.toDouble()
                11 -> u32(at)?.let { Float.fromBits(it.toInt()).toDouble() }
                12 -> {
                    val hi = u32(if (little) at + 4 else at) ?: return null
                    val lo = u32(if (little) at else at + 4) ?: return null
                    Double.fromBits((hi shl 32) or lo)
                }
                else -> {
                    val n = u32(at) ?: return null
                    val d = u32(at + 4) ?: return null
                    if (d == 0L) return null
                    if (e.type == 5) n.toDouble() / d.toDouble() else n.toInt().toDouble() / d.toInt().toDouble()
                }
            }
        }

        companion object {
            /** The TIFF header of [data] at [at]: its byte order, 42, and the offset of IFD0. */
            fun header(data: ByteArray, at: Int): Ifd? {
                val ifd = order(data, at, at, data.size) ?: return null
                if (ifd.u16(at + 2) != 42) return null
                return Ifd(data, at, data.size, ifd.little, ifd.u32(at + 4) ?: return null)
            }

            /** A reader from [base] to [end] in the byte order named by the two bytes at [orderAt]. */
            fun order(data: ByteArray, base: Int, orderAt: Int, end: Int): Ifd? {
                if (orderAt < 0 || orderAt > data.size - 2 || end > data.size) return null
                val little = when {
                    data[orderAt] == 'I'.code.toByte() && data[orderAt + 1] == 'I'.code.toByte() -> true
                    data[orderAt] == 'M'.code.toByte() && data[orderAt + 1] == 'M'.code.toByte() -> false
                    else -> return null
                }
                return Ifd(data, base, end, little, 0)
            }
        }
    }
}
