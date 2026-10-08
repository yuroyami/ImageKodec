package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 12-bit DCT JPEG (#109) on every target: libjpeg-turbo 3.1's `cjpeg -precision 12` of a 16 by 12
 * gray ramp, sequential Huffman and progressive arithmetic with a restart every MCU, and its
 * `djpeg -dct int` of each. The inverse DCT is libjpeg's islow at 12 bits, so the samples must
 * be equal.
 */
class JpegTwelveBitTest {

    internal val sequential = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a070706" +
        "080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffc1000b0c000c00100101" +
        "1100ffc4001500010100000000000000000000000000000d0cffc4002c1000010202060a0301000000000000000002010305" +
        "11000408090b12070a0c1b21283854557414313281ffda0008010100003f00a42754af6835b413b822adc50f40b5b288b367" +
        "93abb75958596716ce1a24428b97e954057f94329efdeda0c6d3878de47184d05d7561edda242a84e7c24cc8e94448c5327e" +
        "a5202e3297094e745ac3549f0deae21dc7f1729c1d3ff78cf8cf5e86465fc186f7710efc41394e0ea07bc67ca7af4fffd9" +
        "",
    )

    private val sequentialReference = hex(
        "0abd0d020eef0fff0fff0ffb0eae0cb30a620828064c052804e705900704091a0a680cad0e9e0fd30fff0fbb0e5d0c5c0a0f" +
        "07ce05f804d20491053b06b108c509790bc10dac0ef50f4f0ed20d6f0b6f092606d9050f03e603a3045205c307d9082f0a7c" +
        "0c640da50e0f0d830c270a2807d205a003be029e025703010489068406d909250b130c500cb60c2f0ad008d106870446026c" +
        "014b010701af032a053c05d208150a050b480bab0b2809c407c605740339015d00340004009f0219042b054b0791097a0abe" +
        "0b250a9d093e074104f102ba00db000b0000001b019d03a7056b07c009a40ae40b4f0ac009660766051902d4010400000003" +
        "004201bc03cd0635087e0a6b0bab0c110b8b0a2a082c05df039d01c700a30060010902830493076b09b40ba10ce10d470cc1" +
        "0b600963071504d302fd01d90196023f03b905c908c50b0e0cfb0e3b0ea10e1b0cba0abd086f062d0457033202f003990513" +
        "072309f00c380e250f650fcb0f450de40be7099907570581045d041a04c3063d084d" +
        "",
    )

    internal val progressiveArithmetic = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb00430006040506050406060506070706080a100a0a09090a140e0f0c" +
        "1017141818171416161a1d251f1a1b231c1616202c20232627292a29191f2d302d283025282928ffca000b0c000c00100101" +
        "1100ffcc00040010ffdd00040002ffda0008010100000001d2e831de1050ffd0d2eb664cd198ffcc00041005ffda00080101" +
        "00010502169a192ecf5aac139f7d38c157da40ffd01699ec79aeb637b9fb8d452d7de9ffcc00041005ffda0008010100063f" +
        "028e95f8e7fd00218a0953f9e98abf90ffd0602c94369f612b6d6cb68cc0ffcc00041005ffda0008010100013f21b3d171a9" +
        "daa72bd5a14ea2ffd008b77f41a14314ffda0008010100000010c0ffd0e0ffcc00041005ffda0008010100013f108501e171" +
        "a6a423db1aeaca80c88ae0af44fc70ffd00e2c32a1db43c0ffd9" +
        "",
    )

    private val progressiveArithmeticReference = hex(
        "0abb0d0b0ee80ffd0fff0fff0ea90cb60a620829064c052104ec058c070f09160a660cab0ea10fdb0fff0fbd0e560c5c0a0e" +
        "07cb05f504d10492053506b308c2097d0bb90db20ef90f4e0ecf0d6f0b70091d06e1051503ea03a0045605c907cf08340a7c" +
        "0c620da00e0a0d830c300a2807e9058b03b902a1025402fc0474069906d6092b0b0c0c500cb70c2f0ad708cd068304410273" +
        "014d010a01b60329053305cf081c0a010b500bb00b2209c507c70578033c015c002f000100a0022304290550078d097e0ac2" +
        "0b240a980940074304f702a600e0000700000024018b03aa057307b009b10ae10b4b0ac5096e0761051902da00fd00050001" +
        "004101bd03cd0634087f0a680bab0c100b890a2b082b05dd039c01c700a1005d010802820492076c09b60ba00ce20d480cc0" +
        "0b630963071504d402fe01d90195024003ba05c908c60b100cfa0e3c0ea20e1a0cbc0abd086e062e0458033202ef03990513" +
        "072309f00c3b0e250f670fcc0f450de70be8099907590583045d041a04c4063e084e" +
        "",
    )

    @Test
    fun twelveBitFilesDecodeToLibjpegTurbosSamples() {
        for ((name, jpeg, reference) in listOf(
            Triple("sequential", sequential, sequentialReference),
            Triple("progressive arithmetic", progressiveArithmetic, progressiveArithmeticReference),
        )) {
            val info = ImageKodec.probe(jpeg)
            assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
            assertEquals(12, info.bitDepth, name)
            val wide = ImageKodec.decode16(jpeg)
            assertEquals(1, wide.channels, name)
            val want = IntArray(16 * 12) {
                val v = ((reference[2 * it].toInt() and 0xFF) shl 8) or (reference[2 * it + 1].toInt() and 0xFF)
                (v shl 4) or (v shr 8)
            }
            assertContentEquals(want, IntArray(wide.samples.size) { wide.samples[it].toInt() and 0xFFFF }, name)
            assertContentEquals(wide.toBitmap().argb, ImageKodec.decode(jpeg).argb, name)
        }
    }
}
