package io.github.yuroyami.imagekodec.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** [KiteImage] converts a file's colours to sRGB unless it is told to keep them (#108). */
class ColorTargetSceneTest {

    /** A gray PNG of level 128 whose `gAMA` chunk says the samples are linear light. */
    private fun linearGrayPng(): ByteArray {
        val plain = ImageKodec.encodePng(KiteBitmap(8, 8, IntArray(64) { 0xFF808080.toInt() }))
        val gama = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(100_000) }.toByteArray()
        val type = "gAMA".toByteArray()
        val crc = CRC32().apply { update(type); update(gama) }.value.toInt()
        val chunk = ByteArrayOutputStream().also {
            val out = DataOutputStream(it)
            out.writeInt(gama.size); out.write(type); out.write(gama); out.writeInt(crc)
        }.toByteArray()
        // The signature is 8 bytes and IHDR is 25, so the chunk lands before the pixels.
        return plain.copyOfRange(0, 33) + chunk + plain.copyOfRange(33, plain.size)
    }

    private fun ImageComposeScene.gray(): Int? {
        val pixel = render(0).toComposeImageBitmap().toPixelMap()[32, 32]
        return if (pixel.alpha > 0.5f) (pixel.red * 255).roundToInt() else null
    }

    private fun ImageComposeScene.grayNear(want: Int): Int {
        var last: Int? = null
        repeat(500) {
            last = gray()
            if (last?.let { abs(it - want) <= 1 } == true) return last!!
            Thread.sleep(4)
        }
        fail("wanted gray $want, last saw $last")
    }

    @Test
    fun aLinearFileDrawsConvertedByDefaultAndAsStoredOnRequest() {
        val data = linearGrayPng()
        val stored = ImageKodec.decode(data)[0, 0] and 0xFF
        val converted = ImageKodec.decode(data, colorTarget = ColorTarget.Srgb())[0, 0] and 0xFF
        assertEquals(128, stored)
        // Linear 128 of 255 is sRGB level 188.
        assertEquals(188, converted)

        var target by mutableStateOf<ColorTarget?>(null)
        ImageComposeScene(width = 64, height = 64) {
            val chosen = target
            if (chosen == null) KiteImage(data, null, Modifier.size(64.dp))
            else KiteImage(data, null, Modifier.size(64.dp), colorTarget = chosen)
        }.use { scene ->
            scene.grayNear(converted)
            // A change of target decodes again.
            target = ColorTarget.Source
            scene.grayNear(stored)
            target = ColorTarget.Srgb()
            assertTrue(abs(scene.grayNear(converted) - converted) <= 1)
        }
    }
}
