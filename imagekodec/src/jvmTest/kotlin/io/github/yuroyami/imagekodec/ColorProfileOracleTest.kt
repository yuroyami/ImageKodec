package io.github.yuroyami.imagekodec

import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real ICC profiles, the JDK's own sRGB and PhotoYCC ones, embedded by ImageMagick in
 * every format it writes them to, then read back by [ImageKodec.probe] byte for byte
 * and checked against ImageMagick's own extraction of the same file (#16). PhotoYCC
 * runs past one JPEG APP2 segment, so it also checks the segments are joined.
 * Skips cleanly without ImageMagick.
 */
class ColorProfileOracleTest {

    private fun run(vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to ""
        }
        return proc.exitValue() to out.toString()
    }

    private fun temp(name: String, ext: String) =
        File.createTempFile("kite-icc-$name", ext).apply { deleteOnExit() }

    @Test
    fun everyFormatHandsBackTheProfileItWasGiven() {
        assumeTrue("ImageMagick not installed", Tools.hasAll("magick"))
        val magick = Tools.require("magick").path
        val source = temp("src", ".png").apply {
            writeBytes(ImageKodec.encodePng(KiteBitmap(24, 16, IntArray(24 * 16) { argb(0xFF, it * 7, 255 - it, it * 3) })))
        }
        val profiles = mapOf(
            "sRGB" to ICC_Profile.getInstance(ColorSpace.CS_sRGB).data,
            "PYCC" to ICC_Profile.getInstance(ColorSpace.CS_PYCC).data,
        )
        assertTrue(profiles.getValue("PYCC").size > 65519, "PhotoYCC should need more than one APP2 segment")
        val report = StringBuilder()
        for ((name, icc) in profiles) {
            val iccFile = temp(name, ".icc").apply { writeBytes(icc) }
            for (ext in listOf("png", "jpg", "tif", "webp", "jp2", "gif", "bmp")) {
                val out = temp("$name-$ext", ".$ext")
                val (code, text) = run(magick, source.path, "-profile", iccFile.path, out.path)
                assertEquals(0, code, "magick could not write $ext: $text")
                // ImageMagick's own reading of what it wrote: the reference.
                val extracted = temp("$name-$ext-out", ".icc")
                val (extractCode, _) = run(magick, out.path, extracted.path)
                if (extractCode != 0 || extracted.length() == 0L) {
                    report.appendLine("$name in $ext: ImageMagick wrote no profile")
                    continue
                }
                assertContentEquals(icc, extracted.readBytes(), "ImageMagick changed the $name profile in $ext")
                val color = assertNotNull(ImageKodec.probe(out.readBytes()).colorProfile, "$name in $ext: no profile")
                assertContentEquals(icc, color.icc, "$name in $ext")
                report.appendLine("$name in $ext: ${icc.size} bytes, \"${color.iccDescription}\"")
            }
        }
        println(report)
        // Every format but those ImageMagick cannot embed in carried a profile.
        assertTrue(report.lines().count { "wrote no profile" in it } <= 4, report.toString())
    }
}
