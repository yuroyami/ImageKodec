package io.github.yuroyami.imagekodec

import io.github.yuroyami.archivekodec.codec.deflate.InflateError
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.imagekodec.internal.flate.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal val FLUSHED_ZLIB = "780104c13101000000c2202f2aafbe3008020e0000ffff04c13101000000c2202f2aafbe3021101c0000ffff030031f602f6"
internal val FLUSHED_RAW = "00060606067706060606770606060606060006770606067706060606067706060606"
internal val INVALID_DISTANCE_ZLIB = "78013b71e2c489132780ffffffff1f"

class InflateTest {
    @Test
    fun aStoredBlockAfterShortCodesPreservesPrefetchedBytes() {
        // Python zlib with Z_SYNC_FLUSH after each PNG row; its independent
        // decompression yields these 34 bytes, including the two filter bytes.
        assertContentEquals(hex(FLUSHED_RAW), Zlib.decompress(hex(FLUSHED_ZLIB), 34))
        val png = PNG_SIGNATURE + pngHeader(16, 2, 8, 0) +
            pngChunk("IDAT", hex(FLUSHED_ZLIB)) + pngChunk("IEND", ByteArray(0))
        val pixels = ImageKodec.decode(png)
        val raw = hex(FLUSHED_RAW)
        for (y in 0..1) for (x in 0..15) assertEquals(gray(raw[y * 17 + x + 1].toInt() and 255), pixels[x, y])
    }

    @Test
    fun reservedDistanceCodesFailWithTheNamedError() {
        val invalid = hex(INVALID_DISTANCE_ZLIB)
        val error = assertFailsWith<InflateException> { Zlib.decompress(invalid, 20) }
        assertEquals(InflateError.INVALID_LITERAL_OR_DISTANCE_CODE, error.error)
        val png = PNG_SIGNATURE + pngHeader(4, 4, 8, 0) +
            pngChunk("IDAT", invalid) + pngChunk("IEND", ByteArray(0))
        assertFailsWith<ImageDecodeException> { ImageKodec.decode(png) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(png, 2) }
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeAnimation(png) }
    }
}
