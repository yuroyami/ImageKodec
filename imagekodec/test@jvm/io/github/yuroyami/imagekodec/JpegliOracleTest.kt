package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JpegEncoderKind.Jpegli] against the encoder it ports (#133): `pyjpegli.encode` of pyjpegli
 * 0.3.2, which builds google/jpegli at commit 031a007 and calls `jpegli_set_defaults` and
 * `jpegli_set_quality` on an RGB image. For each scene of [PhotoScenes] at quality [QUALITY]:
 *
 * - the segments are those of the reference, in its order, with the same quantization tables,
 *   frame header, Huffman tables and scan header;
 * - the file is within 1 percent of the reference's size;
 * - its SSIMULACRA 2 score against the source is within 0.5 of the reference's, both read by
 *   libjpeg-turbo's `djpeg`, which must open every file.
 *
 * The quantization tables are also held to the reference's at every quality from 1 to 100,
 * which checks the quality-to-distance mapping and the table arithmetic to the bit, and sizes
 * from 1x1 to thin strips, whose blocks past the image's edge jpegli codes as empty, to its
 * segments and size. The test
 * needs python3 with pyjpegli (`pip install pyjpegli==0.3.2`) and djpeg; without them it
 * skips, unless IMAGEKODEC_REQUIRE_ORACLES is 1.
 */
class JpegliOracleTest {

    private val dir: File = Files.createTempDirectory("jpegli").toFile().apply { deleteOnExit() }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timed out"
        }
        return process.exitValue() to out
    }

    private fun assumeOracle() {
        assumeTrue("python3 or djpeg is not installed", Tools.hasAll("python3", "djpeg"))
        val (code, _) = run(Tools.require("python3").path, "-c", "import pyjpegli")
        if (code != 0 && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no pyjpegli, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        assumeTrue("python3 has no pyjpegli", code == 0)
    }

    private val script = """
        import sys, pyjpegli
        rgb = open(sys.argv[1], 'rb').read()
        w, h = int(sys.argv[2]), int(sys.argv[3])
        for q in sys.argv[5].split(','):
            open(sys.argv[4] + '.' + q, 'wb').write(pyjpegli.encode(rgb, w, h, int(q)))
    """.trimIndent()

    /** pyjpegli's files of [bitmap] at each of [qualities]. */
    private fun reference(bitmap: KiteBitmap, qualities: List<Int>): Map<Int, ByteArray> {
        val rgb = ByteArray(bitmap.argb.size * 3)
        for ((i, p) in bitmap.argb.withIndex()) {
            rgb[3 * i] = (p ushr 16).toByte()
            rgb[3 * i + 1] = (p ushr 8).toByte()
            rgb[3 * i + 2] = p.toByte()
        }
        val input = File(dir, "in.rgb").apply { writeBytes(rgb) }
        val out = File(dir, "ref.jpg")
        val (code, text) = run(
            Tools.require("python3").path, "-c", script, input.path, bitmap.width.toString(), bitmap.height.toString(), out.path,
            qualities.joinToString(","),
        )
        assertEquals(0, code, "pyjpegli failed: $text")
        return qualities.associateWith { File(out.path + ".$it").readBytes() }
    }

    private fun djpeg(jpeg: ByteArray, name: String): KiteBitmap {
        val input = File(dir, "$name.jpg").apply { writeBytes(jpeg) }
        val out = File(dir, "$name.bmp")
        val (code, text) = run(Tools.require("djpeg").path, "-bmp", "-outfile", out.path, input.path)
        assertEquals(0, code, "djpeg could not open $name: $text")
        assertTrue(!text.contains("Corrupt", ignoreCase = true) && !text.contains("warning", ignoreCase = true), "djpeg warned on $name: $text")
        return ImageKodec.decode(out.readBytes())
    }

    /** Every segment before the entropy-coded data, marker and contents, and the marker list. */
    private fun segments(jpeg: ByteArray): List<String> = buildList {
        var at = 2
        while (at + 4 <= jpeg.size) {
            val marker = jpeg[at + 1].toInt() and 0xFF
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            val body = jpeg.copyOfRange(at + 4, at + 2 + length)
            add("%02X ".format(marker) + body.joinToString("") { "%02x".format(it) }.let { if (marker == 0xC0 || marker == 0xDA || marker == 0xDB || marker == 0xC4) it else "(${body.size} bytes)" })
            if (marker == 0xDA) break
            at += 2 + length
        }
    }

    private fun dqt(jpeg: ByteArray): String = segments(jpeg).first { it.startsWith("DB ") }

    @Test
    fun the_quantization_tables_match_at_every_quality() {
        assumeOracle()
        val image = PhotoScenes.bokeh(40, 24).bitmap
        val qualities = (1..100).toList()
        val reference = reference(image, qualities)
        val failures = qualities.filter { q -> dqt(ImageKodec.encodeJpeg(image, q, JpegEncoderKind.Jpegli)) != dqt(reference.getValue(q)) }
        assertTrue(failures.isEmpty(), "the DQT segment differs from jpegli's at qualities $failures")
    }

    @Test
    fun edge_sizes_match_in_structure_and_size() {
        assumeOracle()
        val failures = ArrayList<String>()
        for ((w, h) in listOf(1 to 1, 8 to 8, 17 to 9, 300 to 9, 9 to 300, 33 to 33, 16 to 31)) {
            val image = PhotoScenes.bokeh(w, h).bitmap
            val ours = ImageKodec.encodeJpeg(image, QUALITY, JpegEncoderKind.Jpegli)
            val theirs = reference(image, listOf(QUALITY)).getValue(QUALITY)
            if (segments(ours) != segments(theirs)) failures += "${w}x$h: the segments differ"
            if (abs(ours.size.toDouble() / theirs.size - 1) > 0.01) failures += "${w}x$h: ${ours.size} bytes, jpegli ${theirs.size}"
            djpeg(ours, "edge-${w}x$h")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun the_photos_match_in_structure_size_and_score() {
        assumeOracle()
        val rows = ArrayList<String>()
        val failures = ArrayList<String>()
        for (scene in PhotoScenes.all()) {
            val ours = ImageKodec.encodeJpeg(scene.bitmap, QUALITY, JpegEncoderKind.Jpegli)
            val theirs = reference(scene.bitmap, listOf(QUALITY)).getValue(QUALITY)
            if (segments(ours) != segments(theirs)) failures += "${scene.name}: segments differ:\n  ours   ${segments(ours)}\n  theirs ${segments(theirs)}"
            val sizeRatio = ours.size.toDouble() / theirs.size - 1
            val ourScore = ImageKodec.ssimulacra2(scene.bitmap, djpeg(ours, "${scene.name}-ours"))
            val theirScore = ImageKodec.ssimulacra2(scene.bitmap, djpeg(theirs, "${scene.name}-theirs"))
            val differing = ours.indices.count { it >= theirs.size || ours[it] != theirs[it] }
            rows += "%-10s %8d %8d %+7.3f%% %8.3f %8.3f %+7.3f %9d".format(scene.name, ours.size, theirs.size, 100 * sizeRatio, ourScore, theirScore, ourScore - theirScore, differing)
            if (abs(sizeRatio) > 0.01) failures += "${scene.name}: ${ours.size} bytes, jpegli ${theirs.size}"
            if (abs(ourScore - theirScore) > 0.5) failures += "${scene.name}: SSIMULACRA 2 $ourScore, jpegli $theirScore"
        }
        println("scene         ours   jpegli    size    ours   jpegli   score  bytes off")
        rows.forEach(::println)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private companion object {
        const val QUALITY = 75
    }
}
