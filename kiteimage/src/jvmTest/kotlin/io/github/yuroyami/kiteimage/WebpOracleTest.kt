package io.github.yuroyami.kiteimage

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure-Kotlin VP8L decoder against libwebp itself. Fixtures are encoded on
 * the fly with `cwebp` from images this library wrote (our own PNG encoder, so
 * the round trip dogfoods both), decoded with [KiteImage], and compared
 * pixel-exact against `dwebp` on the same file.
 *
 * Lossless means "exact", so any disagreement at all is a bug: there is no
 * tolerance band here. The image set is chosen to reach the parts of the format
 * the embedded vectors in `WebpDecoderTest` cannot: every spatial predictor, the
 * cross-colour transform, both palette packings, long back references, and
 * pictures big enough that the encoder splits them into several prefix-code
 * groups via the meta-prefix image.
 *
 * Skips cleanly when the libwebp tools are absent.
 */
class WebpOracleTest {

    private val cwebp = File("/opt/homebrew/bin/cwebp")
    private val dwebp = File("/opt/homebrew/bin/dwebp")

    private fun tools(): Boolean = cwebp.canExecute() && dwebp.canExecute()

    private fun run(vararg args: String): Int {
        val proc = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        proc.inputStream.copyTo(out)
        if (!proc.waitFor(120, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1
        }
        return proc.exitValue()
    }

    /** Read a `dwebp -pam` file back as ARGB, which is our own pixel layout. */
    private fun readPam(file: File): KiteBitmap {
        val bytes = file.readBytes()
        val marker = "ENDHDR\n".toByteArray()
        var at = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) if (bytes[i + j] != marker[j]) continue@outer
            at = i + marker.size
            break
        }
        require(at > 0) { "no PAM header in ${file.name}" }
        val header = String(bytes, 0, at)
        fun field(name: String) = header.lineSequence()
            .first { it.startsWith("$name ") }.substringAfter(' ').trim().toInt()
        val w = field("WIDTH")
        val h = field("HEIGHT")
        val depth = field("DEPTH")
        require(depth == 4) { "expected RGBA PAM, got depth $depth" }

