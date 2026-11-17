package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.PictureHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The intra encoder on every platform: small pictures of each chroma format and depth decode, through this
 * module's decoder, to the reconstruction whose MD5 the encoder wrote in the picture hash SEI, and lie within a
 * PSNR bound of their source; lossless pictures come back exactly; three fixed inputs keep a committed hash
 * of their bytes, so every target writes the same stream; edge sizes, bad arguments and cancellation.
 */
class HevcEncoderTest {

    /** A picture of [w] by [h] in [format] at [depth] bits: gradients, a disc, edges and seeded noise. */
    internal fun picture(w: Int, h: Int, format: Int, depth: Int, seed: Int = 1): HeifPlanes {
        val subW = if (format == 1 || format == 2) 1 else 0
        val subH = if (format == 1) 1 else 0
        val max = (1 shl depth) - 1
        var state = seed * 2654435761.toInt() + 12345
        fun noise(): Int {
            state = state * 1103515245 + 12345
            return ((state ushr 16) and 0x1F) - 16
        }
        val count = if (format == 0) 1 else 3
        val planes = Array(count) { c ->
            val pw = if (c == 0) w else (w + subW) shr subW
            val ph = if (c == 0) h else (h + subH) shr subH
            ShortArray(pw * ph) { i ->
                val x = (i % pw) shl (if (c == 0) 0 else subW)
                val y = (i / pw) shl (if (c == 0) 0 else subH)
                val dx = x - w / 2
                val dy = y - h / 3
                val disc = if (dx * dx + dy * dy < (w * h) / 10) 300 else 0
                val stripes = if ((x / 6 + y / 9) % 3 == 0) 180 else 0
                val v = when (c) {
                    0 -> 200 + x * 500 / maxOf(w, 1) + y * 300 / maxOf(h, 1) + disc + stripes + noise() * 2
                    1 -> 512 + (x - y) * 2 - disc / 2 + noise()
                    else -> 480 + y * 3 - stripes / 3 + noise()
                }
                // The values above are for 10 bits; other depths scale them.
                val scaled = if (depth >= 10) v shl (depth - 10) else v shr (10 - depth)
                scaled.coerceIn(0, max).toShort()
            }
        }
        val strides = IntArray(count) { c -> if (c == 0) w else (w + subW) shr subW }
        return HeifPlanes(w, h, depth, depth, if (format == 0) 1 else subW, if (format == 0) 1 else subH, planes, strides, false)
    }

    /** Decodes [coded] with this module's decoder and checks its hash SEI against the decoded planes. */
    internal fun decode(coded: HevcCoded): DecodedPicture {
        val d = HevcDecoder()
        for (unit in HevcConfig.parse(coded.hvcC).nalUnits) d.decodeNal(unit, 0, unit.size, null)
        for (range in splitSample(coded.sample, 4)) d.decodeNal(coded.sample, range.first, range.last + 1, null)
        val pic = d.finish().single()
        val hash = pic.hash
        if (hash != null) {
            val sps = pic.sps
            for (c in pic.planes.indices) {
                val w = if (c == 0) sps.width else sps.width / sps.subWidthC
                val h = if (c == 0) sps.height else sps.height / sps.subHeightC
                val depth = if (c == 0) sps.bitDepthY else sps.bitDepthC
                val md5 = PictureHash.md5(PictureHash.pictureData(pic.planes[c], 0, pic.strides[c], w, h, depth))
                assertContentEquals(hash.values[c], md5, "MD5 of plane $c")
            }
        }
        return pic
    }

    /** The PSNR in hundredths of a dB of [out] against [src] over the source size, all planes together. */
    internal fun psnr(src: HeifPlanes, out: HeifPlanes): Int {
        var sse = 0L
        var n = 0L
        for (c in src.planes.indices) {
            val w = if (c == 0) src.width else (src.width + src.subX) shr src.subX
            val h = if (c == 0) src.height else (src.height + src.subY) shr src.subY
            for (y in 0 until h) for (x in 0 until w) {
                val d = (src.planes[c][y * src.strides[c] + x].toInt() and 0xFFFF) - (out.planes[c][y * out.strides[c] + x].toInt() and 0xFFFF)
                sse += d.toLong() * d
                n++
            }
        }
        if (sse == 0L) return 10000
        val max = ((1 shl src.bitDepthLuma) - 1).toDouble()
        return (1000.0 * kotlin.math.log10(max * max * n / sse)).toInt()
    }

