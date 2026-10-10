package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The player sums every frame delay to find where a loop ends. A GIF can state a delay of
 * 655.35 s, so 3300 frames add up to 2.16e9 ms, which is past Int.MAX_VALUE. In an Int the
 * sum turned negative and the player took the "nothing to play" branch: it jumped to the
 * last frame at once instead of showing the first one for 655 s.
 */
class LongAnimationSceneTest {

    @Test
    fun aLongAnimationStaysOnItsFirstFrame() {
        val red = KiteBitmap(8, 8, IntArray(64) { 0xFFFF0000.toInt() })
        val blue = KiteBitmap(8, 8, IntArray(64) { 0xFF0000FF.toInt() })
        val frames = List(3300) {
            KiteFrame(if (it == 0) red else blue, delayMillis = 655_350, delayRawCentiseconds = 65_535)
        }
        val animation = KiteAnimation(8, 8, frames, loopCount = 0)

        ImageComposeScene(width = 64, height = 64) {
            KiteAnimatedImage(animation, contentDescription = null, modifier = Modifier.size(64.dp))
        }.use { scene ->
            var t = 0L
            // Give the frame loop time to start and act on the animation.
            repeat(40) {
                Thread.sleep(4)
                t += 16_000_000L
                scene.render(t)
            }
            val pixel = scene.render(t).toComposeImageBitmap().toPixelMap()[32, 32]
            assertTrue(
                pixel.red > 0.5f && pixel.blue < 0.5f,
                "the first frame lasts 655 s, so it should still be showing: $pixel",
            )
        }
    }
}
