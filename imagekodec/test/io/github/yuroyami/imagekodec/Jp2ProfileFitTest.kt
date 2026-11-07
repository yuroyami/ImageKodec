package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** A profile of another colour space than the image is ignored in a JP2 as in a PNG (#118). */
class Jp2ProfileFitTest {

    /** An RGB matrix profile whose curves are linear: gray 128 would come out 188 through it. */
    private val rgbLinear = Base64.decode("AAABWAAAAAACEAAAbW50clJHQiBYWVogAAAAAAAAAAAAAAAAYWNzcAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPbWAAEAAAAA0y0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHd3RwdAAAANgAAAAUclhZWgAAAOwAAAAUZ1hZWgAAAQAAAAAUYlhZWgAAARQAAAAUclRSQwAAASgAAAAQZ1RSQwAAATgAAAAQYlRSQwAAAUgAAAAQWFlaIAAAAAAAAPbWAAEAAAAA0y1YWVogAAAAAAAAb6QAADj2AAADj1hZWiAAAAAAAABilgAAt4cAABjcWFlaIAAAAAAAACSiAAAPgwAAts9jdXJ2AAAAAAAAAAEBAAAAY3VydgAAAAAAAAABAQAAAGN1cnYAAAAAAAAAAQEAAAA=")

    /** A 16 by 16 gray JP2 of value 128 written by OpenJPEG, with [rgbLinear] in a colr box of method 2. */
    private val grayJp2 = Base64.decode(
        "AAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAAGBanAyaAAAABZpaGRyAAAAEAAAABAAAQcHAAAAAAFjY29scgIAAAAAAVgAAAAAAhAAAG1udHJSR0IgWFlaIAAAAAAAAAAAAAAAAGFjc3AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD21gABAAAAANMtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB3d0cHQAAADYAAAAFHJYWVoAAADsAAAAFGdYWVoAAAEAAAAAFGJYWVoAAAEUAAAAFHJUUkMAAAEoAAAAEGdUUkMAAAE4AAAAEGJUUkMAAAFIAAAAEFhZWiAAAAAAAAD21gABAAAAANMtWFlaIAAAAAAAAG+kAAA49gAAA49YWVogAAAAAAAAYpYAALeHAAAY3FhZWiAAAAAAAAAkogAAD4MAALbPY3VydgAAAAAAAAABAQAAAGN1cnYAAAAAAAAAAQEAAABjdXJ2AAAAAAAAAAEBAAAAAAAAkWpwMmP/T/9RACkAAAAAABAAAAAQAAAAAAAAAAAAAAAQAAAAEAAAAAAAAAAAAAEHAQH/UgAMAAAAAQAEBAQAAf9cABBAQEhIUEhIUEhIUEhIUP9kACUAAUNyZWF0ZWQgYnkgT3BlbkpQRUcgdmVyc2lvbiAyLjUuNP+QAAoAAAAAABMAAf+TgICAgID/2Q==",
    )

    @Test
    fun anRgbProfileOnAGrayJp2IsIgnoredAsInAPng() {
        val png = PngColorFiles.png(16, 16, 1, 8, IntArray(256) { 128 }, PngColorFiles.iccp(rgbLinear))
        assertEquals(0xFF808080.toInt(), ImageKodec.decode(png, colorTarget = ColorTarget.Srgb())[0, 0])
        assertEquals(0xFF808080.toInt(), ImageKodec.decode(grayJp2)[0, 0])
        assertEquals(0xFF808080.toInt(), ImageKodec.decode(grayJp2, colorTarget = ColorTarget.Srgb())[0, 0])
    }

    @Test
    fun probeSaysWhetherAnImageDecodesToGrayOrColour() {
        // The public count agrees with what the JPEG 2000 decoder gives (#119).
        assertEquals(1, ImageKodec.probe(grayJp2).colorChannels)
        assertEquals(1, ImageKodec.probe(Jp2ColorFixtures.iccGray).colorChannels)
        for ((name, bytes) in Jp2ColorFixtures.converted) {
            val expected = if (JpxDecoder.decodeForFacade(bytes, 1).colorSpace == "DeviceGray") 1 else 3
            assertEquals(expected, ImageKodec.probe(bytes).colorChannels, name)
        }
        val gray = PngColorFiles.png(2, 2, 1, 8, IntArray(4) { 7 })
        val rgb = PngColorFiles.png(2, 2, 3, 8, IntArray(12) { 7 })
        assertEquals(1, ImageKodec.probe(gray).colorChannels)
        assertEquals(3, ImageKodec.probe(rgb).colorChannels)
    }
}
