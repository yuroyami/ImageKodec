package io.github.yuroyami.imagekodec.coil

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.yuroyami.imagekodec.ImageKodec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Coil moves on to the next decoder only when a factory returns null. A decoder that throws ends the
 * request, so a factory has to claim exactly the files ImageKodec can decode. It decides from the
 * initial probe and the complete WebP chunk headers, including frame codecs beyond 64 KiB.
 */
class KiteImageFactoryClaimTest {

    private val context: PlatformContext = PlatformContext.INSTANCE

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le24(v: Int) = le16(v) + byteArrayOf(((v shr 16) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v) + le16(v shr 16)
    private fun chunk(tag: String, body: ByteArray) = tag.encodeToByteArray() + le32(body.size) + body + ByteArray(body.size and 1)

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.reduce { a, b -> a + b }
        return "RIFF".encodeToByteArray() + le32(body.size) + body + ByteArray(body.size and 1)
    }

    private val vp8x = chunk("VP8X", byteArrayOf(0x20, 0, 0, 0) + le24(15) + le24(15))

    private fun lossyChunk(padding: Int) =
        chunk("VP8 ", byteArrayOf(0x30, 0, 0, 0x9D.toByte(), 0x01, 0x2A) + le16(16) + le16(16) + ByteArray(padding))

    private fun losslessChunk(padding: Int) =
        chunk("VP8L", byteArrayOf(0x2F, 0x0F, 0xC0.toByte(), 0x03, 0x00) + ByteArray(padding))

