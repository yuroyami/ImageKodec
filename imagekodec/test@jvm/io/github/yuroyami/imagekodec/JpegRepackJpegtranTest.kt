package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpegEncoder
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ImageKodec.repackJpeg] against `jpegtran -optimize -copy all`, the check of #132: on the same
 * files, the baseline output is within 1 percent of jpegtran's size, and djpeg reads the same
 * samples from both.
 */
class JpegRepackJpegtranTest {

    private fun run(vararg command: String): ByteArray {
        val process = ProcessBuilder(*command).start()
        process.outputStream.close()
        val err = Thread { process.errorStream.readBytes() }.apply { start() }
        val out = process.inputStream.readBytes()
        err.join()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "${command.first()} did not finish")
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")} failed")
        return out
    }

    private fun photo(width: Int, height: Int, seed: Int): KiteBitmap {
        val random = Random(seed)
        return KiteBitmap(width, height, IntArray(width * height) {
            val x = it % width
            val y = it / width
            val edge = if ((x / 19 + y / 13) % 3 == 0) 50 else 0
            val r = (x * 255 / width + edge + random.nextInt(28)).coerceIn(0, 255)
            val g = (y * 255 / height + random.nextInt(28)).coerceIn(0, 255)
            val b = ((x + y) * 128 / (width + height) + edge + random.nextInt(28)).coerceIn(0, 255)
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        })
    }

    @Test
    fun the_output_is_within_one_percent_of_jpegtran() {
        assumeTrue("jpegtran or djpeg is not installed", Tools.hasAll("jpegtran", "djpeg"))
        val jpegtran = Tools.require("jpegtran").path
        val djpeg = Tools.require("djpeg").path
        val dir = Files.createTempDirectory("jpegtran").toFile().apply { deleteOnExit() }
        val inputs = mapOf(
            "q50" to ImageKodec.encodeJpeg(photo(517, 301, 1), 50),
            "q90" to ImageKodec.encodeJpeg(photo(400, 300, 2), 90),
            "q97" to ImageKodec.encodeJpeg(photo(333, 222, 3), 97),
            "gray" to JpegEncoder.encode(photo(301, 199, 4), 85, null, gray = true),
        )
        for ((name, bytes) in inputs) {
            val input = File(dir, "$name.jpg").apply { writeBytes(bytes) }
            val theirs = run(jpegtran, "-optimize", "-copy", "all", input.path)
            val ours = File(dir, "$name.repacked.jpg").apply { writeBytes(ImageKodec.repackJpeg(bytes)) }
            assertTrue(ours.length() <= theirs.size * 1.01, "$name: ${ours.length()} bytes, jpegtran gives ${theirs.size}")
            val theirsFile = File(dir, "$name.jpegtran.jpg").apply { writeBytes(theirs) }
            assertContentEquals(run(djpeg, "-ppm", theirsFile.path), run(djpeg, "-ppm", ours.path), "$name: djpeg reads other samples")
        }
    }
}
