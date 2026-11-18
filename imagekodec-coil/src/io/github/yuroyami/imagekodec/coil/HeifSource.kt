package io.github.yuroyami.imagekodec.coil

import okio.Buffer
import okio.BufferedSource

/**
 * The leading top-level boxes of the HEIF file on this source, up to and including its `meta` box,
 * for a probe that a fixed peek cannot satisfy: the HEIF reader stops at the first box that does not
 * fit, so a `meta` box past the peek leaves it nothing to describe (#170). The walk copies whole
 * boxes in file order, so the offsets that `iloc` gives stay valid, and it stops at the `meta` box,
 * at the first `mdat` box, whose coded data a probe does not need, at a box that runs to the end of
 * the file or past [limit] bytes, and at the end of the data. Null when it holds no more than the
 * `ftyp` box.
 *
 * Call it on a peek: it consumes what it reads.
 */
internal fun BufferedSource.heifLeadingBoxes(limit: Long): ByteArray? {
    val out = Buffer()
    var boxes = 0
    while (request(8)) {
        val head = Buffer()
        val size32 = readInt().toLong() and 0xffffffffL
        val type = readUtf8(4)
        head.writeInt(size32.toInt()).writeUtf8(type)
        val size = when (size32) {
            // A box that runs to the end of the file: its size is not known from a peek.
            0L -> break
            1L -> {
                if (!request(8)) break
                readLong().also { head.writeLong(it) }
            }
            else -> size32
        }
        val headerSize = head.size
        if (size < headerSize || size > limit - out.size || type == "mdat") break
        if (!request(size - headerSize)) break
        out.write(head, headerSize)
        out.write(this, size - headerSize)
        boxes++
        if (type == "meta") break
    }
    return if (boxes > 1) out.readByteArray() else null
}
