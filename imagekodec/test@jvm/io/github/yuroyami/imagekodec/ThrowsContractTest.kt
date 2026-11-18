package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.CcittFax
import io.github.yuroyami.imagekodec.codec.CcittOptions
import io.github.yuroyami.imagekodec.codec.JpxDecoder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kotlin/Native turns an exception into an NSError only for a function marked `@Throws`. A caller in
 * Swift or Objective-C cannot catch anything else, and the runtime ends the process instead. On the
 * JVM the annotation becomes a `throws` clause, so it can be checked here by reflection: the entry
 * points that the kdoc says throw must declare the exception the kdoc names.
 */
class ThrowsContractTest {

    private val bytes = ByteArray::class.java
    private val int = Int::class.javaPrimitiveType!!
    private val boolean = Boolean::class.javaPrimitiveType!!
    private val target = ColorTarget::class.java
    private val check = Function0::class.java

    private fun declares(owner: Class<*>, name: String, expected: Class<out Throwable>, vararg params: Class<*>) {
        val declared = owner.getMethod(name, *params).exceptionTypes.toList()
        assertTrue(expected in declared, "${owner.simpleName}.$name should declare ${expected.simpleName}, declares $declared")
    }

    @Test
    fun theDecodeEntryPointsDeclareImageDecodeException() {
        val ex = ImageDecodeException::class.java
        declares(ImageKodec::class.java, "probe", ex, bytes)
        declares(ImageKodec::class.java, "decode", ex, bytes, boolean, target, check)
        declares(ImageKodec::class.java, "probePage", ex, bytes, int)
        declares(ImageKodec::class.java, "decodePage", ex, bytes, int, boolean, target, check)
        declares(ImageKodec::class.java, "decode16", ex, bytes, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeReduced", ex, bytes, int, target, check)
        declares(ImageKodec::class.java, "decodeScaled", ex, bytes, int, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeDownscaledTo", ex, bytes, int, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeJpegComponents", ex, bytes, int, check)
        declares(ImageKodec::class.java, "decodeAnimation", ex, bytes, boolean, int, target, Function0::class.java)
        declares(CcittFax::class.java, "decode", ex, bytes, int, CcittOptions::class.java)
    }

    @Test
    fun theEntryPointsThatRefuseAnArgumentDeclareIllegalArgumentException() {
        val ex = IllegalArgumentException::class.java
        declares(ImageKodec::class.java, "probePage", ex, bytes, int)
        declares(ImageKodec::class.java, "decodePage", ex, bytes, int, boolean, target, check)
        declares(ImageKodec::class.java, "decode16", ex, bytes, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeReduced", ex, bytes, int, target, check)
        declares(ImageKodec::class.java, "decodeScaled", ex, bytes, int, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeDownscaledTo", ex, bytes, int, int, boolean, target, check)
        declares(ImageKodec::class.java, "decodeJpegComponents", ex, bytes, int, check)
        declares(ImageKodec::class.java, "decodeAnimation", ex, bytes, boolean, int, target, Function0::class.java)
        declares(KiteBitmap16::class.java, "get", ex, int, int, int)
        declares(ImageKodec::class.java, "encodeGif", ex, KiteBitmap::class.java, boolean)
        declares(ImageKodec::class.java, "encodeGif", ex, KiteAnimation::class.java, boolean)
        declares(ImageKodec::class.java, "encodePng", ex, KiteAnimation::class.java)
        declares(ImageKodec::class.java, "encodeTiff", ex, List::class.java)
        declares(ImageKodec::class.java, "encodeTiff16", ex, List::class.java)
        declares(ImageKodec::class.java, "encodeWebp", ex, KiteBitmap::class.java)
        declares(ImageKodec::class.java, "encodeWebp", ex, KiteBitmap::class.java, int)
        declares(ImageKodec::class.java, "encodeWebp", ex, KiteBitmap::class.java, WebpEncodeOptions::class.java, ImageMetadata::class.java, check)
        declares(ImageKodec::class.java, "encodeWebp", ex, KiteAnimation::class.java, WebpEncodeOptions::class.java, ImageMetadata::class.java, check)
        val webpOptions = WebpEncodeOptions::class.java.getConstructor(boolean, int, int, boolean, int, WebpAlphaFilter::class.java, int)
        assertTrue(ex in webpOptions.exceptionTypes, "WebpEncodeOptions constructor should declare IllegalArgumentException")
        declares(ImageKodec::class.java, "encodeJpeg", ex, KiteBitmap::class.java, int)
        declares(ImageKodec::class.java, "encodeCcitt", ex, KiteBitmap::class.java)
        declares(ImageKodec::class.java, "encodeCcitt", ex, KiteBitmap::class.java, CcittEncodeOptions::class.java, check)
        declares(CcittFax::class.java, "encode", ex, bytes, int, CcittOptions::class.java)
        val ccittOptions = CcittEncodeOptions::class.java.getConstructor(int, boolean, boolean, boolean, boolean)
        assertTrue(ex in ccittOptions.exceptionTypes, "CcittEncodeOptions constructor should declare IllegalArgumentException")
        val jbig2Options = Jbig2EncodeOptions::class.java.getConstructor(int, boolean, boolean, boolean)
        assertTrue(ex in jbig2Options.exceptionTypes, "Jbig2EncodeOptions constructor should declare IllegalArgumentException")
        declares(JpxDecoder::class.java, "decode", ex, bytes, int, check)
        declares(KiteBitmap::class.java, "get", ex, int, int)
        declares(JpegComponents::class.java, "get", ex, int, int, int)
        val scaling = Class.forName("io.github.yuroyami.imagekodec.ScalingKt")
        val transforms = Class.forName("io.github.yuroyami.imagekodec.TransformsKt")
        for (receiver in listOf(KiteBitmap::class.java, KiteAnimation::class.java)) {
            declares(scaling, "scaled", ex, receiver, int, int)
            declares(scaling, "downscaledTo", ex, receiver, int, int)
            declares(transforms, "cropped", ex, receiver, int, int, int, int)
        }
        // encodeHeic refuses an argument and throws UnsupportedImageException without the HEVC module (#174).
        val options = HeifEncodeOptions::class.java
        val metadata = ImageMetadata::class.java
        for (unsupported in listOf(ex, UnsupportedImageException::class.java)) {
            declares(ImageKodec::class.java, "encodeHeic", unsupported, KiteBitmap::class.java, int)
            declares(ImageKodec::class.java, "encodeHeic", unsupported, KiteBitmap::class.java, options, metadata, check)
            declares(ImageKodec::class.java, "encodeHeic16", unsupported, KiteBitmap16::class.java, options, metadata, check)
        }
        val optionsConstructor = options.constructors.maxBy { it.parameterCount }
        assertTrue(ex in optionsConstructor.exceptionTypes, "HeifEncodeOptions constructor should declare IllegalArgumentException")
        val wideConstructor = KiteBitmap16::class.java.getConstructor(int, int, int, ShortArray::class.java)
        assertTrue(ex in wideConstructor.exceptionTypes, "KiteBitmap16 constructor should declare IllegalArgumentException")
        val bitmapConstructor = KiteBitmap::class.java.getConstructor(int, int, IntArray::class.java)
        assertTrue(ex in bitmapConstructor.exceptionTypes, "KiteBitmap constructor should declare IllegalArgumentException")
        val componentsConstructor = JpegComponents::class.java.getConstructor(int, int, int, bytes, int)
        assertTrue(ex in componentsConstructor.exceptionTypes, "JpegComponents constructor should declare IllegalArgumentException")
        val animationConstructor = KiteAnimation::class.java.getConstructor(int, int, List::class.java, java.lang.Long.TYPE)
        assertTrue(ex in animationConstructor.exceptionTypes, "KiteAnimation constructor should declare IllegalArgumentException")
    }
}
