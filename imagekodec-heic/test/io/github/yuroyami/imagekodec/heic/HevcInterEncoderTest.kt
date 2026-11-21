package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.PictureHash
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sequence encoder (#177) on every platform: moving pictures of each chroma format and depth, coded as IPPP
 * and as B pyramids, decode through this module's decoder in output order, every picture to the reconstruction
 * whose MD5 the encoder wrote, within a PSNR bound of its source; lossless sequences come back exactly; the
 * timing a track needs adds up; fixed inputs keep a committed hash of their bytes; bad arguments and cancellation.
 */
class HevcInterEncoderTest {

    private val still = HevcEncoderTest()

    /**
     * [count] frames of [w] by [h]: a window that pans over a larger picture by ([dx], [dy]) samples a frame, two
     * samples at a time in each direction so chroma moves by whole samples, with a disc that moves the other way.
     */
    internal fun frames(w: Int, h: Int, format: Int, depth: Int, count: Int, dx: Int = 2, dy: Int = 2, seed: Int = 1): List<HeifPlanes> {
        val big = still.picture(w + count * dx + 16, h + count * dy + 16, format, depth, seed)
        val subW = if (format == 1 || format == 2) 1 else 0
        val subH = if (format == 1) 1 else 0
        val max = (1 shl depth) - 1
        return List(count) { f ->
            val ox = f * dx
            val oy = f * dy
            val planes = Array(big.planes.size) { c ->
                val sw = if (c == 0) 0 else subW
                val sh = if (c == 0) 0 else subH
                val pw = (w + sw) shr sw
                val ph = (h + sh) shr sh
                ShortArray(pw * ph) { i ->
                    val x = i % pw
                    val y = i / pw
                    var v = big.planes[c][((oy shr sh) + y) * big.strides[c] + (ox shr sw) + x].toInt() and 0xFFFF
                    // A disc moving against the pan.
                    val cx = (w - 1 - f * 3) shr sw
                    val cy = (h / 2) shr sh
                    val r = maxOf(2, minOf(w, h) / 6) shr sw
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) v = if (c == 0) max * 3 / 4 else max / 3
                    v.coerceIn(0, max).toShort()
                }
            }
            val strides = IntArray(planes.size) { c -> if (c == 0) w else (w + subW) shr subW }
            HeifPlanes(w, h, depth, depth, big.subX, big.subY, planes, strides, false)
        }
    }

    /**
     * Decodes [seq] with this module's decoder in output order, each picture checked against the MD5 of its hash
     * SEI, with the tool counts of [HevcStats] in [stats].
     */
    internal fun decode(seq: HevcSequence, stats: IntArray? = null): List<DecodedPicture> {
        val d = HevcDecoder(keepOutput = true, stats = stats)
        for (unit in HevcConfig.parse(seq.hvcC).nalUnits) d.decodeNal(unit, 0, unit.size, null)
        val out = ArrayList<DecodedPicture>()
        for (s in seq.samples) {
            for (range in splitSample(s.data, 4)) d.decodeNal(s.data, range.first, range.last + 1, null)
            out += d.takeOutput()
        }
        out += d.flush()
        for ((k, pic) in out.withIndex()) {
            val hash = pic.hash ?: continue
            val sps = pic.sps
            for (c in pic.planes.indices) {
                val w = if (c == 0) sps.width else sps.width / sps.subWidthC
                val h = if (c == 0) sps.height else sps.height / sps.subHeightC
                val depth = if (c == 0) sps.bitDepthY else sps.bitDepthC
                val md5 = PictureHash.md5(PictureHash.pictureData(pic.planes[c], 0, pic.strides[c], w, h, depth))
                assertContentEquals(hash.values[c], md5, "MD5 of plane $c of picture $k")
            }
        }
        return out
    }

    private fun durations(n: Int) = LongArray(n) { 40L + (it % 3) * 10 }

    @Test
    fun sequencesRoundTripInOutputOrder() {
        for (format in 0..3) for (depth in listOf(8, 10)) for ((w, h) in listOf(64 to 64, 72 to 40, 33 to 17)) for (gop in listOf(1, 4)) {
            val src = frames(w, h, format, depth, 6)
            val label = "format $format, $depth bits, $w x $h, group $gop"
            val seq = HevcEncoder.encodeSequence(
                src, durations(src.size), false, null, HevcEncoderSettings(quality = 60, effort = 1 + (w + gop) % 3, hash = true),
                HevcSequenceSettings(gopSize = gop), null,
            )
            val out = decode(seq)
            assertEquals(src.size, out.size, label)
            for ((i, pic) in out.withIndex()) {
                val p = still.psnr(src[i], pic.toHeifPlanes())
                assertTrue(p > 3000, "$label: frame $i PSNR ${p / 100.0} dB")
            }
        }
    }

    @Test
    fun losslessSequencesAreExact() {
        for (format in 0..3) for (depth in listOf(8, 12)) for (gop in listOf(1, 3)) {
            val src = frames(40, 24, format, depth, 5)
            val seq = HevcEncoder.encodeSequence(
                src, durations(src.size), false, null, HevcEncoderSettings(lossless = true, effort = 1 + (format + gop) % 3, hash = true),
                HevcSequenceSettings(gopSize = gop), null,
            )
            val out = decode(seq)
            for ((i, pic) in out.withIndex()) still.assertSame(src[i], pic.toHeifPlanes(), "lossless format $format, $depth bits, group $gop, frame $i")
        }
    }

    @Test
    fun pyramidsAndPeriodsDecode() {
        // Groups of every size up to 8 and periodic IDR pictures, over frame counts that leave a short last group.
        val src = frames(48, 32, 1, 8, 11)
        for (gop in 1..8) for (period in listOf(0, 5)) {
            val stats = IntArray(HevcStats.COUNT)
            val seq = HevcEncoder.encodeSequence(src, durations(src.size), false, null, HevcEncoderSettings(quality = 50, effort = 1, hash = true), HevcSequenceSettings(gopSize = gop, irapPeriod = period), null)
            val out = decode(seq, stats)
            assertEquals(src.size, out.size, "group $gop, period $period")
            for ((i, pic) in out.withIndex()) assertTrue(still.psnr(src[i], pic.toHeifPlanes()) > 3000, "group $gop, period $period, frame $i")
            val idr = if (period == 0) 1 else (src.size + period - 1) / period
            assertEquals(idr, stats[HevcStats.I_SLICES], "group $gop, period $period: I slices")
            assertEquals(idr, seq.samples.count { it.sync }, "group $gop, period $period: sync samples")
            if (gop == 1) assertEquals(0, stats[HevcStats.B_SLICES], "IPPP has no B slices")
            if (gop >= 2) assertTrue(stats[HevcStats.B_SLICES] > 0, "group $gop codes B slices")
        }
    }

    @Test
    fun plansOrderAndReferTheirPictures() {
        val ippp = GopPlanner.plan(5, 1, 0, 1)
        assertEquals(listOf(0, 1, 2, 3, 4), ippp.pictures.map { it.frame })
        assertEquals(0, ippp.maxNumReorder)
        // The picture before and the current one.
        assertEquals(1, ippp.maxDecPicBufferingMinus1)
        val pyramid = GopPlanner.plan(9, 4, 0, 1)
        assertEquals(listOf(0, 4, 2, 1, 3, 8, 6, 5, 7), pyramid.pictures.map { it.frame })
        assertEquals(listOf(SliceType.I, SliceType.P, SliceType.B, SliceType.B, SliceType.B, SliceType.P, SliceType.B, SliceType.B, SliceType.B), pyramid.pictures.map { it.sliceType })
        // Frame 1 follows frames 4 and 2 in decoding order and precedes them in output order.
        assertEquals(2, pyramid.maxNumReorder)
        val b1 = pyramid.pictures[3]
        assertEquals(listOf(0), b1.refL0.toList())
        assertEquals(listOf(2), b1.refL1.toList())
        // Frame 1 keeps frame 4 for frame 3, unused by itself.
        assertEquals(listOf(-1), b1.rps!!.deltaPocS0.toList())
        assertEquals(listOf(1, 3), b1.rps!!.deltaPocS1.toList())
        assertEquals(listOf(true, false), b1.rps!!.usedS1.toList())
        val periods = GopPlanner.plan(7, 2, 3, 2)
        assertEquals(listOf(0, 2, 1, 3, 5, 4, 6), periods.pictures.map { it.frame })
        assertEquals(listOf(true, false, false, true, false, false, true), periods.pictures.map { it.idr })
        assertEquals(listOf(0, 2, 1, 0, 2, 1, 0), periods.pictures.map { it.poc })
    }

    @Test
    fun timingAddsUp() {
        val src = frames(16, 16, 1, 8, 9)
        val d = LongArray(src.size) { 10L * (it + 1) }
        for (gop in listOf(1, 4, 8)) {
            val seq = HevcEncoder.encodeSequence(src, d, false, null, HevcEncoderSettings(effort = 1), HevcSequenceSettings(gopSize = gop), null)
            assertEquals(d.sum(), seq.samples.sumOf { it.duration }, "group $gop: the decoding durations add up to the presentation")
            var dts = 0L
            val pts = LongArray(src.size)
            for (i in 1 until src.size) pts[i] = pts[i - 1] + d[i - 1]
            for (s in seq.samples) {
                assertTrue(s.compositionOffset >= 0, "group $gop: a negative composition offset")
                assertEquals(pts[s.frame] + seq.presentationOffset, dts + s.compositionOffset, "group $gop: frame ${s.frame}'s composition time")
                dts += s.duration
            }
            assertEquals(gop > 1, seq.reordered, "group $gop")
            if (gop == 1) assertEquals(0L, seq.presentationOffset, "IPPP shows its first frame at once")
            assertEquals((0 until src.size).toList(), seq.samples.map { it.frame }.sorted())
        }
    }

    @Test
    fun badArgumentsAreRefused() {
        val src = frames(16, 16, 1, 8, 3)
        val s = HevcEncoderSettings()
        val q = HevcSequenceSettings()
        assertFailsWith<IllegalArgumentException> { HevcSequenceSettings(gopSize = 0) }
        assertFailsWith<IllegalArgumentException> { HevcSequenceSettings(gopSize = 17) }
        assertFailsWith<IllegalArgumentException> { HevcSequenceSettings(irapPeriod = -1) }
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encodeSequence(emptyList(), LongArray(0), false, null, s, q, null) }
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encodeSequence(src, LongArray(2) { 1 }, false, null, s, q, null) }
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encodeSequence(src, longArrayOf(1, 0, 1), false, null, s, q, null) }
        val other = frames(24, 16, 1, 8, 1)[0]
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encodeSequence(src + other, LongArray(4) { 1 }, false, null, s, q, null) }
        val deeper = frames(16, 16, 1, 10, 1)[0]
        assertFailsWith<IllegalArgumentException> { HevcEncoder.encodeSequence(src + deeper, LongArray(4) { 1 }, false, null, s, q, null) }
    }

    @Test
    fun cancelRunsOncePerCtbRowOfEveryPicture() {
        val src = frames(100, 70, 1, 8, 4)
        var calls = 0
        HevcEncoder.encodeSequence(src, LongArray(4) { 1 }, false, null, HevcEncoderSettings(effort = 1), HevcSequenceSettings(), { calls++ })
        // 70 rows are two CTB rows of 64, in each of four pictures.
        assertEquals(8, calls)
        class Stop : RuntimeException()
        var seen = 0
        assertFailsWith<Stop> {
            HevcEncoder.encodeSequence(src, LongArray(4) { 1 }, false, null, HevcEncoderSettings(effort = 1), HevcSequenceSettings(), { if (++seen == 5) throw Stop() })
        }
        assertEquals(5, seen)
    }

    /** FNV-1a of [b], 32 bits. */
    private fun fnv(b: ByteArray): Int {
        var h = 0x811C9DC5.toInt()
        for (v in b) h = (h xor (v.toInt() and 0xFF)) * 0x01000193
        return h
    }

    /**
     * Fixed sequences give the same bytes on every target: the encoder decides with integers only. The hashes are
     * those the JVM wrote; a change of the encoder's output changes them on purpose.
     */
    @Test
    fun outputIsTheSameOnEveryTarget() {
        val got = listOf(
            HevcEncoder.encodeSequence(frames(48, 40, 1, 8, 5, seed = 3), durations(5), false, null, HevcEncoderSettings(quality = 50, effort = 2, hash = true), HevcSequenceSettings(gopSize = 4), null),
            HevcEncoder.encodeSequence(frames(33, 17, 3, 10, 4, seed = 4), durations(4), true, intArrayOf(1, 13, 6), HevcEncoderSettings(quality = 70, effort = 1), HevcSequenceSettings(gopSize = 1), null),
            HevcEncoder.encodeSequence(frames(40, 24, 0, 8, 4, seed = 5), durations(4), false, null, HevcEncoderSettings(quality = 30, effort = 3), HevcSequenceSettings(gopSize = 2, irapPeriod = 3), null),
            HevcEncoder.encodeSequence(frames(24, 16, 2, 8, 3, seed = 6), durations(3), false, null, HevcEncoderSettings(lossless = true), HevcSequenceSettings(), null),
        ).map { fnv(it.annexB) }
        assertEquals(EXPECTED.toList(), got, "FNV-1a of the streams: ${got.joinToString { "0x" + it.toUInt().toString(16) }}")
    }

    private companion object {
        val EXPECTED = intArrayOf(0xb01c72a6.toInt(), 0x8e668c34.toInt(), 0x6d26ad11, 0xb351b323.toInt())
    }
}
