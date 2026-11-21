package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The JBIG2 layouts of #189 against jbig2dec: arithmetic symbol dictionaries that refine and
 * aggregate, which [Jbig2Writer] writes since jbig2enc writes none, and generic regions of unknown
 * length. jbig2dec must draw each page as the writer drew it, and so
 * must this decoder.
 */
class Jbig2AggregateOracleTest {

    /** jbig2dec's page of the embedded stream [stream], as [Jbig2Decoder] lays a page out. */
    private fun jbig2dec(dir: File, stream: ByteArray, width: Int, height: Int): ByteArray {
        File(dir, "page.jb2").writeBytes(stream)
        val proc = ProcessBuilder(Tools.require("jbig2dec").path, "-e", "-t", "pbm", "-o", "page.pbm", "page.jb2")
            .directory(dir).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().decodeToString()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS) && proc.exitValue() == 0, "jbig2dec failed: $log")
        val d = File(dir, "page.pbm").readBytes()
        var at = 0
        repeat(2) { while (d[at] != '\n'.code.toByte()) at++; at++ }
        val stride = (width + 7) / 8
        val pad = (0xFF shl (stride * 8 - width)) and 0xFF
        return ByteArray(stride * height) { i ->
            val v = d[at + i].toInt() xor 0xFF
            (if (i % stride == stride - 1) v and pad else v).toByte()
        }
    }

    @Test
    fun jbig2decDrawsEveryPageAsTheWriterDid() {
        assumeTrue("jbig2dec is not installed", Tools.hasAll("jbig2dec"))
        val dir = Files.createTempDirectory("imagekodec-jbig2-agg").toFile()
        try {
            val test = Jbig2AggregateTest()
            var pages = 0
            for (seed in 1..10) {
                val f = test.aggregates(seed)
                val name = "aggregates, seed $seed"
                assertContentEquals(f.expected, jbig2dec(dir, f.stream, f.width, f.height), "jbig2dec, $name")
                assertContentEquals(f.expected, Jbig2Decoder.decodeChecked(f.stream, null, f.width, f.height), name)
                pages++
            }
            for (mmr in listOf(false, true)) for (unknownHeight in listOf(false, true)) {
                val f = test.unknownLength(mmr, unknownHeight)
                val name = "unknown length, mmr $mmr, unknown height $unknownHeight"
                assertContentEquals(f.expected, jbig2dec(dir, f.stream, f.width, f.height), "jbig2dec, $name")
                assertContentEquals(f.expected, Jbig2Decoder.decodeChecked(f.stream, null, f.width, f.height), name)
                pages++
            }
            println("$pages JBIG2 pages: jbig2dec and ImageKodec draw each as the writer did")
        } finally {
            dir.deleteRecursively()
        }
    }
}
