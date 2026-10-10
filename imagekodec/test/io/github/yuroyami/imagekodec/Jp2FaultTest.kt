package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Faults `FuzzTest` found once the JPEG 2000 decoder could no longer hide them behind its catch-all
 * (#102). Each damaged file must be refused by name, through the checked entry point and the facade.
 */
class Jp2FaultTest {

    /** Refused at every reduction, for [reason] at [reduction], the one that reached the fault. */
    private fun refused(bytes: ByteArray, reason: String, reduction: Int) {
        for (r in intArrayOf(1, 2, 4, 8)) assertFailsWith<ImageDecodeException> { JpxDecoder.decodeChecked(bytes, r) }
        val e = assertFailsWith<ImageDecodeException> { JpxDecoder.decodeChecked(bytes, reduction) }
        assertTrue(reason in e.message!!, e.message)
        assertFailsWith<ImageDecodeException> { ImageKodec.decodeReduced(bytes, reduction) }
    }

    @Test
    fun aCodeBlockLengthOfMoreThan32BitsIsRefused() {
        // Half of a multi-precinct RPCL file and then the start of a WebP, FuzzTest's splice: the packet headers read the WebP's bytes,
        // whose runs of ones raise Lblock until the length read takes 39 bits and wrapped an Int to a
        // negative length. OpenJPEG refuses more than 32 bits.
        val rpcl = Jp2ProgressionTest().rpcl
        val webp = hex(WEBP_UNIFORM_HISTOGRAM)
        refused(rpcl.copyOf(minOf(rpcl.size / 2, webp.size)) + webp.copyOf(minOf(webp.size, 512)), "code-block length", reduction = 4)
    }

    @Test
    fun aComponentWithNoSamplesIsRefused() {
        // The RGBA codestream with its image moved to start at x = 1 and end at x = 33, a tile wide
        // enough to hold it, and the opacity component subsampled by 255 across: that component's
        // grid starts and ends at ceil(1 / 255) = ceil(33 / 255) = 1, so it holds no sample.
        val bytes = Jp2AlphaFixtures.rgbaCodestream.copyOf()
        val siz = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0x51.toByte() }
        fun u32(at: Int, value: Int) { for (i in 0..3) bytes[siz + at + i] = (value ushr (24 - 8 * i)).toByte() }
        val width = ((bytes[siz + 6].toInt() and 0xFF) shl 24) or ((bytes[siz + 7].toInt() and 0xFF) shl 16) or
            ((bytes[siz + 8].toInt() and 0xFF) shl 8) or (bytes[siz + 9].toInt() and 0xFF)
        u32(6, width + 1)    // Xsiz
        u32(14, 1)           // XOsiz
        u32(22, width + 64)  // XTsiz
        bytes[siz + 40 + 3 * 3 + 1] = 255.toByte()  // XRsiz of component 3
        refused(bytes, "has no samples", reduction = 1)
    }
}
