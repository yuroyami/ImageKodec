package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [Jbig2Decoder] against the streams jbig2enc writes: the arithmetic generic region, with and
 * without typical prediction, and the symbol dictionary and text region of its symbol mode. The
 * generic modes are lossless, so they must give back [Jbig2Page] exactly. Symbol mode loses no
 * pixel on that page but moves some glyphs by a pixel, so it must match jbig2dec, an independent
 * decoder, exactly. The streams in `Jbig2DecoderTest` were made this way; this suite remakes them
 * with the installed tools.
 */
class Jbig2OracleTest {

    private fun encode(dir: File, vararg args: String): Int {
        val proc = ProcessBuilder(listOf(Tools.require("jbig2").path) + args.toList())
            .directory(dir)
            .redirectOutput(File(dir, "out.jb2"))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroyForcibly(); return -1 }
        return proc.exitValue()
    }

    private fun source(dir: File): File =
        File(dir, "page.png").apply { writeBytes(ImageKodec.encodePng(Jbig2Page.bitmap())) }

    @Test
    fun genericRegionsMatchThePage() {
        assumeTrue("jbig2enc is not installed", Tools.hasAll("jbig2"))
        val dir = Files.createTempDirectory("imagekodec-jbig2").toFile()
        try {
            val page = source(dir)
            for (flags in listOf(listOf("-p"), listOf("-p", "-d"))) {
                assertEquals(0, encode(dir, *(flags + page.name).toTypedArray()), "jbig2 ${flags.joinToString(" ")}")
                val decoded = Jbig2Decoder.decode(File(dir, "out.jb2").readBytes(), null, Jbig2Page.WIDTH, Jbig2Page.HEIGHT)
                assertNotNull(decoded, "jbig2 ${flags.joinToString(" ")}")
                assertContentEquals(Jbig2Page.packed(), decoded, "jbig2 ${flags.joinToString(" ")}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSymbolDictionaryAndATextRegionMatchJbig2dec() {
        assumeTrue("jbig2enc or jbig2dec is not installed", Tools.hasAll("jbig2", "jbig2dec"))
        val dir = Files.createTempDirectory("imagekodec-jbig2").toFile()
        try {
            val page = source(dir)
            assertEquals(0, encode(dir, "-s", "-p", "-b", "sym", page.name), "jbig2 -s")
            val proc = ProcessBuilder(Tools.require("jbig2dec").path, "-e", "-t", "pbm", "-o", "ref.pbm", "sym.sym", "sym.0000")
                .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0, "jbig2dec failed")
            val decoded = Jbig2Decoder.decode(
                File(dir, "sym.0000").readBytes(), File(dir, "sym.sym").readBytes(), Jbig2Page.WIDTH, Jbig2Page.HEIGHT,
            )
            assertNotNull(decoded)
            assertContentEquals(readPbm(File(dir, "ref.pbm")), decoded)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A P4 file as [Jbig2Decoder] lays a page out: a set bit white, the row padding clear. */
    private fun readPbm(f: File): ByteArray {
        val d = f.readBytes()
        var at = 0
        repeat(2) { while (d[at] != '\n'.code.toByte()) at++; at++ }
        val stride = (Jbig2Page.WIDTH + 7) / 8
        val pad = (0xFF shl (stride * 8 - Jbig2Page.WIDTH)) and 0xFF
        return ByteArray(stride * Jbig2Page.HEIGHT) { i ->
            val v = d[at + i].toInt() xor 0xFF
            (if (i % stride == stride - 1) v and pad else v).toByte()
        }
    }
}
