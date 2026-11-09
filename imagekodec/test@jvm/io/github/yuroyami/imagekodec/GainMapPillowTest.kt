package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The MPF segment that `encodeJpeg` writes for an Ultra HDR gain map (#130), read by Pillow's
 * Multi-Picture Format parser: two images, the main one first and as long as the file up to the
 * gain map, and a gain map at the offset it gives that Pillow decodes as the 16 by 12 ramp it is.
 */
class GainMapPillowTest {

    /**
     * Pillow opens an Ultra HDR file as a plain JPEG on purpose, so this reads its MPF entries,
     * whose offsets count from the TIFF header at `mpoffset`, and opens the second image there.
     */
    private val script = """
import sys, io
from PIL import Image
im = Image.open(sys.argv[1])
mp = im._getmp()
entries = mp[0xB002]
data = open(sys.argv[1], 'rb').read()
print(mp[0xB001], entries[0]['Attribute']['MPType'], entries[0]['Size'] == len(data) - entries[1]['Size'])
second = Image.open(io.BytesIO(data[im.info['mpoffset'] + entries[1]['DataOffset']:][:entries[1]['Size']])).convert('L')
print(second.size, second.getpixel((0, 0)) < 60, second.getpixel((15, 0)) > 200)
""".trimIndent()

    @Test
    fun pillow_finds_the_gain_map_through_mpf() {
        assumeTrue("python3 is not installed", Tools.hasAll("python3"))
        val python = Tools.require("python3").path
        val pillow = ProcessBuilder(python, "-c", "import PIL").start().waitFor() == 0
        if (!pillow && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no Pillow, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        assumeTrue("python3 has no Pillow", pillow)

        val gray = KiteBitmap(16, 12, IntArray(16 * 12) { 0xFF000000.toInt() or ((it % 16) * 17 * 0x010101) })
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></rdf:RDF></x:xmpmeta>"""
        val gainMap = GainMap(ImageKodec.encodeJpeg(gray, 95, ImageMetadata(xmp = xmp)))
        val main = KiteBitmap(64, 48, IntArray(64 * 48) { 0xFF336699.toInt() })
        val file = File.createTempFile("ultrahdr", ".jpg").apply { deleteOnExit() }
        file.writeBytes(ImageKodec.encodeJpeg(main, 90, ImageMetadata(xmp = xmp, gainMap = gainMap)))

        val process = ProcessBuilder(python, "-c", script, file.path).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString().trim()
        process.waitFor(60, TimeUnit.SECONDS)
        assertEquals("2 Baseline MP Primary Image True\n(16, 12) True True", out)
    }
}
