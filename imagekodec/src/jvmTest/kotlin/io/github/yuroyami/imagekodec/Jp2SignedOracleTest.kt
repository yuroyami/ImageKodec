package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Jp2SignedOracleTest {
    private class Component(val width: Int, val height: Int, val pixels: ByteArray)

    /** PGX preserves the component's sign and precision before display conversion. */
    private fun component(file: File): Component {
        val bytes = file.readBytes()
        val end = bytes.indexOf('\n'.code.toByte())
        val fields = bytes.copyOfRange(0, end).decodeToString().trim().split(Regex("\\s+"))
        assertEquals(6, fields.size); assertEquals("PG", fields[0]); assertEquals("ML", fields[1])
        val signed = fields[2] == "-"
        val precision = fields[3].toInt(); val width = fields[4].toInt(); val height = fields[5].toInt()
        val maximum = (1 shl precision) - 1
        val sampleBytes = (precision + 7) / 8
        assertEquals(end + 1 + width * height * sampleBytes, bytes.size)
        var at = end + 1
        val pixels = ByteArray(width * height) {
            var sample = 0
            repeat(sampleBytes) { sample = (sample shl 8) or (bytes[at++].toInt() and 255) }
            sample = sample and maximum
            if (signed) {
                if (sample and (1 shl (precision - 1)) != 0) sample -= 1 shl precision
                sample += 1 shl (precision - 1)
            }
            (if (precision > 8) sample shr (precision - 8) else sample * 255 / maximum).toByte()
        }
        return Component(width, height, pixels)
    }

    private fun compare(bytes: ByteArray, components: Int, levels: Int, tag: String, tolerance: Int = 0) {
        val dir = Files.createTempDirectory("imagekodec-signed").toFile()
        try {
            val input = File(dir, "input.j2k").apply { writeBytes(bytes) }
            val output = File(dir, "reference.pgx")
            val log = File(dir, "openjpeg.log")
            val args = mutableListOf(Tools.require("opj_decompress").path, "-i", input.path, "-o", output.path)
            if (levels > 0) args += listOf("-r", "$levels")
            val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "$tag/$levels OpenJPEG timed out")
            assertEquals(0, process.exitValue(), log.readText())
            val channels = List(components) { component(File(dir, "reference_$it.pgx")) }
            val ours = assertNotNull(JpxDecoder.decode(bytes, 1 shl levels))
            assertEquals(channels[0].width, ours.width); assertEquals(channels[0].height, ours.height)
            val colors = if (components == 2) 1 else components
            val expected = ByteArray(ours.width * ours.height * colors) { i ->
                channels[i % colors].pixels[i / colors]
            }
            fun pixels(expected: ByteArray, actual: ByteArray, name: String) {
                if (tolerance == 0) assertContentEquals(expected, actual, name)
                else {
                    assertEquals(expected.size, actual.size)
                    for (i in expected.indices) {
                        val delta = kotlin.math.abs((expected[i].toInt() and 255) - (actual[i].toInt() and 255))
                        assertTrue(delta <= tolerance, "$name sample $i differs by $delta")
                    }
                }
            }
            pixels(expected, ours.pixelBytes, "$tag/$levels")
            if (components == 2) pixels(channels[1].pixels, assertNotNull(ours.alpha), "$tag/$levels opacity")
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun gray(levels: Int) {
        assumeTrue("OpenJPEG not installed", Tools.hasAll("opj_decompress"))
        for (precision in 1..16) for (signed in listOf(false, true)) {
            compare(Jp2SignedTest().gray(precision, signed), 1, levels, "$precision-bit signed=$signed")
        }
    }

    private fun rgb(levels: Int) {
        assumeTrue("OpenJPEG not installed", Tools.hasAll("opj_decompress"))
        for (mct in listOf(false, true)) for (mask in listOf(0, 5, 7)) {
            compare(Jp2SignedTest().rgb(mct, mask), 3, levels, "mct=$mct signedMask=$mask")
        }
    }

    @Test fun signedGrayAndAlphaMatchOpenjpeg() {
        assumeTrue("OpenJPEG not installed", Tools.hasAll("opj_decompress"))
        for (mask in listOf(0, 2, 3)) for (levels in 0..2) {
            compare(Jp2SignedTest().grayAlpha(mask), 2, levels, "gray/alpha signedMask=$mask")
        }
    }

    @Test fun irreversibleSignedAndMixedComponentsMatchOpenjpeg() {
        assumeTrue("OpenJPEG not installed", Tools.hasAll("opj_decompress"))
        for (profile in listOf("gray", "rgb", "rgb-ict", "alpha")) {
            val count = if (profile == "gray") 1 else if (profile == "alpha") 2 else 3
            val masks = if (profile == "gray") listOf(0, 1)
                else if (profile == "alpha") listOf(0, 2, 3) else listOf(0, 5, 7)
            for (mask in masks) for (levels in 0..2) {
                compare(Jp2SignedTest().irreversible(profile, mask), count, levels, "$profile signedMask=$mask", tolerance = 4)
            }
        }
    }

    @Test fun fullSignedAndUnsignedGraySamplesMatchOpenjpeg() = gray(0)
    @Test fun reducedSignedAndUnsignedGraySamplesMatchOpenjpeg() { for (levels in 1..2) gray(levels) }
    @Test fun fullSignedAndMixedRgbSamplesMatchOpenjpeg() = rgb(0)
    @Test fun reducedSignedAndMixedRgbSamplesMatchOpenjpeg() { for (levels in 1..2) rgb(levels) }
}
