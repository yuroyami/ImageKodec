package io.github.yuroyami.kiteimage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.math.abs

/**
 * JPEG 2000 through the facade: an ffmpeg-encoded JP2 (32x24, high quality),
 * ground truth = ffmpeg's own decode of the same file to raw RGB. Tolerance
 * covers implementation variance between two independent JPX decoders; the
 * stronger per-pixel oracle (vs OpenJPEG) lives in jvmTest's JpxOracleTest.
 */
class Jp2DecoderTest {

    private val JP2 = "0000000c6a5020200d0a870a00000014667479706a703220000000006a7032200000002d6a703268000000166968647200000018000000200003080700000000000f636f6c72010000000000100000013f6a703263ff4fff51002f000000000020000000" +
            "180000000000000000000001000000010000000000000000000003070101070101070101ff52000c00000001000602020000ff5c0029227f207ee07ee07ea076f076f076c06f006f006ee067506750676850055005504757d357d35762ff64001100014c" +
            "61766336322e31312e313030ff90000a0000000000b60001ff93c7f80208dfc7f40307257fcfe80405cd000000c7f2060000707fa7f80200047ec3f60283f302000478003bc7ec0c000d032ddeae49a07d6060087f5fc1f702403e70400d01e7dc08fbc1" +
            "f503801bec034dc0d500a1f584800cbf2288dac56bab3fc0f942c0f942801bf45d852d0cbf4a893ac1f20600369cf674ee35a1f28800408e009b23c0298fc0f84381e0c0369d274acfcfc0e8805fa7c53fa062008ca4293fc023005fa7bfffd9"
    private val EXPECTED_RGB = "00030207030311030519030820030a29030c31030f3903114203134a03155203185a031a62031c6a031f7303217b03248303268c032894032b9c032da40330ac0332b50334bd0336c50338cd033bd5033edd0340e50342ef0345f50347ff034a000a0307" +
            "0a04110a06190a09200a0b290a0e310a10390a12420a144a0a17520a195a0a1b620a1e6a0a20730a227b0a25830a278c0a2a940a2c9c0a2ea40a31ac0a33b50a35bd0a37c50a39cd0a3cd50a3fdd0a41e50a43ef0a46f50a48ff0a4b0016050716061116" +
            "0819160b20160e2916103116123916144216174a161952161b5a161e6216206a16227316257b16278316298c162c94162e9c1631a41633ac1635b51637bd163ac5163ccd163fd51642dd1644e51645ef1648f5164aff164d00210807210911210b19210e" +
            "2021102921123121153921174221194a211c52211e5a21206221236a21257321277b212a83212c8c212f9421319c2133a42136ac2138b5213abd213cc5213ecd2141d52144dd2146e52148ef214bf5214dff2150002c0a072c0b112c0e192c10202c1329" +
            "2c15312c17392c1a422c1c4a2c1e522c215a2c23622c256a2c28732c2a7b2c2d832c2f8c2c31942c349c2c36a42c39ac2c3bb52c3dbd2c3fc52c41cd2c44d52c47dd2c49e52c4bef2c4ef52c50ff2c5300370c07370e1137101937122037152937173137" +
            "1a39371c42371e4a37215237235a37256237286a372a73372c7b372f8337318c37349437369c3738a4373bac373db5373fbd3741c53744cd3746d53749dd374be5374def3750f53752ff375500420f07421011421219421520421729421a31421c39421e" +
            "4242204a42235242255a422762422a6a422c73422f7b42318342338c42369442389c423ba4423dac423fb54241bd4244c54246cd4249d5424cdd424ee5424fef4252f54254ff4257004d11074d12114d14194d17204d1a294d1c314d1e394d20424d234a" +
            "4d25524d275a4d2a624d2c6a4d2f734d317b4d33834d368c4d38944d3b9c4d3da44d3fac4d42b54d44bd4d46c54d48cd4d4bd54d4edd4d50e54d52ef4d55f54d57ff4d5a00581307581411581719581920581c29581e3158203958234258254a58285258" +
            "2a5a582c62582f6a58317358337b58368358388c583b94583d9c583fa45842ac5844b55846bd5848c5584bcd584dd55850dd5852e55854ef5857f55859ff585c00631507631711631919631c20631e2963213163233963254263284a632a52632c5a632f" +
            "6263316a63337363367b633883633a8c633d94633f9c6342a46344ac6346b56348bd634bc5634dcd6350d56353dd6355e56357ef6359f5635bff635e006e18076e19116e1b196e1e206e21296e23316e25396e28426e2a4a6e2c526e2f5a6e31626e336a" +
            "6e36736e387b6e3a836e3d8c6e3f946e429c6e44a46e46ac6e49b56e4bbd6e4dc56e4fcd6e52d56e55dd6e57e56e59ef6e5cf56e5eff6e61007a1a077a1b117a1e197a20207a23297a25317a28397a2a427a2c4a7a2f527a315a7a33627a366a7a38737a" +
            "3a7b7a3d837a3f8c7a41947a449c7a46a47a49ac7a4bb57a4dbd7a4fc57a51cd7a54d57a57dd7a59e57a5bef7a5ef57a60ff7a6300851d07851e11852019852320852529852831852a39852c42852f4a85315285335a85366285386a853a73853d7b853f" +
            "8385418c85449485469c8549a4854bac854db5854fbd8552c58554cd8557d58559dd855ce5855def8560f58562ff856500901f07902011902219902520902829902a31902c39902f4290314a90335290365a903862903a6a903d73903f7b90418390448c" +
            "90469490489c904ba4904dac904fb59052bd9054c59056cd9059d5905cdd905ee59060ef9062f59064ff9068009b21079b22119b25199b27209b2a299b2c319b2f399b31429b334a9b36529b385a9b3a629b3c6a9b3f739b417b9b43839b468c9b48949b" +
            "4b9c9b4da49b4fac9b52b59b54bd9b56c59b58cd9b5bd59b5edd9b60e59b62ef9b65f59b67ff9b6a00a62307a62511a62719a62920a62c29a62f31a63139a63342a6354aa63852a63a5aa63c62a63f6aa64173a6437ba64683a6488ca64a94a64d9ca64f" +
            "a4a652aca654b5a656bda658c5a65acda65dd5a660dda662e5a664efa667f5a669ffa66c00b12607b12711b12919b12c20b12f29b13131b13339b13542b1384ab13a52b13c5ab13f62b1416ab14373b1467bb14883b14a8cb14d94b14f9cb151a4b154ac" +
            "b156b5b158bdb15ac5b15dcdb15fd5b162ddb164e5b166efb169f5b16bffb16e00bc2907bc2a11bc2c19bc2f20bc3129bc3431bc3639bc3842bc3b4abc3d52bc3f5abc4162bc446abc4673bc487bbc4b83bc4d8cbc4f94bc529cbc54a4bc57acbc59b5bc" +
            "5bbdbc5dc5bc5fcdbc62d5bc65ddbc67e5bc69efbc6cf5bc6effbc7100c82c07c82d11c82f19c83220c83429c83731c83939c83b42c83d4ac84052c8425ac84462c8476ac84973c84b7bc84e83c8508cc85294c8559cc857a4c859acc85cb5c85ebdc860" +
            "c5c862cdc865d5c868ddc86ae5c86cefc86ff5c871ffc87400d22e07d22f11d23119d23320d23629d23831d23b39d23d42d23f4ad24252d2445ad24662d2486ad24b73d24d7bd24f83d2528cd25494d2579cd259a4d25bacd25eb5d260bdd262c5d264cd" +
            "d267d5d26addd26ce5d26eefd271f5d273ffd27600dc3007dc3111dc3319dc3520dc3829dc3a31dc3c39dc3f42dc414adc4352dc465adc4862dc4a6adc4d73dc4f7bdc5183dc548cdc5694dc589cdc5ba4dc5dacdc5fb5dc62bddc64c5dc66cddc69d5dc" +
            "6cdddc6ee5dc70efdc72f5dc74ffdc7800e93207e93311e93619e93820e93b29e93e31e94039e94242e9444ae94752e9495ae94b62e94e6ae95073e9527be95583e9578ce95994e95c9ce95ea4e960ace963b5e965bde967c5e969cde96cd5e96fdde971" +
            "e5e973efe976f5e978ffe97b00f23407f23511f23819f23b20f23e29f24131f24339f24542f2474af24952f24c5af24e62f2506af25373f2557bf25783f25a8cf25c94f25e9cf261a4f263acf265b5f268bdf26ac5f26ccdf26fd5f272ddf274e5f276ef" +
            "f278f5f27afff27e00ff3407ff3611ff3819ff3c20ff3f29ff4131ff4339ff4642ff484aff4a52ff4c5aff4f62ff516aff5373ff567bff5883ff5a8cff5d94ff5f9cff62a4ff64acff66b5ff68bdff6ac5ff6dcdff70d5ff72ddff75e5ff76efff79f5ff" +
            "7bffff7e"