    /** What the factory decides for [bytes]: a decoder when it claims the file, null when it declines. */
    private fun claim(bytes: ByteArray, fragmented: Boolean = false): coil3.decode.Decoder? {
        val bytesSource = Buffer().write(bytes)
        val input = if (fragmented) object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = bytesSource.read(sink, minOf(byteCount, 3L))
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { bytesSource.close() }
        }.buffer() else bytesSource
        val source = ImageSource(input, FileSystem.SYSTEM)
        val loader = ImageLoader(context)
        try {
            val decoder = KiteImageDecoder.Factory().create(
                SourceFetchResult(source, mimeType = null, dataSource = DataSource.MEMORY), Options(context), loader,
            )
            assertContentEquals(bytes, input.readByteArray(), "factory consumed the source")
            return decoder
        } finally {
            source.close()
            loader.shutdown()
        }
    }

    @Test
    fun aLossyWebpWhoseImageChunkFollowsALargeProfileIsClaimed() {
        // The profile ends at byte 70,038, past the peek; the chunk walk reaches the VP8 chunk anyway.
        assertNotNull(claim(riff(vp8x, chunk("ICCP", ByteArray(70_000)), lossyChunk(padding = 2000))))
    }

    @Test
    fun aLossyWebpWhoseImageChunkRunsPastThePeekIsClaimed() {
        // The VP8 chunk starts inside the peek and ends far outside it. Its first bytes are a key frame.
        assertNotNull(claim(riff(vp8x, lossyChunk(padding = 100_000))))
        assertNotNull(claim(riff(lossyChunk(padding = 100_000))))
    }

    @Test
    fun aLossyChunkThatIsNotAKeyFrameIsDeclined() {
        val interFrame = lossyChunk(padding = 0).also { it[8] = 0x31 }
        assertNull(claim(riff(vp8x, interFrame)))
        val badStartCode = lossyChunk(padding = 0).also { it[11] = 0 }
        assertNull(claim(riff(vp8x, badStartCode)))
    }

    @Test
    fun aLosslessWebpLargerThanThePeekIsClaimed() {
        assertNotNull(claim(riff(losslessChunk(padding = 100_000))))
        assertNotNull(claim(riff(vp8x, losslessChunk(padding = 100_000))))
    }

    private val animatedHeader = chunk("VP8X", byteArrayOf(2, 0, 0, 0) + le24(15) + le24(15))
    private fun frame(image: ByteArray) = chunk("ANMF", le24(0) + le24(0) + le24(15) + le24(15) + le24(100) + byteArrayOf(0) + image)
    private val animationControl = chunk("ANIM", ByteArray(6))

    @Test
    fun lossyFramesAfterALargeLosslessPayloadAreClaimed() {
        assertNotNull(claim(riff(animatedHeader, animationControl, frame(losslessChunk(100_000)), frame(lossyChunk(0)))))
    }

    @Test
    fun largeAllLosslessAnimationsAreStillClaimed() {
        assertNotNull(claim(riff(animatedHeader, animationControl, frame(losslessChunk(100_000)), frame(losslessChunk(0)))))
    }

    @Test
    fun headersCanFollowLargeMetadataAndArriveInSmallFragments() {
        val beforeFrames = listOf(animatedHeader, chunk("ICCP", ByteArray(70_001)), animationControl)
        assertNotNull(claim(riff(*(beforeFrames + listOf(frame(losslessChunk(0)), frame(losslessChunk(0)))).toTypedArray()), fragmented = true))
        assertNotNull(claim(riff(*(beforeFrames + listOf(frame(losslessChunk(0)), frame(lossyChunk(0)))).toTypedArray()), fragmented = true))
    }

    @Test
    fun aTruncatedAnimationDoesNotProveSupportForItsUnseenFrames() {
        val bytes = riff(animatedHeader, animationControl, frame(losslessChunk(100_000)), frame(lossyChunk(0)))
        assertNull(claim(bytes.copyOf(65_536)))
    }

    @Test
    fun chunkLengthsCannotEscapeTheDeclaredRiffOrFrame() {
        val bytes = riff(animatedHeader, animationControl, frame(losslessChunk(0)), frame(losslessChunk(0)))
        val secondFrame = 12 + animatedHeader.size + animationControl.size + frame(losslessChunk(0)).size
        le32(-1).copyInto(bytes, secondFrame + 4)
        assertNull(claim(bytes))
        val nested = riff(animatedHeader, animationControl, frame(losslessChunk(0)), frame(frame(losslessChunk(0))))
        assertNull(claim(nested))
    }

    @Test
    fun paddingAndUnsupportedLosslessVersionsAreDeclined() {
        val badPadding = riff(animatedHeader, animationControl, frame(losslessChunk(0)))
        badPadding[badPadding.lastIndex] = 1
        assertNull(claim(badPadding))
        val unsupportedVersion = losslessChunk(0)
        unsupportedVersion[12] = 0x20
        assertNull(claim(riff(animatedHeader, animationControl, frame(unsupportedVersion))))
    }

    // cwebp wrote one red lossless frame and one green lossy frame; webpmux
    // assembled the animation. An allowed unknown chunk moves frame two past
    // the peek. webpmux independently verifies both codecs and frame count.
    private fun realMixedAnimation(): ByteArray {
        val base = Base64.getDecoder().decode("UklGRqQAAABXRUJQVlA4WAoAAAACAAAAAAAAAAAAQU5JTQYAAAD/////AABBTk1GKAAAAAAAAAAAAAAAAAAAAGQAAABWUDhMDwAAAC8AAAAABxD9j/4HIqL/AQBBTk1GSAAAAAAAAAAAAAAAAAAAAMgAAABWUDggMAAAABACAJ0BKgEAAQACADQloAJ0ugH4AfgAA8gA/uZp3/taA4NV/mf/+++FEduv8xQAAA==")
        val result = base.copyOfRange(0, 92) + chunk("JUNK", ByteArray(70_001)) + base.copyOfRange(92, base.size)
        le32(result.size - 8).copyInto(result, 4)
        return result
    }

    @Test
    fun aRealMixedAnimationDecodesBothFrames() {
        // Both codecs decode now (#11), so the file is claimed and plays both frames, where Coil's stock
        // decoder shows only the first.
        val bytes = realMixedAnimation()
        assertEquals(true, ImageKodec.probe(bytes.copyOf(65_536)).isDecodable)
        assertEquals(true, ImageKodec.probe(bytes).isDecodable)
        val stock = ImageLoader(context)
        val registered = ImageLoader.Builder(context).components { add(KiteImageDecoder.Factory()) }.build()
        try {
            fun load(loader: ImageLoader) = assertIs<SuccessResult>(runBlocking {
                loader.execute(ImageRequest.Builder(context).data(bytes).build())
            })
            val reference = assertIs<BitmapImage>(load(stock).image)
            val result = assertIs<KiteAnimationImage>(load(registered).image)
            assertEquals(1 to 1, result.width to result.height)
            assertEquals(listOf(100, 200), result.animation.frames.map { it.delayMillis })
            assertEquals(0xffff0000.toInt(), reference.bitmap.getColor(0, 0))
            assertEquals(reference.bitmap.getColor(0, 0), result.animation.frames[0].bitmap[0, 0])
            // The second frame is cwebp's lossy green.
            val green = result.animation.frames[1].bitmap[0, 0]
            assertEquals(0xFF, green ushr 24)
            assertEquals(true, (green ushr 8 and 0xFF) > 200 && (green ushr 16 and 0xFF) < 40, green.toUInt().toString(16))
        } finally { stock.shutdown(); registered.shutdown() }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun be32(v: Long) = ByteArray(4) { (v ushr (24 - 8 * it)).toByte() }
    private fun box(type: String, body: ByteArray) = be32(8L + body.size) + type.encodeToByteArray() + body

    // libjxl's jxl_from_tree wrote both JPEG XL files: a 20 by 12 still, and a 16 by 12 animation of three frames.
    private val jxlStill = hex("ff0a58002600480806010048004b2832767994935c8980e7988a041708001a")
    private val jxlAnimation = hex(
        "ff0a5830c100d4040818208a000044004b3841bced472503ef4fafe404b54282010898206000040c22917802003c004b386998c88b48" +
            "0e090780fb23c1000898143000040c55b3040000040034004b28328e2d9202cacc39408201",
    )

    @Test
    fun aJpegXlWhoseMetadataRunsPastThePeekIsClaimed() {
        assertNotNull(claim(jxlStill))
        assertNotNull(claim(jxlAnimation))
        // libjxl writes the start of the codestream in a first box, then the metadata, then the rest of the codestream.
        val boxed = hex("0000000c4a584c200d0a870a") +
            box("ftyp", "jxl ".encodeToByteArray() + ByteArray(4) + "jxl ".encodeToByteArray()) +
            box("jxlp", be32(0) + jxlStill.copyOf(12)) + box("Exif", ByteArray(70_000)) +
            box("jxlp", be32(0x80000001L) + jxlStill.copyOfRange(12, jxlStill.size))
        assertNotNull(claim(boxed))
        assertContentEquals(ImageKodec.decode(jxlStill).argb, ImageKodec.decode(boxed).argb)
        // The signature alone is not a file this build can show.
        assertNull(claim(jxlStill.copyOf(2)))
    }

    @Test
    fun aJpegXlDecodesThroughThePipelineStillAndAnimated() {
        val loader = ImageLoader.Builder(context).components { add(KiteImageDecoder.Factory()) }.build()
        try {
            fun load(bytes: ByteArray) = assertIs<SuccessResult>(runBlocking {
                loader.execute(ImageRequest.Builder(context).data(bytes).build())
            }).image
            val still = assertIs<BitmapImage>(load(jxlStill))
            val want = ImageKodec.decode(jxlStill)
            assertEquals(20 to 12, still.width to still.height)
            for ((x, y) in listOf(0 to 0, 7 to 3, 19 to 11)) assertEquals(want[x, y], still.bitmap.getColor(x, y), "pixel $x, $y")
            val moving = assertIs<KiteAnimationImage>(load(jxlAnimation))
            assertEquals(16 to 12, moving.width to moving.height)
            assertEquals(listOf(40, 120, 300), moving.animation.frames.map { it.delayMillis })
        } finally { loader.shutdown() }
    }

    @Test
    fun aRealLosslessAnimationWithLateFramesKeepsItsAnimation() {
        val mixed = realMixedAnimation()
        val firstFrame = mixed.copyOfRange(44, 92)
        val bytes = riff(mixed.copyOfRange(12, 30), chunk("JUNK", ByteArray(70_001)),
            mixed.copyOfRange(30, 44), firstFrame, firstFrame)
        val loader = ImageLoader.Builder(context).components { add(KiteImageDecoder.Factory()) }.build()
        try {
            val result = assertIs<SuccessResult>(runBlocking {
                loader.execute(ImageRequest.Builder(context).data(bytes).build())
            })
            val image = assertIs<KiteAnimationImage>(result.image)
            assertEquals(1 to 1, image.width to image.height)
            assertEquals(0L, image.animation.loopCount)
            assertEquals(listOf(100, 100), image.animation.frames.map { it.delayMillis })
            assertEquals(listOf(0xffff0000.toInt(), 0xffff0000.toInt()), image.animation.frames.map { it.bitmap[0, 0] })
        } finally { loader.shutdown() }
    }
}
