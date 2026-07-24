package io.github.yuroyami.kiteimage.internal

/**
 * Non-premultiplied source-over compositing, shared by every animated format:
 * APNG's `BLEND_OP_OVER` and WebP's alpha-blending frames are the same operator,
 * spelled out identically in both specs.
 *
 * Integer arithmetic throughout. The largest intermediate is 255³, which fits an
 * Int with room to spare, so the result is bit-identical on every target.
 */
internal fun sourceOver(src: Int, dst: Int): Int {
    val sa = src ushr 24
    if (sa == 0xFF) return src
    if (sa == 0) return dst
    val da = dst ushr 24
    if (da == 0) return src

    val inv = 255 - sa
    val denom = sa * 255 + da * inv          // 255 × output alpha
    if (denom == 0) return 0
    val outA = (denom + 127) / 255

    var out = outA shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val sc = (src ushr shift) and 0xFF
        val dc = (dst ushr shift) and 0xFF
        out = out or (((sc * sa * 255 + dc * da * inv + denom / 2) / denom) shl shift)
    }
    return out
}
