package io.github.yuroyami.kiteimagecodec.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Coil moves on to the next decoder only when a factory returns null. A decoder that throws ends the
 * request, so a factory has to claim exactly the files KiteImageCodec can decode. It decides from the
 * first 64 KiB, and these files put their image chunk in front of or across that limit.
 */
class KiteImageFactoryClaimTest {

    private val context: PlatformContext = PlatformContext.INSTANCE

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le24(v: Int) = le16(v) + byteArrayOf(((v shr 16) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v) + le16(v shr 16)
    private fun chunk(tag: String, body: ByteArray) = tag.encodeToByteArray() + le32(body.size) + body

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.reduce { a, b -> a + b }
        return "RIFF".encodeToByteArray() + le32(body.size) + body
    }

    private val vp8x = chunk("VP8X", byteArrayOf(0x20, 0, 0, 0) + le24(15) + le24(15))

    private fun lossyChunk(padding: Int) =
        chunk("VP8 ", byteArrayOf(0x30, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(16) + le16(16) + ByteArray(padding))

    private fun losslessChunk(padding: Int) =
        chunk("VP8L", byteArrayOf(0x2F, 0x0F, 0xC0.toByte(), 0x03, 0x00) + ByteArray(padding))

    /** What the factory decides for [bytes]: a decoder when it claims the file, null when it declines. */
    private fun claim(bytes: ByteArray) = KiteImageDecoder.Factory().create(
        SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), mimeType = null, dataSource = DataSource.MEMORY),
        Options(context),
        ImageLoader(context),
    )

    @Test
    fun aLossyWebpWhoseImageChunkFollowsALargeProfileIsDeclined() {
        // The profile ends at byte 70,038, past the peek, so the peek never reaches the VP8 chunk.
        assertNull(claim(riff(vp8x, chunk("ICCP", ByteArray(70_000)), lossyChunk(padding = 2000))))
    }

    @Test
    fun aLossyWebpWhoseImageChunkRunsPastThePeekIsDeclined() {
        // The VP8 chunk starts inside the peek and ends far outside it. Its first bytes say it is lossy.
        assertNull(claim(riff(vp8x, lossyChunk(padding = 100_000))))
        assertNull(claim(riff(lossyChunk(padding = 100_000))))
    }

    @Test
    fun aLosslessWebpLargerThanThePeekIsClaimed() {
        assertNotNull(claim(riff(losslessChunk(padding = 100_000))))
        assertNotNull(claim(riff(vp8x, losslessChunk(padding = 100_000))))
    }
}
