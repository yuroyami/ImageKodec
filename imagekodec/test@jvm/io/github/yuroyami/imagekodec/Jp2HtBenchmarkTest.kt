package io.github.yuroyami.imagekodec

import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The HT block coder against the Part 1 coder of the same encoder on the photo-like scenes of
 * [PhotoScenes] (#187): the file size of each at quality 50 and 90 and lossless, the PSNR and
 * SSIMULACRA 2 score each reaches, and the time ImageKodec takes to encode and to decode each, in
 * process after a warm-up. No HTJ2K encoder is installed to compare with, so this holds the HT
 * files to the Part 1 ones of the same quality.
 *
 * The bounds are loose and catch a regression: each lossy HT file at most 1.45 times the Part 1
 * file's size, its PSNR within 0.5 dB of the Part 1 file's, and a lossless HT file at most 1.4
 * times the size of the Part 1 one. The HT block coder trades size for speed: on these scenes its
 * files are 4 to 35 percent larger.
 */
class Jp2HtBenchmarkTest {

    private fun psnr(a: KiteBitmap, b: KiteBitmap): Double {
        var se = 0.0
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16)) {
            val d = ((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF)
            se += d * d
        }
        val mse = se / (a.argb.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        var v = block()
        repeat(2) { v = block() }
        return v to (System.nanoTime() - start) / 3_000_000
    }

    @Test
    fun htAgainstPart1OnPhotoScenes() {
        val scenes = PhotoScenes.all()
        repeat(3) {
            for (ht in listOf(false, true)) for (lossless in listOf(false, true)) {
                val bytes = ImageKodec.encodeJp2(scenes[0].bitmap, Jp2EncodeOptions(lossless = lossless, highThroughput = ht))
                ImageKodec.decode(bytes)
            }
        }
        println("| scene | quality | Part 1 bytes | HT bytes | HT / Part 1 | Part 1 PSNR | HT PSNR | Part 1 SSIMULACRA 2 | HT SSIMULACRA 2 | Part 1 encode ms | HT encode ms | Part 1 decode ms | HT decode ms |")
        println("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        var worstRatio = 0.0
        var worstLossless = 0.0
        var worstPsnr = 0.0
        for (scene in scenes) for (quality in listOf(50, 90, -1)) {
            val lossless = quality < 0
            fun options(ht: Boolean) = Jp2EncodeOptions(quality = if (lossless) 75 else quality, lossless = lossless, highThroughput = ht)
            val (part1, part1Encode) = timed { ImageKodec.encodeJp2(scene.bitmap, options(false)) }
            val (ht, htEncode) = timed { ImageKodec.encodeJp2(scene.bitmap, options(true)) }
            val (a, part1Decode) = timed { ImageKodec.decode(part1) }
            val (b, htDecode) = timed { ImageKodec.decode(ht) }
            val ratio = ht.size.toDouble() / part1.size
            val pa = psnr(scene.bitmap, a)
            val pb = psnr(scene.bitmap, b)
            val sa = ImageKodec.ssimulacra2(scene.bitmap, a)
            val sb = ImageKodec.ssimulacra2(scene.bitmap, b)
            if (lossless) worstLossless = maxOf(worstLossless, ratio) else {
                worstRatio = maxOf(worstRatio, ratio)
                worstPsnr = maxOf(worstPsnr, pa - pb)
            }
            println(
                "| ${scene.name} | ${if (lossless) "lossless" else "$quality"} | ${part1.size} | ${ht.size} | ${"%.3f".format(ratio)} | " +
                    "${"%.2f".format(pa)} | ${"%.2f".format(pb)} | ${"%.2f".format(sa)} | ${"%.2f".format(sb)} | " +
                    "$part1Encode | $htEncode | $part1Decode | $htDecode |",
            )
        }
        println("[#187] worst HT / Part 1 size: lossy ${"%.3f".format(worstRatio)}, lossless ${"%.3f".format(worstLossless)}; worst PSNR loss ${"%.2f".format(worstPsnr)} dB")
        assertTrue(worstRatio <= 1.45, "an HT file is $worstRatio times the Part 1 file's size")
        assertTrue(worstLossless <= 1.4, "a lossless HT file is $worstLossless times the Part 1 file's size")
        assertTrue(worstPsnr <= 0.5, "an HT file's PSNR is $worstPsnr dB below the Part 1 file's")
    }
}
