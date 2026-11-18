package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifChroma
import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.ImageMetadata
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.KiteBitmap16
import io.github.yuroyami.imagekodec.Orientation
import io.github.yuroyami.imagekodec.oriented
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `ImageKodec.encodeHeic` through this module's HEVC encoder and back through `decode` on every platform
 * (#174): lossless files return their input exactly, lossy ones stay within a bound of it, grids, alpha,
 * thumbnails, orientation and metadata read back, the bytes of fixed inputs keep a committed hash, and a
 * cancellation check stops the encode.
 */
class HeicEncodeTest {

    init {
        ImageKodecHeic.install()
    }

    /** A [w] by [h] picture of gradients, a disc and seeded noise: more than 256 colours. */
    internal fun photo(w: Int, h: Int, alpha: Boolean = false, seed: Int = 7): KiteBitmap {
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

    /** Four flat colours in quarters: fewer than 256 colours. */
    private fun quarters(w: Int, h: Int) = KiteBitmap(w, h, IntArray(w * h) { i ->
        val q = (if (i % w < w / 2) 0 else 1) + (if (i / w < h / 2) 0 else 2)
        intArrayOf(0xFFE03020.toInt(), 0xFF20A040.toInt(), 0xFF3050E0.toInt(), 0xFFF0F0F0.toInt())[q]
    })

    private fun gray(b: KiteBitmap) = KiteBitmap(b.width, b.height, IntArray(b.argb.size) { i ->
        val p = b.argb[i]
        (p and 0xFF000000.toInt()) or (((p ushr 8) and 0xFF) * 0x010101)
    })

    private val lossless = HeifEncodeOptions(lossless = true, effort = 1)

    @Test
    fun losslessFilesReturnTheirInput() {
        val cases = listOf(
            "1x1" to photo(1, 1),
            "one row" to photo(17, 1),
            "one column" to photo(1, 13),
            "odd size" to photo(23, 9),
            "odd size with alpha" to photo(23, 9, alpha = true),
            "fully transparent" to KiteBitmap(6, 5, IntArray(30) { 0x00102030 }),
            "four colours" to quarters(12, 10),
            "gray" to gray(photo(21, 11)),
            "gray with alpha" to gray(photo(21, 11, alpha = true)),
        )
        for ((name, bitmap) in cases) {
            val file = ImageKodec.encodeHeic(bitmap, lossless)
            assertEquals(ImageFormat.HEIC, ImageKodec.detect(file), name)
            val info = ImageKodec.probe(file)
            assertEquals(bitmap.width to bitmap.height, info.width to info.height, name)
            assertContentEquals(bitmap.argb, ImageKodec.decode(file).argb, name)
        }
    }

    @Test
    fun aLosslessGridWithAlphaReturnsItsInput() {
        val bitmap = photo(150, 70, alpha = true)
        val file = ImageKodec.encodeHeic(bitmap, HeifEncodeOptions(lossless = true, effort = 1, tileSize = 64))
        assertContentEquals(bitmap.argb, ImageKodec.decode(file).argb)
        assertTrue(ImageKodec.probe(file).hasAlpha)
    }

    @Test
    fun lossyFilesStayNearTheirInput() {
        for (chroma in HeifChroma.entries) for ((w, h) in listOf(1 to 1, 9 to 1, 1 to 7, 31 to 17)) {
            val bitmap = photo(w, h, alpha = true)
            val file = ImageKodec.encodeHeic(bitmap, HeifEncodeOptions(quality = 90, chroma = chroma, effort = 1))
            val out = ImageKodec.decode(file)
            assertEquals(w to h, out.width to out.height, "$chroma ${w}x$h")
            var sum = 0L
            for (i in bitmap.argb.indices) for (c in 0 until 4) {
                val d = ((bitmap.argb[i] ushr (8 * c)) and 0xFF) - ((out.argb[i] ushr (8 * c)) and 0xFF)
                sum += d.toLong() * d
            }
            // At quality 90 the mean squared error stays below 50 (PSNR above 31 dB), even with the chroma of
            // per-pixel noise averaged into 4:2:0 (measured worst: 32, 9 by 1 in 4:2:0).
            val mse = sum / (bitmap.argb.size * 4L)
            assertTrue(mse < 50, "$chroma ${w}x$h: mean squared error $mse")
        }
    }

    @Test
    fun sixteenBitLosslessKeepsWhatTheDepthHolds() {
        var s = 99
        val w = 13
        val h = 6
        val samples = ShortArray(w * h * 4) {
            s = s * 1103515245 + 12345
            (s ushr 12).toShort()
        }
        for (depth in listOf(8, 10, 12)) {
            val options = HeifEncodeOptions(lossless = true, bitDepth = depth, effort = 1)
            val once = ImageKodec.decode16(ImageKodec.encodeHeic16(KiteBitmap16(w, h, 4, samples), options))
            val twice = ImageKodec.decode16(ImageKodec.encodeHeic16(once, options))
            assertContentEquals(once.samples, twice.samples, "$depth bits")
            // decode16 widens each coded sample into the bucket of its 8-bit reading, so a value moves by less than that bucket.
            var worst = 0
            for (i in samples.indices) worst = maxOf(worst, kotlin.math.abs((samples[i].toInt() and 0xFFFF) - (once.samples[i].toInt() and 0xFFFF)))
            assertTrue(worst <= (if (depth == 8) 128 else 80), "$depth bits: a sample moved by $worst")
        }
    }

    @Test
    fun orientationThumbnailAndMetadataReadBack() {
        val bitmap = photo(40, 24, alpha = true)
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"
        // A little-endian TIFF header with one IFD entry: orientation 6.
        val exif = byteArrayOf(0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val icc = ByteArray(16) { it.toByte() }
        for (o in Orientation.entries) {
            val file = ImageKodec.encodeHeic(bitmap, HeifEncodeOptions(lossless = true, effort = 1, thumbnailSize = 16, orientation = o), ImageMetadata(exif, xmp, icc))
            assertEquals(o, ImageKodec.probe(file).orientation, "$o")
            assertContentEquals(bitmap.oriented(o).argb, ImageKodec.decode(file, applyOrientation = true).argb, "$o")
            val thumb = assertNotNull(ImageKodec.decodeThumbnail(file), "$o")
            assertEquals(16 to 9, thumb.width to thumb.height)
            val meta = ImageKodec.readMetadata(file)
            assertEquals(xmp, meta.xmp)
            assertContentEquals(icc, meta.icc)
            // The EXIF orientation follows the irot and imir written.
            assertEquals(o.exifValue, assertNotNull(meta.exif)[18].toInt(), "$o")
        }
    }

    @Test
    fun theBytesOfFixedInputsKeepTheirHash() {
        val files = listOf(
            ImageKodec.encodeHeic(photo(37, 21, alpha = true), 50),
            ImageKodec.encodeHeic(photo(70, 40), HeifEncodeOptions(quality = 70, chroma = HeifChroma.Yuv444, tileSize = 64, thumbnailSize = 20)),
            ImageKodec.encodeHeic(gray(photo(19, 13)), lossless),
        )
        val hashes = files.map { f -> Md5.digest(f).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }
        assertEquals(EXPECTED, hashes)
    }

    @Test
    fun theCancellationCheckStopsTheEncode() {
        val stop = IllegalStateException("stop")
        var calls = 0
        val thrown = runCatching {
            ImageKodec.encodeHeic(photo(150, 70), HeifEncodeOptions(tileSize = 64), cancellationCheck = { if (++calls == 5) throw stop })
        }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
        var all = 0
        ImageKodec.encodeHeic(photo(150, 70), HeifEncodeOptions(tileSize = 64), cancellationCheck = { all++ })
        // A check before each of the 6 tiles and once each CTB row of each tile.
        assertTrue(all >= 12, "$all checks")
    }

    @Test
    fun badArgumentsThrow() {
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(photo(4, 4), 101) }
        assertFailsWith<IllegalArgumentException> { ImageKodec.encodeHeic(photo(4, 4), -1) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(effort = 0) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(bitDepth = 9) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(tileSize = 60) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(tileSize = 100) }
        assertFailsWith<IllegalArgumentException> { HeifEncodeOptions(thumbnailSize = 0) }
    }

    private companion object {
        val EXPECTED = listOf("8c39a2a9525c12a555decc60110597b4", "a3b34841936ac21dc96407e180c4d63e", "69eeb1e9f861f0ca844bc7d24bac8a53")
    }
}
