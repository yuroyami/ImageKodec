package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hierarchical JPEG (#19) on every target. [hierarchicalLosslessJpeg] writes lossless
 * hierarchies as T.81 Annex J describes them, which must decode to their source exactly at
 * every precision. The DCT-based files below come from the T.81 reference software
 * (`jpeg -q 85 -h -y 2` and `jpeg -q 80 -a -v -y 2` at release 1.70), with its own decode: that
 * software keeps its frames in fixed point between them, where T.81 hands integer samples
 * from frame to frame, so the two agree within a few levels, not exactly.
 */
class JpegHierarchicalTest {

    internal val dctGray = hex(
        "ffd8ffdb0043000503040404030504040405050506070c08070707070f0b0b090c110f1212110f111113161c1713141a1511" +
        "111821181a1d1d1f1f1f13172224221e241c1e1f1effee000e41646f626500640000000000ffde000b08000d001301001100" +
        "ffc1000b080007000a01001100ffc40033000101000000000000000000000000000004071000010205050000000000000000" +
        "00000001053300020406410714173151ffda0008010000003f0056b7aedcfc5140cf43222729ebb746c29d96a5c8f047ffdf" +
        "000311ffc5000b08000d001301001100ffc40038000003010000000000000000000000000001020304100000050206030000" +
        "00000000000000000001020304136114212231415152b1d1ffda0008010000003f00686e961919f16b0479d2ef8b0bb0e152" +
        "4e7e85a1c74e191a8f6f80bd1d3e47b0d8c474d24ea31fffd9" +
        "",
    )

    private val dctGrayReference = hex(
        "a7c3d7edf3f7f0e1cbb3987f6860565b657a8fa5c1d6ebf2f5eedfc9b1967e675e545963788d9bb7cbe0e7ebe4d5bfa78c73" +
        "5c53494f596e838faabfd4dbdfd8c8b39a806750473d434d61777f9bafc4cbcfc8b9a38b705740382d333d52676d899eb3ba" +
        "bdb7a791795e462f261c212b4056637f94a9b0b3ad9d886f543c251c121722364c5975899fa6a9a2937d654a321b12080d18" +
        "2c425773889da4a7a1917c6348301910060b162a405c788da2a9aca69681684e351e150b101b3045648094aab1b4ad9e8870" +
        "553d261d131823384d748fa4b9c0c4bdae987f654c352c222833475d829eb3c8cfd2ccbca78e745b443b313642566c" +
        "",
    )

    internal val dctRgbProgressive = hex(
        "ffd8ffdb00840006040506050406060506070706080a100a0a09090a140e0f0c1017141818171416161a1d251f1a1b231c16" +
        "16202c20232627292a29191f2d302d283025282928010707070a080a130a0a13281a161a2828282828282828282828282828" +
        "282828282828282828282828282828282828282828282828282828282828282828282828ffde001108000b00110300110001" +
        "1100021100ffca0011080006000903001100011100021100ffcc000a0010011010051105ffda000c03000001110211000001" +
        "fc2b7dbd8b923816ffcc000600101005ffda00080100000105023ab518aef1b0ffcc000601101105ffda0008010111013f01" +
        "15ba568a80ffcc000601101105ffda0008010211013f0119b94280ffcc000600101005ffda0008010000063f021cffcc0002" +
        "ffda0008010000013f2102c57b80ffcc0002ffda000c03000001110211000010d8ffcc0002ffda0008010000013f108acba0" +
        "5473ffcc0002ffda0008010111013f104bcaffcc0002ffda0008010211013f10ae5880ffdf000311ffce001108000b001103" +
        "001100011100021100ffcc000a0010011010051105ffda000c03000001110211000001a8a1822152b728ffcc000600101005" +
        "ffda0008010000010502556e20a4ffcc000601101105ffda0008010111013f013c01e6a1e620ffcc000601101105ffda0008" +
        "010211013f011c72e6e824ffcc000600101005ffda0008010000063f02c0ffcc0002ffda0008010000013f21ea2009290864" +
        "7690ffcc0002ffda000c0300000111021100001074a480ffcc0002ffda0008010000013f10165f9ce47bb0a82508b0ffcc00" +
        "02ffda0008010111013f105b4a28ffcc0002ffda0008010211013f10ab6fe5b0ffd9" +
        "",
    )

    private val dctRgbProgressiveReference = hex(
        "a9ebf0c3f4e1dcfbcfeaf0b2f8e298f7cb84f5b56be29a5dce7f57b36e5d985b64815a7a6a5a916268ac5879c95c92dc67ac" +
        "eea4e7ebbef0dcd7f6cae6ebadf3de93f2c77ff0b066dd9559ca7a52ae695893565f7c557666558c5d63a85475c4578dd763" +
        "a8e99ee0e5b8e9d6d1f0c4dfe4a7edd78decc078e9a960d78f52c3744ca862528d5059764f6f5f4f86565da14d6ebe5187d1" +
        "5ca1e28ed0d5a7d9c6c0e0b4cfd496dcc77ddbb068d99950c77e42b3643b9852427c3f48653f5f4f3e76464d913d5ead4077" +
        "c14c91d27fc1c699cab7b2d1a5c0c688ceb96ecda25acb8b41b87033a4552d8944336e313a573051403067383e822e4f9f32" +
        "68b23e82c472b4b98cbdaaa4c498b3b87ac0ab61c0944cbd7d34ab622697481f7c362660242c4a234333225a2a3175214291" +
        "255ba53075b661a4a87bad9994b387a3a86ab09b50af843cad6d249a521687370f6b261550131c39123323124a1a20651132" +
        "81144a942065a65a9ca174a5928dac809ba163a99449a87d35a6661c934b0f803008641f0e490c15320b2c1c0b4213195e0a" +
        "2a7a0d438d195e9f56999c70a18f89a87e979c60a59044a4792fa262188f470c7d2e06621c0c470a1230092919094010175b" +
        "0728780b418b165b9d5d9fa277a89590af859ea367ac964bab8036a8681f964d1384340c6823124d1019360f30200f47171d" +
        "620e2f7e1147921c61a264a7aa7eb09d97b68ca6ab6eb39e52b2873db070269d551a8b3c13702a1a5417203d173727164e1e" +
        "2569153685194f992267a8" +
        "",
    )

    /** A picture of [channels] samples a pixel of [bits] bits, every value reached somewhere. */
    private fun picture(w: Int, h: Int, channels: Int, bits: Int): IntArray {
        val size = 1 shl bits
        return IntArray(w * h * channels) { i ->
            val c = i % channels
            val x = (i / channels) % w
            val y = (i / channels) / w
            (x * 37 + y * 91 + (x * y) % 13 * 311 + c * 1000) % size
        }
    }

    @Test
    fun losslessHierarchiesComeBackExactly() {
        for (bits in listOf(2, 8, 12, 16)) for (channels in listOf(1, 3)) {
            for ((w, h) in listOf(23 to 17, 16 to 8, 5 to 3)) for (levels in 1..3) for (vertical in listOf(true, false)) {
                val src = picture(w, h, channels, bits)
                val jpeg = hierarchicalLosslessJpeg(src, w, h, channels, bits, levels, vertical, refinements = if (levels == 2) 1 else 0)
                val name = "$bits bits, $channels channels, ${w}x$h, $levels levels${if (vertical) "" else " across only"}"
                val info = ImageKodec.probe(jpeg)
                assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
                assertEquals(w, info.width, name)
                assertEquals(h, info.height, name)
                val wide = ImageKodec.decode16(jpeg)
                assertEquals(w, wide.width, name)
                assertEquals(h, wide.height, name)
                for (i in 0 until w * h) for (c in 0 until channels) {
                    val v = src[i * channels + c]
                    val want = if (bits >= 8) (v shl (16 - bits)) or (v shr (2 * bits - 16).coerceAtLeast(0)) else v * 255 / ((1 shl bits) - 1) * 257
                    assertEquals(want, wide.samples[i * wide.channels + c].toInt() and 0xFFFF, "$name: pixel $i channel $c")
                }
                assertContentEquals(wide.toBitmap().argb, ImageKodec.decode(jpeg).argb, name)
            }
        }
    }

    @Test
    fun dctHierarchiesFromTheReferenceSoftwareMatchItsDecode() {
        for ((name, jpeg, reference, channels) in listOf(
            Quad("gray, sequential Huffman", dctGray, dctGrayReference, 1),
            Quad("RGB, progressive arithmetic", dctRgbProgressive, dctRgbProgressiveReference, 3),
        )) {
            val info = ImageKodec.probe(jpeg)
            assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
            val bitmap = ImageKodec.decode(jpeg)
            assertEquals(reference.size, bitmap.width * bitmap.height * channels, name)
            for (i in 0 until bitmap.width * bitmap.height) for (c in 0 until channels) {
                val ours = (bitmap.argb[i] shr (if (channels == 1) 0 else 16 - 8 * c)) and 0xFF
                val theirs = reference[i * channels + c].toInt() and 0xFF
                assertTrue(abs(ours - theirs) <= 3, "$name: pixel $i channel $c is $ours against the reference software's $theirs")
            }
        }
    }

    @Test
    fun framesCoveringTheImageManyTimesOverAreAFault() {
        // Each frame costs a pass over its area, data or not: a pyramid and a refinement cover
        // about twice the image, and eight refinements more than eight times.
        val src = picture(16, 16, 1, 8)
        ImageKodec.decode(hierarchicalLosslessJpeg(src, 16, 16, 1, 8, levels = 2, refinements = 5))
        val e = assertFailsWith<ImageDecodeException> {
            ImageKodec.decode(hierarchicalLosslessJpeg(src, 16, 16, 1, 8, levels = 2, refinements = 8))
        }
        assertTrue(e !is UnsupportedImageException, e.message)
    }

    private data class Quad(val name: String, val jpeg: ByteArray, val reference: ByteArray, val channels: Int)
}
