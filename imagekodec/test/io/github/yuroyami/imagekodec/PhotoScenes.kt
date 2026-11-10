package io.github.yuroyami.imagekodec

import kotlin.random.Random

/**
 * Photo-like test images for the lossy encoders (#133). The repository holds no photographs, so
 * these stand in for them: what a photo puts in front of a JPEG encoder is smooth gradients,
 * textures at several scales, hard edges and sensor noise, and each scene mixes those in its own
 * proportions. Everything is integer arithmetic on seeded generators, so a scene has the same
 * pixels on every target.
 */
internal object PhotoScenes {

    class Scene(val name: String, val bitmap: KiteBitmap)

    /** A lattice of random values every [cell] pixels, smoothly interpolated: 0..65535. */
    private class Noise(seed: Int, val cell: Int, width: Int, height: Int) {
        private val cols = width / cell + 2
        private val lattice = Random(seed).let { r -> IntArray(cols * (height / cell + 2)) { r.nextInt(65536) } }

        /** smoothstep weight of [t] in 0..[cell], scaled to 0..4096. */
        private fun weight(t: Int): Long {
            val u = t.toLong() * 4096 / cell
            return u * u * (3 * 4096 - 2 * u) / (4096L * 4096)
        }

        fun at(x: Int, y: Int): Int {
            val cx = x / cell
            val cy = y / cell
            val wx = weight(x - cx * cell)
            val wy = weight(y - cy * cell)
            val i = cy * cols + cx
            val top = lattice[i] * (4096 - wx) + lattice[i + 1] * wx
            val bottom = lattice[i + cols] * (4096 - wx) + lattice[i + cols + 1] * wx
            return ((top * (4096 - wy) + bottom * wy) / (4096L * 4096)).toInt()
        }
    }

    /** Octaves of [Noise] from [cell] down to 2 pixels, each half the amplitude of the last: 0..65535. */
    private class Fractal(seed: Int, cell: Int, width: Int, height: Int) {
        private val octaves = generateSequence(cell) { it / 2 }.takeWhile { it >= 2 }.mapIndexed { i, c -> Noise(seed * 31 + i, c, width, height) }.toList()

