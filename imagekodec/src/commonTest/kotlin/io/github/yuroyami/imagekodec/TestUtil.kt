package io.github.yuroyami.imagekodec

/** Decode a lowercase hex string (as printed by the vector generator) to bytes. */
fun hex(s: String): ByteArray {
    require(s.length % 2 == 0)
    return ByteArray(s.length / 2) { i ->
        ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
}

/** Compact ARGB literal helper: argb(0xFF, 0x12, 0x34, 0x56). */
fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** Opaque gray pixel. */
fun gray(v: Int): Int = argb(0xFF, v, v, v)
