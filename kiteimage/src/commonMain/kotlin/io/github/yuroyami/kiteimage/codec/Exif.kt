package io.github.yuroyami.kiteimage.codec

import io.github.yuroyami.kiteimage.Orientation

/**
 * The sliver of EXIF that changes pixels: the `Orientation` tag (274) in IFD0.
 *
 * KiteImage is a codec library, not a metadata library, so this deliberately
 * reads one tag rather than growing into a general EXIF parser. Orientation is
 * the exception because ignoring it is *visibly* wrong: phone cameras store
 * portrait shots as landscape plus a rotation flag, and a decoder that hands
 * back raw pixels shows every one of them on its side.
 *
 * Everything here is total: a malformed EXIF block yields null (meaning "no
 * opinion"), never an exception. Metadata being broken is not a reason to fail
 * a decode that would otherwise succeed, which is exactly how every viewer
 * behaves.
 */
internal object Exif {

    /** EXIF APP1 payload starts with this, then the TIFF header. */
    private val EXIF_MAGIC = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)   // "Exif\0\0"

    /**
     * Read the orientation out of a JPEG APP1 segment payload (the bytes after
     * the segment length, starting at "Exif\0\0"). Null if this isn't an EXIF
     * APP1 or it carries no orientation.
     */
    fun orientationFromApp1(payload: ByteArray): Orientation? {
        if (payload.size < EXIF_MAGIC.size + 8) return null
        for (i in EXIF_MAGIC.indices) if (payload[i] != EXIF_MAGIC[i]) return null
        return orientationFromTiffHeader(payload, EXIF_MAGIC.size)
    }

    /**
     * Read the orientation out of a TIFF structure: [data] at [base] is a TIFF
     * header ("II"/"MM", 42, IFD0 offset). Works for both an EXIF payload's
     * embedded TIFF and a real .tif file, whose orientation lives in the same
     * tag of the same IFD.
     */
    fun orientationFromTiffHeader(data: ByteArray, base: Int): Orientation? {
        if (base < 0 || base + 8 > data.size) return null
        val le = when {
            data[base] == 'I'.code.toByte() && data[base + 1] == 'I'.code.toByte() -> true
            data[base] == 'M'.code.toByte() && data[base + 1] == 'M'.code.toByte() -> false
            else -> return null
        }

        fun u16(at: Int): Int? {
            if (at < 0 || at + 2 > data.size) return null
            val a = data[at].toInt() and 0xFF
            val b = data[at + 1].toInt() and 0xFF
            return if (le) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(at: Int): Int? {
            val lo = u16(if (le) at else at + 2) ?: return null
            val hi = u16(if (le) at + 2 else at) ?: return null
            // EXIF offsets are file positions; anything past 2 GiB is nonsense here.
            val v = lo.toLong() or (hi.toLong() shl 16)
            return if (v in 0..Int.MAX_VALUE.toLong()) v.toInt() else null
        }

        if (u16(base + 2) != 42) return null
        val ifd0 = base + (u32(base + 4) ?: return null)
        val count = u16(ifd0) ?: return null
        // A sane IFD0 is a few dozen entries; a corrupt count could otherwise walk far.
        if (count <= 0 || count > 512) return null

        for (i in 0 until count) {
            val at = ifd0 + 2 + i * 12
            if (at + 12 > data.size) return null
            if ((u16(at) ?: return null) != 274) continue
            val type = u16(at + 2) ?: return null
            // Orientation is a SHORT. Its value is inline (fits in the 4-byte
            // value field), stored at the *start* of that field in both orders.
            if (type != 3) return null
            val raw = u16(at + 8) ?: return null
            return Orientation.fromExif(raw)
        }
        return null
    }

    /**
     * Walk a JPEG's marker stream looking for the EXIF APP1 segment. Stops at
     * SOS (compressed data begins; no more metadata worth having) and is bounded
     * so a corrupt length field can't spin.
     */
    fun orientationFromJpeg(data: ByteArray): Orientation? {
        if (data.size < 4) return null
        if ((data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return null
        var p = 2
        while (p + 4 <= data.size) {
            if ((data[p].toInt() and 0xFF) != 0xFF) return null
            var marker = data[p + 1].toInt() and 0xFF
            var q = p + 2
            // Fill bytes: any run of 0xFF before the real marker code.
            while (marker == 0xFF && q < data.size) {
                marker = data[q].toInt() and 0xFF
                q++
            }
            when (marker) {
                0xD8, 0x01, in 0xD0..0xD7 -> { p = q; continue }        // standalone, no payload
                0xD9, 0xDA -> return null                               // EOI / start of scan
            }
            if (q + 2 > data.size) return null
            val len = ((data[q].toInt() and 0xFF) shl 8) or (data[q + 1].toInt() and 0xFF)
            if (len < 2) return null
            val payloadAt = q + 2
            val payloadLen = len - 2
            if (payloadAt + payloadLen > data.size) return null
            if (marker == 0xE1) {
                orientationFromApp1(data.copyOfRange(payloadAt, payloadAt + payloadLen))?.let { return it }
            }
            p = payloadAt + payloadLen
        }
        return null
    }
}
