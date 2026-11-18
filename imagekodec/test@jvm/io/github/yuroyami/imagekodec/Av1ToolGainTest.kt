package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.Av1EncodeSettings
import io.github.yuroyami.imagekodec.codec.avif.Av1HeifEncoder
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What each coding tool the AV1 encoder chooses for itself brings (#176): the size of `encodeAvif` at effort 2 with
 * the tool, against the size without it at the same SSIMULACRA 2 score, read straight between the qualities that
 * bracket it, on the content the tool is for. The Wiener filter on the photo scenes of [PhotoScenes] and on the
 * same scenes blurred, the chroma palette and intra block copy on screen content: text in colours, and a desktop
 * of windows, icons and text.
 *
 * The table goes to the test output and to `build/av1-tool-gains.md`. It takes minutes, so it runs only with
 * `IMAGEKODEC_AV1_BENCHMARK=1`. The bounds are loose, to catch a tool that stops paying.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class Av1ToolGainTest {

    @AfterTest
    fun restore() = HeifCodecs.reset()

    /** Registers an AV1 encoder that codes as the built-in one does, with [tweak] applied to its settings. */
    private fun encodeWith(tweak: (Av1EncodeSettings) -> Av1EncodeSettings) {
        HeifCodecs.registerEncoder(object : HeifEncoder {
            override val itemType = "av01"
            override val configurationType = "av1C"
            override val alphaUrn = Av1HeifEncoder.alphaUrn
            override fun encode(planes: HeifPlanes, parameters: HeifEncodeParameters, cancellationCheck: (() -> Unit)?): HeifCodedPicture {
                val colour = intArrayOf(parameters.colorPrimaries!!, parameters.transferCharacteristics!!, parameters.matrixCoefficients!!)
                val q = if (parameters.lossless) 0 else Av1HeifEncoder.qIndex(parameters.quality)
                return Av1HeifEncoder.encode(planes, tweak(Av1EncodeSettings(q, parameters.effort, colour)), cancellationCheck)
            }
        })
    }

    private class Point(val bytes: Int, val score: Double)

    private fun curve(bitmap: KiteBitmap, qualities: List<Int>, chroma: HeifChroma): List<Point> = qualities.map { q ->
        val file = ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(quality = q, effort = 2, chroma = chroma))
        Point(file.size, ImageKodec.ssimulacra2(bitmap, ImageKodec.decode(file)))
    }

    private fun sizeAt(points: List<Point>, score: Double): Double? {
        val sorted = points.sortedBy { it.score }
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (score >= a.score && score <= b.score && b.score > a.score) return a.bytes + (score - a.score) / (b.score - a.score) * (b.bytes - a.bytes)
        }
        return null
    }

    /** The mean ratio of the size with the tool to the size without it at the same score, over [scenes]. */
    private fun gain(
        scenes: List<PhotoScenes.Scene>, qualities: List<Int>, chroma: HeifChroma, without: (Av1EncodeSettings) -> Av1EncodeSettings,
        rows: MutableList<String>, tool: String,
    ): Double {
        val ratios = ArrayList<Double>()
        for (scene in scenes) {
            HeifCodecs.reset()
            val on = curve(scene.bitmap, qualities, chroma)
            encodeWith(without)
            val off = curve(scene.bitmap, qualities, chroma)
            HeifCodecs.reset()
            val r = on.mapNotNull { p -> sizeAt(off, p.score)?.let { p.bytes / it } }
            ratios += r
            rows += "| $tool | ${scene.name} | ${on.joinToString(", ") { "${it.bytes} B at ${"%.1f".format(it.score)}" }} | " +
                "${off.joinToString(", ") { "${it.bytes} B at ${"%.1f".format(it.score)}" }} | ${if (r.isEmpty()) "-" else "%.3f".format(r.average())} |"
            println(rows.last())
        }
        return ratios.average()
    }

    @Test
    fun eachToolPays() {
        assumeTrue("set IMAGEKODEC_AV1_BENCHMARK=1 to run", System.getenv("IMAGEKODEC_AV1_BENCHMARK") == "1")
        repeat(2) { ImageKodec.encodeAvif(PhotoScenes.landscape().bitmap, HeifEncodeOptions(quality = 50)) }
        val rows = ArrayList<String>()
        rows += "| Tool | Scene | With it: size at score, by quality | Without it | Size ratio at equal score |"
        rows += "|---|---|---|---|---|"
        val photoQ = listOf(30, 50, 70, 85)
        val screenQ = listOf(10, 20, 30, 40, 50, 60)
        val photos = PhotoScenes.all()
        val blurred = photos.map { PhotoScenes.Scene("${it.name}, blurred", ScreenScenes.blurred(it.bitmap)) }
        val screens = listOf(PhotoScenes.Scene("text", ScreenScenes.text(512, 384)), PhotoScenes.Scene("desktop", ScreenScenes.desktop(512, 384)))
        val only = System.getenv("IMAGEKODEC_AV1_TOOLS")
        val wiener = if (only != null && "wiener" !in only) 0.0 else gain(photos + blurred, photoQ, HeifChroma.Yuv420, { s -> Av1EncodeSettings(s.qIndex, s.effort, s.colour, wiener = false) }, rows, "Wiener filter")
        val palette = gain(screens, screenQ, HeifChroma.Yuv444, { s -> Av1EncodeSettings(s.qIndex, s.effort, s.colour, chromaPalette = false) }, rows, "Chroma palette")
        val copy = gain(screens, screenQ, HeifChroma.Yuv444, { s -> Av1EncodeSettings(s.qIndex, s.effort, s.colour, intrabc = false) }, rows, "Intra block copy")
        rows += ""
        rows += "| Tool | Mean size ratio with it to without it at equal SSIMULACRA 2 score |"
        rows += "|---|---|"
        rows += "| Wiener filter, photo scenes and the same blurred | ${"%.3f".format(wiener)} |"
        rows += "| Chroma palette, screen content in 4:4:4 | ${"%.3f".format(palette)} |"
        rows += "| Intra block copy, screen content in 4:4:4 | ${"%.3f".format(copy)} |"
        val table = rows.joinToString("\n")
        println(table)
        File("build").mkdirs()
        File("build/av1-tool-gains.md").writeText(table + "\n")
        assertTrue(wiener < 1.01, "the Wiener filter grows the files: $wiener")
        assertTrue(palette < 1.0, "the chroma palette grows the files: $palette")
        assertTrue(copy < 1.0, "intra block copy grows the files: $copy")
    }
}
