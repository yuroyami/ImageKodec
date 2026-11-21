package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifDecoder
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A HEIC or AVIF grid reduced while it decodes (#151): `decodeScaled` and `decodeDownscaledTo`
 * must give exactly what the full decode followed by the same scale gives, with chroma upsampled
 * across the seams of the tiles, the clean aperture, the rotation and mirror, alpha and colour
 * conversion.
 */
class HeifGridScaledTest {

    @AfterTest
    fun forgetTheCodec() = HeifCodecs.reset()

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun nclx(p: Int, t: Int, m: Int, full: Boolean) =
        box("colr", "nclx".encodeToByteArray() + be16(p) + be16(t) + be16(m) + byteArrayOf(if (full) 0x80.toByte() else 0)) to false

    /** A clean aperture [w] by [h], off the centre by ([dx], [dy]). */
    private fun clap(w: Int, h: Int, dx: Int, dy: Int) =
        box("clap", be32(w) + be32(1) + be32(h) + be32(1) + be32(dx) + be32(1) + be32(dy) + be32(1)) to true

    private fun irot(angle: Int) = box("irot", byteArrayOf(angle.toByte())) to true
    private fun imir(axis: Int) = box("imir", byteArrayOf(axis.toByte())) to true

    /**
     * Every way to read [file] smaller: `decodeDownscaledTo` at a quarter, a third and one pixel
     * short of the full size, and `decodeScaled` into the same boxes, each equal to the full decode
     * scaled. The band path itself must take the file, so a fallback to the full decode cannot pass.
     */
    private fun assertReducesExactly(file: ByteArray, label: String, applyOrientation: Boolean = false, colorTarget: ColorTarget = ColorTarget.Source) {
        assertTrue(AvifDecoder.reducesWhileDecoding(file), "$label: the band path declines")
        val full = ImageKodec.decode(file, applyOrientation, colorTarget)
        val w = full.width
        val h = full.height
        for ((tw, th) in listOf(w / 4 to h / 4, w / 3 to h / 3, w - 1 to h - 1, w to h - 1, w - 1 to h)) {
            val want = full.downscaledTo(tw, th)
            val got = ImageKodec.decodeDownscaledTo(file, tw, th, applyOrientation, colorTarget)
            assertEquals(tw to th, got.width to got.height, "$label at ${tw}x$th")
            assertContentEquals(want.argb, got.argb, "$label at ${tw}x$th")
            val scaled = ImageKodec.decodeScaled(file, tw, th, applyOrientation, colorTarget)
            assertContentEquals(full.scaled(tw, th).argb, scaled.argb, "$label scaled into ${tw}x$th")
        }
        if (colorTarget == ColorTarget.Source) {
            val info = ImageKodec.probe(file)
            val direct = assertNotNull(AvifDecoder.downscaledGrid(file, info.width / 4, info.height / 4, wide = false, grayOnly = false, cancel = null) { b, _ -> b }, label)
            assertContentEquals(ImageKodec.decode(file).downscaledTo(info.width / 4, info.height / 4).argb, direct.argb, "$label direct")
        }
    }

    @Test
    fun aGridOfEveryChromaFormatAndDepthReducesExactly() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        for (format in 0..3) for (depth in listOf(8, 10, 12)) {
            val file = HashTileCodec.grid(format = format, depth = depth, fullRange = depth == 10)
            assertReducesExactly(file, "format $format, $depth bits")
        }
    }

    @Test
    fun theCleanApertureRotationAndMirrorReduceExactly() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val cropped = HashTileCodec.grid(format = 1, extra = listOf(clap(57, 45, 1, -1)))
        assertEquals(57 to 45, ImageKodec.probe(cropped).let { it.width to it.height })
        assertReducesExactly(cropped, "clap")
        for (angle in 0..3) for (mirror in listOf(-1, 0, 1)) {
            val extra = listOf(clap(57, 45, -2, 1), irot(angle)) + if (mirror >= 0) listOf(imir(mirror)) else emptyList()
            val file = HashTileCodec.grid(format = 2, extra = extra)
            assertReducesExactly(file, "irot $angle, imir $mirror", applyOrientation = true)
            assertReducesExactly(file, "irot $angle, imir $mirror, as stored", applyOrientation = false)
        }
    }

    @Test
    fun alphaStraightAndPremultipliedReducesExactly() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        for (premultiplied in listOf(false, true)) for (alphaFormat in listOf(0, 1)) {
            val grid = HashTileCodec.grid(format = 1, depth = 10, alpha = HashTileCodec.AlphaKind.GRID, alphaFormat = alphaFormat, premultiplied = premultiplied)
            assertTrue(ImageKodec.probe(grid).hasAlpha)
            assertReducesExactly(grid, "alpha grid of format $alphaFormat, premultiplied $premultiplied")
            val single = HashTileCodec.grid(format = 3, alpha = HashTileCodec.AlphaKind.SINGLE, alphaFormat = alphaFormat, premultiplied = premultiplied)
            assertReducesExactly(single, "one alpha item of format $alphaFormat, premultiplied $premultiplied")
        }
    }

    @Test
    fun colourConvertsBeforeTheScaleAsTheFullDecodeConverts() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        // Display P3 at 8 bits converts at 8 bits; BT.2020 with PQ at 10 bits converts at 16.
        for ((depth, colr) in listOf(8 to nclx(12, 13, 1, true), 10 to nclx(9, 16, 9, false), 12 to nclx(9, 1, 9, true))) {
            val file = HashTileCodec.grid(format = 1, depth = depth, extra = listOf(colr, clap(57, 45, 0, 0), irot(1)))
            val source = ImageKodec.decode(file)
            val converted = ImageKodec.decode(file, colorTarget = ColorTarget.Srgb())
            assertFalse(source.argb.contentEquals(converted.argb), "$depth bits: the conversion changes nothing")
            assertReducesExactly(file, "sRGB from $depth bits", applyOrientation = true, colorTarget = ColorTarget.Srgb())
        }
        val withAlpha = HashTileCodec.grid(format = 0, depth = 10, extra = listOf(nclx(9, 16, 9, false)), alpha = HashTileCodec.AlphaKind.GRID, premultiplied = true)
        assertReducesExactly(withAlpha, "sRGB with premultiplied alpha", colorTarget = ColorTarget.Srgb())
    }

    @Test
    fun anAv1GridReducesExactly() {
        assertReducesExactly(AvifFixtures.grid, "the libavif grid")
        // Sixteen tiles of the 24 by 16 card, 4:2:0 at 8 bits, cut to 91 by 61.
        val card = AvifContainer.parse(AvifFixtures.plain)
        val item = card.items.getValue(card.primary!!)
        val config = item.property<AvifProperty.Av1Config>()!!
        val flags = (if (config.highBitdepth) 0x40 else 0) or (if (config.twelveBit) 0x20 else 0) or (if (config.monochrome) 0x10 else 0) or
            (config.subX shl 3) or (config.subY shl 2)
        val av1C = box("av1C", byteArrayOf(0x81.toByte(), (config.profile shl 5).toByte(), flags.toByte(), 0) + config.configObus) to true
        val ispe = box("ispe", ByteArray(4) + be32(24) + be32(16)) to false
        val file = HeifBuilder().apply {
            add(1, "grid", byteArrayOf(0, 0, 3, 3) + be16(91) + be16(61), listOf(box("ispe", ByteArray(4) + be32(91) + be32(61)) to false))
            for (id in 2..17) add(id, "av01", card.itemData(item), listOf(av1C, ispe, nclx(1, 13, 6, false)), hidden = true)
            references += Triple("dimg", 1L, LongArray(16) { it + 2L })
        }.build()
        assertReducesExactly(file, "a 4 by 4 AV1 grid")
    }

    @Test
    fun tilesTallerThanAGridOfOneRowAreCutToIt() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        // Tiles of 200 rows under a grid of 9: the window must hold 9 rows of them, not 200.
        val file = HashTileCodec.grid(format = 1, rows = 1, columns = 3, tileHeight = 200, height = 9, alpha = HashTileCodec.AlphaKind.GRID)
        assertEquals(49 to 9, ImageKodec.probe(file).let { it.width to it.height })
        assertReducesExactly(file, "a grid of one row of tall tiles")
    }

    @Test
    fun theCancellationCheckRunsOnceATileAtLeast() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val file = HashTileCodec.grid(format = 1, alpha = HashTileCodec.AlphaKind.GRID)
        var checks = 0
        ImageKodec.decodeScaled(file, 16, 16, cancellationCheck = { checks++ })
        assertTrue(checks >= 32, "$checks checks for 32 tiles")
        val stop = IllegalStateException("stop")
        val thrown = runCatching { ImageKodec.decodeDownscaledTo(file, 16, 12, cancellationCheck = { throw stop }) }.exceptionOrNull()
        assertTrue(thrown === stop, "$thrown")
    }

    @Test
    fun otherHeifImagesKeepTheFullPath() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val single = HeifBuilder().apply { add(1, HashTileCodec.itemType, be16(1), HashTileCodec.properties(40, 30, 1, 8, false)) }.build()
        assertFalse(AvifDecoder.reducesWhileDecoding(single))
        assertContentEquals(ImageKodec.decode(single).downscaledTo(13, 9).argb, ImageKodec.decodeDownscaledTo(single, 13, 9).argb)
        assertFalse(AvifDecoder.reducesWhileDecoding(AvifFixtures.plain))
        // The full size is not a reduction: the grid decodes as decode gives it.
        val grid = HashTileCodec.grid(format = 1)
        val full = ImageKodec.decode(grid)
        assertContentEquals(full.argb, ImageKodec.decodeDownscaledTo(grid, full.width, full.height).argb)
        assertTrue(runCatching { ImageKodec.decodeDownscaledTo(grid, full.width + 1, full.height) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aRotationOrMirrorAfterTheReductionMovesTheSamePixels() {
        // decodeScaled reduces the stored picture and then turns it, where the full path turns it first.
        val w = 37
        val h = 23
        var s = 12345
        val source = KiteBitmap(w, h, IntArray(w * h) {
            s = s * 1103515245 + 12345
            val a = if ((s ushr 7) % 5 == 0) 0 else (s ushr 24) and 0xFF
            (a shl 24) or ((s ushr 3) and 0xFFFFFF)
        })
        for (o in Orientation.entries) for ((tw, th) in listOf(9 to 5, 12 to 7, 36 to 22, 37 to 11)) {
            val swap = o.swapsAxes
            val turnedFirst = source.oriented(o).downscaledTo(if (swap) th else tw, if (swap) tw else th)
            val scaledFirst = source.downscaledTo(tw, th).oriented(o)
            assertContentEquals(turnedFirst.argb, scaledFirst.argb, "$o at ${tw}x$th")
        }
    }

    @Test
    fun theBandScalerEqualsDownscaledTo() {
        val w = 29
        val h = 31
        val source = KiteBitmap(w, h, IntArray(w * h) { i -> if (i % 7 == 0) 0x00123456 else (((i * 37) and 0xFF) shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) })
        for ((tw, th) in listOf(1 to 1, 7 to 3, 14 to 15, 28 to 30, 29 to 30, 28 to 31, 10 to 31)) {
            val scaler = BandScaler(w, h, tw, th)
            for (y in 0 until h) scaler.add(source.argb, y * w)
            assertContentEquals(source.downscaledTo(tw, th).argb, scaler.result().argb, "${tw}x$th")
        }
    }
}

/**
 * `tsg1` items whose `tsgC` gives the size, chroma format, depth, range and matrix, and whose one
 * sample is a tile number. Every sample is a hash of its position, plane and that number, so a
 * seam that reads the wrong neighbour shows. The planes have rows wider than the picture.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal object HashTileCodec : HeifCodec {
    override val itemType = "tsg1"
    override val sampleEntryTypes = setOf("tsg1")
    override val configurationType = "tsgC"

    private class Config(val w: Int, val h: Int, val format: Int, val depth: Int, val fullRange: Boolean, val matrix: Int)

    private fun config(c: ByteArray?): Config {
        val b = c!!.map { it.toInt() and 0xFF }
        return Config((b[0] shl 8) or b[1], (b[2] shl 8) or b[3], b[4], b[5], b[6] != 0, b[7])
    }

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        val c = config(configuration)
        return HeifCodecInfo(c.w, c.h, c.depth, c.depth, c.format, 1, 13, c.matrix, c.fullRange)
    }

    override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
        override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
            val c = config(configuration)
            val id = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
            val subX = if (c.format == 1 || c.format == 2) 1 else 0
            val subY = if (c.format == 1) 1 else 0
            val count = if (c.format == 0) 1 else 3
            val mask = (1 shl c.depth) - 1
            val strides = IntArray(count) { p -> ((c.w + (if (p > 0) subX else 0)) shr (if (p > 0) subX else 0)) + 3 }
            val planes = Array(count) { p ->
                val pw = if (p > 0) (c.w + subX) shr subX else c.w
                val ph = if (p > 0) (c.h + subY) shr subY else c.h
                ShortArray(strides[p] * ph).also { plane ->
                    for (y in 0 until ph) for (x in 0 until pw) {
                        var v = (x * 0x9E3779B1.toInt()) xor (y * 0x85EBCA77.toInt()) xor (id * 0xC2B2AE3D.toInt()) xor (p * 0x27D4EB2F)
                        v = (v xor (v ushr 15)) * 0x2C1B3C6D
                        v = v xor (v ushr 12)
                        plane[y * strides[p] + x] = (v and mask).toShort()
                    }
                }
            }
            return HeifPlanes(c.w, c.h, c.depth, c.depth, subX, subY, planes, strides, c.fullRange)
        }
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false

    /** The properties of a [w] by [h] tile: chroma [format] 0 to 3 (4:0:0, 4:2:0, 4:2:2, 4:4:4), [depth] bits. */
    fun properties(w: Int, h: Int, format: Int, depth: Int, fullRange: Boolean, matrix: Int = 6) = listOf(
        box(configurationType, be16(w) + be16(h) + byteArrayOf(format.toByte(), depth.toByte(), (if (fullRange) 1 else 0).toByte(), matrix.toByte())) to true,
        ispe(w, h),
    )

    enum class AlphaKind { NONE, GRID, SINGLE }

    /**
     * A [rows] by [columns] grid of [tileWidth] by [tileHeight] tiles, its last column and row cut
     * short so the picture is of odd size, with [extra] properties on the grid item and, as
     * [alpha] asks, an alpha grid of the same layout or one alpha item of the picture's size.
     */
    fun grid(
        format: Int,
        depth: Int = 8,
        fullRange: Boolean = false,
        rows: Int = 4,
        columns: Int = 4,
        tileWidth: Int = 18,
        tileHeight: Int = 14,
        extra: List<Pair<ByteArray, Boolean>> = emptyList(),
        alpha: AlphaKind = AlphaKind.NONE,
        alphaFormat: Int = 0,
        premultiplied: Boolean = false,
        width: Int = columns * tileWidth - 5,
        height: Int = rows * tileHeight - 3,
    ): ByteArray {
        val count = rows * columns
        val auxC = box("auxC", ByteArray(4) + "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".encodeToByteArray() + byteArrayOf(0)) to false
        fun gridData() = byteArrayOf(0, if (width > 65535 || height > 65535) 1 else 0, (rows - 1).toByte(), (columns - 1).toByte()) + be16(width) + be16(height)
        return HeifBuilder().apply {
            add(1, "grid", gridData(), listOf(ispe(width, height)) + extra)
            for (i in 0 until count) add(2 + i, itemType, be16(i), properties(tileWidth, tileHeight, format, depth, fullRange), hidden = true)
            references += Triple("dimg", 1L, LongArray(count) { it + 2L })
            val alphaId = 2L + count
            when (alpha) {
                AlphaKind.NONE -> {}
                AlphaKind.GRID -> {
                    add(alphaId.toInt(), "grid", gridData(), listOf(ispe(width, height), auxC), hidden = true)
                    for (i in 0 until count) {
                        add(alphaId.toInt() + 1 + i, itemType, be16(1000 + i), properties(tileWidth, tileHeight, alphaFormat, depth, fullRange = depth == 8), hidden = true)
                    }
                    references += Triple("dimg", alphaId, LongArray(count) { alphaId + 1 + it })
                }
                AlphaKind.SINGLE -> add(alphaId.toInt(), itemType, be16(999), properties(width, height, alphaFormat, depth, fullRange = false) + auxC, hidden = true)
            }
            if (alpha != AlphaKind.NONE) {
                references += Triple("auxl", alphaId, longArrayOf(1))
                if (premultiplied) references += Triple("prem", 1L, longArrayOf(alphaId))
            }
        }.build()
    }
}
