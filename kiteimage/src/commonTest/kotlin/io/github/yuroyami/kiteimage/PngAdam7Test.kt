package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adam7 vectors from `gen_png_vectors.py` — pass rows constructed pixel-by-pixel
 * in pass order, so expectations are knowable by formula. Geometry coverage:
 * every pass non-empty (9x9), mostly-empty passes (5x3), sub-byte packed pass
 * rows (2-bit palette, 1-bit gray).
 */
class PngAdam7Test {

    private val ADAM7_GRAY8_9X9 = "89504e470d0a1a0a0000000d4948445200000009000000090800000001b2fd695a0000006c4944415478da63600860e08860d060306060d1096110b1619070609071616012d3b20b626093d2730a63e092337263e05130f160e05332f3621050b1f0611052b3f26360e41695d734b6750f6460e61557d436b5f70c6660e59754d63577f40e6560179456d5b774f60d07007b1e0ded3edc9a000000000049454e44ae426082"
    private val ADAM7_RGB8_5X3 = "89504e470d0a1a0a0000000d4948445200000005000000030802000001a3536239000000354944415478da0dc6310100300803c18840444430678e888a40444420b5fc2d0f5c0b1f02a1e3cddcd7a0c1f4e4012a8a568fbc7a1f17670c4f0e09a60f0000000049454e44ae426082"
    private val ADAM7_PAL2_6X6 = "89504e470d0a1a0a0000000d4948445200000006000000060203000001eaa5374d0000000c504c5445ff000000ff000000ffffff00d6028f7b0000000174524e5380ad5e5b460000001b4944415478da636000830620ec602861b803c439090cc70e0049003ee1066b691f4bae0000000049454e44ae426082"
    private val ADAM7_GRAY1_8X2 = "89504e470d0a1a0a0000000d49484452000000080000000201000000013ae890d60000000e4944415478da636000830f0cab000384019b727530720000000049454e44ae426082"

    @Test
    fun gray8AllPassesNonEmpty() {
        val bm = KiteImage.decode(hex(ADAM7_GRAY8_9X9))
        assertEquals(9, bm.width)
        assertEquals(9, bm.height)
        for (y in 0 until 9) for (x in 0 until 9) {
            assertEquals(gray(x * 10 + y), bm[x, y], "($x,$y)")
        }
    }

    @Test
    fun rgb8ThinImageMostPassesEmpty() {
        val bm = KiteImage.decode(hex(ADAM7_RGB8_5X3))
        for (y in 0 until 3) for (x in 0 until 5) {
            assertEquals(argb(0xFF, x * 40, y * 70, (x + y) * 20), bm[x, y], "($x,$y)")
        }
    }

    @Test
    fun palette2BitSubBytePassRows() {
        val bm = KiteImage.decode(hex(ADAM7_PAL2_6X6))
        val pal = intArrayOf(
            argb(0x80, 255, 0, 0),      // tRNS[0] = 128
            argb(0xFF, 0, 255, 0),
            argb(0xFF, 0, 0, 255),
            argb(0xFF, 255, 255, 0),
        )
        for (y in 0 until 6) for (x in 0 until 6) {
            assertEquals(pal[(x + y) % 4], bm[x, y], "($x,$y)")
        }
        assertTrue(bm.hasTransparency())
    }

    @Test
    fun gray1BitInterlaced() {
        val bm = KiteImage.decode(hex(ADAM7_GRAY1_8X2))
        for (y in 0 until 2) for (x in 0 until 8) {
            assertEquals(gray(((x xor y) and 1) * 255), bm[x, y], "($x,$y)")
        }
    }
}
