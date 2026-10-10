package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Same packets with T.800 E-5 derived/expounded tables; OpenJPEG pixels agree. */
class Jp2DerivedQuantTest {
    internal fun encoded(name: String = "coarse", derived: Boolean = true, scope: String = "main-qcd"): ByteArray {
        val direct = hex(streams.getValue("$name-${if (derived) 1 else 2}"))
        if (!derived || scope == "main-qcd") return direct
        val base = hex(streams.getValue("$name-2"))
        fun marker(bytes: ByteArray, code: Int): Int = (0 until bytes.size - 1).first {
            (bytes[it].toInt() and 255) == 255 && (bytes[it + 1].toInt() and 255) == code
        }
        val qcd = marker(direct, 0x5c)
        val length = ((direct[qcd + 2].toInt() and 255) shl 8) or (direct[qcd + 3].toInt() and 255)
        val segment = direct.copyOfRange(qcd, qcd + 2 + length)
        val insert = if (scope.endsWith("qcc"))
            byteArrayOf(0xff.toByte(), 0x5d, 0, (length + 1).toByte(), 0) + segment.copyOfRange(4, segment.size)
        else segment
        val sot = marker(base, 0x90)
        val at = if (scope.startsWith("tile")) sot + 12 else sot
        val result = base.copyOfRange(0, at) + insert + base.copyOfRange(at, base.size)
        if (scope.startsWith("tile")) {
            var psot = 0
            for (i in sot + 6..sot + 9) psot = (psot shl 8) or (result[i].toInt() and 255)
            psot += insert.size
            for (i in 0..3) result[sot + 6 + i] = (psot ushr (24 - 8 * i)).toByte()
        }
        return result
    }

    private fun check(name: String, scope: String = "main-qcd") {
        val derived = assertNotNull(JpxDecoder.decode(encoded(name, scope = scope)))
        val explicit = assertNotNull(JpxDecoder.decode(encoded(name, derived = false)))
        assertEquals(16, derived.width); assertEquals(16, derived.height)
        assertTrue(ImageKodec.probe(encoded(name, scope = scope)).isDecodable)
        assertContentEquals(explicit.pixelBytes, derived.pixelBytes, "$name/$scope derived vs expounded")
        val expected = hex(references.getValue(name))
        for (i in expected.indices) {
            val difference = kotlin.math.abs((expected[i].toInt() and 255) - (derived.pixelBytes[i].toInt() and 255))
            assertTrue(difference <= 4, "$name/$scope sample $i differs by $difference")
        }
    }

    @Test fun coarseDerivedStepsMatchTheirExpoundedTable() = check("coarse")
    @Test fun fractionalDerivedStepsMatchTheirExpoundedTable() = check("fractional")
    @Test fun anotherBaseExponentMatchesItsExpoundedTable() = check("alternate")
    @Test fun undecomposedBandKeepsItsBaseExponent() = check("undecomposed")
    @Test fun mainComponentOverrideUsesDerivedSteps() = check("coarse", "main-qcc")
    @Test fun tileDefaultOverrideUsesDerivedSteps() = check("coarse", "tile-qcd")
    @Test fun tileComponentOverrideUsesDerivedSteps() = check("coarse", "tile-qcc")

