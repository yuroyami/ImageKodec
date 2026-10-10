package io.github.yuroyami.imagekodec.coil

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import io.github.yuroyami.imagekodec.KiteBitmap
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KiteAsyncImageErrorTest {
    private fun renderError(stock: Boolean, explicitBlue: Boolean): Color {
        val context = PlatformContext.INSTANCE
        val loader = ImageLoader.Builder(context).build()
        val missing = File.createTempFile("imagekodec-missing", ".png")
        assertTrue(missing.delete())
        val red = KiteBitmap(1, 1, intArrayOf(0xffff0000.toInt())).toCoilImage(shareable = true)
        val request = ImageRequest.Builder(context).data(missing).error(red).build()
        val received = AtomicReference<ErrorResult>()
        try {
            ImageComposeScene(width = 64, height = 64) {
                val error = if (explicitBlue) ColorPainter(Color.Blue) else null
                if (stock) AsyncImage(request, null, imageLoader = loader, modifier = Modifier.size(64.dp),
                    error = error, onError = { received.set(it.result) })
                else KiteAsyncImage(request, null, imageLoader = loader, modifier = Modifier.size(64.dp),
                    error = error, onError = { received.set(it) })
            }.use { scene ->
                var time = 0L
                val deadline = System.nanoTime() + 10_000_000_000L
                while (received.get() == null && System.nanoTime() < deadline) {
                    scene.render(time)
                    time += 16_000_000
                    Thread.sleep(5)
                }
                assertNotNull(assertNotNull(received.get(), "request did not fail").image, "request error image")
                repeat(10) { scene.render(time); time += 16_000_000; Thread.sleep(5) }
                return scene.render(time).toComposeImageBitmap().toPixelMap()[32, 32]
            }
        } finally { loader.shutdown() }
    }

    @Test
    fun requestErrorImageRendersLikeStockAsyncImage() {
        for (stock in listOf(true, false)) {
            val pixel = renderError(stock, false)
            assertTrue(pixel.alpha > 0.9f && pixel.red > 0.9f && pixel.green < 0.1f && pixel.blue < 0.1f,
                "$stock: expected the request's red error image, got $pixel")
        }
    }

    @Test
    fun explicitErrorPainterOverridesTheRequestErrorImage() {
        for (stock in listOf(true, false)) {
            val pixel = renderError(stock, true)
            assertTrue(pixel.alpha > 0.9f && pixel.blue > 0.9f && pixel.red < 0.1f && pixel.green < 0.1f,
                "$stock: expected the explicit blue painter, got $pixel")
        }
    }
}
