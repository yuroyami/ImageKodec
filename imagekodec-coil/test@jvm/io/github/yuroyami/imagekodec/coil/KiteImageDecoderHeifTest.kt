package io.github.yuroyami.imagekodec.coil

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.size.Size
import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.downscaledTo
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HEIC through Coil (#170): a still decodes at the size of the request, a cancelled request stops
 * between tiles, and a file whose `meta` box runs past the factory's peek is still claimed. The HEVC
 * codec is in `imagekodec-heic`, which this module does not depend on, so [TileCodec] stands in for it.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class KiteImageDecoderHeifTest {

    private val context = PlatformContext.INSTANCE

    private fun decoder(bytes: ByteArray, size: Size) =
        KiteImageDecoder(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), Options(context, size = size, scale = Scale.FIT))

    /** The size of the largest single allocation [block] makes on this thread, as `HeifGridMemoryTest` measures it. */
    private fun largestAllocation(block: () -> Unit): Long {
        val file = Files.createTempFile("coil-heif", ".jfr")
        try {
            Recording().use { r ->
                r.enable("jdk.ObjectAllocationOutsideTLAB")
                r.enable("jdk.ObjectAllocationInNewTLAB")
                r.start()
                block()
                r.stop()
                r.dump(file)
            }
            val thread = Thread.currentThread().id
            return RecordingFile.readAllEvents(file)
                .filter { it.thread?.javaThreadId == thread }
                .maxOf { it.getLong("allocationSize") }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun allocated(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    @Test
    fun aGridAskedForAQuarterOfItsSizeAllocatesNoFullSizeBitmap() {
        val codec = TileCodec("tsc1").also { ImageKodec.registerHeifCodec(it) }
        // 2,043 by 2,045 pixels in a 16 by 16 grid of 128 by 128 tiles.
        val file = codec.grid(rows = 16, columns = 16, tileWidth = 128, tileHeight = 128)
        val info = ImageKodec.probe(file)
        val canvas = info.width.toLong() * info.height * 4
        val request = Size(info.width / 4, info.height / 4)

        fun viaCoil() = runBlocking { decoder(file, request).decode() }
        val result = viaCoil()
        val image = assertIs<BitmapImage>(result.image)
        // Coil's FIT box for the request: 510 by 510.
        assertEquals(510 to 510, image.width to image.height)
        assertTrue(result.isSampled)
        val want = ImageKodec.decode(file).downscaledTo(image.width, image.height)
        for ((x, y) in listOf(0 to 0, 255 to 300, 509 to 509)) assertEquals(want[x, y], image.bitmap.getColor(x, y), "pixel $x, $y")

        // What the decoder did before #170: every still but a JPEG or JPEG 2000 decoded in full, then scaled.
        fun fullThenScaled() = ImageKodec.decodeAnimation(file, applyOrientation = true, colorTarget = ColorTarget.Srgb())
            .frames.first().bitmap.downscaledTo(image.width, image.height)
        repeat(2) { viaCoil(); fullThenScaled() } // warm up

        val beforeLargest = largestAllocation { fullThenScaled() }
        val afterLargest = largestAllocation { viaCoil() }
        val beforeTotal = allocated { fullThenScaled() }
        val afterTotal = allocated { viaCoil() }
        val report = "canvas $canvas bytes as ARGB; full decode then scale: largest $beforeLargest, total $beforeTotal; " +
            "KiteImageDecoder: largest $afterLargest, total $afterTotal"
        println(report)
        assertTrue(beforeLargest >= canvas, report)
        assertTrue(afterLargest < canvas / 4, report)
    }

    @Test
    fun aCancelledRequestStopsTheGridBetweenTiles() {
        val codec = TileCodec("tsc2").also { ImageKodec.registerHeifCodec(it) }
        val file = codec.grid(rows = 8, columns = 8, tileWidth = 64, tileHeight = 64)
        val atThird = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var outcome: Throwable? = null
        codec.onTile = {
            if (codec.decoded.get() == 2) {
                atThird.countDown()
                cancelled.await(10, TimeUnit.SECONDS)
            }
        }
        runBlocking {
            val job = async(Dispatchers.Default) {
                try {
                    decoder(file, Size(100, 100)).decode()
                } catch (e: Throwable) {
                    outcome = e
                    throw e
                } finally {
                    ended.countDown()
                }
            }
            assertTrue(atThird.await(10, TimeUnit.SECONDS), "the decode never reached its third tile")
            job.cancel()
            cancelled.countDown()
            assertTrue(ended.await(10, TimeUnit.SECONDS), "the decode did not stop")
        }
        assertIs<CancellationException>(outcome, "the decode should end cancelled, ended with $outcome")
        assertEquals(3, codec.decoded.get(), "the decode went on past the tile where it was cancelled")
    }

    /** What the factory decides for [bytes]: a decoder when it claims the file, null when it declines. */
    private fun claim(bytes: ByteArray): coil3.decode.Decoder? {
        val input = Buffer().write(bytes)
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
    fun aHeicWhoseMetaBoxRunsPastThePeekIsClaimedAndDecodes() {
        val codec = TileCodec("tsc3").also { ImageKodec.registerHeifCodec(it) }
        val file = codec.grid(rows = 2, columns = 3, tileWidth = 40, tileHeight = 30, padding = 100_000)
        // ftyp, then a meta box of about 100 KB that the first 64 KiB cut short.
        assertTrue(file.size > 100_000)
        assertNull(ImageKodec.probeOrNull(file.copyOf(64 * 1024)), "the peek alone should not describe the file")
        assertEquals(true, ImageKodec.probe(file).isDecodable)

        var leading = 0
        val start = System.nanoTime()
        Buffer().write(file).peek().heifLeadingBoxes(16L * 1024 * 1024)?.let { leading = it.size }
        println("the factory reads $leading of ${file.size} bytes in ${(System.nanoTime() - start) / 1000} us to find the meta box")

        assertNotNull(claim(file))
        val loader = ImageLoader.Builder(context).memoryCache(null).components { add(KiteImageDecoder.Factory()) }.build()
        try {
            val result = assertIs<SuccessResult>(runBlocking { loader.execute(ImageRequest.Builder(context).data(file).build()) })
            val image = assertIs<BitmapImage>(result.image)
            val want = ImageKodec.decode(file, colorTarget = ColorTarget.Srgb())
            assertEquals(want.width to want.height, image.width to image.height)
            for ((x, y) in listOf(0 to 0, 50 to 40, want.width - 1 to want.height - 1)) {
                assertEquals(want[x, y], image.bitmap.getColor(x, y), "pixel $x, $y")
            }
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun aHeicWithNoCodecForItsItemsIsLeftToThePlatformDecoder() {
        // The same layout, of an item type no codec reads: the meta box says so, and the brand alone could not.
        val unknown = TileCodec("tsc9").grid(rows = 2, columns = 3, tileWidth = 40, tileHeight = 30, padding = 100_000)
        assertEquals(false, ImageKodec.probe(unknown).isDecodable)
        assertNull(claim(unknown))
        // A file of no more than its ftyp box, and one whose meta box is cut short, are declined too.
        val codec = TileCodec("tsc4").also { ImageKodec.registerHeifCodec(it) }
        val file = codec.grid(rows = 2, columns = 3, tileWidth = 40, tileHeight = 30, padding = 100_000)
        assertNull(claim(file.copyOf(24)))
        assertNull(claim(file.copyOf(90_000)))
    }
}
