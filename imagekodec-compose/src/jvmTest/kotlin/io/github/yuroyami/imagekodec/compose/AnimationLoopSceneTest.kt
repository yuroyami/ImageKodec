package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimationLoopSceneTest {
    private fun animation(plays: Long): KiteAnimation = KiteAnimation(
        8, 8,
        listOf(
            KiteFrame(KiteBitmap(8, 8, IntArray(64) { 0xffff0000.toInt() }), 100, 10),
            KiteFrame(KiteBitmap(8, 8, IntArray(64) { 0xff0000ff.toInt() }), 100, 10),
        ),
        plays,
    )

    @Test
    fun gifTotalPlaysAgreeWithIndependentSkiaMetadata() {
        for (plays in listOf(0L, 1L, 2L, 3L, 65_536L)) {
            val bytes = ImageKodec.encodeGif(animation(plays))
            Data.makeFromBytes(bytes).use { data ->
                Codec.makeFromData(data).use { codec ->
                    assertEquals(2, codec.frameCount)
                    val repeats = codec.repetitionCount
                    val total = if (repeats == -1) 0L else repeats.toLong() + 1
                    assertEquals(plays, total, "Skia total plays for $plays")
                    assertEquals(total, ImageKodec.probe(bytes).loopCount)
                    assertEquals(total, ImageKodec.decodeAnimation(bytes).loopCount)
                }
            }
        }
    }

    private fun checkPlayback(animation: KiteAnimation, samples: List<Pair<Long, Boolean>>) {
        ImageComposeScene(width = 64, height = 64) {
            KiteAnimatedImage(animation, contentDescription = null, modifier = Modifier.size(64.dp))
        }.use { scene ->
            // Start the effect and its frame clock at time zero before advancing it.
            repeat(15) {
                scene.render(0)
                Thread.sleep(4)
            }
            for ((millis, red) in samples) {
                repeat(5) {
                    scene.render(millis * 1_000_000)
                    Thread.sleep(4)
                }
                val pixel = scene.render(millis * 1_000_000).toComposeImageBitmap().toPixelMap()[32, 32]
                assertTrue(
                    if (red) pixel.red > 0.8f && pixel.blue < 0.2f else pixel.blue > 0.8f && pixel.red < 0.2f,
                    "$millis ms: expected ${if (red) "red" else "blue"}, got $pixel",
                )
            }
        }
    }

    @Test
    fun aGifWithOneRepeatPlaysTwiceThenHoldsItsFinalFrame() {
        val decoded = ImageKodec.decodeAnimation(ImageKodec.encodeGif(animation(2)))
        checkPlayback(decoded, listOf(50L to true, 150L to false, 250L to true, 350L to false, 450L to false))
    }

    @Test
    fun largeFiniteCountsReachTheirLastCycleWithoutOverflow() {
        for (plays in listOf(2_147_483_647L, 2_147_483_648L, 4_294_967_295L)) {
            val actual = if (plays == 4_294_967_295L) ImageKodec.decodeAnimation(Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACGFjVEwAAAAC/////y02s5MAAAAaZmNUTAAAAAAAAAABAAAAAQAAAAAAAAAAAAoAZAAAb+bn6wAAAA1JREFUeJxj+M/A8B8ABQAB/4mZPR0AAAAaZmNUTAAAAAEAAAABAAAAAQAAAAAAAAAAAAoAZAAA9JUNPwAAABFmZEFUAAAAAnicY2Bg+P8fAAMCAf/1e6XXAAAAAElFTkSuQmCC",
            )) else animation(plays)
            checkPlayback(actual, listOf(
                50L to true, 250L to true,
                ((plays - 1) * 200 + 50) to true,
                (plays * 200 + 50) to false,
            ))
        }
    }
}