    private fun assertSame(src: HeifPlanes, out: HeifPlanes, label: String) {
        for (c in src.planes.indices) {
            val w = if (c == 0) src.width else (src.width + src.subX) shr src.subX
            val h = if (c == 0) src.height else (src.height + src.subY) shr src.subY
            for (y in 0 until h) for (x in 0 until w) {
                assertEquals(
                    src.planes[c][y * src.strides[c] + x], out.planes[c][y * out.strides[c] + x],
                    "$label: plane $c sample ($x, $y)",
                )
            }
        }
    }

    @Test
    fun smallPicturesRoundTrip() {
        for (format in 0..3) for (depth in listOf(8, 10, 12)) for ((w, h) in listOf(8 to 8, 1 to 1, 7 to 5, 33 to 17, 64 to 64, 72 to 40, 70 to 1, 1 to 70)) {
            val src = picture(w, h, format, depth)
            val coded = HevcEncoder.encode(src, false, null, HevcEncoderSettings(quality = 70, hash = true), null)
            val out = decode(coded).toHeifPlanes()
            assertEquals(coded.width, out.width)
            val p = psnr(src, out)
            assertTrue(p > 3000, "format $format, $depth bits, $w x $h: PSNR ${p / 100.0} dB")
        }
    }

    @Test
    fun adaptiveQpRoundTrips() {
        for (format in 0..3) for (depth in listOf(8, 12)) for ((w, h) in listOf(7 to 5, 72 to 40, 130 to 70)) for (effort in 1..3) {
            val src = picture(w, h, format, depth, seed = w + effort)
            val coded = HevcEncoder.encode(src, false, null, HevcEncoderSettings(quality = 40 + 10 * effort, effort = effort, hash = true, adaptiveQp = true), null)
            val pic = decode(coded)
            assertTrue(pic.sps.let { true } && psnr(src, pic.toHeifPlanes()) > 3000, "adaptive QP format $format, $depth bits, $w x $h, effort $effort")
        }
    }

    @Test
    fun losslessIsExact() {
        for (format in 0..3) for (depth in listOf(8, 10, 12)) for ((w, h) in listOf(1 to 1, 9 to 7, 40 to 24)) {
            val src = picture(w, h, format, depth)
            for (effort in 1..3) {
                val coded = HevcEncoder.encode(src, false, null, HevcEncoderSettings(lossless = true, effort = effort, hash = true), null)
                assertSame(src, decode(coded).toHeifPlanes(), "lossless format $format, $depth bits, $w x $h, effort $effort")
            }
        }
    }

