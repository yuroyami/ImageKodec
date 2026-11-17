package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteBitmap
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compression of the intra encoder against x265 (`-preset medium` and `-preset ultrafast`, every picture an
 * intra picture), on the photo-like scenes at 512 by 512 and more in 8-bit 4:2:0: the size of the slice data at
 * an equal SSIMULACRA 2 score, and encode times. Both encoders get the same YCbCr samples, and both decodes go
 * back to RGB through the same conversion before the score. x265 runs a sweep of constant rate factors, and its
 * size at this encoder's score comes from interpolating log size between the two nearest points. Prints a
 * table, and holds the mean ratio to a loose bound that catches a regression. Also prints the encode time of
 * large pictures at each effort.
 */
class HevcEncoderBenchmarkTest {

    private val scenes = listOf(
        PhotoScenes.landscape(640, 512), PhotoScenes.portrait(512, 640), PhotoScenes.foliage(640, 512),
        PhotoScenes.city(640, 512), PhotoScenes.night(640, 512), PhotoScenes.bokeh(544, 512),
    )

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

    private fun decodeAnnexB(stream: ByteArray): HeifPlanes {
        val d = HevcDecoder()
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
        for ((k, s) in starts.withIndex()) {
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else stream.size
            while (end > s && stream[end - 1].toInt() == 0) end--
            d.decodeNal(stream, s, end, null)
        }
        return d.finish().single().toHeifPlanes()
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

    private fun x265(dir: File, yuv: File, w: Int, h: Int, preset: String, crf: Int, source: KiteBitmap): Point {
        val out = File(dir, "x265.hevc")
        val start = System.nanoTime()
        val proc = ProcessBuilder(
            "timeout", "-s", "KILL", "300", Tools.require("ffmpeg").path, "-hide_banner", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${w}x$h", "-i", yuv.path, "-frames:v", "1",
            "-c:v", "libx265", "-preset", preset, "-x265-params", "keyint=1:crf=$crf:log-level=none", "-f", "hevc", "-y", out.path,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().decodeToString()
        check(proc.waitFor() == 0) { log }
        val millis = (System.nanoTime() - start) / 1e6
        val stream = out.readBytes()
        val score = ImageKodec.ssimulacra2(source, toRgb(decodeAnnexB(stream), w, h))
        return Point(vclBytes(stream), score, millis)
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
        if (!Tools.hasAll("ffmpeg")) {
            println("ffmpeg not installed: the x265 benchmark is skipped")
            org.junit.Assume.assumeTrue(false)
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "kite-hevc-bench").apply { mkdirs() }
        val qualities = listOf(50, 75)
        val crfs = listOf(8, 12, 16, 20, 24, 28, 32, 36, 40, 44)
        val rows = ArrayList<String>()
        val ratios = HashMap<String, MutableList<Double>>()
        // A first encode warms the JVM up, so the times below are of compiled code.
        HevcEncoder.encode(toYuv(scenes[0].bitmap), true, null, HevcEncoderSettings(), null)
        for (scene in scenes) {
            val b = scene.bitmap
            val yuvPlanes = toYuv(b)
            val yuv = File(dir, "src.yuv").apply {
                writeBytes(ByteArray(b.width * b.height * 3 / 2).also { raw ->
                    var at = 0
                    for (c in 0..2) for (v in yuvPlanes.planes[c]) raw[at++] = v.toByte()
                })
            }
            val curves = listOf("medium", "ultrafast").associateWith { preset -> crfs.map { x265(dir, yuv, b.width, b.height, preset, it, b) } }
            // Each setting: quality, effort and adaptive QP strength in 1/256 (0 off).
            val settings = listOf(Triple(50, 1, 0), Triple(50, 2, 0), Triple(50, 3, 0), Triple(75, 2, 0)) +
                AQ_STRENGTHS.flatMap { s -> listOf(Triple(50, 2, s), Triple(75, 2, s)) }
            for ((q, effort, aq) in settings) {
                val start = System.nanoTime()
                val coded = HevcEncoder.encode(yuvPlanes, true, null, HevcEncoderSettings(quality = q, effort = effort, adaptiveQp = aq > 0, aqStrength = maxOf(aq, 1)), null)
                val millis = (System.nanoTime() - start) / 1e6
                val bytes = coded.sample.size - 4
                val score = ImageKodec.ssimulacra2(b, toRgb(decodeAnnexB(coded.annexB), b.width, b.height))
                val label = "q$q e$effort" + if (aq > 0) " aq$aq" else ""
                val cells = StringBuilder("| ${scene.name} ${b.width}x${b.height} | $label | $bytes | ${"%.2f".format(score)} | ${"%.0f".format(millis)} |")
                for ((preset, curve) in curves) {
                    val theirs = sizeAt(curve, score)
                    val time = curve.map { it.millis }.average()
                    if (theirs == null) {
                        cells.append(" out of range | ${"%.0f".format(time)} |")
                    } else {
                        val ratio = bytes / theirs
                        ratios.getOrPut("$preset $label") { ArrayList() } += ratio
                        cells.append(" ${"%.0f".format(theirs)} (${"%.3f".format(ratio)}) | ${"%.0f".format(time)} |")
                    }
                }
                rows += cells.toString()
            }
        }
        println("| scene | setting | bytes | SSIMULACRA 2 | ms | x265 medium bytes at that score (ratio) | x265 medium ms | x265 ultrafast bytes (ratio) | x265 ultrafast ms |")
        println("|---|---|---|---|---|---|---|---|---|")
        rows.forEach { println(it) }
        for ((k, list) in ratios.toSortedMap()) {
            val geo = exp(list.sumOf { ln(it) } / list.size)
            println("geometric mean of size ratios against x265 $k: ${"%.3f".format(geo)} over ${list.size} scenes")
        }
        val medium = ratios["medium q50 e2"] ?: error("no scene in range of x265 medium")
        val geo = exp(medium.sumOf { ln(it) } / medium.size)
        // A loose bound that catches a regression; the aim is 1.15 (see the report).
        assertTrue(geo < BOUND, "size at equal score is ${"%.3f".format(geo)} times x265 medium's, past $BOUND")
    }

    @Test
    fun encodeTimeOfLargePictures() {
        for ((w, h) in listOf(1920 to 1080, 4032 to 3024)) {
            val planes = toYuv(PhotoScenes.landscape(w, h).bitmap)
            for (effort in 1..3) {
                HevcEncoder.encode(toYuv(PhotoScenes.landscape(256, 256).bitmap), true, null, HevcEncoderSettings(effort = effort), null)
                val start = System.nanoTime()
                val coded = HevcEncoder.encode(planes, true, null, HevcEncoderSettings(effort = effort), null)
                val ms = (System.nanoTime() - start) / 1e6
                println("${w}x$h 8-bit 4:2:0 at quality 50, effort $effort: ${coded.sample.size} bytes in ${"%.0f".format(ms)} ms on the JVM")
            }
        }
    }

    private companion object {
        // The encoder lands near 0.86 (#175): this catches a regression of about a sixth.
        const val BOUND = 1.0

        /** The adaptive QP strengths measured, in 1/256. */
        val AQ_STRENGTHS = listOf(128, 256)
    }
}
