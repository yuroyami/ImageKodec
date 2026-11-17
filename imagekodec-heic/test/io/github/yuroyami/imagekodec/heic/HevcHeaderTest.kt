package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.withDeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The header parser on one small x265 stream, with the values ffmpeg's trace_headers gives for it (#140). */
class HevcHeaderTest {

    /**
     * ffmpeg -f lavfi -i testsrc2=size=24x20 -frames:v 1 -pix_fmt yuv420p -c:v libx265 -x265-params
     * keyint=1:colorprim=bt709:transfer=smpte2084:colormatrix=bt2020nc:range=full:info=0:ctu=16:min-cu-size=8
     * The slice NAL unit is cut after its header; the parser reads no further.
     */
    private val vps = hex("40010c01ffff0408000003009fa800000300001eba0240")
    private val sps = hex("4201010408000003009fa800000300001ea0c867de5baabcaf016e02201208000003000800000300c840")
    private val pps = hex("4401c173c089")
    private val slice = hex("2801af780a4152f9b1f1f4a9bc8b")

    private fun config(units: List<ByteArray>) = HevcConfig(4, 1, 8, 8, units)

    private fun sample(vararg units: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (u in units) out += byteArrayOf((u.size ushr 24).toByte(), (u.size ushr 16).toByte(), (u.size ushr 8).toByte(), u.size.toByte()) + u
        return out
    }

    @Test
    fun parsesTheFixture() {
        val h = assertNotNull(HevcHeaderReader.read(config(listOf(vps, sps, pps)), sample(slice)))
        val s = h.sps
        assertEquals(4, s.ptl.profileIdc)
        assertTrue(s.ptl.compatible(4))
        assertEquals(30, s.ptl.levelIdc)
        assertEquals(0, s.id)
        assertEquals(1, s.chromaFormatIdc)
        assertEquals(24 to 24, s.width to s.height)
        assertEquals(listOf(0, 0, 0, 2), s.confWin.toList())
        assertEquals(24 to 20, s.croppedWidth to s.croppedHeight)
        assertEquals(8 to 8, s.bitDepthY to s.bitDepthC)
        assertEquals(8, s.log2MaxPocLsb)
        assertEquals(2, s.maxDecPicBufferingMinus1[0])
        assertEquals(1L, s.maxLatencyIncreasePlus1[0])
        assertEquals(listOf(3, 4, 2, 4), listOf(s.minCbLog2, s.ctbLog2, s.minTbLog2, s.maxTbLog2))
        assertEquals(2 to 2, s.picWidthInCtbs to s.picHeightInCtbs)
        assertFalse(s.scalingListEnabled)
        assertTrue(s.saoEnabled)
        assertTrue(s.temporalMvpEnabled)
        assertTrue(s.strongIntraSmoothing)
        val vui = assertNotNull(s.vui)
        assertTrue(vui.fullRange)
        assertEquals(listOf(1, 16, 9), listOf(vui.colourPrimaries, vui.transferCharacteristics, vui.matrixCoeffs))
        val p = assertNotNull(h.pps)
        assertTrue(p.signDataHiding)
        assertTrue(p.cuQpDeltaEnabled)
        assertTrue(p.loopFilterAcrossSlices)
        assertFalse(p.tilesEnabled)
        val sh = assertNotNull(h.slice)
        assertEquals(NalType.IDR_N_LP, sh.nalType)
        assertTrue(sh.firstSliceSegmentInPic)
        assertEquals(SliceType.I, sh.sliceType)
        assertTrue(sh.saoLuma && sh.saoChroma)
        assertEquals(-1, sh.qpDelta)
        assertEquals(25, sh.sliceQpY)
        assertTrue(sh.loopFilterAcrossSlices)
        // The trace ends at bit 28, then three alignment bits.
        assertEquals(32, sh.headerBits)
    }