    @Test
    fun badArgumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { HevcEncoderSettings(quality = 101) }
        assertFailsWith<IllegalArgumentException> { HevcEncoderSettings(effort = 0) }
        val src = picture(8, 8, 1, 8)
        assertFailsWith<IllegalArgumentException> {
            HevcEncoder.encode(HeifPlanes(8, 8, 14, 14, 1, 1, src.planes, src.strides, false), false, null, HevcEncoderSettings(), null)
        }
        assertFailsWith<IllegalArgumentException> {
            HevcEncoder.encode(HeifPlanes(8, 8, 8, 8, 0, 1, src.planes, src.strides, false), false, null, HevcEncoderSettings(), null)
        }
        assertFailsWith<IllegalArgumentException> {
            HevcEncoder.encode(HeifPlanes(64, 8, 8, 8, 1, 1, src.planes, src.strides, false), false, null, HevcEncoderSettings(), null)
        }
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encode(src, false, intArrayOf(1, 2), HevcEncoderSettings(), null) }
    }

    /** FNV-1a of [b], 32 bits. */
    private fun fnv(b: ByteArray): Int {
        var h = 0x811C9DC5.toInt()
        for (v in b) h = (h xor (v.toInt() and 0xFF)) * 0x01000193
        return h
    }

    /**
     * Three fixed inputs give the same bytes on every target: the encoder decides with integers only. The
     * hashes are those the JVM wrote; a change of the encoder's output changes them on purpose.
     */
    @Test
    fun outputIsTheSameOnEveryTarget() {
        val inputs = listOf(
            Triple(picture(48, 40, 1, 8, seed = 3), HevcEncoderSettings(quality = 50, effort = 2, hash = true), EXPECTED[0]),
            Triple(picture(33, 17, 3, 10, seed = 4), HevcEncoderSettings(quality = 80, effort = 1), EXPECTED[1]),
            Triple(picture(40, 24, 0, 12, seed = 5), HevcEncoderSettings(quality = 20, effort = 3, hash = true), EXPECTED[2]),
        )
        val got = inputs.map { (src, settings, _) -> fnv(HevcEncoder.encode(src, true, intArrayOf(1, 13, 6), settings, null).annexB) }
        val lossless = fnv(HevcEncoder.encode(picture(24, 16, 2, 10, seed = 6), false, null, HevcEncoderSettings(lossless = true), null).annexB)
        assertEquals(EXPECTED.toList(), got + lossless, "FNV-1a of the streams: ${(got + lossless).joinToString { "0x" + it.toUInt().toString(16) }}")
    }

    @Test
    fun cancelRunsOncePerCtbRowAndStopsTheEncoder() {
        val src = picture(200, 130, 1, 8)
        var calls = 0
        HevcEncoder.encode(src, false, null, HevcEncoderSettings(effort = 1), { calls++ })
        // 130 rows are three CTB rows of 64.
        assertEquals(3, calls)
        class Stop : RuntimeException()
        var seen = 0
        assertFailsWith<Stop> {
            HevcEncoder.encode(src, false, null, HevcEncoderSettings(effort = 1), { if (++seen == 2) throw Stop() })
        }
        assertEquals(2, seen)
    }

    /** The time of one 256x256 picture at each effort, printed for the report; the JS run gives the JS figure. */
    @Test
    fun encodeTimeOf256() {
        val src = picture(256, 256, 1, 8)
        HevcEncoder.encode(src, false, null, HevcEncoderSettings(), null)
        for (effort in 1..3) {
            val start = TimeSource.Monotonic.markNow()
            val coded = HevcEncoder.encode(src, false, null, HevcEncoderSettings(effort = effort), null)
            println("256x256 8-bit 4:2:0, quality 50, effort $effort: ${coded.sample.size} bytes in ${start.elapsedNow().inWholeMilliseconds} ms")
        }
    }

    /** CRC-32 of [values] as little-endian 32-bit words, as tools/hevc_encoder_tables.py computes it. */
    private fun crc32(values: IntArray): Long {
        var c = 0xFFFFFFFFL
        for (v in values) for (k in 0 until 4) {
            c = c xor ((v ushr (8 * k)).toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        }
        return c xor 0xFFFFFFFFL
    }

    @Test
    fun tablesHoldTheirChecksumsAndMd5IsRfc1321() {
        assertEquals(0xF9E1DE31L, crc32(HevcEncoderTables.BIT_COST))
        assertEquals(0x649E7783L, crc32(HevcEncoderTables.LAMBDA_Q8))
        assertEquals(0xC40E2837L, crc32(HevcEncoderTables.SQRT_LAMBDA_Q8))
        assertEquals(MD5_T_CRC, crc32(HevcEncoderTables.MD5_T))
        // RFC 1321, A.5.
        val abc = Md5.digest("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertEquals("900150983cd24fb0d6963f7d28e17f72", abc)
        val long = ByteArray(1000) { (it * 7).toByte() }
        assertContentEquals(PictureHash.md5(long), Md5.digest(long))
    }

    private companion object {
        const val MD5_T_CRC = 0x5FDBF382L
        val EXPECTED = intArrayOf(0x471d0509, 0x9e61daba.toInt(), 0xb02651e6.toInt(), 0x13fba213)
    }
}
