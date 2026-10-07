package io.github.yuroyami.imagekodec.codec

import io.github.yuroyami.imagekodec.internal.ByteReader

internal object GifLooping {
    fun readApplication(r: ByteReader): Long? {
        val size = r.u8()
        val name = r.bytes(size).decodeToString()
        val looping = size == 11 && (name == "NETSCAPE2.0" || name == "ANIMEXTS1.0")
        var plays: Long? = null
        while (true) {
            val n = r.u8()
            if (n == 0) return plays
            if (looping && n == 3) {
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
