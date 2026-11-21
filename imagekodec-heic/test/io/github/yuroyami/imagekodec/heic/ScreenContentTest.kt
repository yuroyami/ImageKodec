package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.FuzzSettings
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.PictureHash
import io.github.yuroyami.imagekodec.withDeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The screen content coding tools on every target (#188), through the stream that [SccStream] writes: the samples
 * of its palette units and of the units that copy them must be those the stream describes, worked out here from
 * 8.4.4.2.7 and 8.5.3.2, and the whole picture must keep its MD5. Then every cut and many altered bytes of its slice
 * must give a picture or an ImageDecodeException. The conformance streams hold the decoder to HM in the oracle test.
 */
class ScreenContentTest {

    private fun decode(units: List<ByteArray>): List<DecodedPicture> {
        val d = HevcDecoder()
        for (u in units) d.decodeNal(u, 0, u.size, null)
        return d.finish()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun theStreamDecodesAsItWasWritten() {
        val p = decode(SccStream.units()).single()
        val s = p.sps
        assertTrue(s.paletteMode && s.currPicRefEnabled && s.intraBoundaryFilteringDisabled && s.motionVectorResolutionControlIdc == 2)
        val n = SccStream.SIZE
        fun at(c: Int, x: Int, y: Int) = p.planes[c][y * n + x].toInt() and 0xFFFF
        val pps = SccStream.PPS_INITIALIZERS
        // CTB 0: predictor entries 0 and 2 and the new entry; escape samples scaled by 8-77 at Qp′ 26 (levelScale 51,
        // a shift of 4); palette_transpose_flag takes picture sample ( x, y ) from scan position ( y, x ).
        val map0 = SccStream.indexMap(SccStream.CTB0_RUNS, 16)
        val entries0 = listOf(IntArray(3) { pps[it][0] }, IntArray(3) { pps[it][2] }, SccStream.NEW_ENTRY)
        for (c in 0..2) for (y in 0 until 16) for (x in 0 until 16) {
            val u = x * 16 + y
            val index = map0[u]
            val expected = if (index == 3) {
                val pos = (0 until 256).first { PaletteDecoder.traverse(it, 16) == u }
                minOf(255, ((SccStream.escapeValue(c, pos) * 51 shl 4) + 32) shr 6)
            } else {
                entries0[index][c]
            }
            assertEquals(expected, at(c, x, y), "CTB 0, component $c at ($x, $y)")
        }
        // Unit b of CTB 2: predictor entries 0 and 1, which the update of 8-79 made the PPS's entries 0 and 2.
        val mapB = SccStream.indexMap(SccStream.UNIT_B_RUNS, 8)
        for (c in 0..2) for (y in 0 until 8) for (x in 0 until 8) {
            val expected = if (mapB[y * 8 + x] == 0) pps[c][0] else pps[c][2]
            assertEquals(expected, at(c, 8 + x, 16 + y), "unit b, component $c at ($x, $y)")
        }
        // Units c and d copy the top half of CTB 0's left 16 columns, by a vector of whole samples and its merge.
        for (c in 0..2) for (y in 0 until 8) for (x in 0 until 16) {
            assertEquals(at(c, x, y), at(c, x, 24 + y), "units c and d, component $c at ($x, $y)")
        }
        // CTB 1 copies CTB 0 and adds residuals of luma and Cb only, which the colour transform spreads over all three
        // components: Cr, with no residual of its own, changes too.
        for (c in 0..2) {
            val changed = (0 until 16).any { y -> (0 until 16).any { x -> at(c, 16 + x, y) != at(c, x, y) } }
            assertTrue(changed, "CTB 1, component $c, takes a share of the residual")
        }
        // The rest (the colour transform over residuals, intra prediction without boundary filters) keeps its MD5.
        val md5 = (0..2).joinToString(" ") { c -> hex(PictureHash.md5(PictureHash.pictureData(p.planes[c], 0, n, n, n, 8))) }
        assertEquals(PICTURE_MD5, md5)
    }

    @Test
    fun everyCutAndAlteredByteFailsCleanly() {
        val units = SccStream.units()
        val index = units.indexOfFirst { NalType.isSlice((it[0].toInt() shr 1) and 0x3F) }
        val slice = units[index]
        fun survives(changed: ByteArray, what: String) {
            try {
                decode(units.toMutableList().also { it[index] = changed })
            } catch (_: ImageDecodeException) {
            } catch (t: Throwable) {
                fail("$what${FuzzSettings.replay} leaked ${t::class.simpleName}: ${t.message}")
            }
        }
        withDeadline("HEVC screen content truncation", 60_000) {
            for (n in 2 until slice.size) survives(slice.copyOf(n), "the slice cut to $n bytes")
        }
        withDeadline("HEVC screen content mutation", 60_000) {
            for (at in 2 until slice.size) for (x in intArrayOf(0x01, 0x10, 0x80, 0xFF)) {
                val m = slice.copyOf()
                m[at] = (m[at].toInt() xor x).toByte()
                survives(m, "byte $at of the slice xor $x")
            }
        }
    }

    private companion object {
        /** The MD5 of each plane of the picture, as this decoder first made it once every check above passed. */
        const val PICTURE_MD5 = "090472caa4a0843bed15ea679af52fc9 7ad0bd39a6d0f16b83921a44d05ebebf 48757f3f610e000358deb4dcd9f69191"
    }
}
