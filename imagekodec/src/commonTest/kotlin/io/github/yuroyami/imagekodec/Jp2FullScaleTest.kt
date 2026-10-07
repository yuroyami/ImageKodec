package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Full-scale 16-bit images through the irreversible 9/7 path: OpenJPEG 2.5's `opj_compress -I`
 * wrote each from a 64 by 48 pattern of the extreme sample values, and `opj_decompress` returns
 * every top byte of the source. A full-scale coefficient times the fixed-point fraction used to
 * pass 2^31 in the lifting, and 1536 of these 3072 samples came out wrong (#101).
 */
class Jp2FullScaleTest {

    /** A gray checkerboard of 0 and 65535. */
    private val checkerGray: ByteArray = Base64.decode(
        "/0//UQApAAAAAABAAAAAMAAAAAAAAAAAAAAAQAAAADAAAAAAAAAAAAABDwEB/1IADAAAAAEABQQEAAD/XAAjQrcgtvC28LbA" +
        "rwCvAK7gp1CnUKdokAWQBZBHl9OX05di/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4w/5AACgAAAAAA" +
        "fwAB/5PAAA+cEAiQgICAgJP/DDCAEU+Ir+1CGrNZTdAAAAAAAAMJCX/hMCAAAAAAYSEv/CcoAAAAAAwkJf9CcoAAAAAAwkJf" +
        "/M0cAAAAAABhIS/8JygAAAAADCQl/3E30AAAAAAABhIS/2EwIAAAAABhIS//f//Z",
    )

    /** The same in 32 by 32 tiles (`-t 32,32`). */
    private val checkerGrayTiled: ByteArray = Base64.decode(
        "/0//UQApAAAAAABAAAAAMAAAAAAAAAAAAAAAIAAAACAAAAAAAAAAAAABDwEB/1IADAAAAAEABQQEAAD/XAAjQrcgtvC28LbA" +
        "rwCvAK7gp1CnUKdokAWQBZBHl9OX05di/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4w/5AACgAAAAAA" +
        "awAB/5PAAA+cCAeAgICAk/8MJwART4ivPr/f9H+AAAAAAYSEv2jwAAADCQl+2mQAAAYSEv20yAAADCQl/wbgAAAAADCQl+1i" +
        "IAAAYSEv/ClwAAAAAAwkJftHgAAAGEhL/3//kAAKAAEAAABrAAH/k8AAD5wIB4CAgICT/wwnABFPiK8+v9/0f4AAAAABhIS/" +
        "aPAAAAMJCX7aZAAABhIS/bTIAAAMJCX/BuAAAAAAMJCX7WIgAABhIS/8KXAAAAAADCQl+0eAAAAYSEv/f/+QAAoAAgAAAF4A" +
        "Af+TwAAPnAgHgICAgJP/DCCAEU+Irz0m5E3AAAAAGEhLuvWABhIS8EmwAAwkJeALQAAYSEv29TAAADCQl3sMAAwkJf4q9gAA" +
        "AAwkJd16wAMJCX//kAAKAAMAAABeAAH/k8AAD5wIB4CAgICT/wwggBFPiK89JuRNwAAAABhIS7r1gAYSEvBJsAAMJCXgC0AA" +
        "GEhL9vUwAAAwkJd7DAAMJCX+KvYAAAAMJCXdesADCQl//9k=",
    )

    /** Magenta and green, 65535 and 0 per channel, through the ICT. */
    private val checkerRgb: ByteArray = Base64.decode(
        "/0//UQAvAAAAAABAAAAAMAAAAAAAAAAAAAAAQAAAADAAAAAAAAAAAAADDwEBDwEBDwEB/1IADAAAAAEBBQQEAAD/XAAjQrcg" +
        "tvC28LbArwCvAK7gp1CnUKdokAWQBZBHl9OX05di/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4w/5AA" +
        "CgAAAAABAAAB/5PAAA+cEAiQgICAgICAgICAgICAgICQf+DKoBP/SFf2oQ1e3KlUAAAAAAABhIS/+ZkQAAAAAADCQl/4TlAA" +
        "AAAAGEhL/wTlAAAAAAGEhL/wnKAAAAAAMJCX/hOUAAAAAAYSEv9hOUAAAAAAYSEv/3+R/0SOABFPiK/tQhq9tbRQAAAAAAGE" +
        "hL/+DwoAAAAAAAADCQl/8zIgAAAAAAGEhL/5mjgAAAAAAMJCX/9/kf9EkEART4iv7UIP8zQJAAAAAABhIS//KT6AAAAAAAAw" +
        "kJf+EwIAAAAABhIS/3qT6AAAAAAAAwkJf+EwIAAAAABhIS//f//Z",
    )

    /** A signed checkerboard of -32768 and 32767 (`-F 64,48,1,16,s`). */
    private val checkerSigned: ByteArray = Base64.decode(
        "/0//UQApAAAAAABAAAAAMAAAAAAAAAAAAAAAQAAAADAAAAAAAAAAAAABjwEB/1IADAAAAAEABQQEAAD/XAAjQrcgtvC28LbA" +
        "rwCvAK7gp1CnUKdokAWQBZBHl9OX05di/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2ZXJzaW9uIDIuNS4w/5AACgAAAAAA" +
        "fwAB/5PAAA+cEAiQgICAgJP/DDCAEU+Ir+1CGrNZTdAAAAAAAAMJCX/hMCAAAAAAYSEv/CcoAAAAAAwkJf9CcoAAAAAAwkJf" +
        "/M0cAAAAAABhIS/8JygAAAAADCQl/3E30AAAAAAABhIS/2EwIAAAAABhIS//f//Z",
    )

    /** The gray checkerboard with an opacity channel of 0 and 65535 in pairs of columns, declared by a cdef box added after encoding. */
    private val checkerOpacity: ByteArray = Base64.decode(
        "AAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAABDanAyaAAAABZpaGRyAAAAMAAAAEAAAg8HAAAAAAAPY29scgEA" +
        "AAAAABEAAAAWY2RlZgACAAAAAAABAAEAAQAAAAAEoWpwMmP/T/9RACwAAAAAAEAAAAAwAAAAAAAAAAAAAABAAAAAMAAAAAAA" +
        "AAAAAAIPAQEPAQH/UgAMAAAAAQAFBAQAAP9cACNCtyC28LbwtsCvAK8AruCnUKdQp2iQBZAFkEeX05fTl2L/ZAAlAAFDcmVh" +
        "dGVkIGJ5IE9wZW5KUEVHIHZlcnNpb24gMi41LjD/kAAKAAAAAAQNAAH/k8AAD5wQCJDAAA+cEAiQgICAgICAgICT/wwwgBFP" +
        "iK/tQhqzWU3QAAAAAAADCQl/4TAgAAAAAGEhL/wnKAAAAAAMJCX/QnKAAAAAAMJCX/zNHAAAAAAAYSEv/CcoAAAAAAwkJf9x" +
        "N9AAAAAAAAYSEv9hMCAAAAAAYSEv/3+n/jL1R/4myAARazTizstxznk2EhAAYSEuwtB/pW+Q8oOyHl7oQ8rtSHNFQBEQAAAA" +
        "GEhLsLQfzkiIc+WCHPlghz5YIdI3AAAMJCXQqGMNgCaNhoLQCa+JoLQCa+JoLQCa+JoLQCa+JoIIy0JdmiIw2AJo2GgtAJr4" +
        "mgtAJr4mgtAJr4mgtAJr4gBgATRsAAEgA/I4wAASAD8jjAABIAPyOMAAEgA/I4wAASAD8jjAAmDOQl2aIAAt4I8I3AABIAPy" +
        "OMAAEgA/I4wAASAD8jjAABIAPyOMACvibIS7r1gGgBnncAGABRXAAMACiuAAYAFFcAAwAO96QqpiyQ58sEOfLBDnywQ58sBe" +
        "ESHMhLpnGAUhXALwMAFIVwC8DABSFcAvAwAUhXALwMAFIVwC8DAAFvBHhG4AAJAB+RxgAAkAH5HGAACQAfkcYAAJAB+RxgAA" +
        "kAH5HGAABhIS8RXo+ZnJDnywQ58sEOfLBDnywAA3GWhLs0RGGwBNGw0FoBNfE0FoBNfE0FoBNfE0FoBNfEAAMJCXa6o/nJEQ" +
        "58sEOfLBDnywQ58sBeBRRALwMRgOQAfiUQaAYgA/Eog0AxAB+JRBoBiAD8SiDQDEAH4lEK0RgAubwAmCuAXgYAKQrgF4GACk" +
        "K4BeBgApCuAXgYAKQrgF4GABEUIYh6/bgPvkyCbWysO7wZ7LoHtomAADCJbyKsVcHvYYAaAGV9QAYAE0bAAwAJo2ABgATRsA" +
        "DAAmjYkAYgnvdYTCQl2uqP5dPEOZ6hDmeoQ5nqEPotKBMpKBDmW0Q5ltEOZbRDmW0AKYAVf5j5mckOfLBDnywQ58sEOkbgAA" +
        "GEhLq5KAaAGedwAYAFFcAAwAKK4ABgAUVwAC/4UVwADAAqzpHzM5Ic+WCHPlghz5YIc+WCHKVALwuAD9zNBaATXxNBaATXxN" +
        "BaATXxNBaATXxkARqgAAAAAAMJCXMz4AC3gjwjcAAEgA/I4wAASAD8jjAABIAPyOMAAEgA/I4wAASAD8jjAABIAPyOMAAEgA" +
        "/I4wAASAD8jjAABIAPyOMAAEgA/I4wAMACiuEfMzkhz5YIc+WCHPlghz5YC8CjCQl2aJP5yREOfLBDnywQ58sEOfLAXhcAH7" +
        "maC0AmviaC0AmviaC0AmviaC0AmviaC0AmviaC0Ao3qAGABRXAAMACiuAAYAFFcAAv+FFcAAwAKK4P/Z",
    )

    private fun check(name: String, bytes: ByteArray, expected: (x: Int, y: Int, c: Int) -> Int) {
        val r = JpxDecoder.decodeForFacade(bytes, 1)
        assertEquals(64, r.width, name)
        assertEquals(48, r.height, name)
        val n = r.pixelBytes.size / (64 * 48)
        for (y in 0 until 48) for (x in 0 until 64) for (c in 0 until n) {
            assertEquals(expected(x, y, c), r.pixelBytes[(y * 64 + x) * n + c].toInt() and 0xFF, "$name at ($x, $y), channel $c")
        }
    }

    private fun checker(x: Int, y: Int) = if ((x + y) % 2 == 1) 255 else 0

    @Test
    fun aFullScaleGrayCheckerboardSurvives() {
        check("gray", checkerGray) { x, y, _ -> checker(x, y) }
        check("gray, tiled", checkerGrayTiled) { x, y, _ -> checker(x, y) }
    }

    @Test
    fun aFullScaleColorCheckerboardSurvivesTheIct() =
        check("RGB", checkerRgb) { x, y, c -> if ((x + y) % 2 == 1) (if (c == 1) 0 else 255) else (if (c == 1) 255 else 0) }

    @Test
    fun aFullScaleSignedCheckerboardSurvives() = check("signed", checkerSigned) { x, y, _ -> checker(x, y) }

    @Test
    fun aFullScaleOpacityChannelSurvives() {
        check("opacity", checkerOpacity) { x, y, _ -> checker(x, y) }
        val alpha = assertNotNull(JpxDecoder.decodeForFacade(checkerOpacity, 1).alpha)
        for (y in 0 until 48) for (x in 0 until 64) {
            assertEquals(if ((x / 2 + y) % 2 == 1) 0 else 255, alpha[y * 64 + x].toInt() and 0xFF, "opacity at ($x, $y)")
        }
    }
}
