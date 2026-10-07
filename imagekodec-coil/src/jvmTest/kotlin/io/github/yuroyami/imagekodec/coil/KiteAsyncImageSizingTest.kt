package io.github.yuroyami.imagekodec.coil

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KiteAsyncImageSizingTest {
    private fun source() = ImageKodec.encodePng(KiteBitmap(400, 200, IntArray(400 * 200) { 0xff408020.toInt() }))

    private fun await(scene: ImageComposeScene, result: AtomicReference<SuccessResult>): SuccessResult {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (result.get() == null && System.nanoTime() < deadline) {
            scene.render(System.nanoTime())
            Thread.sleep(5)
        }
        return assertNotNull(result.get(), "request did not finish")
    }

    private fun load(stock: Boolean, contentScale: ContentScale, kiteDecoder: Boolean = false,
        explicit: Boolean = false): Triple<Int, Int, Scale> {
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context).memoryCache(null)
            .apply { if (kiteDecoder) components { add(KiteImageDecoder.Factory()) } }.build()
        val bytes = source()
        val model = if (explicit) ImageRequest.Builder(context).data(bytes).size(60, 40).scale(Scale.FILL).build() else bytes
        val result = AtomicReference<SuccessResult>()
        try {
            ImageComposeScene(width = 100, height = 100) {
                if (stock) AsyncImage(model, null, imageLoader = loader, modifier = Modifier.size(100.dp),
                    contentScale = contentScale, onSuccess = { result.set(it.result) })
                else KiteAsyncImage(model, null, imageLoader = loader, modifier = Modifier.size(100.dp),
                    contentScale = contentScale, onSuccess = { result.set(it) })
            }.use { scene ->
                val success = await(scene, result)
                return Triple(success.image.width, success.image.height, success.request.scale)
            }
        } finally { loader.shutdown() }
    }

    @Test
    fun noneKeepsTheOriginalPixelsWithTheRegisteredDecoder() {
        val stock = load(true, ContentScale.None, kiteDecoder = true)
        assertEquals(400 to 200, stock.first to stock.second)
        assertEquals(stock, load(false, ContentScale.None, kiteDecoder = true))
    }

    @Test
    fun cropRequestsFillWithAStockDecoder() {
        val stock = load(true, ContentScale.Crop)
        assertEquals(Triple(200, 100, Scale.FILL), stock)
        assertEquals(stock, load(false, ContentScale.Crop))
    }

    @Test
    fun fitAndInsideRequestFitWithAStockDecoder() {
        for (scale in listOf(ContentScale.Fit, ContentScale.Inside)) {
            val stock = load(true, scale)
            assertEquals(Triple(100, 50, Scale.FIT), stock)
            assertEquals(stock, load(false, scale))
        }
    }

    @Test
    fun explicitRequestSizeAndScaleSurviveDrawingOverrides() {
        for (scale in listOf(ContentScale.Fit, ContentScale.None)) {
            val stock = load(true, scale, explicit = true)
            assertEquals(Triple(80, 40, Scale.FILL), stock)
            assertEquals(stock, load(false, scale, explicit = true))
        }
    }

    @Test
    fun changingContentScaleBuildsARequestWithTheNewScale() {
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context).memoryCache(null).build()
        val bytes = source()
        val scale = mutableStateOf<ContentScale>(ContentScale.Fit)
        val result = AtomicReference<SuccessResult>()
        try {
            ImageComposeScene(width = 100, height = 100) {
                KiteAsyncImage(bytes, null, imageLoader = loader, modifier = Modifier.size(100.dp),
                    contentScale = scale.value, onSuccess = { result.set(it) })
            }.use { scene ->
                assertEquals(100, await(scene, result).image.width)
                result.set(null)
                scale.value = ContentScale.Crop
                val cropped = await(scene, result)
                assertEquals(Scale.FILL, cropped.request.scale)
                assertEquals(200, cropped.image.width)
                assertEquals(100, cropped.image.height)
            }
        } finally { loader.shutdown() }
    }
}
