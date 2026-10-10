package io.github.yuroyami.imagekodec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// cwebp 1.6.0, -lossless -exact -m 0: all channel values occur twice.
internal const val WEBP_UNIFORM_HISTOGRAM =
    "524946464c060000574542505650384c3f0600002f1fc00300cd7421a2ff0185000010b66ddbb66ddbb66ddbb66ddbb66ddb" +
    "b63614000040fcffffffffffffffffff7f0a000020feffffffffffffffffffbf88049bd10d185a9931dd92f7f3f4f31d59d0" +
    "b3a5233b1ac292940d180e45cf887254922a30eaa106ded1d9846599c08c74f09da12bd44e3cb2ae02965a3a13c92816c535" +
    "25ec1928f50524742ebd1538d61f2dc057d72dbbbd47314af2280e3b933460ccbe05021814c69905ca54c41a010270a4520a" +
    "2b490c92122f506310384038a9fee281070dc50340a032814c60192d29f8e6d9b0b2cf12db3884eaf2f2aa69f156bf8e0ea5" +
    "260eaf8f1e45009ba523a687cc63d92535ec6b3aa3a259c718ba5a33ba0dd6e01ae87b33019b7b4e355b07bec6e9212e55b2" +
    "b9132d4c8d86787044ef83e0c3e63cd99c2eb1e9785b983b76f0977855967d230f73acb65b2f58ad6fa6bb8773594e2093bd" +
    "aa8e291ea56b79c03b663b07c23b7f14122a79b1127cbd2b107ace1267874057032736ecd57a3d4b3b9182e4a500dd0fe476" +
    "81d8188f428f390afde982f584eec7c92587900489dcc47e31c145e4aa822787ec469860024d9f8f880def85a3a773a5cbaf" +
    "730a97d3daa36009797ced0e247842aa6fcf811fefd7fedfe839479699ad9d47e433d796309f76239e1636e761ec40225c08" +
    "cebd3e8360274c1c9bff59bf2f6908517172e11ca8ce03a92c0a4ba40cd269a5925bc5ddba265b95735b10b79d1594350a00" +
    "92a8017e70f048c3e1f5bf07a1688067a60cf4efbdff2655f02e9b05273ee8e72fa48a92775244046369fed0b99a13a53530" +
    "279d2ce71fbd49fc978199daa8e2c2d03240210d1557a34d86e04308ede12d68d7163d0f5610c4bcfe7cd36ba3f72adad706" +
    "1be9f4f48c69a1e41ceb4c6034fbf4b93afeefb6a06465847aa5c1a1e13a0970ec6e01b231c73843d9a91f11b483bd450150" +
    "4438cb732a160bc9c98d49a2b56e65387574e3e5be6d4fcb92eeafd6325ff37647b4e49f182e3e995a71ebcf536efa83332f" +
    "ee9563c6189284a2c310997a08cb2df5318aaa6a29c33c65a4fe76efc0f6488ceb494ffcf21a70126f58d66478a0c93928b7" +
    "57b5c5211b67d718559fec6af7886938d81a9d8186115d667266fd35dcbe9470f9ea5511b717ac090365f2d9a01e323dfe4a" +
    "5ccc018d5c4e20b512ff6fd6baf69737abc3a54e049320898ebeafae053e25648c7711161f3297adb978b290c3a8eaaa1bc2" +
    "79aa3d3826414ca00591e65a23a111bf39a2e51fcadbe237640f1077ce8dbef1e23f38eb9f153ad2476a57c3720dbb958c92" +
    "d05abec81f54bcf1d54c8b1c42f843287f5c81255f9f46ee775018c110905043191b1548720b6d9249113431e885645b5efe" +
    "e4688e63b830b0767542d8d77db3de9414459f47c92c81d82da1d5dfd8eee319eb643777289d48e5c446edf14a335d67754d" +
    "d573691bd03f2355bdf050631992b0d2df7a025578007efa47663397e87d5cfac178224111dd053485807281d21bdab06cba" +
    "d18b891f612ec03b13916088d78bb9fbbbffb6d00eeb45f8073bbcac88f54ae83672f05ec4afb0ea64f233304bbc31b42e4c" +
    "6bcf8e61e9f619fa546bbc5f74909ada4a6ebf6c05aa83917cae611d5714cb1a29c73fa4f0e87f172a0bd0883d48781e0d7f" +
    "85eb0a6940156ebe887e05bd2a73bc7e37f69c3c6a2897bbb8a41311856b95d4bcf58005a813be3b5f30d4dab957a3201837" +
    "d631ad5b06f2b5c855f29aefbdc917f0904bf17099dc9ceecf277be5138544fdb4f181bc2825e45be16bdf765b731c15f51c" +
    "cfe350bf0242f75564d2b437091dddab9d4dd8e08b6c374301b6761715d48142f099594891cf550ebf32be4d088f55831165" +
    "e92abcb88987c4fe4c5b0adef039f712c9ab7f5f12b26adb4ccc30bbb35aea67dea138dcc33ea43a2a5a55ecb2a87a269960" +
    "0b0fc8cbbc59848bdfc1b8425988e3e4078457b5a01697079461893ef334e333ac553887068c185ec594a3672443fd6d6bd1" +
    "97d74a2fc3cec550862230a5d9674db09224fea83f1ff7ce4c17d68c1547d3fc02859a1382de3d744c1392f76909068f8ef5" +
    "db8d33344c489b940f95082978540fe078691ea32e32593c54280569a48cad41860690c1d6ebc939e57118318482c86be68b" +
    "8fa7a8fd0987a9b4a5ded87f3523b914f7e69a54e19693023a74e18e49f687160dc85cbde5ee3ea9887b2592d37230c0beb2" +
    "ba582126db31331ed3aa19d1aeb5e458d01c5300"

