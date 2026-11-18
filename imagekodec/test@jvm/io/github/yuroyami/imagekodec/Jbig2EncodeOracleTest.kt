package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.BilevelOracleSupport.readPbm
import io.github.yuroyami.imagekodec.BilevelOracleSupport.run
import java.io.File
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ImageKodec.encodeJbig2] against jbig2dec and jbig2enc (#185). jbig2dec, an independent decoder,
 * must read every template, with and without typical prediction, and MMR, both as the embedded
 * stream of a PDF and as a file, back to the source page without a warning. jbig2enc writes the
 * same generic region in its PDF mode, template 0 with the nominal adaptive pixels, and the MQ
 * coder is deterministic, so its stream and ours must agree byte for byte. With typical prediction
 * they differ in one choice: jbig2enc codes the first row in full, where T.88 6.2.5.7 lets a white
 * first row repeat the white row above the region, as this encoder does. The coder's statistics then
 * part, so the two sizes differ by a few bytes either way; jbig2dec checks ours.
 */
class Jbig2EncodeOracleTest {

    private val pages = BilevelPages.edges + BilevelPages.text(1728, 300, 17) + BilevelPages.noise(203, 64, 5)

    private val variants: List<Jbig2EncodeOptions> = buildList {
        for (standalone in listOf(false, true)) {
            for (template in 0..3) for (tp in listOf(false, true)) add(Jbig2EncodeOptions(template, tp, false, standalone))
            add(Jbig2EncodeOptions(mmr = true, standalone = standalone))
        }
    }

    @Test
    fun jbig2decReadsEveryVariantBackToTheSourcePage() {
        assumeTrue("jbig2dec is not installed", Tools.hasAll("jbig2dec"))
        val dir = Files.createTempDirectory("imagekodec-jbig2-encode").toFile()
        try {
            var checked = 0
            for (page in pages) for (options in variants) {
                val label = "${page.name}, $options"
                File(dir, "page.jb2").writeBytes(ImageKodec.encodeJbig2(page.bitmap(), options))
                File(dir, "out.pbm").delete()
                val embedded = if (options.standalone) emptyArray() else arrayOf("-e")
                val result = run(dir, Tools.require("jbig2dec").path, *embedded, "-t", "pbm", "-o", "out.pbm", "page.jb2")
                assertEquals(0, result.exit, "$label: ${result.log}")
                assertTrue(result.log.isBlank(), "$label: jbig2dec says ${result.log}")
                val (w, h, black) = readPbm(File(dir, "out.pbm"))
                assertEquals(page.width to page.height, w to h, label)
                assertContentEquals(page.black, black, label)
                checked++
            }
            assertEquals(pages.size * variants.size, checked)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun jbig2encWritesTheSameGenericRegionByteForByte() {
        assumeTrue("jbig2enc is not installed", Tools.hasAll("jbig2"))
        val dir = Files.createTempDirectory("imagekodec-jbig2-encode").toFile()
        try {
            for (page in pages) {
                File(dir, "page.png").writeBytes(ImageKodec.encodePng(page.bitmap()))
                for (tp in listOf(false, true)) {
                    val flags = if (tp) arrayOf("-p", "-d") else arrayOf("-p")
                    val out = File(dir, "out.jb2")
                    val process = ProcessBuilder(Tools.require("jbig2").path, *flags, "page.png").directory(dir)
                        .redirectOutput(out).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "jbig2 timed out")
                    assertEquals(0, process.exitValue(), "jbig2 ${flags.joinToString(" ")} on ${page.name}")
                    val ours = ImageKodec.encodeJbig2(page.bitmap(), Jbig2EncodeOptions(typicalPrediction = tp))
                    val theirs = out.readBytes()
                    if (tp) {
                        val apart = kotlin.math.abs(ours.size - theirs.size)
                        assertTrue(apart <= 16, "${page.name}: ${ours.size} bytes, jbig2enc's ${theirs.size}")
                    } else {
                        assertContentEquals(theirs, ours, page.name)
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
