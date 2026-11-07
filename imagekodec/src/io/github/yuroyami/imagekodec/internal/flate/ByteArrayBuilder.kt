package io.github.yuroyami.imagekodec.internal.flate

/**
 * A minimal growable byte buffer: the pure-Kotlin equivalent of a `ByteArrayOutputStream`,
 * with no `java.io` dependency so it lives in `commonMain`. Amortised O(1) append by
 * doubling. Used by the DEFLATE encoder and the gzip/zlib framers.
 */
internal class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var len = 0

    /** Number of bytes written so far. */
    val size: Int get() = len

    /** Append a single byte. */
    fun append(b: Byte) {
        ensure(1)
        buf[len++] = b
    }

    /** Append a slice of [bytes]. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length == 0) return
        ensure(length)
        bytes.copyInto(buf, len, offset, offset + length)
        len += length
    }

    private fun ensure(extra: Int) {
        // Long arithmetic: an Int capacity doubled past 2^30 wraps to 0 and never grows (#127).
        val needed = len.toLong() + extra
        if (needed > buf.size) {
            if (needed > MAX_ARRAY) throw IllegalStateException("output of $needed bytes does not fit in one array")
            var cap = maxOf(buf.size.toLong(), 16L)
            while (cap < needed) cap = cap shl 1
            buf = buf.copyOf(minOf(cap, MAX_ARRAY).toInt())
        }
    }

    /** Snapshot the written bytes into a right-sized array. */
    /** Overwrites a byte already appended, as a writer does to fill in an offset it learns later. */
    operator fun set(index: Int, value: Byte) {
        require(index in 0 until len) { "byte $index of $len" }
        buf[index] = value
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

/** The largest `ByteArray` every target can allocate: the JVM refuses a few bytes short of `Int.MAX_VALUE`. */
internal const val MAX_ARRAY: Long = Int.MAX_VALUE - 8L
