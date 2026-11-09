package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [ImageKodec.ssimulacra2] (#134) against two readings of the same pairs: libjxl's own
 * `ssimulacra2` tool, within 0.1, when it is installed, and `tools/ssimulacra2_ref.py`, a plain
 * NumPy transcription of the reference that keeps whole planes, within 0.05. The second checks the
 * streaming of the port; only the first checks it against libjxl. The two float32 readings differ
 * by up to 0.02 near a score of 90, where the 4-norms of tiny errors carry the score, and by less
 * than 0.005 below 85.
 */
class Ssimulacra2OracleTest {

    private fun scene(width: Int, height: Int, seed: Int): KiteBitmap {
        val random = Random(seed)
        return KiteBitmap(width, height, IntArray(width * height) {
            val x = it % width
            val y = it / width
            val r = (128 + 100 * sin(x / 9.0) + random.nextInt(-12, 12)).toInt().coerceIn(0, 255)
            val g = (128 + 90 * cos(y / 7.0)).toInt()
            val b = (x + y) * 255 / (width + height)
            val a = if (x < 12 && y < 12) 128 else 255
            (a shl 24) or (r shl 16) or (g shl 8) or b
        })
    }

    private fun pairs(): List<Triple<String, KiteBitmap, KiteBitmap>> {
        val image = scene(203, 131, 1)
        val opaque = KiteBitmap(image.width, image.height, IntArray(image.argb.size) { image.argb[it] or (0xFF shl 24) })
        val noisy = Random(2).let { random ->
            KiteBitmap(image.width, image.height, IntArray(image.argb.size) {
                val p = image.argb[it]
                val g = (((p ushr 8) and 0xFF) + random.nextInt(-20, 20)).coerceIn(0, 255)
                (p and 0xFFFF00FF.toInt()) or (g shl 8)
            })
        }
        return buildList {
            for (q in listOf(95, 75, 40, 15)) add(Triple("jpeg q$q", opaque, ImageKodec.decode(ImageKodec.encodeJpeg(opaque, q))))
            add(Triple("noise", image, noisy))
            add(Triple("half alpha", image, opaque))
            val odd = scene(37, 23, 3)
            add(Triple("37x23", odd, ImageKodec.decode(ImageKodec.encodeJpeg(odd, 50))))
            val wide = scene(300, 9, 4)
            add(Triple("300x9", wide, ImageKodec.decode(ImageKodec.encodeJpeg(wide, 50))))
        }
    }

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString().trim()
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        return out
    }

    private fun compare(tolerance: Double, score: (File, File) -> Double) {
        val dir = Files.createTempDirectory("ssimulacra2").toFile().apply { deleteOnExit() }
        val failures = ArrayList<String>()
        for ((name, reference, distorted) in pairs()) {
            val a = File(dir, "$name-a.png").apply { writeBytes(ImageKodec.encodePng(reference)) }
            val b = File(dir, "$name-b.png").apply { writeBytes(ImageKodec.encodePng(distorted)) }
            val expected = score(a, b)
            val actual = ImageKodec.ssimulacra2(reference, distorted)
            if (abs(actual - expected) > tolerance) failures += "$name: $actual, expected $expected"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun the_plain_transcription_gives_the_same_scores() {
        assumeTrue("python3 is not installed", Tools.hasAll("python3"))
        val python = Tools.require("python3").path
        val ready = run(python, "-c", "import numpy, PIL") != null
        if (!ready && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no NumPy or Pillow, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        assumeTrue("python3 has no NumPy or Pillow", ready)
        val script = listOf(File("../tools/ssimulacra2_ref.py"), File("tools/ssimulacra2_ref.py")).first { it.isFile }
        compare(0.05) { a, b -> run(python, script.path, a.path, b.path)!!.toDouble() }
    }

    @Test
    fun libjxl_gives_the_same_scores() {
        // No package ships the tool. CI builds it from libjxl, so there its absence fails.
        assumeTrue("libjxl's ssimulacra2 tool is not installed", Tools.hasAll("ssimulacra2"))
        val tool = Tools.require("ssimulacra2").path
        compare(0.1) { a, b -> run(tool, a.path, b.path)!!.lines().last().trim().toDouble() }
    }
}
