package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1EncodeSettings
import io.github.yuroyami.imagekodec.codec.avif.Av1Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the AV1 encoder's searches reuse instead of computing again (#176), checked on every target against the
 * computation it stands for. A transform block's levels kept for a repeat must be those a fresh search of the same
 * block finds, which a kept result whose coefficient contexts differ from the block's would not be; the residual
 * kept with them must be the decoder's; and the CDEF output put together from the CDEF search's passes, which only
 * the loop restoration search reads, must be the frame filtered once more. [Av1EncodeSettings.checkReuse] makes the
 * searches compare each and throw where they differ.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class Av1ReuseCheckTest {

    /** [bitmap] as 8-bit planes, BT.601 full range, chroma averaged over 2x2 where [subsampled]. */
    private fun planes(bitmap: KiteBitmap, subsampled: Boolean): HeifPlanes {
        val w = bitmap.width
        val h = bitmap.height
        val y = ShortArray(w * h)
        val cb = IntArray(w * h)
        val cr = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = bitmap.argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y[i] = ((77 * r + 150 * g + 29 * b + 128) shr 8).toShort()
            cb[i] = ((-43 * r - 85 * g + 128 * b + 128) shr 8) + 128
            cr[i] = ((128 * r - 107 * g - 21 * b + 128) shr 8) + 128
        }
        if (!subsampled) {
            val u = ShortArray(w * h) { cb[it].coerceIn(0, 255).toShort() }
            val v = ShortArray(w * h) { cr[it].coerceIn(0, 255).toShort() }
            return HeifPlanes(w, h, 8, 8, 0, 0, arrayOf(y, u, v), intArrayOf(w, w, w), true)
        }
        val cw = (w + 1) shr 1
        val ch = (h + 1) shr 1
        fun average(c: IntArray, i: Int): Short {
            val x = (i % cw) * 2
            val yy = (i / cw) * 2
            val x1 = minOf(x + 1, w - 1)
            val y1 = minOf(yy + 1, h - 1)
            return ((c[yy * w + x] + c[yy * w + x1] + c[y1 * w + x] + c[y1 * w + x1] + 2) shr 2).coerceIn(0, 255).toShort()
        }
        val u = ShortArray(cw * ch) { average(cb, it) }
        val v = ShortArray(cw * ch) { average(cr, it) }
        return HeifPlanes(w, h, 8, 8, 1, 1, arrayOf(y, u, v), intArrayOf(w, cw, cw), true)
    }

    @Test
    fun aKeptTransformBlockResultIsWhatAFreshSearchFinds() {
        for ((input, effort) in listOf(
            planes(PhotoScenes.portrait(72, 56).bitmap, subsampled = true) to 1,
            planes(PhotoScenes.foliage(72, 56).bitmap, subsampled = true) to 2,
            planes(PhotoScenes.city(56, 40).bitmap, subsampled = false) to 2,
            planes(PhotoScenes.landscape(40, 32).bitmap, subsampled = true) to 3,
        )) {
            val encoder = Av1Encoder(input, Av1EncodeSettings(120, effort, null, checkReuse = true), null)
            encoder.encode()
            val (blocks, _) = encoder.reuseChecks()
            assertTrue(blocks > 100, "effort $effort: only $blocks kept results were checked")
        }
    }

    @Test
    fun theCdefPutTogetherFromTheSearchsPassesIsTheFrameFiltered() {
        // Two pictures whose blocks take luma and chroma strengths from different passes of the search.
        for ((scene, q) in listOf(PhotoScenes.bokeh(256, 192) to 40, PhotoScenes.landscape(256, 192) to 90)) {
            val encoder = Av1Encoder(planes(scene.bitmap, subsampled = true), Av1EncodeSettings(q, 2, null, checkReuse = true), null)
            encoder.encode()
            val (_, cdef) = encoder.reuseChecks()
            assertEquals(1, cdef, "${scene.name}: the CDEF output was checked $cdef times")
            val f = encoder.chosenFilters!!
            assertTrue(f.cdefBits > 0 && (0 until (1 shl f.cdefBits)).any { f.cdefY[it] != f.cdefUv[it] }, "${scene.name}: ${f.cdefY.toList()}, ${f.cdefUv.toList()}")
        }
    }
}
