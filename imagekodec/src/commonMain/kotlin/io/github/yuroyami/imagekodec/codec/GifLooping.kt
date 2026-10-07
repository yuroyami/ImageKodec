package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.ByteReader

internal object GifLooping {
    /**
     * Reads an application extension: the play count of a looping one, and the data of
     * an ICC profile one, which goes to [icc] when it is given.
     */
    fun readApplication(r: ByteReader, icc: ((ByteArray) -> Unit)? = null): Long? {
        val size = r.u8()
        val name = r.bytes(size).decodeToString()
        val looping = size == 11 && (name == "NETSCAPE2.0" || name == "ANIMEXTS1.0")
        val profile = if (icc != null && ColorChunks.isGifIcc(name)) ArrayList<Byte>() else null
        var plays: Long? = null
        while (true) {
            val n = r.u8()
            if (n == 0) {
                profile?.let { icc!!(it.toByteArray()) }
                return plays
            }
            if (profile != null) {
                r.bytes(n).forEach { profile += it }
            } else if (looping && n == 3) {
                val id = r.u8()
                val repeats = r.u16le()
                if (id == 1) {
                    // Skia's repetition contract excludes the initial play.
                    plays = if (repeats == 0) 0L else repeats.toLong() + 1
                }
            } else {
                r.skip(n)
            }
        }
    }
}
