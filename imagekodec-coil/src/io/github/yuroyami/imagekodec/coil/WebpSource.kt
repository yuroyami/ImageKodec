package io.github.yuroyami.imagekodec.coil

import okio.BufferedSource

/**
 * Whether every image chunk of the WebP on this source is one ImageKodec decodes: a VP8 key frame
 * or a VP8L image of the version it knows. Animation frames can mix the two, and a fixed peek cannot
 * reach a late frame, so this walks the RIFF and ANMF headers on a peek, skipping compressed payloads
 * without copying them into a second byte array. Chunk lengths and padding follow the WebP container
 * specification.
 */
internal fun BufferedSource.hasOnlyDecodableWebpImages(): Boolean {
    if (!request(12) || readUtf8(4) != "RIFF") return false
    val riffSize = readIntLe().toLong() and 0xffffffffL
    if (riffSize < 4 || readUtf8(4) != "WEBP") return false

    fun skipPayload(size: Long): Boolean {
        if (!request(size)) return false
        skip(size)
        return true
    }

    fun chunks(length: Long, insideFrame: Boolean): Boolean {
        var remaining = length
        var sawImage = false
        while (remaining > 0) {
            if (remaining < 8 || !request(8)) return false
            val tag = readUtf8(4)
            val size = readIntLe().toLong() and 0xffffffffL
            val paddedSize = size + (size and 1L)
            if (paddedSize > remaining - 8) return false

            when (tag) {
                "ANMF" -> {
                    if (insideFrame || size < 16 || !skipPayload(16)) return false
                    if (!chunks(size - 16, insideFrame = true)) return false
                    sawImage = true
                }
                "VP8 " -> {
                    // A key frame (bit 0 of the frame tag clear) with the 9D 01 2A start code.
                    if (size < 10 || !request(10)) return false
                    val frameTag = readByte().toInt()
                    skip(2)
                    val start = readByte().toInt() and 255 shl 16 or (readByte().toInt() and 255 shl 8) or (readByte().toInt() and 255)
                    if (frameTag and 1 != 0 || start != 0x9D012A || !skipPayload(size - 6)) return false
                    sawImage = true
                }
                "VP8X" -> if (size != 10L || !skipPayload(size)) return false
                "ANIM" -> if (size != 6L || !skipPayload(size)) return false
                "VP8L" -> {
                    if (size < 5 || !request(5)) return false
                    if (readByte().toInt() and 255 != 0x2f) return false
                    val header = readIntLe()
                    if (header ushr 29 != 0 || !skipPayload(size - 5)) return false
                    sawImage = true
                }
                else -> if (!skipPayload(size)) return false
            }
            if (size and 1L != 0L) {
                if (!request(1) || readByte() != 0.toByte()) return false
            }
            remaining -= 8 + paddedSize
        }
        return sawImage
    }

    return chunks(riffSize - 4, insideFrame = false)
}
