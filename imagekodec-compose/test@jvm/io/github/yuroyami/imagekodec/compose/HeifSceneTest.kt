package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.ImageKodec
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HEIC in [KiteImage] (#170): a still decodes at the size it draws at, again when its box grows, and
 * a decode that nobody will see stops between tiles. [TileCodec] stands in for the HEVC codec of
 * `imagekodec-heic`, which this module does not depend on.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class HeifSceneTest {

    @Composable
    private fun Decoded(data: ByteArray, side: Int, decode: AnimationDecoder, contentScale: ContentScale = ContentScale.Fit) {
        DecodedImage(
            data, null, Modifier.size(side.dp), Alignment.Center, contentScale, DefaultAlpha, null,
            DrawScope.DefaultFilterQuality, animate = true, onError = null, decode = decode,
        )
    }

    @Test
    fun leavingCompositionStopsAGridBetweenTiles() {
        val codec = TileCodec("tsk1").also { ImageKodec.registerHeifCodec(it) }
        val data = codec.grid(rows = 8, columns = 8, tileWidth = 64, tileHeight = 64)
        val atThird = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var outcome: Throwable? = null
        codec.onTile = {
            if (codec.decoded.get() == 2) {
                atThird.countDown()
                removed.await(10, TimeUnit.SECONDS)
            }
        }
        val decode: AnimationDecoder = { bytes, maxFrames, bounds, check ->
            try {
                decodeForDisplay(bytes, maxFrames, bounds, check)
            } catch (e: Throwable) {
                outcome = e
                throw e
            } finally {
                ended.countDown()
            }
        }
        var shown by mutableStateOf(true)
        ImageComposeScene(width = 64, height = 64) {
            if (shown) Decoded(data, 64, decode)
        }.use { scene ->
            repeat(500) {
                if (atThird.count == 0L) return@repeat
                scene.render(0)
                Thread.sleep(4)
            }
            assertEquals(0L, atThird.count, "the decode never reached its third tile")
            shown = false
            scene.render(0)
            removed.countDown()
            assertTrue(ended.await(10, TimeUnit.SECONDS), "the decode did not stop")
            assertTrue(outcome is CancellationException, "the decode should end cancelled, ended with $outcome")
            assertEquals(3, codec.decoded.get(), "the decode went on past the tile where it was abandoned")
        }
    }

    @Test
    fun aStillDecodesAtTheSizeItDrawsAtAndAgainWhenItsBoxGrows() {
        val codec = TileCodec("tsk2").also { ImageKodec.registerHeifCodec(it) }
        // 507 by 509 pixels.
        val data = codec.grid(rows = 8, columns = 8, tileWidth = 64, tileHeight = 64)
        val sizes = Collections.synchronizedList(ArrayList<IntSize>())
        val decode: AnimationDecoder = { bytes, maxFrames, bounds, check ->
            decodeForDisplay(bytes, maxFrames, bounds, check).also { sizes += IntSize(it.animation.width, it.animation.height) }
        }
        var side by mutableStateOf(64)
        ImageComposeScene(width = 300, height = 300) {
            Decoded(data, side, decode)
        }.use { scene ->
            fun renderUntil(count: Int) {
                repeat(500) {
                    if (sizes.size >= count) return
                    scene.render(0)
                    Thread.sleep(4)
                }
            }
            renderUntil(1)
            // Fit in a 64 by 64 box: 64 / 509 of each side, rounded up.
            assertEquals(listOf(IntSize(64, 64)), sizes.toList())
            side = 32
            repeat(20) { scene.render(0); Thread.sleep(4) }
            assertEquals(1, sizes.size, "a smaller box should keep the pixels it has")
            side = 200
            renderUntil(2)
            assertEquals(listOf(IntSize(64, 64), IntSize(200, 200)), sizes.toList())
        }
        // Cropped to fill the box, the short side covers it.
        assertEquals(IntSize(100, 101), DrawBounds(100, 100, ContentScale.Crop).sizeFor(507, 509))
        assertEquals(IntSize(100, 101), DrawBounds(100, 1000, ContentScale.FillWidth).sizeFor(507, 509))
        assertEquals(IntSize(507, 509), DrawBounds(100, 100, ContentScale.None).sizeFor(507, 509))
        assertEquals(IntSize(507, 509), DrawBounds(0, 0, ContentScale.Fit).sizeFor(507, 509))
        assertEquals(IntSize(507, 509), DrawBounds(1000, 1000, ContentScale.Fit).sizeFor(507, 509))
    }
}