internal fun uniformHistogramPixels(): IntArray {
    val channels = Array(3) { channel ->
        val values = IntArray(512) { it % 256 }
        var seed = channel + 1
        for (i in 511 downTo 1) {
            seed = seed * 1664525 + 1013904223
            val j = ((seed.toLong() and 0xffffffffL) % (i + 1)).toInt()
            val old = values[i]; values[i] = values[j]; values[j] = old
        }
        values
    }
    return IntArray(512) { argb(255, channels[0][it], channels[1][it], channels[2][it]) }
}

internal fun losslessWebp(payload: ByteArray): ByteArray {
    fun le(v: Int) = ByteArray(4) { (v ushr (it * 8)).toByte() }
    val chunk = "VP8L".encodeToByteArray() + le(payload.size) + payload +
        (if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    return "RIFF".encodeToByteArray() + le(4 + chunk.size) + "WEBP".encodeToByteArray() + chunk
}

/** Small trees written directly from section 6.2.1 of the VP8L specification. */
internal fun prefixWebp(lengths: IntArray, duplicateSimple: Boolean = false): ByteArray {
    val bits = ArrayList<Int>()
    fun put(value: Int, count: Int) { repeat(count) { bits.add((value ushr it) and 1) } }
    fun simple(symbol: Int) {
        put(1, 1); put(0, 1); put(if (symbol < 2) 0 else 1, 1)
        put(symbol, if (symbol < 2) 1 else 8)
    }
    put(0x2f, 8); put(3, 14); put(0, 14); put(0, 1); put(0, 3) // 4 x 1, opaque
    put(0, 1); put(0, 1); put(0, 1) // no transforms, cache or meta image
    if (duplicateSimple) {
        put(1, 1); put(1, 1); put(0, 1); put(0, 1); put(0, 8)
    } else {
        put(0, 1); put(1, 4) // normal code, five code-length code lengths
        // Code-length symbols 0, 1, 2 have lengths 1, 2, 2: canonical codes 0, 10, 11.
        for (length in intArrayOf(0, 0, 1, 2, 2)) put(length, 3)
        put(1, 1); put(0, 3); put(lengths.size - 2, 2)
        for (length in lengths) when (length) {
            0 -> put(0, 1)
            1 -> { put(1, 1); put(0, 1) }
            2 -> { put(1, 1); put(1, 1) }
        }
    }
    put(1, 1); put(1, 1); put(1, 1); put(10, 8); put(20, 8) // two red symbols
    simple(0); simple(255); simple(0) // blue, alpha, distance
    repeat(4) {
        if (!duplicateSimple && lengths.count { it != 0 } != 1) put(0, 1)
        put(1 - it % 2, 1) // alternating red 20, 10
    }
    val payload = ByteArray((bits.size + 7) / 8)
    for (i in bits.indices) payload[i / 8] = (payload[i / 8].toInt() or (bits[i] shl (i % 8))).toByte()
    return losslessWebp(payload)
}

class WebpPrefixTest {
    @Test
    fun uniformHistogramMatchesLibwebp() {
        val bitmap = ImageKodec.decode(hex(WEBP_UNIFORM_HISTOGRAM))
        assertEquals(32, bitmap.width)
        assertEquals(16, bitmap.height)
        assertContentEquals(uniformHistogramPixels(), bitmap.argb)
    }

    @Test
    fun normallyCodedSingletonConsumesNoBits() {
        val bitmap = ImageKodec.decode(prefixWebp(intArrayOf(1, 0)))
        assertContentEquals(IntArray(4) { argb(255, if (it % 2 == 0) 20 else 10, 0, 0) }, bitmap.argb)
    }

    @Test
    fun duplicateSimpleSymbolsConsumeNoBits() {
        val bitmap = ImageKodec.decode(prefixWebp(intArrayOf(1, 0), duplicateSimple = true))
        assertContentEquals(IntArray(4) { argb(255, if (it % 2 == 0) 20 else 10, 0, 0) }, bitmap.argb)
    }

    @Test
    fun completeTreeDecodes() {
        val bitmap = ImageKodec.decode(prefixWebp(intArrayOf(1, 1)))
        assertContentEquals(IntArray(4) { argb(255, if (it % 2 == 0) 20 else 10, 0, 0) }, bitmap.argb)
    }

    @Test
    fun incompleteAndOversubscribedTreesAreRejected() {
        for (lengths in listOf(intArrayOf(1, 2), intArrayOf(1, 1, 1), intArrayOf(0, 0))) {
            val error = assertFailsWith<ImageDecodeException> { ImageKodec.decode(prefixWebp(lengths)) }
            assertTrue("prefix code" in error.message.orEmpty(), error.message.orEmpty())
        }
    }
}