    private val streams = mapOf(
        "coarse-1" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c0005414000ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "coarse-2" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c0029424000400040004000380038003800300030003000280028002800200020002000180018001800ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "fractional-1" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c0005414300ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "fractional-2" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c00294243004300430043003b003b003b003300330033002b002b002b002300230023001b001b001b00ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "alternate-1" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c0005414e00ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "alternate-2" to "ff4fff510029000000000010000000100000000000000000000001000000010000000000000000000001070101ff52000c00000001000602020000ff5c0029424e004e004e004e004600460046003e003e003e003600360036002e002e002e00260026002600ff64001100014c61766336322e31312e313030ff90000a0000000000410001ff93c7f60305f97f0000c7ea070fc808031d7f0474c3e90707c60c0cfb7f0b3d7fc3e30b07042219c2e0d7037fc08a022036a13da5ffd9",
        "undecomposed-1" to "ff4fff510029000000000010000000100000000000000000000000100000001000000000000000000001070101ff52000c00000001000004040000ff5c0005414000ff640025000143726561746564206279204f70656e4a5045472076657273696f6e20322e352e34ff90000a0000000000290001ff93de64123765da17a1b4a1f84a8ed21b7a0fabb4eab59bc1f95cf05bffd9",
        "undecomposed-2" to "ff4fff510029000000000010000000100000000000000000000000100000001000000000000000000001070101ff52000c00000001000004040000ff5c0005424000ff640025000143726561746564206279204f70656e4a5045472076657273696f6e20322e352e34ff90000a0000000000290001ff93de64123765da17a1b4a1f84a8ed21b7a0fabb4eab59bc1f95cf05bffd9",
    )
    private val references = mapOf(
        "coarse" to "00020e1e2d3945505c6d7c848eaac4ca00041120303c47535f6f7f8791adc6cd0209162535414c586474848c96b2cbd2080f1c2b3b47525e6a7a8a919cb8d1d80e152231414d586470809098a2bed7de1219263545515d687484949ca6c2dce2161d2a394955606c788898a0aac6e0e61b222e3e4d5965707d8d9ca4aecae4ea20273343525e6a758292a1a9b3cfe9ef272e3b4a5a66717d8999a9b0bad7f0f62f364352616e798491a1b0b8c2def8fe363d4a596975808c98a8b7bfc9e6ffff3c43505f6e7a86919eaebdc5cfebffff3e455261717d8893a0b0bfc7d1edffff3f455261717d8994a0b0c0c8d2eeffff3f465362727e8994a1b1c0c8d2eeffff",
        "fractional" to "000000000e1f2e3e4f657b8593badde6000000001222324253697e8997bee1e900000003192939495a7085909ec4e8f00000000b2132415162788d98a6cdf0f800000014293a49596a8096a0aed5f8ff000005192f404f5f70869ba6b4dbfeff00000a1f34455564758ba1acb9e0ffff000010253a4b5a6a7b91a7b1bfe6ffff0005172b415261718298aeb8c6edffff060f21364b5c6c7b8ca2b8c2d0f7ffff101a2c405667768697adc2cddbffffff1a24364a60708090a1b7ccd7e5ffffff222c3d5268788898a9bfd4dfedffffff252f40556b7b8b9bacc2d7e2f0ffffff263041566c7c8c9cadc3d8e3f1ffffff273042576c7d8c9cadc3d9e3f1ffffff",
        "alternate" to "0000000000071b2f455f798798c2e8f100000000000c203449647e8c9dc7edf6000000000014283c516c8694a5cff5fe00000000091e32465b76909eafd9ffff0000000013283c5065809aa8b9e3ffff000000001b3044586d88a2b0c1ebffff0000000823384c607590aab8c9f3ffff0000000f2b4053677d98b1c0d1fbffff0000001732475b6f859fb9c7d8ffffff000007213c5165798ea9c3d1e2ffffff0000102a455a6e8297b2ccdaebffffff0000132d485d71859ab5cfddeeffffff000218324d62768a9fbad4e2f3ffffff0e19304a657a8da2b7d2ecfaffffffff242f46607b90a4b8cde8ffffffffffff29354b658095a9bdd2edffffffffffff",
        "undecomposed" to "00202020202050505080808080808080202020202020505080808080808080b02020202020505050808080808080b0b02020202020505080808080808080b0e020202020505050808080808080b0b0e020202020505050808080808080b0b0e020202020505080808080808080b0e0e0202020505050808080808080b0b0e0e02020205050808080808080b0b0e0e0e02020505050808080808080b0b0e0e0e02020505050808080808080b0b0e0e0e02020505080808080808080b0e0e0e0e020505050808080808080b0b0e0e0e0e020505080808080808080b0e0e0e0e0e050505080808080808080b0e0e0e0e0e050505080808080808080b0e0e0e0e0e0",
    )
}
