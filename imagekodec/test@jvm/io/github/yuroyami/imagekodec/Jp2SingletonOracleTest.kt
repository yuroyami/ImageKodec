package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Jp2SingletonOracleTest {
    private fun compare(levels: Int) {
        assumeTrue("OpenJPEG not installed", Tools.hasAll("opj_decompress"))
        for (name in listOf("horizontal", "vertical", "corner", "reversible", "ordinary")) {
            val bytes = Jp2SingletonTest().encoded(name)
            val input = File.createTempFile("imagekodec-singleton", ".j2k").apply { deleteOnExit(); writeBytes(bytes) }
            val output = File.createTempFile("imagekodec-singleton", ".pgm").apply { deleteOnExit() }
            val log = File.createTempFile("imagekodec-singleton", ".log").apply { deleteOnExit() }
            val args = mutableListOf(Tools.require("opj_decompress").path, "-i", input.path, "-o", output.path)
            if (levels > 0) args += listOf("-r", "$levels")
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "$name/$levels OpenJPEG timed out")
            assertEquals(0, process.exitValue(), log.readText())
            val pgm = output.readBytes()
            var at = 0
            fun token(): String {
                while (at < pgm.size && pgm[at].toInt().toChar().isWhitespace()) at++
                if (pgm[at] == '#'.code.toByte()) {
                    while (at < pgm.size && pgm[at] != '\n'.code.toByte()) at++
                    return token()
                }
                val start = at
                while (at < pgm.size && !pgm[at].toInt().toChar().isWhitespace()) at++
                return pgm.copyOfRange(start, at).decodeToString()
            }
            assertEquals("P5", token()); val w = token().toInt(); val h = token().toInt()
            assertEquals("255", token()); at++
            val ours = assertNotNull(JpxDecoder.decode(bytes, 1 shl levels))
            assertEquals(w, ours.width); assertEquals(h, ours.height)
            assertEquals(w * h, ours.pixelBytes.size)
            val tolerance = if (name == "reversible") 0 else 4
            for (i in ours.pixelBytes.indices) {
                val delta = kotlin.math.abs((ours.pixelBytes[i].toInt() and 255) - (pgm[at + i].toInt() and 255))
                assertTrue(delta <= tolerance, "$name/r$levels sample $i differs by $delta")
            }
        }
    }
    @Test fun fullSingletonPixelsMatchOpenjpeg() { compare(0) }
    @Test fun reducedSingletonPixelsMatchOpenjpeg() { for (levels in 1..3) compare(levels) }
}
