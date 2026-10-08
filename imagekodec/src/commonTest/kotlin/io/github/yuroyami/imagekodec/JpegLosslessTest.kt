package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Lossless JPEG (#19) on every target. [losslessJpeg] writes Huffman-coded files at every
 * precision, predictor and point transform, which must decode to their source exactly, and the
 * arithmetic-coded (SOF11) files below come from the T.81 reference software's `jpeg -p -a -c`
 * at release 1.70, of the pictures [picture] draws: 12-bit gray with predictor 7 and a restart
 * every row, 8-bit RGB with predictor 6, and 16-bit gray with predictor 5 and a restart in the
 * middle of each row, which that software writes and reads by restarting only the entropy decoder.
 */
class JpegLosslessTest {

    internal val gray12 = hex(
        "ffd8ffdd00040013ffee000e41646f626500640000000000ffcb000b0c000b001301001100ffcc00040010ffda0008010000" +
        "070000ff00fc1cbbad2a6387e0a2127e556185a0ffd0ff00fc931d6ecb20b94ea29a6173e221526681c73da9462f3b710033" +
        "94f4ffd1ff00fce33fec84deed4f25ce9e007cddf8b806db352dd842292bd8ed5afbc00fe080ffd2ff00fd16271f4e608979" +
        "b1792e5fc09b69036db9046efbe77d5c81fe32cddd4b123040ffd3ff00fd2e4f0a22601186269289293572f55fee6836c793" +
        "2ac492be49060fbcc0ffd4ff00fcf8b009b317b79472710a3f78eabbd0a5de4fafb46fd0124dd10b83cb4579222943173526" +
        "60ffd5ff00fb8d291117a656462d7c4dddf18a94bcc17e28865cebe23b8b38c344d8fb2815e8ffd6ff00fb97340602102cdf" +
        "88be173ef1e6939fc8ef0d89fbe8c6ac097eab6bdd6fdb027333cc18ffd7ff00fbb4420432150f6a8acc8ce5634042466aec" +
        "a1fc8b20fd017e43a04cf337c4ef5f0d6014ffd0ff00fb4d7fc05d28000729b6922cb85b1674e2c6424d102330f0fc778d8b" +
        "65c228afb5f23cd280ffd1ff00fb2f0e90a665bcecefa341007115276ee4e82010b218d04b03c5c3657ee0ffd9" +
        "",
    )

    internal val rgb8 = hex(
        "ffd8ffee000e41646f626500640000000000ffcb0011080009000d03001100011100021100ffcc00040010ffda000c030000" +
        "01000200060000ff00c2ef03c835d1a008aa855f50f4dfef97d252fbe5328124a5935dc8a9873087746150abb98408a83df1" +
        "6d767801d567b35417db9b68d2178603d9dcff0001f342e36692e69d070b636e68d9c725ecf7d96cc2d49a2ff935092e694e" +
        "37fc1694f997685aa527a491086ff3d075c5b853723273ad9490ced77cc412c6324b69f3b2dede51ae4422f97cf0a5c8f0fe" +
        "d164430041e735f2b794fca2e352b2712bc9e894c87bf4af09ce9010236925adc38df4c9a0a4443a5835b14bf0259955f108" +
        "dda07b56aa57a8c7f508f939ff003f91fad49ce789b8706014643c45e824901480630033585eb4babc52fc9e843c9ad80dcc" +
        "f7e6194552587a0ff1d4bfa65d0a9d9d5da58f45cbe6ea7f796f812fe19629d0997fdbd2cbcd596f35bc6b7f0a60920433ea" +
        "7f94abf4b0d7c150d9eaf0c2b500ae4313380d9c5ae454486b04fc634e4400b06a6ee1ec17327297e7f3284b2aa158877d8a" +
        "d22501e3230ab9c624ce260799f66903e885ff00d83c6e351285bdd1aa8286f9b01f8db1fee4fec4b29628a3bfca40630091" +
        "c7bd0bb6e06cfce0ffd9" +
        "",
    )

    private val gray16 = hex(
        "ffd8ffdd00040005ffee000e41646f626500640000000000ffcb000b100007000901001100ffcc00040010ffda0008010000" +
        "050000ff00ff00c0ca5ad2a680ffd0d25df5b054ce40ffd1d2e05031c687da1e32ffd2d2e05031c687e976d0f0ffd3d2e3dc" +
        "7b5bb6ca8e2affd4ff00fe89a0a1b88e8b6426d8ffd5d2e8b47977991c0680c6a0ffd6d2e8b49bf4bc7973bc50ffd7ff00fe" +
        "7d412c3403cf4081cfb220ffd0ff00ff00c755cf19d7eee88f76aaba2cffd1d2ee795407252c3fa7aca8a2ffd2d2eca9d836" +
        "a42eafe68814a48cffd3d2eff8f19b76145ba0ffd9" +
        "",
    )

    /** The picture the fixtures hold: [channels] samples a pixel of [bits] bits. */
    private fun picture(w: Int, h: Int, channels: Int, bits: Int): IntArray {
        val size = 1 shl bits
        return IntArray(w * h * channels) { i ->
            val c = i % channels
            val x = (i / channels) % w
            val y = (i / channels) / w
            (x * 37 + y * 91 + (x * y) % 13 * 311 + c * 1000) % size
        }
    }

    /** [v] of [bits] bits as `decode16` widens it. */
    private fun widened(v: Int, bits: Int): Int = when {
        bits >= 8 -> (v shl (16 - bits)) or (v shr (2 * bits - 16).coerceAtLeast(0))
        else -> v * 255 / ((1 shl bits) - 1) * 257
    }

    /** [v] of [bits] bits as `decode` narrows it. */
    private fun narrowed(v: Int, bits: Int): Int = if (bits >= 8) v shr (bits - 8) else v * 255 / ((1 shl bits) - 1)

    private fun check(name: String, jpeg: ByteArray, want: IntArray, w: Int, h: Int, channels: Int, bits: Int) {
        val info = ImageKodec.probe(jpeg)
        assertTrue(info.isDecodable, "$name: ${info.unsupportedReason}")
        assertEquals(bits, info.bitDepth, name)
        val wide = ImageKodec.decode16(jpeg)
        val narrow = ImageKodec.decode(jpeg)
        assertEquals(w, wide.width, name)
        assertEquals(h, wide.height, name)
        // At 8 bits or fewer decode16 widens decode's RGB; deeper, gray stays one channel.
        val stride = wide.channels
        for (i in 0 until w * h) for (c in 0 until channels) {
            val v = want[i * channels + c]
            assertEquals(widened(v, bits), wide.samples[i * stride + c].toInt() and 0xFFFF, "$name: pixel $i channel $c of decode16")
            assertEquals(narrowed(v, bits), (narrow.argb[i] shr (if (channels == 1) 0 else 16 - 8 * c)) and 0xFF, "$name: pixel $i channel $c of decode")
        }
        assertContentEquals(wide.toBitmap().argb, narrow.argb, "$name: decode is not decode16's high bytes")
    }

    @Test
    fun writtenFilesComeBackAtEveryPrecisionPredictorAndPointTransform() {
        val w = 13
        val h = 9
        for (bits in listOf(2, 7, 8, 9, 12, 16)) for (channels in listOf(1, 3)) for (predictor in 1..7) {
            val src = picture(w, h, channels, bits)
            for (pt in listOf(0, 1, bits - 1).distinct()) {
                val restart = if (predictor % 2 == 0) 2 * w else 0
                val jpeg = losslessJpeg(src, w, h, channels, bits, predictor, pt, restart)
                val want = IntArray(src.size) { (src[it] shr pt) shl pt }
                check("$bits bits, $channels channels, predictor $predictor, Pt $pt, restart $restart", jpeg, want, w, h, channels, bits)
            }
        }
    }

    @Test
    fun arithmeticCodedFilesFromTheReferenceSoftwareComeBack() {
        check("12-bit gray", gray12, picture(19, 11, 1, 12), 19, 11, 1, 12)
        check("8-bit RGB", rgb8, picture(13, 9, 3, 8), 13, 9, 3, 8)
        check("16-bit gray", gray16, picture(9, 7, 1, 16), 9, 7, 1, 16)
    }

    @Test
    fun aReducedDecodeAveragesTheFullOne() {
        val jpeg = losslessJpeg(picture(13, 9, 3, 8), 13, 9, 3, 8, 4)
        for (reduction in listOf(2, 4, 8)) {
            assertContentEquals(ImageKodec.decode(jpeg).reducedBy(reduction).argb, ImageKodec.decodeReduced(jpeg, reduction).argb, "1/$reduction")
        }
        val components = ImageKodec.decodeJpegComponents(gray12, reduction = 2)
        assertEquals(10, components.width)
        assertEquals(6, components.height)
    }

    @Test
    fun aBadScanIsAFaultNotAFeature() {
        // Predictor 0 belongs to hierarchical differential frames only, and a point transform must
        // leave a bit.
        for ((predictor, pt) in listOf(0 to 0, 8 to 0, 1 to 8)) {
            val jpeg = losslessJpeg(picture(4, 4, 1, 8), 4, 4, 1, 8, 1)
            val sos = (0 until jpeg.size - 1).first { (jpeg[it].toInt() and 0xFF) == 0xFF && (jpeg[it + 1].toInt() and 0xFF) == 0xDA }
            jpeg[sos + 7] = predictor.toByte()
            jpeg[sos + 9] = pt.toByte()
            val e = assertFailsWith<ImageDecodeException>("predictor $predictor, Pt $pt") { ImageKodec.decode(jpeg) }
            assertTrue(e !is UnsupportedImageException, "predictor $predictor, Pt $pt: ${e.message}")
        }
    }
}
