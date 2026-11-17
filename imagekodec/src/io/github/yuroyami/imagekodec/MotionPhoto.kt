package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer

/**
 * Where the video of a Motion Photo sits in its file (#131): [videoOffset] bytes from the start,
 * [videoLength] bytes long. [format] says how the file named it.
 */
public class MotionPhotoInfo(
    public val videoOffset: Int,
    public val videoLength: Int,
    public val format: Format,
) {
    /** How a file names its video. */
    public enum class Format {
        /**
         * Google's Motion Photo format: `Camera:MotionPhoto` and a `Container:Directory` item of semantic
         * `MotionPhoto` in a JPEG, or the top-level `mpvd` box of a HEIC or AVIF file (#155).
         */
        GOOGLE_MOTION_PHOTO,

        /** The older Google format: `GCamera:MicroVideo` and `GCamera:MicroVideoOffset`. */
        GOOGLE_MICRO_VIDEO,

        /** A Samsung trailer: a `MotionPhoto_Data` entry after the end of the JPEG, indexed by `SEFH` and `SEFT`. */
        SAMSUNG,
    }

    override fun toString(): String = "MotionPhotoInfo($format, video at $videoOffset, $videoLength bytes)"
}

/** Finds and removes the video of a Motion Photo (#131). */
internal object MotionPhoto {

    private val SAMSUNG_MARKER = "MotionPhoto_Data".encodeToByteArray()

    // The namespaces of Motion Photo 1.0 (developer.android.com/media/platform/motion-photo-format).
    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

