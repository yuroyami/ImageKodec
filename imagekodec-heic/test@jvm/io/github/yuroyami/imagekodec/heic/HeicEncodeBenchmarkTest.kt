package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.HeifEncodeOptions
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.TileRunner
import io.github.yuroyami.imagekodec.Tools
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The size and encode time of `ImageKodec.encodeHeic` on a 12-megapixel photo-like scene (4000 by 3000, the
 * landscape of [PhotoScenes]) at quality 50 and 80, on one thread and with a tile runner on a pool of four
 * (#174), with the SSIMULACRA 2 score of each file against the scene. libheif's `heif-enc` (x265) on the same
 * picture at the same quality numbers is printed beside it for scale; its quality scale is its own. The four
 * runners must write the same bytes. The table goes to the test output and to `build/heic-encode-benchmark.md`.
 *
 * It takes minutes, so it runs only with `IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1`.
 */
class HeicEncodeBenchmarkTest {

    init {
        ImageKodecHeic.install()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(listOf("timeout", "-s", "KILL", "900") + args.toList()).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }

    @Test
    fun twelveMegapixels() {
        assumeTrue("set IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1 to run", System.getenv("IMAGEKODEC_HEIC_ENCODE_BENCHMARK") == "1")
        val scene = PhotoScenes.landscape(4000, 3000).bitmap
        // Warm-up on a smaller picture, so the JIT has compiled the encoder before the clock runs.
        repeat(2) { ImageKodec.encodeHeic(PhotoScenes.landscape(1024, 768).bitmap, 50) }
        val pool = Executors.newFixedThreadPool(4)
        val rows = ArrayList<String>()
        rows += "| Encoder | Quality | Threads | Size (bytes) | Bits a pixel | Time (s) | SSIMULACRA 2 |"
        rows += "|---|---|---|---|---|---|---|"
        val pixels = scene.width.toDouble() * scene.height
        try {
            for (quality in listOf(50, 80)) {
                var serial: ByteArray? = null
                for (threads in listOf(1, 4)) {
                    ImageKodec.setTileRunner(if (threads == 1) TileRunner.Serial else TileRunner { jobs -> jobs.map { pool.submit(it::run) }.forEach { it.get() } })
                    val start = System.nanoTime()
                    val file = ImageKodec.encodeHeic(scene, quality)
                    val seconds = (System.nanoTime() - start) / 1e9
                    if (serial == null) serial = file else assertContentEquals(serial, file, "the pool wrote other bytes")
                    val score = ImageKodec.ssimulacra2(scene, ImageKodec.decode(file))
                    rows += "| ImageKodec | $quality | $threads | ${file.size} | ${"%.3f".format(file.size * 8 / pixels)} | ${"%.1f".format(seconds)} | ${"%.2f".format(score)} |"
                    println(rows.last())
                }
            }
        } finally {
            ImageKodec.setTileRunner(TileRunner.Serial)
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
        if (Tools.hasAll("heif-enc", "timeout")) {
            val dir = File(System.getProperty("java.io.tmpdir"), "kite-heic-bench").apply { mkdirs() }
            val png = File(dir, "scene.png").apply { writeBytes(ImageKodec.encodePng(scene)) }
            for (quality in listOf(50, 80)) {
                val out = File(dir, "scene.heic").apply { delete() }
                val start = System.nanoTime()
                val (code, log) = run(Tools.require("heif-enc").path, "-q", "$quality", "-o", out.path, png.path)
                val seconds = (System.nanoTime() - start) / 1e9
                assertTrue(code == 0, "heif-enc failed: $log")
                val bytes = out.readBytes()
                val score = ImageKodec.ssimulacra2(scene, ImageKodec.decode(bytes))
                rows += "| heif-enc 1.17.6 (x265) | $quality | its own | ${bytes.size} | ${"%.3f".format(bytes.size * 8 / pixels)} | ${"%.1f".format(seconds)} | ${"%.2f".format(score)} |"
                println(rows.last())
            }
        }
        val table = rows.joinToString("\n")
        println(table)
        File("build").mkdirs()
        File("build/heic-encode-benchmark.md").writeText(table + "\n")
    }
}
