package io.github.yuroyami.kiteimage.internal.flate

/*
 * Vendored from KiteArchive (io.github.yuroyami.kitearchive.checksum), minus the
 * Checksum interface: KiteImage only needs the incremental CRC for PNG chunk
 * verification. Swap for the `kitearchive` artifact once it is on Maven Central.
 */

/**
 * CRC-32 (IEEE 802.3 / zlib / gzip / PNG): reflected polynomial `0xEDB88320`,
 * init `0xFFFFFFFF`, final XOR `0xFFFFFFFF`. Matches `zlib`'s `crc32()`.
 *
 * Check value: `CRC32("123456789") == 0xCBF43926`.
 */
internal class Crc32 {
    private var crc = -1 // 0xFFFFFFFF

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var c = crc
        var i = offset
        val end = offset + length
        while (i < end) {
            c = (c ushr 8) xor TABLE[(c xor data[i].toInt()) and 0xFF]
            i++
        }
        crc = c
    }

    fun value(): Long = (crc.inv().toLong() and 0xFFFFFFFFL)

    fun reset() { crc = -1 }

    companion object {
        private const val POLY = 0xEDB88320.toInt()

        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) POLY xor (c ushr 1) else c ushr 1 }
            c
        }

        /** One-shot convenience. */
        fun of(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long =
            Crc32().apply { update(data, offset, length) }.value()
    }
}
