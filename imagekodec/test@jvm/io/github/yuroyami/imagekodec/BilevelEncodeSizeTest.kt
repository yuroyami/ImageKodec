package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.BilevelOracleSupport.run
import io.github.yuroyami.imagekodec.BilevelOracleSupport.singleStrip
import io.github.yuroyami.imagekodec.BilevelOracleSupport.tiff
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size of [ImageKodec.encodeCcitt]'s Group 4 and [ImageKodec.encodeJbig2]'s generic region on
 * text-like pages, against libtiff's `tiffcp -c g4` and jbig2enc's generic coder (`jbig2 -p`, with
 * `-d` for typical prediction), printed as a table with the encode times. Group 4 has one coding of
 * a page, so libtiff must write exactly as many bytes. jbig2enc must too without typical prediction,
 * and with it may differ by a few bytes, for the reason `Jbig2EncodeOracleTest` gives.
 */
class BilevelEncodeSizeTest {

    private val pages = listOf(
        BilevelPages.text(1728, 2200, 1),
        BilevelPages.text(2480, 3508, 2),
        BilevelPages.text(1728, 1100, 3),
        BilevelPages.page("Jbig2Page", Jbig2Page.WIDTH, Jbig2Page.HEIGHT) { x, y -> Jbig2Page.black[y * Jbig2Page.WIDTH + x] },
    )

    /** The best of three runs, in milliseconds, and the output. */
    private fun timed(encode: () -> ByteArray): Pair<Double, ByteArray> {
        var best = Long.MAX_VALUE
        var out = ByteArray(0)
        repeat(3) {
            val start = System.nanoTime()
            out = encode()
            best = minOf(best, System.nanoTime() - start)
        }
        return best / 1e6 to out
    }

    @Test
    fun group4AndGenericRegionSizesAgainstLibtiffAndJbig2enc() {
        assumeTrue("tiffcp or jbig2enc is not installed", Tools.hasAll("tiffcp", "jbig2"))
        val dir = Files.createTempDirectory("imagekodec-bilevel-size").toFile()
        try {
            val table = StringBuilder()
            table.appendLine("| page | G4 ours | tiffcp -c g4 | G4 ms | G3 2D K=4 | JBIG2 T0 | jbig2 -p | JBIG2 T0+TP | jbig2 -p -d | T0+TP ms | T1+TP | T2+TP | T3+TP | MMR |")
            table.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (page in pages) {
                val bitmap = page.bitmap()
                val (g4Ms, g4) = timed { ImageKodec.encodeCcitt(bitmap) }
                val g3 = ImageKodec.encodeCcitt(bitmap, CcittEncodeOptions(k = 4, endOfLine = true))

                File(dir, "raw.tif").writeBytes(tiff(page.packed(blackIs1 = true), page.width, page.height, 1))
                val tiffcp = run(dir, Tools.require("tiffcp").path, "-c", "g4", "-r", page.height.toString(), "raw.tif", "g4.tif")
                assertEquals(0, tiffcp.exit, tiffcp.log)
                val libtiff = singleStrip(File(dir, "g4.tif").readBytes()).size

                fun jbig2enc(vararg flags: String): Int {
                    File(dir, "page.png").writeBytes(ImageKodec.encodePng(bitmap))
                    val out = File(dir, "out.jb2")
                    val p = ProcessBuilder(Tools.require("jbig2").path, *flags, "page.png").directory(dir)
                        .redirectOutput(out).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    assertTrue(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0, "jbig2 failed")
                    return out.readBytes().size
                }
                val t0 = ImageKodec.encodeJbig2(bitmap, Jbig2EncodeOptions(typicalPrediction = false)).size
                val (tpMs, tp) = timed { ImageKodec.encodeJbig2(bitmap, Jbig2EncodeOptions()) }
                val others = (1..3).map { ImageKodec.encodeJbig2(bitmap, Jbig2EncodeOptions(template = it)).size }
                val mmr = ImageKodec.encodeJbig2(bitmap, Jbig2EncodeOptions(mmr = true)).size
                val plain = jbig2enc("-p")
                val withTp = jbig2enc("-p", "-d")
                table.appendLine(
                    "| ${page.name} | ${g4.size} | $libtiff | ${"%.1f".format(g4Ms)} | ${g3.size} | $t0 | $plain | ${tp.size} | $withTp | " +
                        "${"%.1f".format(tpMs)} | ${others.joinToString(" | ")} | $mmr |",
                )
                // libtiff ends the strip with EOFB and pads the last byte, as the default options do.
                assertEquals(libtiff, g4.size, "${page.name}: Group 4 bytes")
                assertTrue(t0 == plain && tp.size <= withTp + 16, "${page.name}: JBIG2 $t0 and ${tp.size}, jbig2enc $plain and $withTp")
            }
            println(table)
        } finally {
            dir.deleteRecursively()
        }
    }
}
