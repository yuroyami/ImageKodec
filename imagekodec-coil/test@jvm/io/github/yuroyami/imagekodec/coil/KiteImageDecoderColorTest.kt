package io.github.yuroyami.imagekodec.coil

import coil3.BitmapImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.yuroyami.imagekodec.ColorTarget
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteFrame
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [KiteImageDecoder] converts a file's colours to sRGB unless its factory is told to keep them (#108). */
class KiteImageDecoderColorTest {

    private val context: PlatformContext = PlatformContext.INSTANCE
    private val gray = 0xFF808080.toInt()
    // Linear 128 of 255 is sRGB level 188.
    private val converted = 0xFFBCBCBC.toInt()

    /** [png] with a `gAMA` chunk that says its samples are linear light. */
    private fun linear(png: ByteArray): ByteArray {
        val gama = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(100_000) }.toByteArray()
        val type = "gAMA".toByteArray()
        val crc = CRC32().apply { update(type); update(gama) }.value.toInt()
        val chunk = ByteArrayOutputStream().also {
            val out = DataOutputStream(it)
            out.writeInt(gama.size); out.write(type); out.write(gama); out.writeInt(crc)
        }.toByteArray()
        // The signature is 8 bytes and IHDR is 25, so the chunk lands before the pixels.
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }

    private fun solid() = KiteBitmap(8, 8, IntArray(64) { gray })

    private fun load(bytes: ByteArray, factory: KiteImageDecoder.Factory): SuccessResult {
        val loader = ImageLoader.Builder(context).components { add(factory) }.build()
        return assertIs<SuccessResult>(runBlocking { loader.execute(ImageRequest.Builder(context).data(bytes).build()) })
    }

    @Test
    fun aStillConvertsByDefaultAndStaysAsStoredOnRequest() {
        val bytes = linear(ImageKodec.encodePng(solid()))
        assertEquals(converted, ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb())[0, 0])
        val byDefault = assertIs<BitmapImage>(load(bytes, KiteImageDecoder.Factory()).image)
        assertEquals(converted, byDefault.bitmap.getColor(3, 3))
        val asStored = assertIs<BitmapImage>(load(bytes, KiteImageDecoder.Factory(colorTarget = ColorTarget.Source)).image)
        assertEquals(gray, asStored.bitmap.getColor(3, 3))
    }

    @Test
    fun everyFrameOfAnAnimationConverts() {
        val frames = List(3) { KiteFrame(solid(), 100, 10) }
        val bytes = linear(ImageKodec.encodePng(KiteAnimation(8, 8, frames, 0)))
        val byDefault = assertIs<KiteAnimationImage>(load(bytes, KiteImageDecoder.Factory()).image)
        assertEquals(List(3) { converted }, byDefault.animation.frames.map { it.bitmap[3, 3] })
        val asStored = assertIs<KiteAnimationImage>(load(bytes, KiteImageDecoder.Factory(colorTarget = ColorTarget.Source)).image)
        assertEquals(List(3) { gray }, asStored.animation.frames.map { it.bitmap[3, 3] })
    }
}
