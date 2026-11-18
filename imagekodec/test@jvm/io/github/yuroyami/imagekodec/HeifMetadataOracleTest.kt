package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** readMetadata on real HEIC files against exiftool's extraction of the same payloads (#149). */
class HeifMetadataOracleTest {

    private val samples = File(System.getenv("IMAGEKODEC_HEIC_DATA") ?: "../reference/heic", "samples")

    private fun exiftool(tag: String, file: File): ByteArray {
        val p = ProcessBuilder(Tools.require("exiftool").path, "-b", "-$tag", file.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        p.waitFor()
        return out
    }

    @Test
    fun realFilesMatchExiftool() {
        assumeTrue(Tools.hasAll("exiftool"))
        if (!samples.isDirectory) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("no HEIC samples at $samples; run tools/heic_data.py")
            assumeTrue("no HEIC samples at $samples", false)
        }
        var checked = 0
        for (name in listOf("pug.heic", "cat.hif", "spatial_photo.heic", "example.heic")) {
            val file = File(samples, name)
            val m = ImageKodec.readMetadata(file.readBytes())
            val exif = exiftool("EXIF", file)
            val xmp = exiftool("XMP", file)
            val icc = exiftool("ICC_Profile", file)
            if (exif.isEmpty()) assertEquals(null, m.exif, name) else assertContentEquals(exif, m.exif, "$name exif")
            if (xmp.isEmpty()) assertEquals(null, m.xmp, name) else assertEquals(xmp.decodeToString(), m.xmp, "$name xmp")
            if (icc.isEmpty()) assertEquals(null, m.icc, name) else assertContentEquals(icc, m.icc, "$name icc")
            checked++
        }
        assertEquals(4, checked)
    }
}
