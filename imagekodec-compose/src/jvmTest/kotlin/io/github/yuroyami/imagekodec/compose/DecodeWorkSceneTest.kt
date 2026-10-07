package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Where [KiteImage] and [KiteAnimatedImage] do their work (#74): a decode stops once
 * nobody will see it, a pinned image decodes one frame, and no frame converts to an
 * `ImageBitmap` on the thread that renders.
 */
class DecodeWorkSceneTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun solid(size: Int, color: Int) = KiteBitmap(size, size, IntArray(size * size) { color })

    /** [count] frames of 100 ms, red then blue then red and so on, looping forever. */
    private fun gif(count: Int): ByteArray = ImageKodec.encodeGif(
        KiteAnimation(8, 8, List(count) { KiteFrame(solid(8, if (it % 2 == 0) red else blue), 100, 10) }, 0),
    )

    /** The scene's frame clock, which only moves forward. */
    private var now = 0L

    private fun ImageComposeScene.pixel(): Color = render(now).toComposeImageBitmap().toPixelMap()[32, 32]

    /**
     * Renders until [done] holds, moving the clock [stepMillis] a frame and leaving
     * real time between frames for the work on other threads.
     */
    private fun ImageComposeScene.renderUntil(what: String, stepMillis: Long = 0, done: (Color) -> Boolean): Color {
        repeat(500) {
            val pixel = pixel()
            if (done(pixel)) return pixel
            now += stepMillis * 1_000_000
            Thread.sleep(4)
        }
        fail("never saw $what")
    }

    @Composable
    private fun Decoded(data: ByteArray, animate: Boolean, decode: AnimationDecoder, onError: ((ImageDecodeException) -> Unit)? = null) {
        DecodedImage(
            data, null, Modifier.size(64.dp), Alignment.Center, ContentScale.Fit, DefaultAlpha, null,
            DrawScope.DefaultFilterQuality, animate, onError, decode,
        )
    }

    @Test
    fun leavingCompositionStopsTheDecodeAtTheNextFrame() {
        val data = gif(50)
        var checks = 0
        val atThird = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var outcome: Throwable? = null
        val decode: AnimationDecoder = { bytes, maxFrames, check ->
            try {
                decodeForDisplay(bytes, maxFrames) {
                    if (++checks == 3) {
                        atThird.countDown()
                        removed.await(10, TimeUnit.SECONDS)
                    }
                    check()
                }
            } catch (e: Throwable) {
                outcome = e
                throw e
            } finally {
                ended.countDown()
            }
        }
        var shown by mutableStateOf(true)
        ImageComposeScene(width = 64, height = 64) {
            if (shown) Decoded(data, animate = true, decode = decode)
        }.use { scene ->
            repeat(500) {
                if (atThird.count == 0L) return@repeat
                scene.render(0)
                Thread.sleep(4)
            }
            assertEquals(0L, atThird.count, "the decode never reached its third frame")
            shown = false
            scene.render(0)
            removed.countDown()
            assertTrue(ended.await(10, TimeUnit.SECONDS), "the decode did not stop")
            assertTrue(outcome is CancellationException, "the decode should end cancelled, ended with $outcome")
            assertEquals(3, checks, "the decode went on past the frame where it was abandoned")
        }
    }

    @Test
    fun aPinnedImageDecodesOneFrameAndKeepsItWhileTheRestDecode() {
        val data = gif(2)
        val asked = Collections.synchronizedList(ArrayList<Int>())
        val decode: AnimationDecoder = { bytes, maxFrames, check ->
            asked += maxFrames
            decodeForDisplay(bytes, maxFrames, check)
        }
        var animate by mutableStateOf(false)
        ImageComposeScene(width = 64, height = 64) {
            Decoded(data, animate = animate, decode = decode)
        }.use { scene ->
            scene.renderUntil(what = "the first frame") { it.red > 0.5f }
            assertEquals(listOf(1), asked.toList())

            animate = true
            // The pinned frame stays up through the second decode, and playback then runs.
            scene.renderUntil(what = "the second frame", stepMillis = 16) { pixel ->
                assertTrue(pixel.alpha > 0.5f, "the pinned frame went away while the rest decoded")
                pixel.blue > 0.5f
            }
            assertEquals(listOf(1, Int.MAX_VALUE), asked.toList())

            // Pinning it again keeps what was decoded.
            animate = false
            scene.renderUntil(what = "the first frame again") { it.red > 0.5f }
            assertEquals(listOf(1, Int.MAX_VALUE), asked.toList())
        }
    }

    @Test
    fun aLaterFrameThatFailsLeavesThePinnedFrameUp() {
        val data = gif(2)
        val errors = Collections.synchronizedList(ArrayList<ImageDecodeException>())
        val decode: AnimationDecoder = { bytes, maxFrames, check ->
            if (maxFrames > 1) throw ImageDecodeException("the second frame is damaged")
            decodeForDisplay(bytes, maxFrames, check)
        }
        var animate by mutableStateOf(false)
        ImageComposeScene(width = 64, height = 64) {
            Decoded(data, animate = animate, decode = decode, onError = { errors += it })
        }.use { scene ->
            scene.renderUntil(what = "the first frame") { it.red > 0.5f }
            animate = true
            scene.renderUntil(what = "the error") { errors.isNotEmpty() }
            assertTrue(scene.pixel().red > 0.5f, "the first frame should stay up")
        }
    }

    @Test
    fun framesConvertAwayFromTheThreadThatRenders() {
        // 300 by 300 is past the 65,536 pixels that may convert in composition.
        val frames = List(4) { KiteFrame(solid(300, if (it % 2 == 0) red else blue), 100, 10) }
        val animation = KiteAnimation(300, 300, frames, 0)
        val threads = Collections.synchronizedList(ArrayList<Thread>())
        val renderer = Thread.currentThread()
        ImageComposeScene(width = 64, height = 64) {
            FramePlayer(
                frames = remember {
                    FrameBitmaps(animation, convert = { threads += Thread.currentThread(); it.toPreparedImageBitmap() })
                },
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                alignment = Alignment.Center,
                contentScale = ContentScale.Fit,
                alpha = DefaultAlpha,
                colorFilter = null,
                filterQuality = DrawScope.DefaultFilterQuality,
                animate = true,
            )
        }.use { scene ->
            scene.renderUntil(what = "the first frame") { it.red > 0.5f }
            scene.renderUntil(what = "the second frame", stepMillis = 16) { it.blue > 0.5f }
            repeat(500) {
                if (threads.size == 4) return@repeat
                Thread.sleep(4)
            }
            assertEquals(4, threads.size, "every frame converts once, ahead of playback")
            assertTrue(renderer !in threads, "a frame converted on the thread that renders")
        }
    }

    @Test
    fun aSmallFirstFrameDrawsOnTheFirstRender() {
        val animation = KiteAnimation(8, 8, listOf(KiteFrame(solid(8, red), 100, 10), KiteFrame(solid(8, blue), 100, 10)), 0)
        ImageComposeScene(width = 64, height = 64) {
            KiteAnimatedImage(animation, contentDescription = null, modifier = Modifier.size(64.dp))
        }.use { scene ->
            val pixel = scene.pixel()
            assertTrue(pixel.red > 0.5f, "an 8 by 8 frame should show at once, got $pixel")
        }
    }

    @Test
    fun aLargeBitmapLaysOutAtItsSizeBeforeItHasConverted() {
        val bitmap = solid(300, red)
        val sizes = Collections.synchronizedList(ArrayList<IntSize>())
        ImageComposeScene(width = 400, height = 400) {
            KiteImage(bitmap, contentDescription = null, modifier = Modifier.onGloballyPositioned { sizes += it.size })
        }.use { scene ->
            val first = scene.pixel()
            scene.renderUntil(what = "the bitmap") { it.red > 0.5f }
            assertTrue(sizes.isNotEmpty())
            assertEquals(setOf(IntSize(300, 300)), sizes.toSet(), "the slot changed size when the bitmap arrived")
            // Whether the first render already had it depends on the pool; the size never does.
            assertTrue(first.alpha == 0f || first.red > 0.5f)
        }
    }
}
