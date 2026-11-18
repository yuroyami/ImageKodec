package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifBuilder

/**
 * The two pictures of the HEIC benchmark (#169), which x265 codes through ffmpeg at test time so
 * that nothing large is committed: a photo-sized grid of 512 by 512 tiles at 8 bits 4:2:0, cut
 * from one picture by ffmpeg's `untile` and coded as pictures of their own, and the same picture
 * at 10 bits as one item with wavefront rows. The source is `testsrc2` under noise, which x265
 * codes at QP 24 to about a bit a pixel, near what a phone writes. Each platform's benchmark runs
 * [Case.command] through a shell, reads the stream it writes and builds the file with [Case.file].
 */
internal object HeicBenchmark {

    const val TILE = 512

    /** One picture: [name] in the report, [stream] the file x265 writes, [command] the shell line that writes it. */
    class Case(val name: String, val width: Int, val height: Int, val stream: String, val command: String, val file: (ByteArray) -> ByteArray)

    /** The cases at [width] by [height]: a whole number of tiles, 4096 by 3072 for 12 megapixels. */
    fun cases(dir: String, width: Int = 4096, height: Int = 3072): List<Case> {
        val columns = width / TILE
        val rows = height / TILE
        val source = "-f lavfi -i testsrc2=size=${width}x$height:rate=1"
        val ffmpeg = "timeout -s KILL 300 ffmpeg -hide_banner -loglevel error $source"
        // ipratio=1 keeps x265 from moving the QP of an I picture (AGENTS.md).
        val x265 = "-c:v libx265 -x265-params keyint=1:open-gop=0:qp=24:ipratio=1:log-level=none"
        return listOf(
            Case(
                "${width}x$height grid of ${columns}x$rows tiles, 8 bits", width, height, "$dir/grid.hevc",
                "$ffmpeg -vf noise=alls=10:allf=t,untile=${columns}x$rows -frames:v ${columns * rows} -pix_fmt yuv420p $x265 -f hevc -y $dir/grid.hevc",
            ) { grid(it, columns, rows, width, height) },
            Case(
                "${width}x$height picture with WPP, 10 bits", width, height, "$dir/wpp.hevc",
                "$ffmpeg -vf noise=alls=10:allf=t -frames:v 1 -pix_fmt yuv420p10le $x265:wpp=1 -f hevc -y $dir/wpp.hevc",
            ) { single(it, width, height, 10) },
        )
    }

    private fun type(unit: ByteArray) = (unit[0].toInt() shr 1) and 0x3F

    /** The parameter sets and the pictures of [stream], each a list of its NAL units, without x265's SEI. */
    private fun pictures(stream: ByteArray): Pair<List<ByteArray>, List<List<ByteArray>>> {
        val units = AnnexB.split(stream).filter { type(it) != 39 && type(it) != 40 }
        val sets = units.filter { type(it) in 32..34 }
        val pictures = ArrayList<ArrayList<ByteArray>>()
        for (u in units) {
            if (type(u) > 31) continue
            // first_slice_segment_in_pic_flag, the first bit after the NAL unit header.
            if ((u[2].toInt() and 0x80) != 0 || pictures.isEmpty()) pictures += ArrayList<ByteArray>()
            pictures.last() += u
        }
        return sets to pictures
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false
    private val bt709 = box("colr", "nclx".encodeToByteArray() + byteArrayOf(0, 1, 0, 1, 0, 1, 0)) to false

    private fun heif(build: HeifBuilder.() -> Unit) = HeifBuilder("heic", listOf("mif1", "heic", "miaf")).apply(build).build()

    /** The pictures of [stream] as the tiles, row by row, of a [columns] by [rows] grid. */
    fun grid(stream: ByteArray, columns: Int, rows: Int, width: Int, height: Int): ByteArray {
        val (sets, pictures) = pictures(stream)
        check(pictures.size == columns * rows) { "x265 wrote ${pictures.size} pictures for $columns by $rows tiles" }
        val hvcC = box("hvcC", AnnexB.hvcC(sets, 4, 1, 8, 8)) to true
        return heif {
            add(1, "grid", byteArrayOf(0, 0, (rows - 1).toByte(), (columns - 1).toByte(), (width shr 8).toByte(), width.toByte(), (height shr 8).toByte(), height.toByte()), listOf(ispe(width, height), bt709))
            for ((k, p) in pictures.withIndex()) add(2 + k, "hvc1", AnnexB.sample(p), listOf(hvcC, ispe(TILE, TILE), bt709), hidden = true)
            references += Triple("dimg", 1L, LongArray(pictures.size) { it + 2L })
        }
    }

    /** The one picture of [stream] as one item. */
    fun single(stream: ByteArray, width: Int, height: Int, depth: Int): ByteArray {
        val (sets, pictures) = pictures(stream)
        check(pictures.size == 1) { "x265 wrote ${pictures.size} pictures" }
        val hvcC = box("hvcC", AnnexB.hvcC(sets, 4, 1, depth, depth)) to true
        return heif { add(1, "hvc1", AnnexB.sample(pictures[0]), listOf(hvcC, ispe(width, height), bt709)) }
    }

    /** The shell line that times libde265 on one thread over [stream], fastest of three, in milliseconds, or nothing without it. */
    fun dec265Command(stream: String): String =
        "command -v libde265-dec265 >/dev/null 2>&1 && D=libde265-dec265 || D=dec265; " +
            "for i in 1 2 3; do s=\$(date +%s%N); timeout -s KILL 300 \$D -q -t 0 $stream >/dev/null 2>&1 || exit 1; " +
            "echo \$(( (\$(date +%s%N) - s) / 1000000 )); done | sort -n | head -1"

    /** The decodes a benchmark times, after this many to warm up. */
    const val WARM_UP = 2
    const val RUNS = 5

    /**
     * The bounds, an order of magnitude past what the JVM measures (`docs/limits.md`): they catch a
     * decoder that grows tenfold, not a slow run on a loaded machine.
     */
    const val MAX_MILLIS = 30_000L
    const val MAX_PEAK_MB = 1_500L
}