    @Test
    fun theCodecReportsTheCroppedSizeAndTheColour() {
        val hvcC = hvcC(listOf(vps, sps, pps))
        val info = HevcCodec.inspect(hvcC, sample(slice))
        assertEquals(24 to 20, info.width to info.height)
        assertEquals(8 to 8, info.bitDepthLuma to info.bitDepthChroma)
        assertEquals(1, info.chromaFormat)
        assertEquals(listOf(1, 16, 9), listOf(info.colorPrimaries, info.transferCharacteristics, info.matrixCoefficients))
        assertEquals(true, info.fullRange)
        assertEquals(HevcSupport.NOT_IMPLEMENTED, info.unsupportedReason)
        // Probing a depth reads hvcC alone, with the SPS inside it.
        assertEquals(24, HevcCodec.inspect(hvcC, null).width)
        // Parameter sets in the sample data, with an hvcC that holds none.
        assertEquals(20, HevcCodec.inspect(hvcC(emptyList()), sample(vps, sps, pps, slice)).height)
        assertEquals(8, HevcCodec.inspect(hvcC(emptyList()), null).bitDepthLuma)
        assertFailsWith<ImageDecodeException> { HevcCodec.inspect(hvcC(emptyList()), sample(slice)) }
    }

    @Test
    fun aUnitOfAnotherLayerIsIgnored() {
        // nuh_layer_id 1 on a broken SPS: it must not be read at all.
        val other = byteArrayOf(0x42, 0x09, 0xFF.toByte(), 0xFF.toByte())
        assertNotNull(HevcHeaderReader.read(config(listOf(vps, other, sps, pps)), sample(slice)))
    }

    @Test
    fun outOfRangeFieldsAreNamed() {
        // sps_seq_parameter_set_id is the ue(v) at bit 120 of the SPS's RBSP. 000010001 codes 16, past its range.
        val rbsp = unescapeNal(sps)
        rbsp[15] = 0x08
        rbsp[16] = (rbsp[16].toInt() or 0x80).toByte()
        val r = BitReader(rbsp)
        readNalHeader(r)
        val e = assertFailsWith<ImageDecodeException> { readSps(r) }
        assertEquals("HEVC: sps_seq_parameter_set_id 16 is out of range", e.message)
        assertNull(HevcHeaderReader.read(config(emptyList()), null))
    }

    @Test
    fun truncationAtEveryByteGivesAHeaderOrADecodeError() {
        val units = listOf(vps, sps, pps, slice)
        withDeadline("HEVC header truncation", 20_000) {
            for ((k, unit) in units.withIndex()) {
                for (cut in 0 until unit.size) {
                    val cutUnits = units.mapIndexed { i, u -> if (i == k) u.copyOf(cut) else u }
                    try {
                        HevcHeaderReader.read(config(cutUnits.take(3)), sample(cutUnits[3]))
                    } catch (_: ImageDecodeException) {
                        // A cut unit fails as malformed data, naming what is cut.
                    }
                    // The test kit's hvcC writer reads each unit's type from its first byte.
                    if (cut > 0) {
                        try {
                            HevcCodec.inspect(hvcC(cutUnits.take(3)), sample(cutUnits[3]))
                        } catch (_: ImageDecodeException) {
                        }
                    }
                    try {
                        HevcConfig.parse(hvcC(units).copyOf(cut))
                    } catch (_: ImageDecodeException) {
                    }
                }
            }
        }
    }

    @Test
    fun aMalformedRbspFailsAsSuch() {
        // A ue(v) prefix of 32 zero bits is no code, and an SPS with no stop bit has no end.
        val zeros = byteArrayOf(0x42, 0x01) + ByteArray(8)
        assertFailsWith<ImageDecodeException> { HevcHeaderReader.read(config(listOf(zeros)), null) }
        val r = BitReader(byteArrayOf(0, 0, 0, 0, 0, 1))
        val e = assertFailsWith<ImageDecodeException> { r.ueLong("x") }
        assertTrue(e.message!!.contains("x"), e.message)
    }

    @Test
    fun emulationPreventionBytesAreRemoved() {
        // 7.3.1.1: a 0x03 after two zero bytes goes, even last in the unit; one after a single zero stays.
        assertEquals(hex("42010000000001000303"), unescapeNal(hex("420100000300000301000303")))
        assertEquals(hex("4201000000"), unescapeNal(hex("420100000300")))
    }

    private fun assertEquals(expected: ByteArray, actual: ByteArray) = assertEquals(expected.toList(), actual.toList())

    private fun hvcC(units: List<ByteArray>) = io.github.yuroyami.imagekodec.AnnexB.hvcC(units)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
