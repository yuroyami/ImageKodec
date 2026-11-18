package io.github.yuroyami.imagekodec

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * File sizes of the PNG writer (#182): the call without options (8-bit RGB or RGBA, as before),
 * [PngColorType.Auto] at each effort, and `optipng -o2` on the file without options where it is
 * installed, over the photo-like scenes and some pictures of few colours. Printed as a table; the
 * only bound is that Auto at the default effort is never larger than the file without options,
 * which it also tries to be (the same filters and search on the same type, or a smaller type).
 */
class PngSizeBenchmarkTest {

    private fun posterized(scene: PhotoScenes.Scene, levels: Int): KiteBitmap = KiteBitmap(
        scene.bitmap.width, scene.bitmap.height,
        IntArray(scene.bitmap.argb.size) { i ->
            val p = scene.bitmap.argb[i]
            fun q(v: Int) = (v * (levels - 1) + 127) / 255 * 255 / (levels - 1)
            argb(255, q((p ushr 16) and 255), q((p ushr 8) and 255), q(p and 255))
        },
    )

    private fun grayOf(scene: PhotoScenes.Scene): KiteBitmap = KiteBitmap(
        scene.bitmap.width, scene.bitmap.height,
        IntArray(scene.bitmap.argb.size) { i ->
            val p = scene.bitmap.argb[i]
            gray((77 * ((p ushr 16) and 255) + 150 * ((p ushr 8) and 255) + 29 * (p and 255)) ushr 8)
        },
    )

    /** A chart: flat bars of eight colours on white, with gridlines. */
    private fun chart(w: Int = 400, h: Int = 300): KiteBitmap {
        val colours = intArrayOf(0x1f77b4, 0xff7f0e, 0x2ca02c, 0xd62728, 0x9467bd, 0x8c564b, 0xe377c2, 0x7f7f7f)
        return KiteBitmap(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val bar = x / 50
            val top = h - 30 - (bar * 37 + 50) % (h - 60)
            when {
                x % 50 in 8..41 && y in top until h - 30 -> colours[bar] or (0xFF shl 24)
                y % 30 == 0 || y == h - 30 -> argb(255, 200, 200, 200)
                else -> argb(255, 255, 255, 255)
            }
        })
    }

    /** Black text-like strokes on white: two colours. */
    private fun text(w: Int = 480, h: Int = 200): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val glyph = (x / 9 * 7 + y / 16 * 13) % 11
        val ink = y % 16 in 3..12 && x % 9 in 1..6 && ((x % 9 + y % 16 + glyph) % 5 < 2 || x % 9 == 1 + glyph % 5)
        if (ink) argb(255, 0, 0, 0) else argb(255, 255, 255, 255)
    })

    /** An icon: a disc in 24 shades with an anti-aliased edge of translucent pixels, on transparent. */
    private fun icon(size: Int = 128): KiteBitmap = KiteBitmap(size, size, IntArray(size * size) { i ->
        val x = i % size - size / 2
        val y = i / size - size / 2
        val d2 = x * x + y * y
        val r = size * 2 / 5
        when {
            d2 <= (r - 1) * (r - 1) -> argb(255, 40 + (x + size / 2) / 6 * 8, 90, 200 - (y + size / 2) / 6 * 6)
            d2 <= r * r -> argb(128, 40, 90, 200)
            else -> 0
        }
    })

    /** 200 colours in a smooth ramp. */
    private fun ramp(w: Int = 400, h: Int = 200): KiteBitmap = KiteBitmap(w, h, IntArray(w * h) { i ->
        val c = (i % w) * 200 / w
        argb(255, c, 255 - c, (c * 3) and 255)
    })

    private fun optipng(png: ByteArray): Int? {
        val tool = Tools.find("optipng") ?: return null
        val dir = kotlin.io.path.createTempDirectory("kite-optipng").toFile()
        try {
            val file = File(dir, "in.png").apply { writeBytes(png) }
            val proc = ProcessBuilder(tool.path, "-quiet", "-o2", "-out", File(dir, "out.png").path, file.path).redirectErrorStream(true).start()
            proc.inputStream.readBytes()
            if (!proc.waitFor(300, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            return File(dir, "out.png").takeIf { it.exists() }?.length()?.toInt()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun timed(block: () -> ByteArray): Pair<ByteArray, Long> {
        val start = System.nanoTime()
        val out = block()
        return out to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun sizesAgainstTheCallWithoutOptions() {
        val scenes = PhotoScenes.all()
        val pictures = scenes.map { it.name to it.bitmap } +
            scenes.take(3).map { "${it.name} 6 levels" to posterized(it, 6) } +
            scenes.take(2).map { "${it.name} gray" to grayOf(it) } +
            listOf("chart" to chart(), "text" to text(), "icon" to icon(), "ramp 200" to ramp())
        // Warm the JIT so the first row's times are not the compiler's.
        repeat(2) { ImageKodec.encodePng(pictures[0].second, PngEncodeOptions(effort = 3)) }
        println("| picture | colours | today | Auto e1 | Auto e2 | Auto e3 | optipng -o2 | type e2 | ms today/e1/e2/e3 |")
        println("|---|---|---|---|---|---|---|---|---|")
        var today = 0L
        var e2Total = 0L
        var e3Total = 0L
        var optiTotal = 0L
        for ((name, bitmap) in pictures) {
            val (plain, tPlain) = timed { ImageKodec.encodePng(bitmap) }
            val runs = (1..3).map { effort -> timed { ImageKodec.encodePng(bitmap, PngEncodeOptions(effort = effort)) } }
            for ((png, _) in runs) assertContentEquals(bitmap.argb, ImageKodec.decode(png).argb, name)
            val opti = optipng(plain)
            val e2 = runs[1].first
            val type = when (e2[25].toInt()) { 0 -> "gray"; 2 -> "RGB"; 3 -> "palette"; 4 -> "gray+alpha"; else -> "RGBA" } + " " + e2[24]
            val colours = bitmap.argb.toSet().size.let { if (it > 256) ">256" else "$it" }
            println("| $name | $colours | ${plain.size} | ${runs[0].first.size} | ${e2.size} | ${runs[2].first.size} | ${opti ?: "-"} | $type | $tPlain/${runs[0].second}/${runs[1].second}/${runs[2].second} |")
            assertTrue(e2.size <= plain.size, "$name: Auto at effort 2 wrote ${e2.size} bytes, the call without options ${plain.size}")
            today += plain.size
            e2Total += e2.size
            e3Total += runs[2].first.size
            optiTotal += opti ?: 0
        }
        println("| total | | $today | | $e2Total | $e3Total | ${if (optiTotal > 0) optiTotal else "-"} | | |")
    }
}
