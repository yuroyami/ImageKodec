package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.BilevelOracleSupport.run
import io.github.yuroyami.imagekodec.BilevelOracleSupport.singleStrip
import io.github.yuroyami.imagekodec.BilevelOracleSupport.tiff
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ImageKodec.encodeCcitt] against libtiff (#185): each coding goes into a TIFF of the compression
 * that holds it, and libtiff's `tiff2rgba` must read the source page back, pixel for pixel, without
 * a warning. TIFF compression 2 is Group 3 one-dimensional with byte-aligned rows and no EOL,
 * compression 3 is Group 3 with an EOL before each row, one- or two-dimensional as T4Options bit 0
 * says, and compression 4 is Group 4. The byte-aligned Group 3 cases put fill before each EOL so that
 * the EOL starts a byte, which T.4 4.1.3 allows anywhere; T4Options bit 2 promises fill that makes
 * the EOL end a byte instead, so it stays clear.
 */
class CcittEncodeOracleTest {

    private class Case(val label: String, val compression: Int, val faxOptions: Long, val options: CcittEncodeOptions)

    private val cases = listOf(
        Case("MH (compression 2)", 2, 0, CcittEncodeOptions(k = 0, encodedByteAlign = true, endOfBlock = false)),
        Case("G3 1D", 3, 0, CcittEncodeOptions(k = 0, endOfLine = true, endOfBlock = false)),
        Case("G3 1D with RTC", 3, 0, CcittEncodeOptions(k = 0, endOfLine = true)),
        Case("G3 1D byte-aligned", 3, 0, CcittEncodeOptions(k = 0, endOfLine = true, encodedByteAlign = true)),
        Case("G3 2D K=2", 3, 1, CcittEncodeOptions(k = 2, endOfLine = true, endOfBlock = false)),
        Case("G3 2D K=4 with RTC", 3, 1, CcittEncodeOptions(k = 4, endOfLine = true)),
        Case("G3 2D K=4 byte-aligned", 3, 1, CcittEncodeOptions(k = 4, endOfLine = true, encodedByteAlign = true)),
        Case("G4", 4, 0, CcittEncodeOptions(endOfBlock = false)),
        Case("G4 with EOFB", 4, 0, CcittEncodeOptions()),
    )

    private val pages = BilevelPages.edges + BilevelPages.text(1728, 300, 17) + BilevelPages.noise(203, 64, 5)

    @Test
    fun libtiffReadsEveryCodingBackToTheSourcePage() {
        assumeTrue("libtiff tools not installed", Tools.hasAll("tiff2rgba"))
        val dir = Files.createTempDirectory("imagekodec-ccitt-encode").toFile()
        try {
            var checked = 0
            for (page in pages) for (case in cases) {
                // blackIs1 only tells a reader the polarity; TIFF's WhiteIsZero says the same.
                val data = ImageKodec.encodeCcitt(page.bitmap(), case.options)
                File(dir, "in.tif").writeBytes(tiff(data, page.width, page.height, case.compression, case.faxOptions))
                val label = "${page.name}, ${case.label}"
                val result = run(dir, Tools.require("tiff2rgba").path, "-c", "none", "in.tif", "out.tif")
                assertEquals(0, result.exit, "$label: ${result.log}")
                assertTrue(result.log.isBlank(), "$label: libtiff says ${result.log}")
                val decoded = assertNotNull(ImageIO.read(File(dir, "out.tif")), label)
                assertEquals(page.width, decoded.width, label)
                assertEquals(page.height, decoded.height, label)
                for (y in 0 until page.height) for (x in 0 until page.width) {
                    val want = if (page.black[y * page.width + x]) 0xFF000000.toInt() else -1
                    assertEquals(want, decoded.getRGB(x, y), "$label at $x, $y")
                }
                checked++
            }
            assertEquals(pages.size * cases.size, checked)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * T.4 and T.6 leave an encoder no choice once the framing is fixed: each run has one code and
     * each 2D step one mode. So libtiff's own encoder, given an uncompressed page, must write the
     * same bytes. libtiff writes an EOL before each Group 3 row, ends a Group 4 strip with EOFB and
     * leaves RTC out.
     */
    @Test
    fun libtiffWritesTheSameBytes() {
        assumeTrue("libtiff tools not installed", Tools.hasAll("tiffcp"))
        val dir = Files.createTempDirectory("imagekodec-ccitt-encode").toFile()
        try {
            val codecs = listOf(
                "g4" to CcittEncodeOptions(),
                "g3:1d" to CcittEncodeOptions(k = 0, endOfLine = true, endOfBlock = false),
                "g3:2d" to CcittEncodeOptions(k = 2, endOfLine = true, endOfBlock = false),
            )
            for (page in pages) {
                File(dir, "raw.tif").writeBytes(tiff(page.packed(blackIs1 = true), page.width, page.height, 1))
                for ((codec, options) in codecs) {
                    val result = run(dir, Tools.require("tiffcp").path, "-c", codec, "-r", page.height.toString(), "raw.tif", "fax.tif")
                    assertEquals(0, result.exit, "${page.name}, $codec: ${result.log}")
                    val theirs = singleStrip(File(dir, "fax.tif").readBytes())
                    assertContentEquals(theirs, ImageKodec.encodeCcitt(page.bitmap(), options), "${page.name}, $codec")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
