package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `encodeAvif` and `encodeAvif16` (#176) on every target: the AV1 encoder of the core through the HEIF writer, read
 * back by the core's AVIF decoder. Lossless returns every input exactly (edge sizes, gray, alpha, fewer and more than
 * 256 colours, 8 to 12 bits); lossy stays within a bound; fixed inputs keep a committed hash, which also holds every
 * target to the same bytes; the cancellation check stops an encode.
 */
class AvifEncodeTest {

    @AfterTest
    fun restore() {
        ImageKodec.setTileRunner(TileRunner.Serial)
    }

    /** A [w] by [h] picture of gradients, a disc and seeded noise, with varied alpha when [alpha]. */
    private fun photo(w: Int, h: Int, alpha: Boolean = false, seed: Int = 3): KiteBitmap {
        var s = seed * 2654435761.toInt() + 1
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            s = s * 1103515245 + 12345
            val n = (s ushr 16) and 15
            val x = i % w
            val y = i / w
            val disc = (x - w / 2) * (x - w / 2) + (y - h / 3) * (y - h / 3) < w * h / 8
            val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
            val g = (y * 255 / maxOf(1, h - 1) + (if (disc) 60 else 0)).coerceIn(0, 255)
            val b = ((x + y) * 3 + n * 2).coerceIn(0, 255)
            val a = if (alpha) (if (disc) 255 else (x * 7 + y * 3) and 0xFF) else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    private fun gray(b: KiteBitmap) = KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
        val p = b.argb[i]
        (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
    })

    /** Four colours in blocks, as a chart or a screenshot has. */
    private fun fewColours(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        val c = ((i % w) / 3 + (i / w) / 2) % 4
        intArrayOf(0xFF102030.toInt(), 0xFFF0E0D0.toInt(), 0xFF20C040.toInt(), 0xFFC02060.toInt())[c]
    })

    @Test
    fun losslessReturnsEveryPixel() {
        for ((name, b) in listOf(
            "1x1" to photo(1, 1), "one row" to photo(19, 1), "one column" to photo(1, 11), "odd with alpha" to photo(25, 13, alpha = true),
            "transparent" to KiteBitmap(5, 3, IntArray(15)), "gray" to gray(photo(14, 9)), "gray with alpha" to gray(photo(14, 9, alpha = true)),
            "four colours" to fewColours(23, 17), "many colours" to photo(40, 30),
        )) {
            for (depth in listOf(8, 10, 12)) {
                val file = ImageKodec.encodeAvif(b, HeifEncodeOptions(lossless = true, bitDepth = depth, effort = 1))
                assertContentEquals(b.argb, ImageKodec.decode(file).argb, "$name at $depth bits")
            }
        }
    }

    @Test
    fun sixteenBitLosslessReturnsWhatItsDepthHolds() {
        var s = 5
        for (channels in 1..4) for (depth in listOf(10, 12)) {
            val samples = ShortArray(9 * 7 * channels) {
                s = s * 1103515245 + 12345
                (s ushr 9).toShort()
            }
            val options = HeifEncodeOptions(lossless = true, bitDepth = depth, effort = 1)
            val once = ImageKodec.decode16(ImageKodec.encodeAvif16(KiteBitmap16(9, 7, channels, samples), options))
            val twice = ImageKodec.decode16(ImageKodec.encodeAvif16(once, options))
            assertContentEquals(once.samples, twice.samples, "$channels channels at $depth bits")
        }
    }

    @Test
    fun lossyStaysCloseToTheSource() {
        // Measured worst mean absolute error a channel at quality 90: about 2 levels; the bound leaves room.
        for ((name, b) in listOf("photo" to photo(48, 40), "odd" to photo(33, 17, alpha = true), "gray" to gray(photo(30, 20)))) {
            for (effort in 1..3) {
                val out = ImageKodec.decode(ImageKodec.encodeAvif(b, HeifEncodeOptions(quality = 90, effort = effort)))
                assertEquals(b.width, out.width)
                assertEquals(b.height, out.height)
                var sum = 0L
                for (i in b.argb.indices) for (shift in intArrayOf(0, 8, 16, 24)) {
                    sum += abs(((b.argb[i] ushr shift) and 0xFF) - ((out.argb[i] ushr shift) and 0xFF))
                }
                val mean = sum.toDouble() / (b.argb.size * 4)
                assertTrue(mean < 4.0, "$name at effort $effort: mean error $mean")
            }
        }
    }

    @Test
    fun aReversingTileRunnerWritesTheSameBytes() {
        val bitmap = photo(150, 70, alpha = true)
        val options = HeifEncodeOptions(tileSize = 64, effort = 1)
        val serial = ImageKodec.encodeAvif(bitmap, options)
        ImageKodec.setTileRunner { jobs -> for (job in jobs.asReversed()) job.run() }
        assertContentEquals(serial, ImageKodec.encodeAvif(bitmap, options))
    }

    @Test
    fun theBytesOfFixedInputsKeepTheirHash() {
        val files = listOf(
            ImageKodec.encodeAvif(photo(37, 21, alpha = true), 50),
            ImageKodec.encodeAvif(photo(70, 50), HeifEncodeOptions(quality = 80, effort = 1, chroma = HeifChroma.Yuv444)),
            ImageKodec.encodeAvif(photo(40, 33), HeifEncodeOptions(quality = 30, effort = 3, chroma = HeifChroma.Yuv422)),
            ImageKodec.encodeAvif(fewColours(30, 20), HeifEncodeOptions(lossless = true)),
            ImageKodec.encodeAvif16(KiteBitmap16(5, 4, 2, ShortArray(40) { (it * 1601).toShort() }), HeifEncodeOptions(quality = 60)),
        )
        val hashes = files.map { f -> PictureHash.md5(f).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        assertEquals(EXPECTED, hashes)
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        val stop = IllegalStateException("stop")
        var calls = 0
        val thrown = runCatching {
            ImageKodec.encodeAvif(photo(130, 70), HeifEncodeOptions(effort = 1), cancellationCheck = { if (++calls == 3) throw stop })
        }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
    }

    @Test
    fun hostileArgumentsThrow() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeAvif(photo(2, 2), 101) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeAvif(photo(2, 2), -1) }
    }

    private companion object {
        /** The MD5 of each file, written on the JVM; every other target must write the same bytes. */
        val EXPECTED = listOf(
            "09473960c0121a90380cc50332bc26a4",
            "030363baf405f5cbd0046a387706e3cd",
            "f871e971453e11b1d677a15d55ba106a",
            "0a02a9e27160c4c81846950096e103fd",
            "819aab2cf7089fa993612b293f3268d1",
        )
    }
}
