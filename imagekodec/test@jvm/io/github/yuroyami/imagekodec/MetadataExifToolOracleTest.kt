package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The metadata of every format against ExifTool (#186). ExifTool reads every block an encoder
 * here wrote, and [ImageKodec.readMetadata] reads every block ExifTool wrote into a file of each
 * format: an EXIF block is compared through the tags ExifTool reads from it, the XMP packet and
 * the ICC profile byte for byte against what `exiftool -b` extracts. ExifTool reads no JPEG XL
 * codestream profile, so libjxl's `djxl --icc_out` stands in for it there, and it writes no BMP,
 * so ImageMagick writes the BMP that embeds a profile.
 */
class MetadataExifToolOracleTest {

    private val dir = createTempDirectory("metadata-oracle").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A real RGB profile, from icc-profiles-free. */
    private val profileFile = File("/usr/share/color/icc/compatibleWithAdobeRGB1998.icc")

    private fun ready(vararg tools: String): ByteArray {
        assumeTrue(Tools.hasAll("exiftool", *tools))
        if (!profileFile.isFile) {
            if (System.getenv(Tools.REQUIRE_ENV) == "1") error("$profileFile (icc-profiles-free) is missing")
            assumeTrue("no $profileFile", false)
        }
        return profileFile.readBytes()
    }

    private fun run(vararg command: String): ByteArray {
        val p = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        check(p.waitFor(120, TimeUnit.SECONDS)) { "${command[0]} did not finish" }
        check(p.exitValue() == 0) { "${command.joinToString(" ")} exited with ${p.exitValue()}" }
        return out
    }

    private fun exiftool(vararg args: String): ByteArray = run(Tools.require("exiftool").path, *args)

    /** The EXIF tags this suite compares, read as numbers where ExifTool can. */
    private val tags = listOf(
        "Make", "Model", "Orientation", "ModifyDate", "XResolution", "ResolutionUnit", "DateTimeOriginal", "ExposureTime", "ISO",
        "ExifVersion", "InteropIndex", "InteropVersion", "GPSVersionID", "GPSLatitudeRef", "GPSLatitude",
    )

    /** The [tags] ExifTool reads from the EXIF of [file], by name. */
    private fun exifTags(file: File): Map<String, String> {
        val out = exiftool("-n", "-s", "-s", *tags.map { "-EXIF:$it" }.toTypedArray(), file.path).decodeToString()
        return out.lines().filter { ": " in it }.associate { it.substringBefore(": ") to it.substringAfter(": ") }
    }

    /** The [tags] ExifTool reads from [exif] in a JPEG's APP1 segment, the plain carrier of an EXIF block. */
    private fun exifTags(exif: ByteArray): Map<String, String> {
        val jpeg = File(dir, "carrier.jpg")
        jpeg.writeBytes(ImageKodec.encodeJpeg(bitmap, 90, ImageMetadata(exif = exif)))
        return exifTags(jpeg)
    }

    private val bitmap = KiteBitmap(9, 5, IntArray(45) { 0xFF000000.toInt() or (it * 0x050307) })

    private fun file(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    @Test
    fun exiftoolReadsWhatEveryEncoderWrites() {
        val icc = ready("djxl")
        val metadata = ImageMetadata(MetadataFixtures.exif(), MetadataFixtures.XMP, icc)
        val want = exifTags(metadata.exif!!)
        assertEquals(tags.size, want.size, "ExifTool reads every tag of the fixture: $want")
        val files = listOf(
            Triple("jpg", ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(), metadata), "exif"),
            Triple("png", ImageKodec.encodePng(bitmap, PngEncodeOptions(), metadata), "exif"),
            Triple("webp", ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(lossless = true), metadata), "exif"),
            Triple("lossy.webp", ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(quality = 80), metadata), "exif"),
            Triple("jp2", ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true), metadata), "exif"),
            Triple("jxl", ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true), metadata), "exif"),
            Triple("avif", ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(lossless = true, effort = 1, orientation = Orientation.Rotate90), metadata), "exif"),
            Triple("tif", ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(), metadata), "exif"),
            Triple("pages.tif", ImageKodec.encodeTiff(listOf(bitmap, bitmap), TiffEncodeOptions(compression = TiffCompression.Lzw), metadata), "exif"),
            Triple("jxr", ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true), metadata), "jxr"),
            Triple("gif", ImageKodec.encodeGif(bitmap, metadata), "none"),
            Triple("bmp", ImageKodec.encodeBmp(bitmap, BmpEncodeOptions(), metadata), "none"),
        )
        val report = StringBuilder()
        for ((name, bytes, exif) in files) {
            val f = file("out.$name", bytes)
            // ExifTool reads no JPEG XL codestream profile; djxl gives the one the file holds.
            val profile = if (name == "jxl") {
                val out = File(dir, "jxl.icc")
                run(Tools.require("djxl").path, f.path, File(dir, "jxl.ppm").path, "--icc_out=${out.path}")
                out.readBytes()
            } else {
                exiftool("-b", "-ICC_Profile", f.path)
            }
            assertContentEquals(icc, profile, "$name: the ICC profile")
            if (name != "bmp") assertEquals(MetadataFixtures.XMP, exiftool("-b", "-XMP", f.path).decodeToString(), "$name: the XMP packet")
            val got = exifTags(f)
            when (exif) {
                "exif" -> assertEquals(want, got, "$name: the EXIF tags")
                // JPEG XR keeps the text tags of IFD0 and its own resolution, and the orientation in
                // its Transformation tag (T.832 Table A.4): 4 is a turn of 90 degrees clockwise.
                "jxr" -> {
                    val own = listOf("XResolution", "ResolutionUnit", "Orientation")
                    assertEquals(want - own, got - own, "$name: the EXIF tags")
                    assertEquals("4", exiftool("-n", "-s", "-s", "-s", "-Transformation", f.path).decodeToString().trim(), "$name: the Transformation tag")
                }
                else -> assertTrue(got.isEmpty(), "$name: no EXIF, got $got")
            }
            report.append("$name: ICC ${profile.size} B, XMP ${if (name == "bmp") "-" else "ok"}, EXIF tags ${got.size}\n")
        }
        println(report)
    }

    /** What ExifTool writes into each fixture: EXIF tags of IFD0 and of the EXIF and GPS directories, an XMP title and the profile. */
    private fun exiftoolWrite(file: File, vararg extra: String) {
        exiftool(
            "-q", "-overwrite_original", *extra,
            "-Make=Kite", "-Model=Oracle Two", "-Orientation#=8", "-DateTimeOriginal=2026:11:10 09:15:00", "-ExposureTime=0.01",
            "-ISO=400", "-GPSLatitude=48.25", "-GPSLatitudeRef=N", "-XMP-dc:Title=Written by ExifTool",
            "-ICC_Profile<=${profileFile.path}", file.path,
        )
    }

    @Test
    fun readMetadataReadsWhatExiftoolWrites() {
        ready("djxl", "cjxl", "magick")
        val fixtures = listOf(
            "jpg" to ImageKodec.encodeJpeg(bitmap, 90),
            "png" to ImageKodec.encodePng(bitmap),
            "webp" to ImageKodec.encodeWebp(bitmap),
            "jp2" to ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true)),
            "avif" to ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(lossless = true, effort = 1)),
            "tif" to ImageKodec.encodeTiff(bitmap),
            "pages.tif" to ImageKodec.encodeTiff(listOf(bitmap, bitmap)),
            "jxr" to ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true)),
            "gif" to ImageKodec.encodeGif(bitmap),
            // A JPEG XL container, which ExifTool adds boxes to.
            "jxl" to ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true, container = true)),
        )
        val report = StringBuilder()
        var compressed = 0
        for ((name, plain) in fixtures + listOf("brob.jxl" to fixtures.last().second)) {
            val f = file("in.$name", plain)
            if (name == "brob.jxl") exiftoolWrite(f, "-api", "Compress=1") else exiftoolWrite(f)
            val bytes = f.readBytes()
            val m = ImageKodec.readMetadata(bytes)
            val icc = exiftool("-b", "-ICC_Profile", f.path)
            // ExifTool writes no profile into a JPEG XL codestream or a HEIF item.
            if (icc.isEmpty()) {
                assertTrue(name.endsWith("jxl") || name == "avif", "$name: ExifTool wrote no profile")
                assertNull(m.icc, "$name: a profile ExifTool did not write")
            } else {
                assertContentEquals(icc, m.icc, "$name: the ICC profile")
            }
            assertEquals(exiftool("-b", "-XMP", f.path).decodeToString(), m.xmp, "$name: the XMP packet")
            val fromFile = exifTags(f)
            if (name == "gif") {
                // ExifTool keeps EXIF tags of a GIF in XMP, the one place a GIF has.
                assertNull(m.exif)
            } else {
                val block = assertNotNull(m.exif, "$name: the EXIF block")
                val fromBlock = exifTags(block)
                assertEquals(fromFile.filterKeys { it in setOf("Make", "Model", "Orientation", "DateTimeOriginal", "ExposureTime", "ISO", "GPSLatitude", "GPSLatitudeRef") }.size, 8, "$name: ExifTool wrote every tag: $fromFile")
                assertEquals(fromFile, fromBlock, "$name: the EXIF tags")
            }
            if (name == "brob.jxl") compressed = String(bytes, Charsets.ISO_8859_1).windowed(4).count { it == "brob" }
            report.append("$name: ICC ${m.icc?.size ?: 0} B, XMP ${m.xmp?.length ?: 0} chars, EXIF ${m.exif?.size ?: 0} B, ${fromFile.size} tags\n")
        }
        assertEquals(2, compressed, "ExifTool compressed the Exif and xml boxes into brob boxes")

        // The profile of a JPEG XL codestream that cjxl wrote, against djxl's.
        val png = file("icc.png", ImageKodec.encodePng(bitmap, ImageMetadata(icc = profileFile.readBytes())))
        val jxl = File(dir, "cjxl.jxl")
        run(Tools.require("cjxl").path, png.path, jxl.path, "-d", "0")
        val djxlIcc = File(dir, "cjxl.icc")
        run(Tools.require("djxl").path, jxl.path, File(dir, "cjxl.ppm").path, "--icc_out=${djxlIcc.path}")
        assertContentEquals(djxlIcc.readBytes(), ImageKodec.readMetadata(jxl.readBytes()).icc, "cjxl: the codestream profile")
        report.append("cjxl: ICC ${djxlIcc.length()} B\n")

        // A BMP whose BITMAPV5HEADER embeds the profile, written by ImageMagick.
        val bmp = File(dir, "magick.bmp")
        run(Tools.require("magick").path, png.path, "-profile", profileFile.path, "BMP:${bmp.path}")
        val bmpIcc = exiftool("-b", "-ICC_Profile", bmp.path)
        assertTrue(bmpIcc.isNotEmpty(), "ImageMagick embedded the profile")
        assertContentEquals(bmpIcc, ImageKodec.readMetadata(bmp.readBytes()).icc, "magick BMP: the ICC profile")
        report.append("magick bmp: ICC ${bmpIcc.size} B\n")
        println(report)
    }
}
