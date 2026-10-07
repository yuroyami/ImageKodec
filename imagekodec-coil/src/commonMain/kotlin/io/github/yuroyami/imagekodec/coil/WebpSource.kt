package io.github.yuroyami.imagekodec.coil

import okio.BufferedSource

/**
 * WebP permits VP8 and VP8L in different animation frames. A fixed peek cannot
 * establish support for the whole file. Walk the RIFF/ANMF headers on a peek,
 * skipping compressed payloads without copying them into a second byte array.
 * Chunk lengths and padding follow the WebP container specification.
 */
internal fun BufferedSource.hasOnlyLosslessWebpImages(): Boolean {
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
                "VP8 " -> return false
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
