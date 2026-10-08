package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The files [losslessJpeg] writes, which `JpegLosslessTest` decodes on every target, read by
 * ffmpeg's decoder too, so the writer and the decoder cannot share a misreading of T.81 Annex H:
 * every predictor and point transform at 2 to 16 bits. The picture starts at 1, not 0: at 16 bits
 * a 0 after the first prediction of 32768 is the difference -32768, category 16 with no extra
 * bits (T.81 Table H.2), which ffmpeg 6 decodes as something else. `JpegLosslessTest` decodes
 * that case.
 */
class LosslessWriterFfmpegTest {

    @Test
    fun ffmpegReadsTheWrittenFilesAsTheirSource() {
        assumeTrue("ffmpeg is not installed", Tools.hasAll("ffmpeg"))
        val dir = Files.createTempDirectory("imagekodec-lossless-writer").toFile()
        try {
            val w = 21
            val h = 11
            for (bits in listOf(2, 7, 8, 9, 12, 16)) for (predictor in 1..7) for (pt in listOf(0, 1, bits - 1).distinct()) {
                val size = 1 shl bits
                val src = IntArray(w * h) { i -> ((i % w) * 37 + (i / w) * 91 + ((i % w) * (i / w)) % 13 * 311 + 1) % size }
                val jpeg = File(dir, "in.jpg").apply { writeBytes(losslessJpeg(src, w, h, 1, bits, predictor, pt, if (predictor % 2 == 0) w else 0)) }
                val raw = File(dir, "out.raw")
                val format = if (bits > 8) "gray16le" else "gray"
                val process = ProcessBuilder(Tools.require("ffmpeg").path, "-v", "error", "-y", "-i", jpeg.path, "-f", "rawvideo", "-pix_fmt", format, raw.path)
                    .redirectErrorStream(true).start()
                val log = process.inputStream.readBytes().decodeToString()
                assertEquals(0, process.waitFor(), "ffmpeg failed: $log")
                val d = raw.readBytes()
                for (i in 0 until w * h) {
                    val v = if (bits > 8) {
                        ((d[2 * i].toInt() and 0xFF) or ((d[2 * i + 1].toInt() and 0xFF) shl 8)) shr (16 - bits)
                    } else {
                        (d[i].toInt() and 0xFF) shr (8 - bits)
                    }
                    assertEquals((src[i] shr pt) shl pt, v, "$bits bits, predictor $predictor, Pt $pt: sample $i")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