        fun at(x: Int, y: Int): Int {
            var sum = 0L
            var amplitude = 1L shl octaves.size
            var total = 0L
            for (o in octaves) {
                sum += o.at(x, y) * amplitude
                total += amplitude
                amplitude = amplitude shr 1
            }
            return (sum / total).toInt()
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Gaussian-like sensor noise: the sum of four uniform draws in -[spread]..[spread], halved. */
    private fun grain(random: Random, spread: Int): Int =
        if (spread == 0) 0 else (random.nextInt(-spread, spread + 1) + random.nextInt(-spread, spread + 1) + random.nextInt(-spread, spread + 1) + random.nextInt(-spread, spread + 1)) / 2

    private fun scene(name: String, width: Int, height: Int, pixel: (x: Int, y: Int) -> Int) =
        Scene(name, KiteBitmap(width, height, IntArray(width * height) { pixel(it % width, it / width) }))

    /** A sky graded to a horizon, fractal hills, and textured grass with a little grain. */
    fun landscape(width: Int = 512, height: Int = 384): Scene {
        val hills = Fractal(1, 128, width, 4)
        val texture = Fractal(2, 32, width, height)
        val random = Random(3)
        return scene("landscape", width, height) { x, y ->
            val horizon = height / 2 + (hills.at(x, 0) - 32768) * height / 4 / 65536
            val n = grain(random, 3)
            if (y < horizon) {
                rgb(90 + y * 100 / height + n, 140 + y * 80 / height + n, 230 - y * 30 / height + n)
            } else {
                val t = texture.at(x, y) / 256
                rgb(40 + t / 3 + n, 90 + t / 2 + n, 30 + t / 5 + n)
            }
        }
    }

    /** A softly lit face-like oval against a blurred, grainy background. */
    fun portrait(width: Int = 384, height: Int = 512): Scene {
        val background = Fractal(4, 64, width, height)
        val skin = Fractal(5, 16, width, height)
        val random = Random(6)
        return scene("portrait", width, height) { x, y ->
            val dx = (x - width / 2).toLong() * 1000 / (width / 3)
            val dy = (y - height * 2 / 5).toLong() * 1000 / (height / 3)
            val d = dx * dx + dy * dy
            val n = grain(random, 2)
            if (d < 1_000_000) {
                val light = 255 - (d / 8000).toInt() - (x * 40 / width)
                val s = skin.at(x, y) / 2048 - 16
                rgb(light * 230 / 255 + s + n, light * 180 / 255 + s + n, light * 150 / 255 + s + n)
            } else {
                val b = background.at(x, y) / 256
                rgb(b / 2 + 40 + n, b / 3 + 50 + n, b / 2 + 70 + n)
            }
        }
    }

    /** Dense leaves: fine fractal texture in greens and yellows, high contrast at every scale. */
    fun foliage(width: Int = 448, height: Int = 336): Scene {
        val leaves = Fractal(7, 16, width, height)
        val shade = Fractal(8, 96, width, height)
        val random = Random(9)
        return scene("foliage", width, height) { x, y ->
            val l = leaves.at(x, y) / 256
            val s = shade.at(x, y) / 256
            val v = (l * 2 - 255).coerceIn(0, 255) * s / 255
            val n = grain(random, 4)
            rgb(v * 3 / 5 + 10 + n, v + 20 + n, v / 4 + 5 + n)
        }
    }

    /** Buildings: flat walls with hard edges, rows of lit windows, a graded sky and grain. */
    fun city(width: Int = 512, height: Int = 320): Scene {
        val random = Random(10)
        val tops = Random(11).let { r -> IntArray(width / 32 + 1) { height / 5 + r.nextInt(height / 2) } }
        val tones = Random(12).let { r -> IntArray(width / 32 + 1) { 60 + r.nextInt(120) } }
        val lit = Random(13).let { r -> BooleanArray(4096) { r.nextInt(3) == 0 } }
        return scene("city", width, height) { x, y ->
            val b = x / 32
            val n = grain(random, 3)
            when {
                y < tops[b] -> rgb(200 - y * 60 / height + n, 170 - y * 40 / height + n, 120 + y * 60 / height + n)
                (x % 32) in 4..27 && (y - tops[b]) % 20 in 6..13 && (x % 8) in 2..5 ->
                    if (lit[(b * 97 + y / 20 * 13 + x / 8) and 4095]) rgb(250 + n, 220 + n, 140 + n) else rgb(30 + n, 35 + n, 45 + n)
                else -> rgb(tones[b] + n, tones[b] - 10 + n, tones[b] - 25 + n)
            }
        }
    }

    /** A dim street at night: dark gradients, two lamps with glow, and heavy sensor noise. */
    fun night(width: Int = 480, height: Int = 360): Scene {
        val random = Random(14)
        val haze = Fractal(15, 64, width, height)
        val lamps = listOf(width / 4 to height / 3, width * 3 / 4 to height / 4)
        return scene("night", width, height) { x, y ->
            var glow = 0L
            for ((lx, ly) in lamps) {
                val d = (x - lx).toLong() * (x - lx) + (y - ly).toLong() * (y - ly)
                glow += 2_000_000L / (d + 900)
            }
            val g = glow.coerceAtMost(230).toInt()
            val h = haze.at(x, y) / 4096
            val n = grain(random, 8)
            rgb(12 + h + g + n, 10 + h + g * 4 / 5 + n, 20 + h + g / 2 + n)
        }
    }

    /** Out-of-focus highlights: overlapping soft discs over a smooth colour field, an odd size. */
    fun bokeh(width: Int = 333, height: Int = 251): Scene {
        val field = Fractal(16, 128, width, height)
        val discs = Random(17).let { r -> List(24) { Triple(r.nextInt(width), r.nextInt(height), 10 + r.nextInt(40)) } }
        val colours = Random(18).let { r -> List(24) { Triple(r.nextInt(256), r.nextInt(256), r.nextInt(256)) } }
        val random = Random(19)
        return scene("bokeh", width, height) { x, y ->
            val f = field.at(x, y) / 512
            var r = 30 + f
            var g = 20 + f / 2
            var b = 60 + f
            for (i in discs.indices) {
                val (cx, cy, radius) = discs[i]
                val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                if (d < radius * radius) {
                    val edge = (radius * radius - d) * 64 / (radius * radius)
                    val a = edge.coerceAtMost(40)
                    r += (colours[i].first - r) * a / 100
                    g += (colours[i].second - g) * a / 100
                    b += (colours[i].third - b) * a / 100
                }
            }
            val n = grain(random, 2)
            rgb(r + n, g + n, b + n)
        }
    }

    fun all(): List<Scene> = listOf(landscape(), portrait(), foliage(), city(), night(), bokeh())
}
