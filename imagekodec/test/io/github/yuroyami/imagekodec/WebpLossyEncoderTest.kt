package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Vp8Decoder
import io.github.yuroyami.imagekodec.codec.Vp8Encoder
import io.github.yuroyami.imagekodec.codec.Vp8Import
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The lossy WebP writer (#178) on every target. */
class WebpLossyEncoderTest {

    private fun photo(w: Int, h: Int, alpha: Boolean = false): KiteBitmap {
        var state = 0x2545F491
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
            val n = state and 15
            argb(
                if (alpha) (x * 5 + y * 3) and 0xFF else 0xFF,
                (x * 255 / maxOf(1, w - 1) + n) and 0xFF, (y * 255 / maxOf(1, h - 1) + n) and 0xFF, ((x * y) / 7 + n) and 0xFF,
            )
        })
    }

    /** The encoder predicts from exactly the pixels the decoder rebuilds. */
    @Test
    fun theEncodersReconstructionIsTheDecoders() {
        val pictures = listOf(photo(48, 40), photo(17, 33), PhotoScenes.bokeh(70, 50).bitmap, photo(1, 1), photo(130, 3))
        for ((n, b) in pictures.withIndex()) for (quality in listOf(0, 30, 75, 100)) for (effort in 1..3) {
            val planes = Vp8Import.planes(b.argb, b.width, b.height, sharp = false)
            val r = Vp8Encoder.encodeForTest(planes, quality, effort, filterOff = true)
            val d = Vp8Decoder.decode(r.bytes, 0, r.bytes.size)
            val what = "picture $n at quality $quality, effort $effort"
            assertContentEquals(r.y, d.y, "$what: Y")
            assertContentEquals(r.u, d.u, "$what: U")
            assertContentEquals(r.v, d.v, "$what: V")
        }
    }

    @Test
    fun edgeCasesReadBack() {
        for (b in listOf(photo(1, 1), photo(1, 37), photo(53, 1), photo(31, 17), photo(16, 16), photo(33, 65, alpha = true))) {
            val data = ImageKodec.encodeWebp(b, 75)
            val back = ImageKodec.decode(data)
            assertEquals(b.width to b.height, back.width to back.height)
        }
    }
}
