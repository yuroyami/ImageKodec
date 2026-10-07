package io.github.yuroyami.imagekodec.coil

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A paused [KiteAsyncImage] asks Coil for one frame, and playing it asks for the
 * rest without the pinned frame leaving the screen (#106).
 */
class KiteAsyncImagePauseTest {

    private var now = 0L

    private fun ImageComposeScene.pixel(): Color = render(now).toComposeImageBitmap().toPixelMap()[32, 32]

    /** Renders until [done] holds, moving the clock [stepMillis] a frame and leaving real time for the request. */
    private fun ImageComposeScene.renderUntil(what: String, stepMillis: Long = 0, done: (Color) -> Boolean) {
        repeat(2_000) {
            if (done(pixel())) return
            now += stepMillis * 1_000_000
            Thread.sleep(5)
        }
        fail("never saw $what")
    }

    @Test
    fun aPausedImageRequestsOneFrameAndKeepsItWhileTheRestLoad() {
        val red = KiteBitmap(8, 8, IntArray(64) { 0xFFFF0000.toInt() })
        val blue = KiteBitmap(8, 8, IntArray(64) { 0xFF0000FF.toInt() })
        val gif = ImageKodec.encodeGif(KiteAnimation(8, 8, listOf(KiteFrame(red, 100, 10), KiteFrame(blue, 100, 10)), 0))
        val context = PlatformContext.INSTANCE
        // Holds a request for every frame until the test lets it go, so the frames
        // rendered while it runs can be checked.
        val release = CompletableDeferred<Unit>()
        val holdFullRequests = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.maxFrames == Int.MAX_VALUE) release.await()
                return chain.proceed()
            }
        }
        val loader = ImageLoader.Builder(context)
            .components {
                add(holdFullRequests)
                add(KiteImageDecoder.Factory())
            }
            .build()
        val successes = CopyOnWriteArrayList<SuccessResult>()
        var animate by mutableStateOf(false)
        try {
            ImageComposeScene(width = 64, height = 64) {
                KiteAsyncImage(
                    gif, null, imageLoader = loader, modifier = Modifier.size(64.dp),
                    animate = animate, onSuccess = { successes += it },
                )
            }.use { scene ->
                scene.renderUntil("the first frame") { it.red > 0.5f }
                assertEquals(listOf(1), successes.map { it.request.maxFrames })
                assertIs<BitmapImage>(successes[0].image)

                animate = true
                repeat(20) {
                    now += 16_000_000
                    assertTrue(scene.pixel().red > 0.5f, "the pinned frame went away while the rest loaded")
                    Thread.sleep(5)
                }
                release.complete(Unit)
                scene.renderUntil("the second frame", stepMillis = 16) { pixel ->
                    assertTrue(pixel.alpha > 0.5f, "the pinned frame went away while the rest loaded")
                    pixel.blue > 0.5f
                }
                assertEquals(listOf(1, Int.MAX_VALUE), successes.map { it.request.maxFrames })
                assertIs<KiteAnimationImage>(successes[1].image)

                // Every frame is there, so pausing again needs no request.
                animate = false
                scene.renderUntil("the first frame again") { it.red > 0.5f }
                repeat(20) {
                    scene.render(now)
                    Thread.sleep(5)
                }
                assertEquals(2, successes.size)
            }
        } finally {
            loader.shutdown()
        }
    }
}
