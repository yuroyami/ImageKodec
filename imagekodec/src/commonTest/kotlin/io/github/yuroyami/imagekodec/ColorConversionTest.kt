package io.github.yuroyami.imagekodec

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Conversion to sRGB (#108) on every target. lcms2 built the profiles below through
 * `tools/icc_lcms.c` (a v4 Display P3 with the sRGB curve as `para` type 3, a v2 gray profile
 * with a gamma of 2.2, and a v4 profile whose `para` type 4 curve lifts black, so black point
 * compensation applies) and converted the samples through each to its built-in sRGB, perceptual,
 * relative and absolute colorimetric. Ours must land within one level.
 */
class ColorConversionTest {

    private val displayP3 = hex(
        "000002486c636d73043000006d6e74725247422058595a2007ea000a0008000800090035616373704150504c000000000000" +
        "0000000000000000000000000000000000000000f6d6000100000000d32d6c636d7300000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000b646573630000010800000034637072740000" +
        "013c0000004c777470740000018800000014636861640000019c0000002c7258595a000001c8000000146258595a000001dc" +
        "000000146758595a000001f00000001472545243000002040000002067545243000002040000002062545243000002040000" +
        "00206368726d00000224000000246d6c756300000000000000010000000c656e5553000000180000001c0052004700420020" +
        "006200750069006c0074002d0069006e6d6c756300000000000000010000000c656e5553000000300000001c004e006f0020" +
        "0063006f0070007900720069006700680074002c002000750073006500200066007200650065006c007958595a2000000000" +
        "0000f6d6000100000000d32d736633320000000000010c42000005defffff325000007930000fd90fffffba1fffffda20000" +
        "03dc0000c06e58595a2000000000000083df00003dbfffffffbb58595a2000000000000028380000110b0000c8b958595a20" +
        "0000000000004abf0000b13700000ab9706172610000000000030000000266660000f2a700000d59000013d000000a5b6368" +
        "726d00000000000300000000ae14000051ec000043d70000b0a40000266600000f5c" +
        "",
    )

    private val displayP3Samples = hex(
        "edbf88465f03aded29ab14c256e7d85056791a384320c434956872d72c886bcb8fae166602d21cc1fb470c79d939013e6567" +
        "a9042a44082bfe65d723cb622f4a58151b8a4c89113e" +
        "",
    )

    private val displayP3Perceptual = hex(
        "f6bd7f3f60009aef00bb00c900ebd84f567c0c394400c8009d6672ea008a3cce89be006700d600b0fd00007be03f00416567" +
        "ae002b46002cff13da00da5a19465900008d4596003e" +
        "",
    )

    private val displayP3Relative = hex(
        "f6bd7f3f60009aef00bb00c900ebd84f567c0c394400c8009d6672ea008a3cce89be006700d600b0fd00007be03f00416567" +
        "ae002b46002cff13da00da5a19465900008d4596003e" +
        "",
    )

    private val displayP3Absolute = hex(
        "f6bd7f3f60009aef00bb00c900ebd84f567c0c394400c8009d6672ea008a3cce89be006700d600b0fd00007be03f00416567" +
        "ae002b46002cff13da00da5a19465900008d4596003e" +
        "",
    )

    private val grayGamma22 = hex(
        "000001806c636d73021000006d6e74724752415958595a2007ea000a0008000800090036616373704150504c000000000000" +
        "0000000000000000000000000000000000000000f6d6000100000000d32d6c636d7300000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000464657363000000b400000084637072740000" +
        "013800000021777470740000015c000000146b545243000001700000000e64657363000000000000000e6772617920627569" +
        "6c742d696e00000000000000000e00670072006100790020006200750069006c0074002d0069006e00000000000000000000" +
        "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000074657874000000004e6f20636f707972696768742c2075736520667265656c79000000005859" +
        "5a20000000000000f6d6000100000000d32d63757276000000000000000102330000" +
        "",
    )

    private val grayGamma22Samples = hex(
        "000b16212c37424d58636e79848f9aa5b0bbc6d1dce7f2fd" +
        "",
    )

    private val grayGamma22Perceptual = hex(
        "0000000303030e0e0e1b1b1b2828283434344040404c4c4c5757576363636e6e6e7a7a7a8585859090909b9b9ba7a7a7b2b2" +
        "b2bcbcbcc7c7c7d2d2d2dddddde8e8e8f2f2f2fdfdfd" +
        "",
    )

    private val grayGamma22Relative = hex(
        "0000000303030e0e0e1b1b1b2828283434344040404c4c4c5757576363636e6e6e7a7a7a8585859090909b9b9ba7a7a7b2b2" +
        "b2bcbcbcc7c7c7d2d2d2dddddde8e8e8f2f2f2fdfdfd" +
        "",
    )

    private val grayGamma22Absolute = hex(
        "0000000303030e0e0e1b1b1b2828283434344040404c4c4c5757576363636e6e6e7a7a7a8585859090909b9b9ba7a7a7b2b2" +
        "b2bcbcbcc7c7c7d2d2d2dddddde8e8e8f2f2f2fdfdfd" +
        "",
    )

    private val para4 = hex(
        "000002506c636d73043000006d6e74725247422058595a2007ea000a0008000800090036616373704150504c000000000000" +
        "0000000000000000000000000000000000000000f6d6000100000000d32d6c636d7300000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000b646573630000010800000034637072740000" +
        "013c0000004c777470740000018800000014636861640000019c0000002c7258595a000001c8000000146258595a000001dc" +
        "000000146758595a000001f00000001472545243000002040000002867545243000002040000002862545243000002040000" +
        "00286368726d0000022c000000246d6c756300000000000000010000000c656e5553000000180000001c0052004700420020" +
        "006200750069006c0074002d0069006e6d6c756300000000000000010000000c656e5553000000300000001c004e006f0020" +
        "0063006f0070007900720069006700680074002c002000750073006500200066007200650065006c007958595a2000000000" +
        "0000f6d6000100000000d32d736633320000000000010c42000005defffff325000007930000fd90fffffba1fffffda20000" +
        "03dc0000c06e58595a2000000000000083df00003dbfffffffbb58595a2000000000000028380000110b0000c8b958595a20" +
        "0000000000004abf0000b13700000ab970617261000000000004000000024ccd0000e6660000199a00001eb80000147b0000" +
        "028f000001486368726d00000000000300000000ae14000051ec000043d70000b0a40000266600000f5c" +
        "",
    )

    private val para4Samples = hex(
        "ce795216bb2cb138bbe76bcd6509c2a800dd396d71e38aa62d9d91341c0dc3dbfeb17f21d97631cdbebd479457840f195886" +
        "543d4b06181fe66ac79607b59148f68e462c973c21d7" +
        "",
    )

    private val para4Perceptual = hex(
        "e0815900c51ac83fc8fb70d77e00d0c100ea387c7ff490af00a99c492f0bc4e0ffc18817ec7d30d5c4c338a0609d002a5a93" +
        "5e4a5b002b33f355d09c00c09a00fc8e5a3da75133e4" +
        "",
    )

    private val para4Relative = hex(
        "e1825b00c621c842c8fb71d77f08d1c200ea3c7d80f491b000aa9c4c3316c4e0ffc2891fec7e34d6c5c43ba1619e032f5b94" +
        "604d5d003037f357d09d00c09b00fc8f5c40a75337e5" +
        "",
    )

    private val para4Absolute = hex(
        "e1825b00c621c842c8fb71d77f08d1c200ea3c7d80f491b000aa9c4c3316c4e0ffc2891fec7e34d6c5c43ba1619e032f5b94" +
        "604d5d003037f357d09d00c09b00fc8f5c40a75337e5" +
        "",
    )

    private fun check(name: String, icc: ByteArray, samples: ByteArray, channels: Int, intent: RenderingIntent, want: ByteArray) {
        val n = samples.size / channels
        val bitmap = KiteBitmap(n, 1, IntArray(n) { i ->
            val r = samples[i * channels].toInt() and 0xFF
            val g = if (channels == 3) samples[i * 3 + 1].toInt() and 0xFF else r
            val b = if (channels == 3) samples[i * 3 + 2].toInt() and 0xFF else r
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        })
        val out = ImageKodec.convertToSrgb(bitmap, ColorProfile(icc = icc), intent)
        for (i in 0 until n) for (c in 0 until 3) {
            val got = (out.argb[i] shr (16 - 8 * c)) and 0xFF
            val expected = want[i * 3 + c].toInt() and 0xFF
            assertTrue(abs(got - expected) <= 1, "$name $intent: sample $i channel $c is $got against lcms2's $expected")
        }
    }

    @Test
    fun profilesConvertAsLcms2ConvertsThem() {
        for ((name, icc, samples, results) in listOf(
            Case("Display P3", displayP3, displayP3Samples, listOf(displayP3Perceptual, displayP3Relative, displayP3Absolute)),
            Case("gray 2.2", grayGamma22, grayGamma22Samples, listOf(grayGamma22Perceptual, grayGamma22Relative, grayGamma22Absolute)),
            Case("para 4", para4, para4Samples, listOf(para4Perceptual, para4Relative, para4Absolute)),
        )) {
            val channels = if (name.startsWith("gray")) 1 else 3
            val intents = listOf(RenderingIntent.Perceptual, RenderingIntent.RelativeColorimetric, RenderingIntent.AbsoluteColorimetric)
            for ((intent, want) in intents.zip(results)) check(name, icc, samples, channels, intent, want)
        }
    }

    @Test
    fun alphaIsKeptAndNothingDeclaredChangesNothing() {
        val bitmap = KiteBitmap(2, 1, intArrayOf(0x40FF0000, 0x80123456.toInt()))
        val out = ImageKodec.convertToSrgb(bitmap, ColorProfile(icc = displayP3))
        assertEquals(0x40, out.argb[0] ushr 24)
        assertEquals(0x80, out.argb[1] ushr 24)
        assertSame(bitmap, ImageKodec.convertToSrgb(bitmap, ColorProfile()))
        assertSame(bitmap, ImageKodec.convertToSrgb(bitmap, ColorProfile(icc = byteArrayOf(1, 2, 3))))
        // A file with no profile decodes the same whatever the target.
        val png = ImageKodec.encodePng(bitmap)
        assertContentEquals(ImageKodec.decode(png).argb, ImageKodec.decode(png, colorTarget = ColorTarget.Srgb()).argb)
    }

    private data class Case(val name: String, val icc: ByteArray, val samples: ByteArray, val results: List<ByteArray>)
}
