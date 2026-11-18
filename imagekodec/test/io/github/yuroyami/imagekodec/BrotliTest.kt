package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.internal.brotli.Brotli
import io.github.yuroyami.imagekodec.internal.brotli.BrotliDictionary
import io.github.yuroyami.imagekodec.internal.brotli.BrotliException
import io.github.yuroyami.imagekodec.internal.flate.Crc32
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Brotli decoder that reads the `brob` boxes of a JPEG XL file (#186), against streams that
 * libbrotlienc 1.1 wrote with `BrotliEncoderCompress(quality, lgwin, mode, ...)` through
 * Python's ctypes: an XMP packet and English words at quality 11, which use the static
 * dictionary and its transforms, a 1 KiB window, binary data at qualities 1 and 9, and nothing.
 */
class BrotliTest {

    /** A stream, and the size and CRC-32 of the data it holds. The name gives the quality, the window bits, and mode 2 (font) or 0 (generic). */
    private class Vector(val name: String, val stream: String, val size: Int, val crc: Long)

    private val vectors = listOf(
        Vector("xmp_q11_w22_m2", "G0wDICwOMrkXO2mUzedE/mJDMdyoQHhq93dxTmUHuusJQBeHlE9Yc9gGm0/QVq3AE0T1fy5HdkB+/9/YozVxbSrtZRRTnFDD9CZdNWUFp0BJU2RNihj9JfKfftvn2jlaCsveVYl4VpcXq1zW59p0IgzAj3yRkQKmhjDWBDk/OOGNhrXvVk24HDxWm4l8YI81xR/4+pD/w5TDG7o+ofQXZARQkOqoUPO20giPsG1jZA1tl7OpjPLEWxLOMFOMcD3l8xT8aA6D1CA38p8PJPrWFIwHcjZgRSRDNPcyuOHZSNEiygEkCOvJLZGL+zLJlBUpAxMEhDuM0hH/p5NAUQUXBk9araFW4iliczK/MyZt+ieRoMd8ew0KiGIP/GiwnHWxAoSJgvg71CL/VesNzoGO+IwhybbgOQcDsGWaxjblZMunjGVP3ip5NJTBWng2lufX88daOa3nn/1la1hEtr1xfGmntzO+CJRQFq8TDMJgRHkR5pkdfCumk/gzPr/FQ4MPtqmLAu4enkEgy3huJ2Kh/zAheTwR8KUotekE", 845, 0x269CF1DBL),
        Vector("xmp_q5_w10_m0", "oWAaACD/O2e+Rv2X2WuTA0oJB5y36ZTGKY+0PZLy7rmfwH73I8NqVVRBewOewLBBCmhmM7cWkf16Ncc557yC0MrTzGst/awF0EpH/19/Nv9g/Tom49LNtrvoXmfL+4Ql90PymSLTkLQrvZ7KcR3TX5jqX3UU5RkVDc16jpEhgVanOV14gV5dp7mO2B8ae9EDiJfIE356kus0l5ubaPlVFk0PhkjTvuFAhpULdYTA7TTZ8rKrj3uUJtgXDfyoPGL9+Hoqky8axXBswO4rL/GjQozvAdDShLoRjQjsY3uiHXdYZEhZVzkKvzLYNoOI13HR1cBb8PhF3EL0OgVvGw0DCMaKkDIuhiqUmu/dKOrSomVWHb5hQ++hJqc/ltWAqLyNIRCyuJ5K6go7Mo+3mcGI/5MfH2XyycgHfSaUEA3rUaZspR5fiwdeinwI+oslg1E/jBhMV/4CMu6sbeYvHsCeeKLLztRmS2CRzcBeLOdHvr3qT0IFqGjOi423gGmBFfqy+pQJuHxCJxPhRw2Bo3IoupYvp3+7ESN9aqiTd4OGGk+CtFAaYaa+EHH7cIJIRvnwwgsMScOxf2BIvWcyE6mOLsg0AA==", 845, 0x269CF1DBL),
        Vector("words_q11_w16_m2", "4ikAlOjJUn3qknAKwbqNasAhB05fqu3glnKwMbaeEQX6JryByhonRIXnn96DOJmmUbimA67BAQ==", 336, 0x771FC14DL),
        Vector("binary_q9_w18_m0", "E6sRABypUV/M7gb0tDePH8ZKJSnIaDBODIzTCQylkSzQsZ2cGGc3GAlCSHFR/PfwIAYYC3bA/HBsA/BAJfaEKWTCNbD80V8NIPUNbjc+QYrg+GH7ByqNzJ73/hHi+PItV0arAfj3dsh0Vo9CdxOOp3EafZzzne2LxMtK5Oldp98BMgD+XNdwuhT2s2sSjdmtwrFDsRR2y9d9ur18EDgDZP7h3eYXWBmaPO78wmTR+ct+ESlJrN7zFYwcRP7OI5ardhy+n3K9zbPY00Lg6lwmX9dib/8m8wGUg8jXpo7Xp3H/vkUy5baLpy7VWtyr3AwYjsph8AKUB0b3W98QSXj6tPsHl8cWr9kSSpVcfxSqWC2E/l/GbHf9JPIw4/la5/HnpdDdvUq9nX4Kod/bBsGQ2cwf2mRzfqd01aPZSvuxcWJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmLiD9q4cePGjRs3bty4cePGjRs3bsh1JdTv6QKCwBAoDI5AEkkYLBVPIJLIFCqNzmCy2Bwujy+g0RlMFpvD5fEFQpFYIpXJFUqVWqPV6Q1Gk9litdkdG06X2+P1+QPBUDgSjcUTSQCEUulMNpcvFEvlSrVWbzRb7U631x8MR+PJdDZfLFdrGEExXKXWaHV6g9FktlhtdofT5fZ4ff5AMBSORGPxRDKVzmRz+UKxVK5Ua/VGs9XudHv9wXA0nkxn88Vylc3lCweHR8cnp2fnF5dX1ze3d/cPj0/PL69v7x+fX98/v3//xVK5Uq3VG81Wu9Pt9QfD0Xgync0Xy9V6Aw==", 4524, 0x3B9B5559L),
        Vector("binary_q1_w16_m0", "g9UIAICqqqrqf13XDb7axG/oFOtLVqswb7ry5DkZACFEhRX/a19w1UKErKjbTOD+dwMglbJP6dwDppB1Su85BKNWcM3qP4GkU/bIHTqHYVTzrl54xeI3iaxb/sAVMo9pXPsmELWKb9n8I5awTWrfASRT8y4YvYJn0fQrnrhFYtcJLJt+QOXSD6mce8bgNQqvWQQkk3ZI7doHoZRzzug9AqdRdMseOIWiV/HMG76AZVL3qVl8wxYwjapf+cQTtohtWv8hFLNOaN36J5G0S+7YBSJX9ykcu0Zg1fIvmbxD5tANKp95ROM2CK2af8HkM46oXQIil3FM6z4ApZJ3yew7hqBVcs8ZPINmUPXKH7mEY9bwrV16xxE0i25Y/cIXsYxr3vglErdJbNsGID39+a2Gb9H4DSKbdkDp1D0Kp15wxewTOo9hWF57cFxwetmDE9IOpx97cDh9D44LTy+uhyOkjGPa6Y/Q8odznu84gqc/ApQKzjmnR8MqnHOc2IODEwtrgIMTbrSmBRzLOVGsY00LOPGJ58QR4DTWNIDD6QtrgMPp1rWmBRxOTwhaJWvegYMT2bzxa807cCjLwhrg+Lss5V6nCRyXlaXc6zSBw+n3Ok3g+KcsxVB1qhM4UT5TliNat+r0msDh9HudJnA4/V6nCRzKEm3bKyLf6zSBw4pcO7OGw4q4HY4QKp4rctgIjsDp186s4bhqRUp07Ea2MRyB0w0HR2BFonMvsk3gCDixO6/dCXfVqWINcDh9d167E0aoU8Ua4ODEtTPUCWtC1Wten92Ja2eoE9YAh9N357U74Z461c+7Mzhh8zpVrAEOK2KkOlWsAQ4rEr1HkW1Rp4o1wOH05bszlMV9cARWxJZwBFZk+e50OKxI9J9EHh/k1YqMD3K3BjisiAesaX3mtTsR1glwznHCaNa0Jk540JoWcFgR21jTAg6njw/yAAcnPPSlcgzWfOa1O2Fba1oTJ4xhTQs4nO7hOtUJHMpiO2tawKEsxqxTncChLDFyuSLy/gdaFoxeRU1ph9So+YYtEDULx66jpoxjWtR8xxGMmkXjN1FT1ik9an7gCkXN4onbqCnnnBE1P/GEo2bJ5F3UlHfJjJpf+CJRs3TqPoIBfv5Y8/sBAgGDgIKBQ0BCQcPAwsEjICIho6CioWNgYmHj4OLhExASEZOQkpFTUFJR09DS0TMwMjGzsLKxc3BycfPw8vELCAoJi4iKiUtISknLyMrJKygqKauoqqlraGpp6+jq6RsYGhmbmJqZW1haWdvY2tk7ODo5u7i6uXt4enn7+Pr5BwQGBYeEhoVHREZFx8TGxSckJiWnpKalZ2RmZefk5uUXFBYVl5SWlVdUVlXX1NbVNzQ2Nbe0trV3dHZ19/T29Q8MDg2PjI6NT0xOTc/Mzs0vLC4tr6yurW9sbm3v7O7tHxweHZ+cnp1fXF5d39ze3T88Pj2/vL69f3x+ff/8/v0DADL4t5UB", 4524, 0x3B9B5559L),
        Vector("empty_q11_w22_m0", "Bg==", 0, 0x00000000L),
    )

    private fun crc(data: ByteArray): Long = Crc32().also { it.update(data) }.value()

    @Test
    fun the_dictionary_is_rfc_7932s() {
        assertEquals(BrotliDictionary.SIZE, BrotliDictionary.data.size)
        assertEquals(BrotliDictionary.CRC32, crc(BrotliDictionary.data))
    }

    @Test
    fun every_vector_decodes_to_its_data() {
        for (v in vectors) {
            val out = Brotli.decompress(Base64.decode(v.stream), 1 shl 20)
            assertEquals(v.size, out.size, "${v.name}: size")
            assertEquals(v.crc, crc(out), "${v.name}: CRC-32")
        }
    }

    @Test
    fun a_cut_stream_or_a_small_ceiling_throws() {
        val stream = Base64.decode(vectors[0].stream)
        for (cut in intArrayOf(0, 1, 7, stream.size / 2, stream.size - 1)) {
            assertFailsWith<BrotliException>("cut at $cut") { Brotli.decompress(stream.copyOf(cut), 1 shl 20) }
        }
        assertFailsWith<BrotliException> { Brotli.decompress(stream, vectors[0].size - 1) }
    }

    @Test
    fun a_damaged_stream_throws_brotli_exception_or_decodes() {
        for (v in vectors) {
            val stream = Base64.decode(v.stream)
            for (at in stream.indices) for (bit in 0 until 8 step 3) {
                val bad = stream.copyOf()
                bad[at] = (bad[at].toInt() xor (1 shl bit)).toByte()
                try {
                    Brotli.decompress(bad, 1 shl 16)
                } catch (_: BrotliException) {
                    // The one fault a damaged stream may give.
                }
            }
        }
    }
}

/** The XMP packet of [BrotliTest]'s first vector, Brotli-compressed, for the `brob` box of a JPEG XL file. */
internal const val BROTLI_XMP_STREAM = "G0wDICwOMrkXO2mUzedE/mJDMdyoQHhq93dxTmUHuusJQBeHlE9Yc9gGm0/QVq3AE0T1fy5HdkB+/9/YozVxbSrtZRRTnFDD9CZdNWUFp0BJU2RNihj9JfKfftvn2jlaCsveVYl4VpcXq1zW59p0IgzAj3yRkQKmhjDWBDk/OOGNhrXvVk24HDxWm4l8YI81xR/4+pD/w5TDG7o+ofQXZARQkOqoUPO20giPsG1jZA1tl7OpjPLEWxLOMFOMcD3l8xT8aA6D1CA38p8PJPrWFIwHcjZgRSRDNPcyuOHZSNEiygEkCOvJLZGL+zLJlBUpAxMEhDuM0hH/p5NAUQUXBk9araFW4iliczK/MyZt+ieRoMd8ew0KiGIP/GiwnHWxAoSJgvg71CL/VesNzoGO+IwhybbgOQcDsGWaxjblZMunjGVP3ip5NJTBWng2lufX88daOa3nn/1la1hEtr1xfGmntzO+CJRQFq8TDMJgRHkR5pkdfCumk/gzPr/FQ4MPtqmLAu4enkEgy3huJ2Kh/zAheTwR8KUotekE"

/** The CRC-32 of the packet [BROTLI_XMP_STREAM] holds. */
internal const val BROTLI_XMP_CRC = 0x269CF1DBL
