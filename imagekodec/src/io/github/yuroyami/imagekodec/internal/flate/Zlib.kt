package io.github.yuroyami.imagekodec.internal.flate

import io.github.yuroyami.archivekodec.codec.deflate.InflateBlockListener
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Zlib as ArchiveZlib

/**
 * zlib (RFC 1950) streams for PNG `IDAT`, `iCCP`, `zTXt` and TIFF Deflate strips, through ArchiveKodec.
 *
 * The decoders read the way libpng and libtiff do in practice: the Adler-32 trailer is not checked,
 * and bytes after the stream are ignored.
 */
internal object Zlib {

    /** Compress [data] into a zlib stream. */
    fun compress(data: ByteArray): ByteArray = ArchiveZlib.compress(data)

    /**
     * Decompress a zlib stream.
     *
     * [maximumSize] is the decompression-bomb guard. The inflater stops as soon as the output would
     * pass it, before it allocates that memory. PNG and TIFF pass the exact size that the headers
     * give, which also preallocates the output. A caller whose [maximumSize] is only a ceiling passes
     * a [sizeHint] of its own, or 0 to let the buffer grow.
     *
     * [cancel] runs after each DEFLATE block, so an image decode can stop inside its inflate.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveException for a malformed or truncated stream,
     *   or for output past [maximumSize].
     */
    fun decompress(
        input: ByteArray,
        maximumSize: Long,
        sizeHint: Int = if (maximumSize <= Int.MAX_VALUE) maximumSize.toInt() else 0,
        cancel: (() -> Unit)? = null,
    ): ByteArray {
        require(maximumSize > 0) { "maximumSize must be > 0" }
        val options = InflateOptions {
            limits = ArchiveLimits { maxUncompressedBytes = maximumSize }
            this.sizeHint = sizeHint.toLong()
            verifyChecksum = false
            allowTrailingData = true
            if (cancel != null) onBlock = InflateBlockListener { _, _ -> cancel() }
        }
        return ArchiveZlib.decompress(input, options).output
    }
}
