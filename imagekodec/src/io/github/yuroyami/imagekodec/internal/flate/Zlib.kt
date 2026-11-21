package io.github.yuroyami.imagekodec.internal.flate

import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.InflateBlockListener
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Zlib as ArchiveZlib

/**
 * zlib (RFC 1950) streams for PNG `IDAT`, `iCCP`, `zTXt` and TIFF Deflate strips, through ArchiveKodec.
 *
 * Bytes after the stream are ignored. The Adler-32 trailer is checked unless the caller opts out.
 */
internal object Zlib {

    /** Compress [data] into a zlib stream. */
    fun compress(data: ByteArray): ByteArray = ArchiveZlib.compress(data)

    /** [compress] at the level and strategy of [options]. */
    fun compress(data: ByteArray, options: DeflateOptions): ByteArray = ArchiveZlib.compress(data, options)

    /**
     * Decompress a zlib stream. With [checkAdler], the Adler-32 after the last block must match the
     * output, as RFC 1950 2.3 requires (#160). PNG passes false: its chunks carry a CRC-32 each, and
     * the `png` crate, a reader in wide use, also skips the Adler-32 by default.
     *
     * [maximumSize] is the decompression-bomb guard. The inflater stops as soon as the output would
     * pass it, before it allocates that memory. PNG and TIFF pass the exact size that the headers
     * give, which also preallocates the output. A caller whose [maximumSize] is only a ceiling passes
     * a [sizeHint] of its own, or 0 to let the buffer grow.
     *
     * [cancel] runs after each DEFLATE block, so an image decode can stop inside its inflate.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveException for a malformed or truncated stream,
     *   a wrong or missing Adler-32 when [checkAdler] is set, or output past [maximumSize].
     */
    fun decompress(
        input: ByteArray,
        maximumSize: Long,
        sizeHint: Int = if (maximumSize <= Int.MAX_VALUE) maximumSize.toInt() else 0,
        cancel: (() -> Unit)? = null,
        checkAdler: Boolean = true,
    ): ByteArray {
        require(maximumSize > 0) { "maximumSize must be > 0" }
        val options = InflateOptions {
            limits = ArchiveLimits { maxUncompressedBytes = maximumSize }
            this.sizeHint = sizeHint.toLong()
            verifyChecksum = checkAdler
            allowTrailingData = true
            if (cancel != null) onBlock = InflateBlockListener { _, _ -> cancel() }
        }
        return ArchiveZlib.decompress(input, options).output
    }
}
