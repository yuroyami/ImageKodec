package io.github.yuroyami.imagekodec.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KiteImageDecoderSizingTest {
    private val context = PlatformContext.INSTANCE
    private fun bitmap(width: Int = 400, height: Int = 200, color: Int = 0xff408020.toInt()) =
        KiteBitmap(width, height, IntArray(width * height) { color })

    private fun load(bytes: ByteArray, size: Size, scale: Scale, stock: Boolean,
        maxSize: Size = Size.ORIGINAL): SuccessResult {
        val loader = ImageLoader.Builder(context).memoryCache(null)
            .apply { if (!stock) components { add(KiteImageDecoder.Factory()) } }.build()
        try {
            return assertIs<SuccessResult>(runBlocking {
                loader.execute(ImageRequest.Builder(context).data(bytes).size(size).scale(scale)
                    .precision(Precision.INEXACT).maxBitmapSize(maxSize).build())
            })
        } finally { loader.shutdown() }
    }

    private fun compare(size: Size, scale: Scale, expected: Pair<Int, Int>,
        source: KiteBitmap = bitmap(), maxSize: Size = Size.ORIGINAL) {
        val bytes = ImageKodec.encodePng(source)
        val stock = load(bytes, size, scale, stock = true, maxSize)
        assertEquals(expected, stock.image.width to stock.image.height, "stock $size $scale")
        val kite = load(bytes, size, scale, stock = false, maxSize)
        assertEquals(expected, kite.image.width to kite.image.height, "Kite $size $scale")
        assertEquals(stock.isSampled, kite.isSampled, "sampled $size $scale")
    }

    @Test
    fun fillRetainsEnoughPixelsToCoverTheRequest() {
        compare(Size(100, 100), Scale.FILL, 200 to 100)
        compare(Size(100, 100), Scale.FIT, 100 to 50)
    }

    @Test
    fun widthOnlyWorksForFitAndFill() {
        for (scale in Scale.entries) compare(Size(Dimension.Pixels(100), Dimension.Undefined), scale, 100 to 50)
    }

    @Test
    fun heightOnlyWorksForFitAndFill() {
        for (scale in Scale.entries) compare(Size(Dimension.Undefined, Dimension.Pixels(50)), scale, 100 to 50)
    }

    @Test
    fun nonIntegralRatiosMatchStockDimensionsWithoutFittingAgain() {
        compare(Size(200, 200), Scale.FIT, 199 to 111, bitmap(1370, 767))
        compare(Size(128, 128), Scale.FIT, 127 to 75, bitmap(841, 499))
        compare(Size(200, 200), Scale.FILL, 357 to 200, bitmap(1370, 767))
    }

    @Test
    fun originalAndLargerRequestsDoNotUpscaleOrClaimSampling() {
        for (scale in Scale.entries) {
            compare(Size.ORIGINAL, scale, 400 to 200)
            compare(Size(800, 100), scale, if (scale == Scale.FILL) 400 to 200 else 200 to 100)
        }
    }

    @Test
    fun maximumBitmapSizeAlsoConstrainsOriginalAndFillRequests() {
        compare(Size.ORIGINAL, Scale.FIT, 100 to 50, maxSize = Size(100, 100))
        compare(Size(100, 100), Scale.FILL, 140 to 70, maxSize = Size(150, 70))
        compare(Size(Dimension.Undefined, Dimension.Pixels(100)), Scale.FILL, 80 to 40,
            maxSize = Size(Dimension.Pixels(80), Dimension.Undefined))
    }

    @Test
    fun aStillJpegReducedInsideItsDecoderKeepsTheStockSizes() {
        // The size goes down to the JPEG decoder, which reduces inside its inverse DCT (#13). The sizes
        // and the sampled flag stay those of Coil's own decoder, and the pixels are what
        // decodeDownscaledTo gives for that size.
        val source = KiteBitmap(1370, 767, IntArray(1370 * 767) { i ->
            val x = i % 1370
            val y = i / 1370
            (0xFF shl 24) or ((x * 255 / 1369) shl 16) or ((y * 255 / 766) shl 8) or ((x xor y) and 0x3F)
        })
        val bytes = ImageKodec.encodeJpeg(source, quality = 90)
        val cases = listOf(
            Size(200, 200) to Scale.FIT, Size(128, 128) to Scale.FIT, Size(200, 200) to Scale.FILL,
            Size(Dimension.Pixels(100), Dimension.Undefined) to Scale.FILL, Size.ORIGINAL to Scale.FIT,
        )
        for ((size, scale) in cases) {
            val stock = load(bytes, size, scale, stock = true)
            val kite = load(bytes, size, scale, stock = false)
            val width = kite.image.width
            val height = kite.image.height
            assertEquals(stock.image.width to stock.image.height, width to height, "$size $scale")
            assertEquals(stock.isSampled, kite.isSampled, "$size $scale")
            val expected = ImageKodec.decodeDownscaledTo(bytes, width, height, applyOrientation = true)
            val pixels = assertIs<coil3.BitmapImage>(kite.image).bitmap
            for ((x, y) in listOf(0 to 0, width / 2 to height / 3, width - 1 to height - 1)) {
                assertEquals(expected[x, y], pixels.getColor(x, y), "$size $scale at $x, $y")
            }
        }
    }

    @Test
    fun animationFramesUseTheSameSizingAndPreservePlayback() {
        val source = bitmap()
        val bytes = ImageKodec.encodeGif(KiteAnimation(400, 200, listOf(
            KiteFrame(source, 100, 10), KiteFrame(bitmap(color = 0xff0000ff.toInt()), 250, 25),
        ), loopCount = 3))
        val cases = listOf(
            Triple(Size(100, 100), Scale.FILL, Size.ORIGINAL),
            Triple(Size(100, 100), Scale.FIT, Size.ORIGINAL),
            Triple(Size(Dimension.Pixels(100), Dimension.Undefined), Scale.FILL, Size.ORIGINAL),
            Triple(Size(Dimension.Undefined, Dimension.Pixels(50)), Scale.FIT, Size.ORIGINAL),
            Triple(Size.ORIGINAL, Scale.FILL, Size(100, 100)),
        )
        for ((size, scale, maxSize) in cases) {
            val stock = load(ImageKodec.encodePng(source), size, scale, stock = true, maxSize)
            val success = load(bytes, size, scale, stock = false, maxSize)
            val image = assertIs<KiteAnimationImage>(success.image)
            assertEquals(stock.image.width to stock.image.height, image.width to image.height)
            assertEquals(stock.isSampled, success.isSampled)
            assertEquals(3L, image.animation.loopCount)
            assertEquals(listOf(100, 250), image.animation.frames.map { it.delayMillis })
            assertEquals(listOf(10, 25), image.animation.frames.map { it.delayRawCentiseconds })
            assertEquals(listOf(0xff408020.toInt(), 0xff0000ff.toInt()),
                image.animation.frames.map { it.bitmap[0, 0] })
            for (frame in image.animation.frames) {
                assertEquals(image.width to image.height, frame.bitmap.width to frame.bitmap.height)
            }
        }
    }
}
