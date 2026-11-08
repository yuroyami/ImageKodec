package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.Jbig2Decoder
import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The cancellation check of the still decodes (#123): it runs during the decode, a check that never
 * throws changes no pixel, and one that throws stops the decode with its own exception, whatever
 * the decoder catches on the way.
 */
class CancellationTest {

    private class Stop : RuntimeException("stop")

    /** A picture big enough to give each decoder many steps: 256 by 256 of gradients. */
    private val picture = KiteBitmap(256, 256, IntArray(256 * 256) { i ->
        val x = i % 256
        val y = i / 256
        (0xFF shl 24) or (x shl 16) or (y shl 8) or ((x xor y) and 0xFF)
    })

    /**
     * Runs [decode] with a counting check, then with one that throws halfway through that count,
     * and gives the count. The throw must come out as it is, and no check may run after it.
     */
    private fun stops(name: String, decode: (check: () -> Unit) -> Unit): Int {
        var calls = 0
        decode { calls++ }
        assertTrue(calls > 0, "$name: the check never ran")
        val stopAt = (calls + 1) / 2
        val stop = Stop()
        var after = 0
        val thrown = assertFailsWith<Stop>(name) {
            decode {
                after++
                if (after == stopAt) throw stop
                assertTrue(after < stopAt, "$name: the decode went on after the check threw")
            }
        }
        assertSame(stop, thrown, name)
        assertEquals(stopAt, after, name)
        return calls
    }

    @Test
    fun eachFormatStopsWhereTheCheckThrows() {
        val png = ImageKodec.encodePng(picture)
        val jpeg = ImageKodec.encodeJpeg(picture)
        val tiff = ImageKodec.encodeTiff(picture)
        val sources = listOf(
            "PNG" to png, "JPEG" to jpeg, "TIFF" to tiff,
            "JPEG 2000" to Jp2ColorFixtures.palette, "AVIF" to AvifFixtures.plain, "JPEG XL" to JxlFixtures.lossless,
        )
        for ((name, data) in sources) {
            val plain = ImageKodec.decode(data)
            var calls = 0
            val checked = ImageKodec.decode(data, cancellationCheck = { calls++ })
            assertContentEquals(plain.argb, checked.argb, "$name: a check that never throws changed the pixels")
            assertTrue(calls > 0, "$name: the check never ran")
            stops(name) { check -> ImageKodec.decode(data, cancellationCheck = check) }
        }
        // A 256-row JPEG checks once a row of MCUs, and a PNG every 64 rows and every block.
        assertTrue(stops("JPEG rows") { ImageKodec.decode(jpeg, cancellationCheck = it) } >= 16)
        assertTrue(stops("PNG rows") { ImageKodec.decode(png, cancellationCheck = it) } >= 4)
    }

    @Test
    fun everyStillEntryPointTakesTheCheck() {
        val jpeg = ImageKodec.encodeJpeg(picture)
        val png = ImageKodec.encodePng(picture)
        stops("decodePage") { ImageKodec.decodePage(ImageKodec.encodeTiff(picture), 0, cancellationCheck = it) }
        stops("decode16") { ImageKodec.decode16(png, cancellationCheck = it) }
        stops("decodeReduced") { ImageKodec.decodeReduced(jpeg, 2, cancellationCheck = it) }
        stops("decodeScaled") { ImageKodec.decodeScaled(jpeg, 100, 100, cancellationCheck = it) }
        stops("decodeDownscaledTo") { ImageKodec.decodeDownscaledTo(png, 100, 100, cancellationCheck = it) }
        stops("decodeJpegComponents") { ImageKodec.decodeJpegComponents(jpeg, cancellationCheck = it) }
        stops("srgb") { ImageKodec.decode(jpeg, colorTarget = ColorTarget.Srgb(), cancellationCheck = it) }
    }

    @Test
    fun theDirectDecodersPassTheExceptionThroughTheirCatch() {
        // Both return null for any failure of their own, so the check's exception must get past that.
        val jp2 = Jp2ColorFixtures.palette
        val full = assertNotNull(JpxDecoder.decode(jp2))
        val checked = assertNotNull(JpxDecoder.decode(jp2, cancellationCheck = {}))
        assertContentEquals(full.pixelBytes, checked.pixelBytes)
        stops("JpxDecoder") { JpxDecoder.decode(jp2, cancellationCheck = it) }
        stops("JpxDecoder reduced") { JpxDecoder.decode(jp2, 2, cancellationCheck = it) }

        val generic = hex(GENERIC)
        val page = assertNotNull(Jbig2Decoder.decode(generic, null, Jbig2Page.WIDTH, Jbig2Page.HEIGHT, cancellationCheck = {}))
        assertContentEquals(Jbig2Page.packed(), page)
        stops("Jbig2Decoder") { Jbig2Decoder.decode(generic, null, Jbig2Page.WIDTH, Jbig2Page.HEIGHT, cancellationCheck = it) }
    }
}