        val px = IntArray(w * h)
        for (i in px.indices) {
            val o = at + i * 4
            px[i] = ((bytes[o + 3].toInt() and 0xFF) shl 24) or
                ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                (bytes[o + 2].toInt() and 0xFF)
        }
        return KiteBitmap(w, h, px)
    }

    /** Encode [source] losslessly with cwebp, decode both ways, compare exactly. */
    private fun checkLossless(name: String, source: KiteBitmap, extraArgs: List<String> = emptyList()) {
        val png = File.createTempFile("kite-webp-$name", ".png").apply { deleteOnExit() }
        png.writeBytes(KiteImage.encodePng(source))
        val webp = File.createTempFile("kite-webp-$name", ".webp").apply { deleteOnExit() }
        val pam = File.createTempFile("kite-webp-$name", ".pam").apply { deleteOnExit() }

        val args = mutableListOf(cwebp.path, "-lossless", "-exact")
        args += extraArgs
        args += listOf(png.path, "-o", webp.path)
        assertEquals(0, run(*args.toTypedArray()), "$name: cwebp failed")
        assertEquals(0, run(dwebp.path, "-pam", webp.path, "-o", pam.path), "$name: dwebp failed")

        val reference = readPam(pam)
        val ours = KiteImage.decode(webp.readBytes())

        assertEquals(reference.width, ours.width, "$name width")
        assertEquals(reference.height, ours.height, "$name height")
        for (y in 0 until reference.height) {
            for (x in 0 until reference.width) {
                assertEquals(
                    reference[x, y].toUInt().toString(16),
                    ours[x, y].toUInt().toString(16),
                    "$name pixel ($x, $y)",
                )
            }
        }

        // The header-only probe must agree with the pixels it never looked at.
        val info = KiteImage.probe(webp.readBytes())
        assertEquals(ours.width, info.width, "$name probe width")
        assertEquals(ours.height, info.height, "$name probe height")
        assertTrue(info.isDecodable, "$name probe decodable")
    }

    private fun make(w: Int, h: Int, f: (Int, Int) -> Int) =
        KiteBitmap(w, h, IntArray(w * h) { f(it % w, it / w) })

    @Test
    fun smoothGradientMatchesLibwebp() {
        assumeTrue("libwebp tools not installed", tools())
        // Smooth data is where the spatial predictors earn their keep.
        checkLossless("gradient", make(97, 61) { x, y ->
            argb(0xFF, x * 255 / 96, y * 255 / 60, (x + y) * 255 / 156)
        })
    }

    @Test
    fun sharpEdgesMatchLibwebp() {
        assumeTrue("libwebp tools not installed", tools())
        // Hard edges push the encoder onto the gradient/select predictors.
        checkLossless("edges", make(64, 64) { x, y ->
            if ((x / 7 + y / 5) % 2 == 0) argb(0xFF, 250, 20, 30) else argb(0xFF, 15, 240, 200)
        })
    }

    @Test
    fun pseudoRandomNoiseMatchesLibwebp() {
        assumeTrue("libwebp tools not installed", tools())
        // Noise defeats prediction, so this exercises long literal runs and the
        // full distance-code space instead.
        var seed = 0x12345
        checkLossless("noise", make(80, 53) { _, _ ->
            seed = seed * 1103515245 + 12345
            argb(0xFF, (seed ushr 16) and 0xFF, (seed ushr 8) and 0xFF, seed and 0xFF)
        })
    }

    @Test
    fun repeatingPatternExercisesBackReferences() {
        assumeTrue("libwebp tools not installed", tools())
        // A tiled motif compresses almost entirely into back references, including
        // long-distance ones that go through the plane-code table.
        checkLossless("tiles", make(128, 96) { x, y ->
            val tx = x % 13
            val ty = y % 11
            argb(0xFF, tx * 19, ty * 23, (tx * ty) and 0xFF)
        })
    }

    @Test
    fun twoColourImageUsesTheDensestPalettePacking() {
        assumeTrue("libwebp tools not installed", tools())
        // <= 2 colours bundles eight pixels per byte.
        checkLossless("mono", make(61, 37) { x, y ->
            if ((x * y) % 3 == 0) argb(0xFF, 0, 0, 0) else argb(0xFF, 255, 255, 255)
        })
    }

    @Test
    fun fourColourImageUsesTheNextPalettePacking() {
        assumeTrue("libwebp tools not installed", tools())
        val palette = intArrayOf(
            argb(0xFF, 200, 0, 0), argb(0xFF, 0, 200, 0),
            argb(0xFF, 0, 0, 200), argb(0xFF, 200, 200, 0),
        )
        checkLossless("four", make(45, 29) { x, y -> palette[(x + y * 3) % 4] })
    }

    @Test
    fun sixteenColourImageUsesTheLastPalettePacking() {
        assumeTrue("libwebp tools not installed", tools())
        checkLossless("sixteen", make(51, 33) { x, y ->
            val i = (x + y) % 16
            argb(0xFF, i * 17, 255 - i * 17, (i * 5) and 0xFF)
        })
    }

    @Test
    fun alphaChannelSurvivesExactly() {
        assumeTrue("libwebp tools not installed", tools())
        checkLossless("alpha", make(72, 48) { x, y ->
            argb((x * 3 + y * 5) and 0xFF, x * 3, y * 5, (x xor y) and 0xFF)
        })
    }

    @Test
    fun fullyTransparentRegionsSurvive() {
        assumeTrue("libwebp tools not installed", tools())
        // -exact keeps the colour under alpha 0, which is exactly where a decoder
        // that "helpfully" zeroes RGB would diverge.
        checkLossless("holes", make(40, 40) { x, y ->
            if ((x / 8 + y / 8) % 2 == 0) argb(0, 111, 222, 33) else argb(0xFF, 10, 20, 30)
        })
    }

    @Test
    fun largeImageSplitsIntoSeveralPrefixGroups() {
        assumeTrue("libwebp tools not installed", tools())
        // Big enough, and varied enough by region, that cwebp emits a meta-prefix
        // image and several entropy groups.
        checkLossless("meta", make(320, 240) { x, y ->
            when {
                x < 160 && y < 120 -> argb(0xFF, x and 0xFF, y and 0xFF, 0)
                x >= 160 && y < 120 -> argb(0xFF, 200, (x * y) and 0xFF, 40)
                x < 160 -> if ((x + y) % 2 == 0) argb(0xFF, 0, 0, 0) else argb(0xFF, 255, 255, 255)
                else -> argb(0xFF, (x xor y) and 0xFF, 128, (x + y) and 0xFF)
            }
        })
    }

    @Test
    fun everyCompressionEffortLevelDecodes() {
        assumeTrue("libwebp tools not installed", tools())
        // -z picks different transform and entropy choices at each level, so this
        // sweeps combinations the fixed default would never produce.
        val src = make(56, 40) { x, y -> argb(0xFF, x * 4, y * 6, (x * y) and 0xFF) }
        for (z in 0..9) {
            checkLossless("z$z", src, listOf("-z", z.toString()))
        }
    }

    @Test
    fun singleRowAndSingleColumnImagesDecode() {
        assumeTrue("libwebp tools not installed", tools())
        checkLossless("row", make(97, 1) { x, _ -> argb(0xFF, x * 2, 255 - x * 2, x and 0xFF) })
        checkLossless("column", make(1, 83) { _, y -> argb(0xFF, y * 3, y, 255 - y * 3) })
        checkLossless("dot", make(1, 1) { _, _ -> argb(0xFF, 7, 200, 63) })
    }
}