    /** A namespace's prefixes in [xmp]: those its `xmlns` declarations bind, then the usual ones. */
    private fun prefixes(xmp: String, uri: String, usual: List<String>): List<String> =
        (Regex("""xmlns:([A-Za-z_][\w.-]*)\s*=\s*"${Regex.escape(uri)}"""").findAll(xmp).map { it.groupValues[1] }.toList() + usual).distinct()

    private fun camera(xmp: String) = prefixes(xmp, CAMERA_NS, listOf("Camera", "GCamera"))

    /** The value of the property [local] in any of [prefixes]. */
    private fun flag(xmp: String, prefixes: List<String>, local: String): String? =
        prefixes.firstNotNullOfOrNull { flag(xmp, "$it:$local") }

    fun find(data: ByteArray): MotionPhotoInfo? {
        if (AvifContainer.heifFormat(data) != null) return findHeif(data)
        if (data.size < 4 || (data[0].toInt() and 0xFF) != 0xFF || (data[1].toInt() and 0xFF) != 0xD8) return null
        val xmp = ImageKodec.readMetadata(data).xmp
        if (xmp != null) {
            if (flag(xmp, camera(xmp), "MotionPhoto") == "1") {
                val length = videoItemLength(xmp)
                if (length != null && length in 1..data.size) {
                    return MotionPhotoInfo(data.size - length, length, MotionPhotoInfo.Format.GOOGLE_MOTION_PHOTO)
                }
            }
            if (flag(xmp, camera(xmp), "MicroVideo") == "1") {
                val offset = flag(xmp, camera(xmp), "MicroVideoOffset")?.toIntOrNull()
                if (offset != null && offset in 1..data.size) {
                    return MotionPhotoInfo(data.size - offset, offset, MotionPhotoInfo.Format.GOOGLE_MICRO_VIDEO)
                }
            }
        }
        // A Samsung file: the video follows the marker, after the end of the JPEG.
        val end = jpegEnd(data) ?: return null
        val marker = indexOf(data, SAMSUNG_MARKER, end)
        if (marker < 0) return null
        val start = marker + SAMSUNG_MARKER.size
        val trailer = samsungIndexStart(data, end)
        val stop = if (trailer in start..data.size) trailer else data.size
        if (stop <= start) return null
        return MotionPhotoInfo(start, stop - start, MotionPhotoInfo.Format.SAMSUNG)
    }

    /**
     * The video of a HEIF Motion Photo: the payload of its top-level `mpvd` box, when its XMP says
     * `MotionPhoto` 1. A box of size 0, which Motion Photo 1.0 forbids there, names no video.
     */
    private fun findHeif(data: ByteArray): MotionPhotoInfo? {
        val box = mpvd(data) ?: return null
        val xmp = ImageKodec.readMetadata(data).xmp ?: return null
        if (flag(xmp, camera(xmp), "MotionPhoto") != "1") return null
        return MotionPhotoInfo(box[1], box[2] - box[1], MotionPhotoInfo.Format.GOOGLE_MOTION_PHOTO)
    }

    /** The start, payload start and end of the top-level `mpvd` box of [data], or null. */
    private fun mpvd(data: ByteArray): IntArray? {
        var at = 0
        while (at + 8 <= data.size) {
            var size = 0L
            for (i in 0 until 4) size = (size shl 8) or (data[at + i].toLong() and 0xFF)
            val type = CharArray(4) { (data[at + 4 + it].toInt() and 0xFF).toChar() }.concatToString()
            var header = 8
            if (size == 1L) {
                if (at + 16 > data.size) return null
                size = 0
                for (i in 0 until 8) size = (size shl 8) or (data[at + 8 + i].toLong() and 0xFF)
                header = 16
            }
            if (size == 0L || size < header || size > data.size - at) return null
            if (type == "mpvd") return intArrayOf(at, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return null
    }

    fun remove(data: ByteArray): ByteArray {
        val info = find(data) ?: return data.copyOf()
        if (AvifContainer.heifFormat(data) != null) {
            val box = mpvd(data)!!
            // The XMP stays as it is, which Motion Photo 1.0 allows when no video follows, so no item moves.
            if (box[2] != data.size) {
                throw ImageDecodeException("Motion Photo: boxes follow the 'mpvd' box, so removing it would move their data")
            }
            return data.copyOfRange(0, box[0])
        }
        return when (info.format) {
            MotionPhotoInfo.Format.SAMSUNG -> data.copyOfRange(0, jpegEnd(data) ?: info.videoOffset)
            else -> {
                val still = data.copyOfRange(0, info.videoOffset)
                rewriteXmp(still) { xmp -> stillXmp(xmp) }
            }
        }
    }

    /** The value of the XMP property [name], as an attribute or an element, or null. */
    private fun flag(xmp: String, name: String): String? {
        Regex("""${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(xmp)?.let { return it.groupValues[1].trim() }
        Regex("""<${Regex.escape(name)}>([^<]*)</${Regex.escape(name)}>""").find(xmp)?.let { return it.groupValues[1].trim() }
        return null
    }

    /** The `Item:Length` of the directory item whose semantic is `MotionPhoto`. */
    private fun videoItemLength(xmp: String): Int? {
        val item = prefixes(xmp, ITEM_NS, listOf("Item"))
        return directoryItems(xmp).firstOrNull { flag(it, item, "Semantic") == "MotionPhoto" }
            ?.let { flag(it, item, "Length")?.toIntOrNull() }
    }

    /** The `rdf:li` items of the `Container:Directory`, each as its text. */
    private fun directoryItems(xmp: String): List<String> {
        val directory = prefixes(xmp, CONTAINER_NS, listOf("Container")).firstNotNullOfOrNull { p ->
            Regex("""<${Regex.escape(p)}:Directory>([\s\S]*?)</${Regex.escape(p)}:Directory>""").find(xmp)
        } ?: return emptyList()
        return Regex("""<rdf:li\b[\s\S]*?</rdf:li>""").findAll(directory.groupValues[1]).map { it.value }.toList()
    }

    /**
     * [xmp] for the still photo: `Camera:MotionPhoto` 0, the directory item of the video and the
     * MicroVideo fields gone, and the timestamp of the still frame gone. A gain map item stays.
     */
    private fun stillXmp(xmp: String): String {
        var s = xmp
        for (cam in camera(xmp)) {
            val c = Regex.escape(cam)
            s = Regex("""($c:MotionPhoto\s*=\s*)"1"""").replace(s) { it.groupValues[1] + "\"0\"" }
            s = Regex("""<$c:MotionPhoto>\s*1\s*</$c:MotionPhoto>""").replace(s, "<$cam:MotionPhoto>0</$cam:MotionPhoto>")
            for (local in listOf("MotionPhotoPresentationTimestampUs", "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs")) {
                s = Regex("""\s$c:$local\s*=\s*"[^"]*"""").replace(s, "")
                s = Regex("""<$c:$local>[^<]*</$c:$local>""").replace(s, "")
            }
        }
        // The video's item of the directory; matched within one rdf:li, so a gain map item stays.
        // [\s\S] spans lines on every platform; DOT_MATCHES_ALL is the JVM's alone.
        for (item in prefixes(xmp, ITEM_NS, listOf("Item"))) {
            val i = Regex.escape(item)
            s = Regex("""<rdf:li\b(?:(?!</rdf:li>)[\s\S])*?$i:Semantic\s*=\s*"MotionPhoto"(?:(?!</rdf:li>)[\s\S])*?</rdf:li>""")
                .replace(s, "")
        }
        return s
    }

    /** [jpeg] with its XMP packet replaced by [change] of it; the bytes before and after stay. */
    private fun rewriteXmp(jpeg: ByteArray, change: (String) -> String): ByteArray {
        val signature = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()
        var at = 2
        while (at + 4 <= jpeg.size) {
            if ((jpeg[at].toInt() and 0xFF) != 0xFF) break
            val marker = jpeg[at + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            if (length < 2 || at + 2 + length > jpeg.size) break
            if (marker == 0xE1 && length - 2 >= signature.size && (0 until signature.size).all { jpeg[at + 4 + it] == signature[it] }) {
                val old = jpeg.copyOfRange(at + 4 + signature.size, at + 2 + length).decodeToString()
                val packet = change(old).encodeToByteArray()
                val newLength = 2 + signature.size + packet.size
                if (newLength > 0xFFFF) return jpeg
                val head = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (newLength ushr 8).toByte(), newLength.toByte())
                return jpeg.copyOfRange(0, at) + head + signature + packet + jpeg.copyOfRange(at + 2 + length, jpeg.size)
            }
            at += 2 + length
        }
        return jpeg
    }

    /** The offset just after the EOI marker of the JPEG at the start of [data], or null. */
    private fun jpegEnd(data: ByteArray): Int? {
        var at = 2
        while (at + 4 <= data.size) {
            if ((data[at].toInt() and 0xFF) != 0xFF) return null
            val marker = data[at + 1].toInt() and 0xFF
            if (marker == 0xFF) { at++; continue }
            if (marker == 0xD9) return at + 2
            if (marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
            val length = ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)
            if (length < 2 || at + 2 + length > data.size) return null
            at += 2 + length
            if (marker == 0xDA) {
                // The entropy-coded data runs to the next marker that is not a stuffed 0 or a restart.
                while (at + 1 < data.size) {
                    if ((data[at].toInt() and 0xFF) == 0xFF) {
                        val next = data[at + 1].toInt() and 0xFF
                        if (next != 0 && next !in 0xD0..0xD7) break
                    }
                    at++
                }
            }
        }
        return null
    }

    /** Where a Samsung trailer's `SEFH` index starts, from the `SEFT` footer at the end, or -1. */
    private fun samsungIndexStart(data: ByteArray, jpegEnd: Int): Int {
        if (data.size < 8 || CharArray(4) { (data[data.size - 4 + it].toInt() and 0xFF).toChar() }.concatToString() != "SEFT") return -1
        val size = (data[data.size - 8].toInt() and 0xFF) or ((data[data.size - 7].toInt() and 0xFF) shl 8) or
            ((data[data.size - 6].toInt() and 0xFF) shl 16) or ((data[data.size - 5].toInt() and 0xFF) shl 24)
        val start = data.size - 8 - size
        return if (start >= jpegEnd) start else -1
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
