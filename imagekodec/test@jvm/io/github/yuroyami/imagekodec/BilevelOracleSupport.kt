package io.github.yuroyami.imagekodec

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** What the CCITT and JBIG2 encoder oracles share: running a tool, and a bilevel TIFF of one strip. */
internal object BilevelOracleSupport {

    class Run(val exit: Int, val log: String)

    /** Runs [command] in [dir] with a time limit, and returns its exit code and everything it printed. */
    fun run(dir: File, vararg command: String): Run {
        val log = File(dir, "tool.log")
        val process = ProcessBuilder(*command).directory(dir).redirectErrorStream(true).redirectOutput(log).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "${command.first()} timed out")
        return Run(process.exitValue(), log.readText())
    }

    /**
     * A little-endian TIFF of one strip holding [data], a bilevel page of [width] by [height] coded
     * with [compression], WhiteIsZero, so a 1 bit or a black run is black. [faxOptions] is
     * T4Options for compression 3 and T6Options for compression 4.
     */
    fun tiff(data: ByteArray, width: Int, height: Int, compression: Int, faxOptions: Long = 0): ByteArray {
        val tags = ArrayList<LongArray>() // tag, type, value
        fun tag(id: Int, type: Int, value: Long) { tags += longArrayOf(id.toLong(), type.toLong(), value) }
        val dataAt = 8L
        tag(256, 4, width.toLong())
        tag(257, 4, height.toLong())
        tag(258, 3, 1)
        tag(259, 3, compression.toLong())
        tag(262, 3, 0)
        tag(273, 4, dataAt)
        tag(277, 3, 1)
        tag(278, 4, height.toLong())
        tag(279, 4, data.size.toLong())
        if (compression == 3) tag(292, 4, faxOptions)
        if (compression == 4) tag(293, 4, faxOptions)
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        val ifdAt = (dataAt + data.size + 1) and 1L.inv()
        out.write('I'.code); out.write('I'.code); u16(42); u32(ifdAt)
        out.write(data)
        while (out.size() < ifdAt) out.write(0)
        u16(tags.size)
        for (t in tags) {
            u16(t[0].toInt()); u16(t[1].toInt()); u32(1)
            if (t[1] == 3L) { u16(t[2].toInt()); u16(0) } else u32(t[2])
        }
        u32(0)
        return out.toByteArray()
    }

    /** The one strip of the first page of a little-endian TIFF that holds its page in one strip. */
    fun singleStrip(tiff: ByteArray): ByteArray {
        fun u16(at: Int) = (tiff[at].toInt() and 0xFF) or ((tiff[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
        check(tiff[0] == 'I'.code.toByte()) { "a big-endian TIFF" }
        val ifd = u32(4)
        fun value(tag: Int): Int {
            for (i in 0 until u16(ifd)) {
                val at = ifd + 2 + 12 * i
                if (u16(at) != tag) continue
                check(u32(at + 4) == 1) { "tag $tag has ${u32(at + 4)} values, so the page is in several strips" }
                return if (u16(at + 2) == 3) u16(at + 8) else u32(at + 8)
            }
            error("no tag $tag")
        }
        val offset = value(273)
        return tiff.copyOfRange(offset, offset + value(279))
    }

    /** A P4 PBM: the page as a row-major array, true where black. */
    fun readPbm(file: File): Triple<Int, Int, BooleanArray> {
        val d = file.readBytes()
        var at = 0
        fun token(): String {
            while (d[at].toInt().toChar().isWhitespace() || d[at] == '#'.code.toByte()) {
                if (d[at] == '#'.code.toByte()) while (d[at] != '\n'.code.toByte()) at++
                at++
            }
            val start = at
            while (!d[at].toInt().toChar().isWhitespace()) at++
            return String(d, start, at - start)
        }
        check(token() == "P4") { "${file.name} is not a P4 PBM" }
        val w = token().toInt()
        val h = token().toInt()
        at++
        val stride = (w + 7) / 8
        val black = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (d[at + y * stride + (x shr 3)].toInt() shr (7 - (x and 7))) and 1 == 1
        }
        return Triple(w, h, black)
    }
}
