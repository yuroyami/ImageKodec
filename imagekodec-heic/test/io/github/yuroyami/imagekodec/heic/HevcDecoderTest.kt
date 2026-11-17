package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.PictureHash
import io.github.yuroyami.imagekodec.withDeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Three tiny intra pictures from libx265, with the loop filters off, decoded on every platform and
 * held to the MD5 that each carries in its decoded picture hash SEI (D.3.20). Then every cut and many
 * altered bytes of one of them must give a picture or an ImageDecodeException, within a deadline.
 */
class HevcDecoderTest {

    private fun nal(vararg parts: String): ByteArray {
        val s = parts.joinToString("")
        return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    /** 48x48, 4:2:0, 8 bits, CTBs of 16, wavefront rows and two slices: x265 with hash=1:keyint=1:no-deblock=1:no-sao=1:ctu=16:min-cu-size=8:wpp=1:slices=2:qp=30. */
    private val wavefront420 = listOf(
        nal("4201010408000003009fa800000300001ea0620c596eaaf0bc05a02000000300200000030321"),
        nal("4401c17182a480"),
        nal(
            "2801ad60d350a130cd2d8e8ff9099ba3181156e56f06d3198b41f7cbd05381dc301af04a58a17ec456a9b36e1f6874f1506b9aa5fa8067f7613e20cb",
            "90c768fb81c93f8684c47f86675a14c8a51c44c546d128972ed6b99d5cdec5488b4140924d7a957ff346209943bd4ae74dd24a13b202593ab0135dc9",
            "748de9c22976c30aed31eb08293e0630ecae5e6e082fb01ced90972c10640a82264774f0770c2dd15d57c7d4934ebf44820ed6ffe94a097decc2420e",
            "6ca225e11adf1ce24827e3e3aacf300bf0300c054cdbd2316b3034b940d3724a1e194fb2784313837354a42e5e0b51d9c8d67f5a5f030133ed4d9297",
            "fb96bd0ede9c6b9a487c41d642fc17528ff62d95e3d9d63e3678e210d44c67fe565a43d9e6b662d939317b1049adac1f33c0ac2ef8e444a762dde024",
            "313f1df5f5106f401a183d79199e264f826909ff91c64ddf2de30404b66467957a7960f44e7fe831e3302c09c47db8128ba75f8821e2abddff49ce0e",
            "24b1ab348357270e8c037a7e6ec62cd86d579244bf324c1519e4eeb35153d2a35453943eab909ad82568ec0ac4f9b0d020093314e7c405044264aeec",
            "f7108e55b17c943351c01cb8e8e4087f68b94272ed7a2a327003",
        ),
        nal(
            "280126d235f0a198bfeb809ab39b6956dd11048206bdc44c2eaabbf4672be81a38a229d1d7d909480bff7bb3ad16498e54c795b55d689be7aeeed205",
            "7fc63f528e38d76fffe1ac36f300b51f7fc008e6cf45f18db96ae9f79185a21cd2f71fb3d28b84e2c91450abeab0cb2919a57cc37a0932e31f949f33",
            "429d5bdc8716ced8e9ddfc611d349f05385d6f5d925801691106f82baf5a26117a60",
        ),
        nal("500184310064cc437bdfa77db782cd5d5b7188d0cf02a1f38e651126eb824d69b4bf127fdd9708bde846c62494e672163e0e07ccea80"),
    )

    /** 24x16, 4:2:2, 10 bits: x265 with ctu=16:min-cu-size=8:qp=28 and the same loop filter and hash settings. */
    private val chroma422 = listOf(
        nal("4201010408000003009d2800000300001eb0c844d96eaaf0bc05a02000000300200000030321"),
        nal("4401c17181a480"),
        nal(
            "2801adc03f8d630f640d6e2a5aa219020a9af35ca5a3a807f46f7f41fc3b62076e21bb62586cee04a507c4d9f787ef4dfaba1875e1be8c85e7adfe70",
            "342ec88d9ab5fafdc179bef05243774aadcce6ef239575d8e45925816381c8c1330013b2f265bf9d3b579653d5cd43088bca9605a8fe23c2659b7ecd",
            "bdb33c84ac099a3cf220794c75d0226caff5e7ea09cb0fde59ae44cfd04688726456e0cfea2d23e3cc202a1006013fb132c4dee847a694c9fd120378",
            "d156b48f738d00b1d16a4ae37e46dde19a4f5bebd6f9731128439e5942b661e38037f0b22fbc60b27ef3c2626ef6a1067b17471a59313b03fb203e39",
            "3a8b0afb22e02df1dcaa90058a523ac5f2d9bde64576ba8c024c88bfcc4ae0f5569e7c491c0b63df9ac25f614120f93e2ccce5436a62fe97793430f5",
            "c9f616102f491da2c4d0314b40",
        ),
        nal("50018431000e0c8c90eb379bdffb35a7997fd1bc321d6a60e29335d3cf0c7e01d4e5a4bc8fc68f36951c63d776124fffbe84461f4880"),
    )

    /** 16x16, 4:4:4, 12 bits, transform skip: x265 with ctu=16:min-cu-size=8:tskip=1:qp=30 and the same settings. */
    private val chroma444 = listOf(
        nal("420101040800000300982800000300001e9011088a52dd55e1780b4040000003004000000642"),
        nal("4401c17430601a48"),
        nal(
            "2801ad40d3514aea486696c74801eff567c7f1ee0ef018678efb8f5f36e7475ddb24d094dc748f44431b94820bef4db23caf02aa61a980a69b68987c",
            "9101bc9ffadcd9eb742a6152bccfd7df1077ca10ea3f4ba3e5e3e1469b4e969e17bbe5cbf1e7cce1e750683c04fb419d3760864a59c3eb5553475ea9",
            "a959cea16a6ba747029c5b841513ebe32e618b48e3ef17c8fa77734e0bc114950649a6c8f4b8e260fe25fc04f6",
        ),
        nal("5001843100bab3089b57a7f247d091d5237ea3c6b14d296db0ededf7a5f650bd71176cb22f7dcfaa37d6f0a130e3abcc8b1ca1d1e180"),
    )

    private fun decode(units: List<ByteArray>): List<DecodedPicture> {
        val d = HevcDecoder()
        for (u in units) d.decodeNal(u, 0, u.size, null)
        return d.finish()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun assertMatchesHash(name: String, units: List<ByteArray>): DecodedPicture {
        val p = decode(units).single()
        val hash = assertNotNull(p.hash, "$name: no hash SEI")
        assertEquals(0, hash.hashType)
        for (c in p.planes.indices) {
            val w = if (c == 0) p.sps.width else p.sps.width / p.sps.subWidthC
            val h = if (c == 0) p.sps.height else p.sps.height / p.sps.subHeightC
            val depth = if (c == 0) p.sps.bitDepthY else p.sps.bitDepthC
            val md5 = PictureHash.md5(PictureHash.pictureData(p.planes[c], 0, p.strides[c], w, h, depth))
            assertEquals(hex(hash.values[c]), hex(md5), "$name: plane $c")
        }
        return p
    }

    @Test
    fun wavefrontRowsAndTwoSlices() {
        val p = assertMatchesHash("4:2:0 WPP", wavefront420)
        assertEquals(48 to 48, p.sps.width to p.sps.height)
        // The features the fixture is for, read from the stream.
        val store = ParameterSetStore()
        val slices = wavefront420.mapNotNull { u ->
            val r = BitReader(unescapeNal(u))
            val nal = readNalHeader(r)
            if (NalType.isSlice(nal.type)) readSliceHeader(r, nal, store, null, u.size) else null.also { store.add(nal, r) }
        }
        assertEquals(2, slices.size)
        assertTrue(slices[0].pps.entropyCodingSync)
        // The second slice holds two CTB rows, so its second row starts from the contexts the first stored.
        assertEquals(1, slices[1].entryPointOffsets.size)
        assertEquals(4, slices[0].sps.ctbLog2)
    }

    @Test
    fun chroma422At10Bits() {
        val p = assertMatchesHash("4:2:2 10 bits", chroma422)
        assertEquals(2 to 10, p.sps.chromaFormatIdc to p.sps.bitDepthC)
    }

    @Test
    fun chroma444At12BitsWithTransformSkip() {
        val p = assertMatchesHash("4:4:4 12 bits", chroma444)
        assertEquals(3 to 12, p.sps.chromaFormatIdc to p.sps.bitDepthY)
    }

    @Test
    fun aHeicItemDecodesThroughTheCore() {
        ImageKodecHeic.install()
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
        val hvcC = box("hvcC", AnnexB.hvcC(wavefront420.take(2))) to true
        val ispe = box("ispe", ByteArray(4) + be32(48) + be32(48)) to false
        val heic = HeifBuilder("heic", listOf("mif1", "heic")).apply { add(1, "hvc1", AnnexB.sample(wavefront420.drop(2)), listOf(hvcC, ispe)) }.build()
        val info = ImageKodec.probe(heic)
        assertTrue(info.isDecodable, info.unsupportedReason)
        val bitmap = ImageKodec.decode(heic)
        assertEquals(48 to 48, bitmap.width to bitmap.height)
    }

    /** One altered slice NAL unit: it must give a picture or an ImageDecodeException, and never anything else. */
    private fun survives(units: List<ByteArray>, index: Int, slice: ByteArray, what: String) {
        val altered = units.toMutableList().also { it[index] = slice }
        try {
            decode(altered)
        } catch (e: ImageDecodeException) {
            // The one legal failure.
        } catch (e: Exception) {
            fail("$what: ${e::class.simpleName} ${e.message}")
        }
    }

    @Test
    fun everyCutAndAlteredByteFailsCleanly() {
        val index = chroma422.indexOfFirst { NalType.isSlice((it[0].toInt() shr 1) and 0x3F) }
        val slice = chroma422[index]
        withDeadline("HEVC truncation", 60_000) {
            for (n in 2 until slice.size) survives(chroma422, index, slice.copyOf(n), "the slice cut to $n bytes")
        }
        withDeadline("HEVC mutation", 60_000) {
            for (at in 2 until slice.size) for (x in intArrayOf(0x01, 0x10, 0x80, 0xFF)) {
                val m = slice.copyOf()
                m[at] = (m[at].toInt() xor x).toByte()
                survives(chroma422, index, m, "byte $at of the slice xor $x")
            }
        }
    }
}
