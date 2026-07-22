package io.github.yuroyami.kiteimage.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The automated eyeball: render the KiteImage() composable inside an
 * ImageComposeScene and advance the virtual frame clock. An animated GIF's
 * pixels MUST change between frames (playback runs), and a static PNG's must
 * not. This is the proof the composable's frame loop actually drives the UI —
 * everything below it (decode, compositing) is covered by the codec suites.
 */
class GifPlaybackSceneTest {

    /** 2-frame GIF, 100 ms delays: frame 0 = solid red, frame 1 = solid blue (8x8). */
    private fun twoFrameGif(): ByteArray {
        fun sub(vararg blocks: ByteArray): ByteArray {
            val out = ArrayList<Byte>()
            for (b in blocks) { out.add(b.size.toByte()); b.forEach { out.add(it) } }
            out.add(0)
            return out.toByteArray()
        }
        // Literal-only LZW for a solid 8x8 frame of index [idx] at min code size 2:
        // the decoder's dictionary grows per literal, so a CLEAR precedes every
        // literal to pin the code width at 3 bits (same trick as the vector rig).
        fun lzwSolid(idx: Int): ByteArray {
            val bits = ArrayList<Int>()
            fun emit(code: Int) { for (i in 0 until 3) bits.add((code shr i) and 1) }
            emit(4)
            repeat(64) {
                emit(idx)
                emit(4)
            }
            emit(5)
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < bits.size) {
                var b = 0
                for (j in 0 until 8) if (i + j < bits.size && bits[i + j] == 1) b = b or (1 shl j)
                bytes.add(b.toByte()); i += 8
            }
            return bytes.toByteArray()
        }
        fun frame(idx: Int): ByteArray =
            byteArrayOf(0x21, 0xF9.toByte(), 4, 4, 10, 0, 0, 0) +            // GCE delay 10cs
                byteArrayOf(0x2C, 0, 0, 0, 0, 8, 0, 8, 0, 0, 2) + sub(lzwSolid(idx))
        return "GIF89a".encodeToByteArray() +
            byteArrayOf(8, 0, 8, 0, 0x91.toByte(), 0, 0) +                    // LSD + GCT(4)
            byteArrayOf(255.toByte(), 0, 0, 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 255.toByte(), 255.toByte()) +
            byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".encodeToByteArray() + byteArrayOf(3, 1, 0, 0, 0) +
            frame(0) + frame(1) +
            byteArrayOf(0x3B)
    }

    @Test
    fun animatedGifPixelsChangeBetweenFramesStaticDoNot() {
        val gif = twoFrameGif()
        val staticPng = io.github.yuroyami.kiteimage.KiteImage.encodePng(
            io.github.yuroyami.kiteimage.KiteBitmap(8, 8, IntArray(64) { 0xFF00FF00.toInt() }),
        )

        ImageComposeScene(width = 128, height = 64) {
            androidx.compose.foundation.layout.Row {
                KiteImage(data = gif, contentDescription = "anim", modifier = Modifier.size(64.dp))
                KiteImage(data = staticPng, contentDescription = "static", modifier = Modifier.size(64.dp))
            }
        }.use { scene ->
            var t = 0L
            // Pump a few frames so decode (Dispatchers.Default) + first draw land.
            var px = scene.render(t).toComposeImageBitmap().toPixelMap()
            var tries = 0
            while (tries < 300 && (px[32, 32].alpha == 0f || px[96, 32].alpha == 0f)) {
                Thread.sleep(4)
                t += 16_000_000L
                px = scene.render(t).toComposeImageBitmap().toPixelMap()
                tries++
            }
            val animBefore = px[32, 32]
            val staticBefore = px[96, 32]
            assertTrue(animBefore.red > 0.5f && animBefore.blue < 0.5f, "frame 0 should be red, got $animBefore")
            assertTrue(staticBefore.green > 0.5f, "static tile should be green")

            // Advance past the 100 ms frame delay (with margin).
            repeat(20) {
                Thread.sleep(4)
                t += 16_000_000L
                scene.render(t)
            }
            val after = scene.render(t).toComposeImageBitmap().toPixelMap()
            val animAfter = after[32, 32]
            val staticAfter = after[96, 32]

            assertTrue(
                animAfter.blue > 0.5f && animAfter.red < 0.5f,
                "GIF did not advance to the blue frame: $animAfter",
            )
            assertEquals(staticBefore, staticAfter, "static image must not change")
        }
    }
}
