package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compression of the sequence encoder (#177) against x265 (`ffmpeg -c:v libx265 -preset medium`, its own GOP and
 * B frames) on animated scenes in 8-bit 4:2:0: the bytes of the slice data of the whole sequence at an equal mean
 * SSIMULACRA 2 score over its frames, and encode times. Both encoders get the same YCbCr frames, and every decode
 * goes back to RGB through the same conversion before the score. x265 runs a sweep of constant rate factors, and its
 * size at this encoder's score comes from interpolating log size between the two nearest points. Prints a table,
 * and holds the mean ratio to a loose bound that catches a regression.
 *
 * It takes minutes, so it runs only with IMAGEKODEC_HEVC_INTER_BENCH=1.
 */
class HevcInterBenchmarkTest {

    private val frames = 24

    /**
     * The animated scenes: a camera that pans over a photo-like scene by 1.5 and 0.5 samples a frame; a still city
     * with two objects that cross it; and a landscape pan with sensor grain that changes every frame, which no
     * motion predicts.
     */
    private fun scenes(): List<Pair<String, List<KiteBitmap>>> {
        val w = 384
        val h = 256
        fun crop(b: KiteBitmap, ox: Int, oy: Int) = KiteBitmap(w, h, IntArray(w * h) { b.argb[(oy + it / w) * b.width + ox + it % w] })
        val land = PhotoScenes.landscape(w + 48, h + 24).bitmap
        val pan = List(frames) { f -> crop(land, (f * 3) / 2, f / 2) }
        val city = PhotoScenes.city(w, h).bitmap
        val objects = List(frames) { f ->
            val px = city.argb.copyOf()
            fun box(x0: Int, y0: Int, bw: Int, bh: Int, colour: Int) {
                for (y in maxOf(0, y0) until minOf(h, y0 + bh)) for (x in maxOf(0, x0) until minOf(w, x0 + bw)) {
                    // A shaded body, so its inside is not flat.
                    val shade = ((x - x0) * 40 / bw) - 20
                    val r = ((colour shr 16) and 0xFF) + shade
                    val g = ((colour shr 8) and 0xFF) + shade
                    val b = (colour and 0xFF) + shade
                    px[y * w + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
            }
            box(-60 + f * 7, 150, 70, 34, 0xC03020)
            box(w - f * 5, 60, 40, 60, 0x2060B0)
            KiteBitmap(w, h, px)
        }
        val random = Random(77)
        val grainy = pan.map { b ->
            KiteBitmap(w, h, IntArray(w * h) { i ->
                val n = random.nextInt(-3, 4)
                val p = b.argb[i]
                val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
                val bl = ((p and 0xFF) + n).coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            })
        }
        return listOf("pan" to pan, "objects" to objects, "grain" to grainy)
    }

    /** Full range BT.601 4:2:0, in integers; the chroma of each 2x2 square is its mean. */
    private fun toYuv(b: KiteBitmap): HeifPlanes {
        val w = b.width
        val h = b.height
        val y = ShortArray(w * h)
        val cbFull = IntArray(w * h)
        val crFull = IntArray(w * h)
        for (i in 0 until w * h) {
            val p = b.argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            y[i] = ((77 * r + 150 * g + 29 * bl + 128) shr 8).coerceIn(0, 255).toShort()
            cbFull[i] = (((-43 * r - 85 * g + 128 * bl + 128) shr 8) + 128).coerceIn(0, 255)
            crFull[i] = (((128 * r - 107 * g - 21 * bl + 128) shr 8) + 128).coerceIn(0, 255)
        }
        val cw = w / 2
        val ch = h / 2
        fun sub(f: IntArray) = ShortArray(cw * ch) { i ->
            val x = (i % cw) * 2
            val yy = (i / cw) * 2
            ((f[yy * w + x] + f[yy * w + x + 1] + f[(yy + 1) * w + x] + f[(yy + 1) * w + x + 1] + 2) shr 2).toShort()
        }
        return HeifPlanes(w, h, 8, 8, 1, 1, arrayOf(y, sub(cbFull), sub(crFull)), intArrayOf(w, cw, cw), true)
    }

    /** The inverse of [toYuv], each chroma sample repeated over its square. */
    private fun toRgb(p: HeifPlanes, w: Int, h: Int): KiteBitmap {
        val px = IntArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val yv = p.planes[0][j * p.strides[0] + i].toInt() and 0xFF
            val cb = (p.planes[1][(j shr 1) * p.strides[1] + (i shr 1)].toInt() and 0xFF) - 128
            val cr = (p.planes[2][(j shr 1) * p.strides[2] + (i shr 1)].toInt() and 0xFF) - 128
            val r = yv + ((359 * cr + 128) shr 8)
            val g = yv - ((88 * cb + 183 * cr + 128) shr 8)
            val b = yv + ((454 * cb + 128) shr 8)
            px[j * w + i] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        return KiteBitmap(w, h, px)
    }

    /** Every picture of an Annex B [stream], in output order, through this module's decoder. */
    private fun decodeAnnexB(stream: ByteArray): List<HeifPlanes> {
        val d = HevcDecoder(keepOutput = true)
        var i = 0
        val starts = ArrayList<Int>()
        while (i + 3 <= stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<HeifPlanes>()
        for ((k, s) in starts.withIndex()) {
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else stream.size
            while (end > s && stream[end - 1].toInt() == 0) end--
            d.decodeNal(stream, s, end, null)
            out += d.takeOutput().map { it.toHeifPlanes() }
        }
        out += d.flush().map { it.toHeifPlanes() }
        return out
    }

    /** The bytes of the VCL NAL units of an Annex B [stream]: the slice data, without parameter sets or SEI. */
    private fun vclBytes(stream: ByteArray): Int {
        var total = 0
        var i = 0
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && ((stream[start].toInt() shr 1) and 0x3F) < 32) total += end - start
        }
        while (i + 3 <= stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                var end = i
                while (end > 0 && stream[end - 1].toInt() == 0 && end > start) end--
                close(end)
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        close(stream.size)
        return total
    }

    private class Point(val bytes: Int, val score: Double, val millis: Double)

    /** The mean SSIMULACRA 2 score of the decoded [stream] against [source]. */
    private fun score(stream: ByteArray, source: List<KiteBitmap>): Double {
        val out = decodeAnnexB(stream)
        check(out.size == source.size) { "${out.size} pictures decoded of ${source.size}" }
        return source.indices.sumOf { ImageKodec.ssimulacra2(source[it], toRgb(out[it], source[it].width, source[it].height)) } / source.size
    }

    private fun x265(dir: File, yuv: File, w: Int, h: Int, crf: Int, tune: List<String>, source: List<KiteBitmap>): Point {
        val out = File(dir, "x265.hevc")
        val start = System.nanoTime()
        val proc = ProcessBuilder(
            listOf(
                "timeout", "-s", "KILL", "600", Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${w}x$h", "-r", "25", "-i", yuv.path,
                "-c:v", "libx265", "-preset", "medium",
            ) + tune + listOf("-x265-params", "crf=$crf:log-level=none", "-f", "hevc", "-y", out.path),
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().decodeToString()
        check(proc.waitFor() == 0) { log }
        val millis = (System.nanoTime() - start) / 1e6
        val stream = out.readBytes()
        return Point(vclBytes(stream), score(stream, source), millis)
    }

    /** The size of [curve] at [score], interpolating log size between the points around it, or null outside it. */
    private fun sizeAt(curve: List<Point>, score: Double): Double? {
        val sorted = curve.sortedBy { it.score }
        for (k in 0 until sorted.size - 1) {
            val a = sorted[k]
            val b = sorted[k + 1]
            if (score >= a.score && score <= b.score && b.score > a.score) {
                val t = (score - a.score) / (b.score - a.score)
                return exp(ln(a.bytes.toDouble()) * (1 - t) + ln(b.bytes.toDouble()) * t)
            }
        }
        return null
    }

    @Test
    fun sizeAtEqualScoreAgainstX265() {
        org.junit.Assume.assumeTrue("set IMAGEKODEC_HEVC_INTER_BENCH=1 to run", System.getenv("IMAGEKODEC_HEVC_INTER_BENCH") == "1")
        org.junit.Assume.assumeTrue("ffmpeg not installed", Tools.hasAll("ffmpeg"))
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-hevc-inter-bench").apply { mkdirs() }
        val crfs = listOf(6, 10, 14, 18, 22, 26, 30, 34, 38, 42)
        val rows = ArrayList<String>()
        val ratios = HashMap<String, MutableList<Double>>()
        val only = System.getenv("IMAGEKODEC_HEVC_INTER_BENCH_SETTINGS")
        for ((name, bitmaps) in scenes()) {
            val w = bitmaps[0].width
            val h = bitmaps[0].height
            val planes = bitmaps.map { toYuv(it) }
            val yuv = File(dir, "src.yuv").apply {
                writeBytes(ByteArray(w * h * 3 / 2 * frames).also { raw ->
                    var at = 0
                    for (p in planes) for (c in 0..2) for (v in p.planes[c]) raw[at++] = v.toByte()
                })
            }
            // x265 as its medium preset is, and tuned for SSIM, which turns its psycho-visual rate-distortion off.
            val curves = listOf("medium" to emptyList(), "medium ssim" to listOf("-tune", "ssim")).map { (name, tune) ->
                name to crfs.map { x265(dir, yuv, w, h, it, tune, bitmaps) }
            }
            for (s in SETTINGS) {
                if (only != null && s.label !in only.split(",")) continue
                val start = System.nanoTime()
                val seq = HevcEncoder.encodeSequence(
                    planes, LongArray(frames) { 40 }, true, null, HevcEncoderSettings(quality = s.quality, effort = s.effort),
                    HevcSequenceSettings(gopSize = s.gop, pOffset = s.p, bOffset = s.b), null,
                )
                val millis = (System.nanoTime() - start) / 1e6
                val bytes = vclBytes(seq.annexB)
                val score = score(seq.annexB, bitmaps)
                val cells = StringBuilder("| $name ${w}x$h, $frames frames | ${s.label} | $bytes | ${"%.2f".format(score)} | ${"%.0f".format(millis)} |")
                for ((x265Name, curve) in curves) {
                    val theirs = sizeAt(curve, score)
                    val time = curve.map { it.millis }.average()
                    if (theirs == null) {
                        cells.append(" out of range (${curve.minOf { it.score }.let { "%.1f".format(it) }} to ${curve.maxOf { it.score }.let { "%.1f".format(it) }}) | ${"%.0f".format(time)} |")
                    } else {
                        val ratio = bytes / theirs
                        ratios.getOrPut("x265 $x265Name, ${s.label}") { ArrayList() } += ratio
                        cells.append(" ${"%.0f".format(theirs)} (${"%.3f".format(ratio)}) | ${"%.0f".format(time)} |")
                    }
                }
                rows += cells.toString()
                println(cells)
            }
        }
        println("| scene | setting | bytes | mean SSIMULACRA 2 | ms | x265 medium bytes at that score (ratio) | x265 ms | x265 medium, tune ssim (ratio) | x265 ms |")
        println("|---|---|---|---|---|---|---|---|---|")
        rows.forEach { println(it) }
        for ((k, list) in ratios.toSortedMap()) {
            val geo = exp(list.sumOf { ln(it) } / list.size)
            println("geometric mean of size ratios against $k: ${"%.3f".format(geo)} over ${list.size} scenes")
        }
        val main = ratios["x265 medium, ${SETTINGS[0].label}"] ?: return
        val geo = exp(main.sumOf { ln(it) } / main.size)
        assertTrue(geo < BOUND, "size at equal score is ${"%.3f".format(geo)} times x265 medium's, past $BOUND")
    }

    private class Setting(val quality: Int, val effort: Int, val gop: Int, val p: Int = 3, val b: Int = 4) {
        val label = "q$quality e$effort g$gop" + if (p != 3 || b != 4) " p$p b$b" else ""
    }

    private companion object {
        /** The first is the default (quality 50, effort 2, groups of 4), which the bound holds. */
        val SETTINGS = listOf(
            Setting(50, 2, 4), Setting(75, 2, 4), Setting(50, 1, 4), Setting(50, 3, 4),
            Setting(50, 2, 1), Setting(50, 2, 8),
            // The QP offsets of P and B pictures (pOffset and bOffset), measured to choose the defaults of 3 and 4.
            Setting(50, 2, 4, 0, 0), Setting(50, 2, 4, 1, 1), Setting(50, 2, 4, 2, 2), Setting(50, 2, 4, 2, 4), Setting(50, 2, 4, 3, 3),
            Setting(50, 2, 4, 4, 4), Setting(50, 2, 4, 5, 5), Setting(50, 2, 1, 0, 0), Setting(50, 2, 1, 2, 2), Setting(50, 2, 1, 5, 5),
        )

        // A loose bound that catches a regression; see docs/heic.md for the measured ratio.
        const val BOUND = 3.0
    }
}
