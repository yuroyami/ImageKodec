package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.Orientation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole HEIC file through `ImageKodec` on every target (#145): the grid, its alpha and its rotation of
 * [HeicVector], held to checksums of the pixels that `HeicOracleTest` holds to libde265 and libheif.
 */
class HeicDecodeTest {

    /** FNV-1a 64 over [values], each as [bytes] bytes, high byte first. */
    private fun fnv(values: IntArray, bytes: Int): Long {
        var h = -0x340d631b7bdddcdbL
        for (v in values) for (k in bytes - 1 downTo 0) h = (h xor ((v ushr (8 * k)) and 255).toLong()) * 0x100000001b3L
        return h
    }

    @Test
    fun aGridWithAlphaAndARotationDecodes() {
        ImageKodecHeic.install()
        val data = HeicVector.file
        val info = ImageKodec.probe(data)
        assertEquals(ImageFormat.HEIC, info.format)
        assertTrue(info.isDecodable, info.unsupportedReason)
        assertEquals(60 to 56, info.width to info.height)
        assertEquals(8, info.bitDepth)
        assertTrue(info.hasAlpha)
        assertEquals(Orientation.Rotate270, info.orientation)
        assertContentEquals(intArrayOf(1, 13, 1, 1), info.colorProfile?.cicp)

        val bitmap = ImageKodec.decode(data)
        assertEquals(60 to 56, bitmap.width to bitmap.height)
        assertEquals(EXPECTED_ARGB, fnv(bitmap.argb, 4), "ARGB checksum")
        val wide = ImageKodec.decode16(data)
        assertEquals(4, wide.channels)
        assertEquals(EXPECTED_16, fnv(IntArray(wide.samples.size) { wide.samples[it].toInt() and 0xFFFF }, 2), "decode16 checksum")
        assertContentEquals(bitmap.argb, wide.toBitmap().argb, "decode16 narrows to decode")

        val turned = ImageKodec.decode(data, applyOrientation = true)
        assertEquals(56 to 60, turned.width to turned.height)
        // A quarter turn anticlockwise puts the top right corner at the top left.
        assertEquals(bitmap.argb[59], turned.argb[0])
    }

    /** The session checks for cancellation once a CTB row: two rows in each of the four tiles and four in the alpha item. */
    @Test
    fun theCancellationCheckRunsOnceACtbRow() {
        ImageKodecHeic.install()
        var calls = 0
        ImageKodec.decode(HeicVector.file, cancellationCheck = { calls++ })
        assertTrue(calls >= 4 * 2 + 4, "$calls checks")
    }

    private companion object {
        const val EXPECTED_ARGB = 2442337725284536808L
        const val EXPECTED_16 = -7320728821407840296L
    }
}
