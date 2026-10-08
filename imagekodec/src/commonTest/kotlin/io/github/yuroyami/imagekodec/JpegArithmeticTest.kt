package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arithmetic-coded JPEG (#19) on every target. libjpeg's `cjpeg -quality 85 -optimize` wrote
 * [huffman], 21 by 13 pixels of noisy shading at 4:2:0, and `tools/arith_jpeg.c` re-encoded its
 * coefficients with arithmetic coding: [sequential] with DAC conditioning L 2, U 6, K 20 and a
 * restart every 2 MCUs, [progressive] with L 1, U 5, K 3, libjpeg's default progression and a
 * restart every MCU. The coefficients are the same, so the pixels must be.
 */
class JpegArithmeticTest {

    internal val huffman = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb0043000503040404030504040405050506070c08070707070f0b0b09" +
        "0c110f1212110f111113161c1713141a1511111821181a1d1d1f1f1f13172224221e241c1e1f1effdb004301050505070607" +
        "0e08080e1e1411141e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e" +
        "1e1e1e1e1e1e1e1effc0001108000d001503012200021101031101ffc4001700000301000000000000000000000000000006" +
        "0705ffc400261001000102050304030000000000000000010203110004051241062131132251a1156171ffc4001601010101" +
        "00000000000000000000000000070506ffc40027110000040307050100000000000000000001031112000204050713213132" +
        "41066162728142ffda000c03010002110311003f0043e8dcad5cee5e9d0253a4035192a097b87dadede2ff00cc3f69790a51" +
        "8d3d1ca7bf7c929cf9a84a449bc78f27bafc7930b3d0b3a7aad3a597297a248dc5d2606e7b5addf9e79c5074fcc067a7a6ca" +
        "9132ec24abb65246d2dbfab7ce2a5ab77f2819316d44cdaba793b9f58d7f5fda440d101c332ae4f4dde0de3dbb4503a5b47f" +
        "c6e9c3448e63d4017734a25b83b777bbf5f3831abd291a7a66914e569cdacb7613d8fb542ea37fac1835a8bbc034d9a702c0" +
        "ccf739abf1724d3e2c12136b53d3c80562e127e1ae4e7772baf6548fffd9" +
        "",
    )

    internal val sequential = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb0043000503040404030504040405050506070c08070707070f0b0b09" +
        "0c110f1212110f111113161c1713141a1511111821181a1d1d1f1f1f13172224221e241c1e1f1effdb004301050505070607" +
        "0e08080e1e1411141e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e" +
        "1e1e1e1e1e1e1e1effc9001108000d001503012200021101031101ffcc000a0062101401621114ffdd00040002ffda000c03" +
        "010002110311003f00ff009796c3ee54fc112836b264a3e34f17186c4c6b1fb43d94b6e5006f7fdf7a0338c04643de79f4f6" +
        "69b27ac5e00455cc04c2d1fb1149baf5be446854caf3d27a21868a3cff0078eddabffcfe04d707bba47f82f37d66b80a4f47" +
        "de21b182ee57e044473c049e088e9c7d9f9b7dccc9727f346fdbccd8de925fad1cf6e652b51a015bb5964e2b40bf38a44745" +
        "3160c58b3493a02087057219e32ab6cd6f5c6439020fb4c8199578667516fa80f310353d6eb810a30b3675118e282bda0bdc" +
        "58aa9f8c12dbd0b56a55e47fc4a959d6f64ac5b2068c18b625b19b4fcfa83d20ffd9" +
        "",
    )

    internal val progressive = hex(
        "ffd8ffe000104a46494600010100000100010000ffdb0043000503040404030504040405050506070c08070707070f0b0b09" +
        "0c110f1212110f111113161c1713141a1511111821181a1d1d1f1f1f13172224221e241c1e1f1effdb004301050505070607" +
        "0e08080e1e1411141e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e1e" +
        "1e1e1e1e1e1e1e1effca001108000d001503012200021101031101ffcc000600510151ffdd00040001ffda000c0301000210" +
        "0310000001ff0034dd0e63748970ffd0d2640b268382ffcc00041003ffda0008010100010502159e022830ffd0144360ffd1" +
        "159b9e70ffd216000b98ffd31a97e0ffd41595820affcc00041103ffda0008010301013f013af6f91217c797fd5250ff0064" +
        "df80ffd017bb241ca01b2497b9f6c760ffcc00041103ffda0008010201013f01398de36877ea77fe57b9dbd980ffd0442506" +
        "d8e03e08ec6940ffcc00041003ffda0008010100063f0210b3ebffd01213734346ffd16e15eee5ffd2135850ffd3a150ffd4" +
        "084cffcc00041003ffda0008010100013f21d4caa0ffd049022102b43226c8ffd1643ceb70ffd2d2fe3763ffd35ecdd22033" +
        "64a0ffd4f2ae21cc06ffda000c0301000200030000001068ffd0d4ffcc00041103ffda0008010301013f10cccbb8ef8a995a" +
        "bdffd0615dfa07d423eab478ffcc00041103ffda0008010201013f10b6db8961ecc348ffd01d333d12a2ea2a600758ffcc00" +
        "041003ffda0008010100013f1094a34bfa65f4009bcab70e05d8ffd00a6b125f4a27d8c2bbb4ffd1b094e412fb1ca5b49b00" +
        "93d0ffd23d6a82c7f4e6720e854cffd3a202d0b0eab6063d30ffd4b777f11aeee107154b64ffd9" +
        "",
    )

    @Test
    fun arithmeticFilesDecodeToThePixelsOfTheirHuffmanOriginal() {
        for (reduction in listOf(1, 2, 4, 8)) {
            val expected = ImageKodec.decodeReduced(huffman, reduction)
            for ((name, jpeg) in listOf("sequential" to sequential, "progressive" to progressive)) {
                val actual = ImageKodec.decodeReduced(jpeg, reduction)
                assertEquals(expected.width, actual.width, "$name 1/$reduction")
                assertEquals(expected.height, actual.height, "$name 1/$reduction")
                assertContentEquals(expected.argb, actual.argb, "$name 1/$reduction")
            }
        }
    }

    @Test
    fun probeCallsThemDecodable() {
        for (jpeg in listOf(sequential, progressive)) {
            val info = ImageKodec.probe(jpeg)
            assertTrue(info.isDecodable, info.unsupportedReason)
            assertEquals(21, info.width)
            assertEquals(13, info.height)
        }
    }

    @Test
    fun aTruncatedArithmeticFileStillDecodes() {
        // The decoder reads zeros once the data ends, as libjpeg does, so the image keeps its size.
        val cut = sequential.copyOfRange(0, sequential.size - 60)
        val bitmap = ImageKodec.decode(cut + byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        assertEquals(21, bitmap.width)
        assertEquals(13, bitmap.height)
    }
}
