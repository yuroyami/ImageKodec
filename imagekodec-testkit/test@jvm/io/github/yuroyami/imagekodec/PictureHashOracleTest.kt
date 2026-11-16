package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PictureHash] against the decoded picture hash SEI that x265 writes: ffmpeg encodes a picture
 * with each hash type and decodes its planes, and each plane must hash to the value in the stream.
 * libde265 1.0.15 rejects x265's checksums above 8 bits, which match D-25 here, so it is no oracle
 * for that type.
 */
class PictureHashOracleTest {

    private class Case(val pixFmt: String, val planes: Int, val subX: Int, val subY: Int, val depth: Int)

    private val cases = listOf(
        Case("yuv420p", 3, 1, 1, 8),
        Case("yuv422p10le", 3, 1, 0, 10),
        Case("yuv444p12le", 3, 0, 0, 12),
        Case("gray", 1, 0, 0, 8),
    )

    private fun run(vararg command: String): ByteArray {
        val p = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes()
        check(p.waitFor() == 0) { "${command.joinToString(" ")} failed" }
        return out
    }

    /** The rbsp of [nal] without its header and emulation prevention bytes. */
    private fun rbsp(nal: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (i in 2 until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
        }
        return out.toByteArray()
    }

    /** The hash type and the per-plane hash bytes of the decoded picture hash SEI in [stream]. */
    private fun seiHash(stream: ByteArray, planes: Int): Pair<Int, List<ByteArray>> {
        for (nal in AnnexB.split(stream)) {
            if ((nal[0].toInt() ushr 1) and 0x3F != 40) continue
            val r = rbsp(nal)
            var p = 0
            var type = 0
            while (r[p].toInt() and 0xFF == 0xFF) { type += 255; p++ }
            type += r[p++].toInt() and 0xFF
            var size = 0
            while (r[p].toInt() and 0xFF == 0xFF) { size += 255; p++ }
            size += r[p++].toInt() and 0xFF
            if (type != 132) continue
            val hashType = r[p++].toInt()
            val width = when (hashType) { 0 -> 16; 1 -> 2; else -> 4 }
            return hashType to List(planes) { k -> r.copyOfRange(p + k * width, p + (k + 1) * width) }
        }
        error("no decoded picture hash SEI")
    }

    @Test
    fun everyHashTypeMatchesX265() {
        assumeTrue(Tools.hasAll("ffmpeg"))
        val dir = File(System.getProperty("java.io.tmpdir"), "imagekodec-hash").apply { mkdirs() }
        var checked = 0
        for (case in cases) for (hashType in 1..3) {
            val stream = File(dir, "${case.pixFmt}-$hashType.hevc")
            run(
                Tools.require("ffmpeg").path, "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=72x40",
                "-frames:v", "1", "-pix_fmt", case.pixFmt, "-c:v", "libx265", "-x265-params", "hash=$hashType:log-level=none",
                "-f", "hevc", stream.path,
            )
            val bytes = stream.readBytes()
            val raw = run(Tools.require("ffmpeg").path, "-loglevel", "error", "-i", stream.path, "-f", "rawvideo", "-pix_fmt", case.pixFmt, "-")
            val wide = case.depth > 8
            val samples = ShortArray(raw.size / (if (wide) 2 else 1)) { i ->
                if (wide) ((raw[2 * i].toInt() and 0xFF) or ((raw[2 * i + 1].toInt() and 0xFF) shl 8)).toShort() else (raw[i].toInt() and 0xFF).toShort()
            }
            val (type, hashes) = seiHash(bytes, case.planes)
            assertEquals(hashType - 1, type)
            var offset = 0
            for (c in 0 until case.planes) {
                // x265 3.5 writes a wrong CRC for chroma: libde265 rejects those streams too.
                if (type == 1 && c > 0) break
                val w = if (c == 0) 72 else 72 shr case.subX
                val h = if (c == 0) 40 else 40 shr case.subY
                val expected = hashes[c]
                val actual = when (type) {
                    0 -> PictureHash.md5(PictureHash.pictureData(samples, offset, w, w, h, case.depth))
                    1 -> PictureHash.crc(PictureHash.pictureData(samples, offset, w, w, h, case.depth)).let { byteArrayOf((it ushr 8).toByte(), it.toByte()) }
                    else -> PictureHash.checksum(samples, offset, w, w, h, case.depth).let { v -> ByteArray(4) { k -> (v ushr (24 - 8 * k)).toByte() } }
                }
                assertEquals(expected.toList(), actual.toList(), "${case.pixFmt} hash type $type plane $c")
                offset += w * h
                checked++
            }
        }
        assertTrue(checked == 24, "checked $checked planes")
    }
}
