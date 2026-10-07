package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.TiffFixed128
import io.github.yuroyami.imagekodec.codec.TiffReferenceBlackWhite
import java.io.File
import java.math.BigInteger
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TiffReferenceOracleTest {
    @Test
    fun ycbcrRangesAgreeWithLibtiffAndImageIo() {
        assumeTrue("libtiff not installed", Tools.hasAll("tiff2rgba"))
        val ranges = listOf(defaultReferences(true),
            longArrayOf(15,1,235,1,128,1,240,1,128,1,240,1),
            longArrayOf(1,2,511,2,257,2,511,2,257,2,511,2))
        for (le in listOf(true, false)) for (refs in ranges) {
            val bytes = withReferences(referenceTiff(le = le), refs)
            val source = File.createTempFile("imagekodec-reference", ".tif").apply { deleteOnExit(); writeBytes(bytes) }
            val output = File.createTempFile("imagekodec-reference-rgba", ".tif").apply { deleteOnExit() }
            val log = File.createTempFile("imagekodec-reference", ".log").apply { deleteOnExit() }
            val process = ProcessBuilder(Tools.require("tiff2rgba").path, "-c", "none", source.path, output.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            assertTrue(finished, "libtiff timed out")
            assertEquals(0, process.exitValue(), log.readText())
            val bitmap = ImageKodec.decode(bytes)
            for ((name, reader) in listOf("libtiff" to output.readBytes(), "ImageIO" to bytes)) {
                val image = assertNotNull(ImageIO.read(reader.inputStream()))
                for (x in 0..5) for ((c,shift) in listOf(16,8,0).withIndex()) {
                    // ImageIO labels its converted YCbCr samples with a linear
                    // RGB color model; getRGB applies another transfer curve.
                    // Compare the converted channel codes in its raster.
                    val reference = if (name == "ImageIO") image.raster.getSample(x,0,c)
                        else (image.getRGB(x,0) ushr shift) and 255
                    val difference = abs(((bitmap[x,0] ushr shift) and 255) - reference)
                    // Independent readers use different conversion tables and
                    // final rounding; allow at most two display codes.
                    assertTrue(difference <= 2, "$name/$le/${refs.toList()} at $x/$shift differs by $difference")
                }
            }
        }
    }

    @Test
    fun wideArithmeticMatchesBigIntegerAcrossUnsignedDivisors() {
        for (a in listOf(Long.MIN_VALUE, -65536L, -1L, 0L, 1L, 65536L, Long.MAX_VALUE)) {
            for (divisor in listOf(1uL, 2uL, 1uL shl 63, ULong.MAX_VALUE)) {
                assertEquals(BigInteger.valueOf(a).divide(BigInteger(divisor.toString())),
                    big(TiffFixed128.of(a).dividedBy(divisor)))
            }
        }
        val random = Random(0x48544946)
        repeat(4000) {
            val a = random.nextLong()
            val b = random.nextLong()
            val factorA = random.nextInt().toLong() and 0xFFFFFFFFL
            val factorB = random.nextInt().toLong() and 0xFFFFFFFFL
            val wideA = TiffFixed128.of(a).times(factorA).shift16()
            val wideB = TiffFixed128.of(b).times(factorB).shift16()
            val bigA = BigInteger.valueOf(a).multiply(BigInteger.valueOf(factorA)).shiftLeft(16)
            val bigB = BigInteger.valueOf(b).multiply(BigInteger.valueOf(factorB)).shiftLeft(16)
            assertEquals(bigA + bigB, big(wideA + wideB))
            assertEquals(bigA - bigB, big(wideA - wideB))
            val divisor = random.nextLong().toULong().let { if (it == 0uL) 1uL else it }
            assertEquals(bigA.divide(BigInteger(divisor.toString())), big(wideA.dividedBy(divisor)))
        }
    }

    @Test
    fun rationalExpansionMatchesBigIntegerForEntireUnsignedDomain() {
        val random = Random(0x52415449)
        repeat(2000) {
            val pairs = LongArray(12) { random.nextInt().toLong() and 0xFFFFFFFFL }
            for (i in listOf(1,3,5,7,9,11)) if (pairs[i] == 0L) pairs[i] = 1
            val refs = parse(pairs)
            for (c in 0..2) {
                val code = random.nextInt(65536)
                val coding = if (c == 0) 65535 else 127
                val at = c * 4
                fun n(i: Int) = BigInteger.valueOf(pairs[at+i])
                val numerator = (BigInteger.valueOf(code.toLong()) * n(1) - n(0)) * n(3) * BigInteger.valueOf(coding.toLong())
                val denominator = n(2) * n(1) - n(0) * n(3)
                assertEquals(numerator.shiftLeft(16).divide(denominator), big(refs.expanded(code, c, coding)))
            }
        }
    }

    @Test
    fun extremeColorCancellationMatchesExactRationalMatrix() {
        val m = 0xFFFFFFFFL
        val random = Random(0x434F4C52)
        val ranges = mutableListOf(
            longArrayOf(m-1,m,m,m-1, m-1,m,m,m-1, m-1,m,m,m-1),
            longArrayOf(0,m,m-1,m, m,m-1,m-1,m, m-1,m,m,m-1))
        repeat(60) {
            ranges += LongArray(12) { i -> if (i % 4 == 1 || i % 4 == 3)
                maxOf(1, random.nextInt().toLong() and m) else random.nextInt().toLong() and m }
        }
        for (pairs in ranges) {
            val refs = parse(pairs)
            val tables = assertNotNull(refs.ycbcrTables())
            repeat(100) {
                val codes = IntArray(3) { random.nextInt(256) }
                val channels = Array(3) { c ->
                    val at = c*4
                    val code = Fraction(BigInteger.valueOf(codes[c].toLong()), BigInteger.ONE)
                    val black = Fraction(BigInteger.valueOf(pairs[at]), BigInteger.valueOf(pairs[at+1]))
                    val white = Fraction(BigInteger.valueOf(pairs[at+2]), BigInteger.valueOf(pairs[at+3]))
                    (code - black) / (white - black) * if (c == 0) 255 else 127
                }
                val y = channels[0]; val cb = channels[1]; val cr = channels[2]
                val expected = listOf(y + cr * 701 / 500,
                    y - cb * 101004 / 293500 - cr * 209599 / 293500,
                    y + cb * 443 / 250)
                val actual = refs.ycbcrToArgb(codes[0], codes[1], codes[2], tables)
                for ((c,shift) in listOf(16,8,0).withIndex()) {
                    // Q16 expansion has less than 1/65536 code error, including
                    // extreme cancellation; display rounding can differ by one
                    // only when an exact rational straddles a half-code boundary.
                    assertTrue(abs(expected[c].byte() - ((actual ushr shift) and 255)) <= 1,
                        "${pairs.toList()}/${codes.toList()} at $c")
                }
            }
        }
    }

    private fun parse(pairs: LongArray): TiffReferenceBlackWhite {
        val bytes = withReferences(referenceTiff(), pairs)
        val entry = referenceFieldEntry(bytes, 532)
        return TiffReferenceBlackWhite.read(bytes, true, 5, 6, entry+8)
    }

    private fun big(value: TiffFixed128): BigInteger {
        val words = value.words()
        val result = BigInteger(words[0].toString()).shiftLeft(64) + BigInteger(words[1].toString())
        return if (words[2] == 1uL) -result else result
    }

    private class Fraction(n: BigInteger, d: BigInteger) {
        private val sign = if (d.signum() < 0) -BigInteger.ONE else BigInteger.ONE
        private val numerator = n * sign
        private val denominator = d * sign
        operator fun plus(v: Fraction) = Fraction(numerator*v.denominator + v.numerator*denominator, denominator*v.denominator)
        operator fun minus(v: Fraction) = Fraction(numerator*v.denominator - v.numerator*denominator, denominator*v.denominator)
        operator fun div(v: Fraction) = Fraction(numerator*v.denominator, denominator*v.numerator)
        operator fun times(v: Int) = Fraction(numerator*BigInteger.valueOf(v.toLong()), denominator)
        operator fun div(v: Int) = Fraction(numerator, denominator*BigInteger.valueOf(v.toLong()))
        fun byte(): Int = (numerator*BigInteger.TWO + denominator).divide(denominator*BigInteger.TWO)
            .max(BigInteger.ZERO).min(BigInteger.valueOf(255)).toInt()
    }
}
