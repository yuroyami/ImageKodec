package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AVIF pictures of the matrices that are not linear (#189): BT.2020 constant luminance (10),
 * chromaticity-derived constant luminance (13) and ICtCp (14), against zimg, which converts all
 * three. zimg (ffmpeg's `zscale`) makes the YUV samples from a photo-like scene, avifenc codes them
 * as they are (a y4m keeps its samples, range and depth), avifdec gives the decoded samples back as
 * a y4m, and zimg converts those to RGB, which the decode must match.
 */
class AvifMatrixOracleTest {

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        proc.inputStream.copyTo(ByteArrayOutputStream())
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    private fun temp(ext: String) = File.createTempFile("kite-matrix", ext).apply { deleteOnExit() }

    private class Case(val zimgMatrix: String, val matrix: Int, val zimgTransfer: String, val transfer: Int, val depth: Int, val primaries: Int = 9)

    @Test
    fun nonLinearMatricesMatchZimg() {
        assumeTrue("ffmpeg, avifenc or avifdec is not installed", Tools.hasAll("ffmpeg", "avifenc", "avifdec"))
        val ffmpeg = Tools.require("ffmpeg").path
        val scene = PhotoScenes.landscape(64, 48).bitmap
        val png = temp(".png").apply { writeBytes(ImageKodec.encodePng(scene)) }
        val reports = ArrayList<String>()
        for (c in listOf(
            Case("2020_cl", 10, "bt2020-10", 14, 10),
            Case("2020_cl", 10, "709", 1, 8),
            Case("chroma-derived-c", 13, "bt2020-12", 15, 12),
            Case("chroma-derived-c", 13, "709", 1, 10, primaries = 1),
            Case("ictcp", 14, "smpte2084", 16, 10),
            Case("ictcp", 14, "arib-std-b67", 18, 12),
        )) {
            val prim = if (c.primaries == 9) "2020" else "709"
            val pix = if (c.depth == 8) "yuv444p" else "yuv444p${c.depth}le"
            val y4m = temp(".y4m")
            assertEquals(0, run(ffmpeg, "-v", "error", "-y", "-i", png.path, "-vf",
                "zscale=min=gbr:tin=${c.zimgTransfer}:pin=$prim:rin=full:m=${c.zimgMatrix}:t=${c.zimgTransfer}:p=$prim:r=limited,format=$pix",
                "-strict", "-1", "-f", "yuv4mpegpipe", y4m.path), "zimg could not make the YUV")
            val avif = temp(".avif")
            assertEquals(0, run(Tools.require("avifenc").path, "-q", "90", "--cicp", "${c.primaries}/${c.transfer}/${c.matrix}", y4m.path, avif.path))
            val decoded = temp(".y4m")
            assertEquals(0, run(Tools.require("avifdec").path, avif.path, decoded.path))
            val raw = temp(".raw")
            assertEquals(0, run(ffmpeg, "-v", "error", "-y", "-i", decoded.path, "-vf",
                "zscale=min=${c.zimgMatrix}:tin=${c.zimgTransfer}:pin=$prim:rin=limited:m=gbr:t=${c.zimgTransfer}:p=$prim:r=full,format=gbrp16le",
                "-f", "rawvideo", raw.path))
            val ref = raw.readBytes()
            val bytes = avif.readBytes()
            val info = ImageKodec.probe(bytes)
            assertTrue(info.isDecodable, info.unsupportedReason)
            val ours = ImageKodec.decode(bytes)
            val n = ours.width * ours.height
            assertEquals(n * 6, ref.size)
            fun plane(p: Int, i: Int) = (ref[(p * n + i) * 2].toInt() and 0xFF) or ((ref[(p * n + i) * 2 + 1].toInt() and 0xFF) shl 8)
            var worst = 0
            var total = 0L
            for (i in 0 until n) {
                // gbrp: green, blue, red.
                val want = intArrayOf(plane(2, i), plane(0, i), plane(1, i)).map { (it * 255.0 / 65535).roundToInt() }
                val got = intArrayOf((ours.argb[i] shr 16) and 0xFF, (ours.argb[i] shr 8) and 0xFF, ours.argb[i] and 0xFF)
                for (k in 0 until 3) {
                    val d = abs(want[k] - got[k])
                    worst = maxOf(worst, d)
                    total += d
                }
            }
            val report = "matrix ${c.matrix}, transfer ${c.transfer}, ${c.depth} bits: mean ${total.toDouble() / (n * 3)}, worst $worst"
            reports += report + if (worst > 1) " FAILED" else ""
        }
        println(reports.joinToString("\n"))
        assertTrue(reports.none { it.endsWith("FAILED") }, reports.joinToString("\n"))
    }
}
