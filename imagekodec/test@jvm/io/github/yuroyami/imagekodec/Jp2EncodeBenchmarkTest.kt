package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The JPEG 2000 encoder against `opj_compress` on the photo-like scenes of [PhotoScenes] (#180).
 * For each scene and each of the ratios 10, 20 and 40, OpenJPEG codes the scene with the 9/7
 * wavelet at that ratio (`-I -r`), and this encoder codes it to no more than OpenJPEG's file size;
 * the two are then held at equal size, both decoded by ImageKodec, by PSNR over the three channels
 * and by SSIMULACRA 2. The lossless sizes compare `opj_compress` with no option against
 * `lossless = true`, and the quality mapping prints the PSNR reached at a few qualities. The
 * encode times are this encoder's in process after a warm-up, and `opj_compress`'s whole run,
 * reading the PPM and writing the file included.
 *
 * The bounds are loose and catch a regression: the mean PSNR within 0.2 dB of OpenJPEG's at each
 * ratio, the mean SSIMULACRA 2 score within 1, and each lossless file within 1 percent of OpenJPEG's.
 */
class Jp2EncodeBenchmarkTest {

    private val dir: File = Files.createTempDirectory("kite-j2b").toFile().apply { deleteOnExit() }

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "timed out"
        }
        return process.exitValue() to out
    }

    private fun ppm(bitmap: KiteBitmap): File {
        val header = "P6\n${bitmap.width} ${bitmap.height}\n255\n".encodeToByteArray()
        val body = ByteArray(bitmap.argb.size * 3)
        for ((i, p) in bitmap.argb.withIndex()) {
            body[i * 3] = (p ushr 16).toByte(); body[i * 3 + 1] = (p ushr 8).toByte(); body[i * 3 + 2] = p.toByte()
        }
        return File(dir, "src.ppm").apply { writeBytes(header + body) }
    }

    private fun psnr(a: KiteBitmap, b: KiteBitmap): Double {
        var se = 0.0
        for (i in a.argb.indices) for (shift in intArrayOf(0, 8, 16)) {
            val d = ((a.argb[i] ushr shift) and 0xFF) - ((b.argb[i] ushr shift) and 0xFF)
            se += d * d
        }
        val mse = se / (a.argb.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    /** opj_compress of [src] with [args]: the file and the milliseconds of the whole run. */
    private fun openjpeg(src: File, vararg args: String): Pair<ByteArray, Long> {
        val out = File(dir, "opj.jp2")
        val start = System.nanoTime()
        val (code, log) = run(Tools.require("opj_compress").path, "-i", src.path, "-o", out.path, *args)
        val ms = (System.nanoTime() - start) / 1_000_000
        check(code == 0) { "opj_compress failed: $log" }
        return out.readBytes() to ms
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun sizeAndQualityAgainstOpenjpeg() {
        assumeTrue("OpenJPEG tools not installed", Tools.hasAll("opj_compress"))
        val scenes = PhotoScenes.all()
        // Warm the JIT so that the times below are of the encoder, not of the compiler.
        repeat(5) { for (o in listOf(Jp2EncodeOptions(ratios = listOf(20.0)), Jp2EncodeOptions(lossless = true))) ImageKodec.encodeJp2(scenes[0].bitmap, o) }

        println("| scene | ratio | OpenJPEG bytes | ours bytes | OpenJPEG PSNR | ours PSNR | OpenJPEG SSIMULACRA 2 | ours SSIMULACRA 2 | OpenJPEG ms | ours ms |")
        println("|---|---|---|---|---|---|---|---|---|---|")
        val ratios = listOf(10, 20, 40)
        val psnrGap = DoubleArray(ratios.size)
        val ssimGap = DoubleArray(ratios.size)
        for (scene in scenes) {
            val bitmap = scene.bitmap
            val src = ppm(bitmap)
            val raw = bitmap.width.toDouble() * bitmap.height * 3
            for ((k, ratio) in ratios.withIndex()) {
                val (theirs, theirMs) = openjpeg(src, "-I", "-r", "$ratio")
                // At most OpenJPEG's size: the ratio its file reached.
                val (ours, ourMs) = timed { ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(raw / theirs.size))) }
                assertTrue(ours.size <= theirs.size, "${scene.name} at $ratio: ${ours.size} bytes, more than OpenJPEG's ${theirs.size}")
                val theirPicture = ImageKodec.decode(theirs)
                val ourPicture = ImageKodec.decode(ours)
                val p1 = psnr(bitmap, theirPicture)
                val p2 = psnr(bitmap, ourPicture)
                val s1 = ImageKodec.ssimulacra2(bitmap, theirPicture)
                val s2 = ImageKodec.ssimulacra2(bitmap, ourPicture)
                psnrGap[k] += (p2 - p1) / scenes.size
                ssimGap[k] += (s2 - s1) / scenes.size
                println(
                    "| ${scene.name} | $ratio | ${theirs.size} | ${ours.size} | ${"%.2f".format(p1)} | ${"%.2f".format(p2)} | " +
                        "${"%.2f".format(s1)} | ${"%.2f".format(s2)} | $theirMs | $ourMs |",
                )
            }
        }
        for ((k, ratio) in ratios.withIndex()) {
            println("ratio $ratio: mean PSNR difference ${"%+.3f".format(psnrGap[k])} dB, mean SSIMULACRA 2 difference ${"%+.3f".format(ssimGap[k])}")
            assertTrue(psnrGap[k] >= -0.2, "at ratio $ratio the PSNR is ${psnrGap[k]} dB below OpenJPEG's on average")
            assertTrue(ssimGap[k] >= -1.0, "at ratio $ratio the SSIMULACRA 2 score is ${ssimGap[k]} below OpenJPEG's on average")
        }

        println()
        println("| scene | OpenJPEG lossless bytes | ours | ours, effort 3 | OpenJPEG ms | ours ms | ours, effort 3, ms |")
        println("|---|---|---|---|---|---|---|")
        for (scene in scenes) {
            val bitmap = scene.bitmap
            val (theirs, theirMs) = openjpeg(ppm(bitmap))
            val (ours, ourMs) = timed { ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true)) }
            val (thorough, thoroughMs) = timed { ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true, effort = 3)) }
            println("| ${scene.name} | ${theirs.size} | ${ours.size} | ${thorough.size} | $theirMs | $ourMs | $thoroughMs |")
            assertTrue(ours.size <= theirs.size * 1.01, "${scene.name}: lossless ${ours.size} bytes, OpenJPEG ${theirs.size}")
            assertTrue(thorough.size <= ours.size, "${scene.name}: effort 3 wrote ${thorough.size} bytes, effort 2 ${ours.size}")
        }

        println()
        println("| quality | target PSNR | mean PSNR reached | mean bytes |")
        println("|---|---|---|---|")
        for (quality in listOf(10, 25, 50, 75, 90, 100)) {
            var reached = 0.0
            var bytes = 0L
            for (scene in scenes) {
                val file = ImageKodec.encodeJp2(scene.bitmap, quality)
                reached += psnr(scene.bitmap, ImageKodec.decode(file)) / scenes.size
                bytes += file.size / scenes.size
            }
            println("| $quality | ${"%.2f".format(20 + 0.28 * quality)} | ${"%.2f".format(reached)} | $bytes |")
        }
    }
}
