package io.github.yuroyami.imagekodec.codec.avif

import kotlin.jvm.JvmInline

/**
 * A per-4x4 array of small signed values (modes, sizes, reference frames, segment ids),
 * one byte each but read and written as Int, so a 12-megapixel frame's mode information
 * takes a quarter of what IntArrays would.
 */
@JvmInline
internal value class ByteGrid(val a: ByteArray) {
    constructor(size: Int, initial: Int = 0) : this(ByteArray(size).also { if (initial != 0) it.fill(initial.toByte()) })

    operator fun get(i: Int): Int = a[i].toInt()

    operator fun set(i: Int, v: Int) {
        a[i] = v.toByte()
    }

    fun fill(v: Int) = a.fill(v.toByte())
}

/** A per-4x4 array of values that fit 16 signed bits, such as motion vectors, read and written as Int. */
@JvmInline
internal value class ShortGrid(val a: ShortArray) {
    constructor(size: Int) : this(ShortArray(size))

    operator fun get(i: Int): Int = a[i].toInt()

    operator fun set(i: Int, v: Int) {
        a[i] = v.toShort()
    }
}
