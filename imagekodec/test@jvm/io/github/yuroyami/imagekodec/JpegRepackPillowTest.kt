package io.github.yuroyami.imagekodec

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import io.github.yuroyami.imagekodec.codec.JpegRepack
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ImageKodec.repackJpeg] on JPEGs that libjpeg-turbo writes through Pillow (#132): 4:2:0, 4:2:2
 * and 4:4:4, gray, CMYK, progressive with refinement scans and with restart markers. Each rewrite
 * decodes in libjpeg to the same samples as its input, byte for byte, and no output is larger.
 * A baseline output is within 1 percent of the same pixels saved with `optimize=True`, libjpeg's
 * optimal tables, which give the coefficients of the plain save, as `jpegtran -optimize` does.
 * A progressive libjpeg file has optimal tables already, so it need not get smaller.
 */
class JpegRepackPillowTest {

    private val makeScript = """
import sys, random
from PIL import Image
random.seed(132)
w, h = 413, 287
im = Image.new('RGB', (w, h))
px = im.load()
for y in range(h):
    for x in range(w):
        e = 70 if (x // 17 + y // 11) % 3 == 0 else 0
        px[x, y] = (min(255, x * 255 // w + e + random.randrange(30)), min(255, y * 255 // h + random.randrange(30)), min(255, (x + y) * 128 // (w + h) + e + random.randrange(30)))
out = sys.argv[1]
cases = {
  'q90_420': dict(quality=90, subsampling=2),
  'q75_422': dict(quality=75, subsampling=1),
  'q95_444': dict(quality=95, subsampling=0),
  'q85_progressive': dict(quality=85, progressive=True),
  'q85_restart': dict(quality=85, restart_marker_blocks=5),
}
for name, kw in cases.items():
    im.save(out + '/' + name + '.jpg', 'JPEG', **kw)
    im.save(out + '/' + name + '.optimal', 'JPEG', **{k: v for k, v in kw.items() if k not in ('progressive', 'restart_marker_blocks')}, optimize=True)
im.convert('L').save(out + '/gray.jpg', 'JPEG', quality=88)
im.convert('L').save(out + '/gray.optimal', 'JPEG', quality=88, optimize=True)
im.convert('CMYK').save(out + '/cmyk.jpg', 'JPEG', quality=88)
im.convert('CMYK').save(out + '/cmyk.optimal', 'JPEG', quality=88, optimize=True)
""".trimIndent()

    private val sameScript = """
import sys
from PIL import Image
a = Image.open(sys.argv[1]); b = Image.open(sys.argv[2])
print(a.mode == b.mode and a.size == b.size and a.tobytes() == b.tobytes())
""".trimIndent()

    private fun python(vararg args: String): String {
        val process = ProcessBuilder(Tools.require("python3").path, *args).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString().trim()
        process.waitFor(300, TimeUnit.SECONDS)
        return out
    }

    @Test
    fun libjpeg_reads_the_same_samples_from_a_smaller_file() {
        assumeTrue("python3 is not installed", Tools.hasAll("python3"))
        val pillow = python("-c", "import PIL") == ""
        if (!pillow && System.getenv(Tools.REQUIRE_ENV) == "1") error("python3 has no Pillow, and ${Tools.REQUIRE_ENV}=1 forbids skipping")
        assumeTrue("python3 has no Pillow", pillow)

        val dir = Files.createTempDirectory("repack").toFile().apply { deleteOnExit() }
        assertEquals("", python("-c", makeScript, dir.path))
        val inputs = dir.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
        assertEquals(7, inputs.size)
        for (input in inputs) {
            val bytes = input.readBytes()
            val optimal = File(dir, input.name.removeSuffix(".jpg") + ".optimal").length()
            for (progressive in listOf(false, true)) {
                // The rewrite before the size rule, so that a libjpeg progressive file, whose tables
                // are optimal already, still tests the decode of its refinement scans.
                val rewritten = assertNotNull(JpegRepack.rewrite(bytes, progressive), "${input.name} could not be read")
                val file = File(dir, input.name + if (progressive) ".progressive" else ".baseline")
                file.writeBytes(rewritten)
                assertEquals("True", python("-c", sameScript, input.path, file.path), "${file.name}: libjpeg reads other samples")
                val out = ImageKodec.repackJpeg(bytes, progressive)
                assertTrue(out.size <= bytes.size, "${file.name}: ${out.size} is larger than ${bytes.size}")
                if (!input.name.contains("progressive")) {
                    assertTrue(out.size < bytes.size, "${file.name}: ${out.size} is not smaller than ${bytes.size}")
                    if (!progressive) assertTrue(out.size <= optimal * 1.01, "${file.name}: ${out.size} bytes, libjpeg's optimal tables give $optimal")
                }
            }
        }
    }
}