    @Test
    fun sniffsJp2Container() {
        assertEquals(ImageFormat.JP2, ImageFormat.sniff(hex(JP2)))
    }

    @Test
    fun sniffsRawCodestream() {
        assertEquals(ImageFormat.JP2, ImageFormat.sniff(hex("ff4fff51")))
    }

    @Test
    fun decodesCloseToFfmpeg() {
        val bm = KiteImage.decode(hex(JP2))
        assertEquals(32, bm.width)
        assertEquals(24, bm.height)
        val exp = hex(EXPECTED_RGB)
        var worst = 0
        var sum = 0L
        for (i in 0 until 32 * 24) {
            val p = bm.argb[i]
            for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) {
                val d = abs((exp[i * 3 + c].toInt() and 0xFF) - ((p shr shift) and 0xFF))
                if (d > worst) worst = d
                sum += d
            }
        }
        val mean = sum.toDouble() / (32 * 24 * 3)
        assertTrue(worst <= 8, "max diff $worst > 8 (mean $mean)")
        assertTrue(mean <= 2.0, "mean diff $mean > 2.0")
    }

    // --- probe agrees with decode ---------------------------------------------------

    /** The bare codestream inside [JP2]; both the probe and the decoder take it alone. */
    private fun codestream(): ByteArray {
        val all = hex(JP2)
        return all.copyOfRange(indexOfMarker(all, 0xFF4F), all.size)
    }

    private fun indexOfMarker(data: ByteArray, marker: Int): Int {
        val hi = ((marker shr 8) and 0xFF).toByte()
        val lo = (marker and 0xFF).toByte()
        for (i in 0 until data.size - 1) {
            if (data[i] == hi && data[i + 1] == lo) return i
        }
        fail("marker ${marker.toString(16)} is not in this vector")
    }

    /** [codestream], with a [marker] segment spliced in ahead of the first tile-part. */
    private fun withMainHeaderMarker(marker: Int): ByteArray {
        val cs = codestream()
        val sot = indexOfMarker(cs, 0xFF90)
        val segment = byteArrayOf(
            ((marker shr 8) and 0xFF).toByte(), (marker and 0xFF).toByte(),
            0x00, 0x04, 0x00, 0x00,                  // length 4, two bytes of payload
        )
        return cs.copyOfRange(0, sot) + segment + cs.copyOfRange(sot, cs.size)
    }

    @Test
    fun supportedCodestreamProbesDecodable() {
        val cs = codestream()
        val info = KiteImage.probe(cs)
        assertTrue(info.isDecodable, "reason: ${info.unsupportedReason}")
        assertEquals(32, info.width)
        assertEquals(32, KiteImage.decode(cs).width)
    }

    /**
     * The main-header features the JPX decoder gives up on have to be visible to
     * a header read, or `probe` promises pixels `decode` will not produce.
     */
    @Test
    fun probeNamesTheMainHeaderFeaturesTheDecoderDeclines() {
        val cases = listOf(
            0xFF5E to "region of interest",
            0xFF5F to "progression order change",
            0xFF60 to "packed packet headers",
        )
        for ((marker, label) in cases) {
            val spliced = withMainHeaderMarker(marker)
            val info = KiteImage.probe(spliced)
            assertFalse(info.isDecodable, "$label should probe undecodable")
            assertNotNull(info.unsupportedReason, "$label reason")
            assertFailsWith<ImageDecodeException>(label) { KiteImage.decode(spliced) }
        }
    }

    @Test
    fun probeRejectsANonBaselineCodeBlockStyle() {
        val cs = codestream()
        // COD: marker(2) Lcod(2) Scod SGcod(4), then the SPcod code-block style byte.
        val patched = cs.copyOf()
        patched[indexOfMarker(cs, 0xFF52) + 12] = 0x01   // selective arithmetic bypass
        val info = KiteImage.probe(patched)
        assertFalse(info.isDecodable)
        assertTrue(info.unsupportedReason!!.contains("code-block"), info.unsupportedReason!!)
        assertFailsWith<ImageDecodeException> { KiteImage.decode(patched) }
    }
}
