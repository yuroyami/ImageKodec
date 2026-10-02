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

    private fun declares(owner: Class<*>, name: String, expected: Class<out Throwable>, vararg params: Class<*>) {
        val declared = owner.getMethod(name, *params).exceptionTypes.toList()
        assertTrue(expected in declared, "${owner.simpleName}.$name should declare ${expected.simpleName}, declares $declared")
    }

    @Test
    fun theDecodeEntryPointsDeclareImageDecodeException() {
        val ex = ImageDecodeException::class.java
        declares(ImageKodec::class.java, "probe", ex, bytes)
        declares(ImageKodec::class.java, "decode", ex, bytes, boolean)
        declares(ImageKodec::class.java, "decodeReduced", ex, bytes, int)
        declares(ImageKodec::class.java, "decodeAnimation", ex, bytes, boolean, Function0::class.java)
        declares(CcittFax::class.java, "decode", ex, bytes, int, CcittOptions::class.java)
    }

    @Test
    fun theEntryPointsThatRefuseAnArgumentDeclareIllegalArgumentException() {
        val ex = IllegalArgumentException::class.java
        declares(ImageKodec::class.java, "decodeReduced", ex, bytes, int)
        declares(ImageKodec::class.java, "encodeGif", ex, KiteBitmap::class.java, boolean)
        declares(ImageKodec::class.java, "encodeGif", ex, KiteAnimation::class.java, boolean)
        declares(ImageKodec::class.java, "encodeJpeg", ex, KiteBitmap::class.java, int)
        declares(JpxDecoder::class.java, "decode", ex, bytes, int)
    }
}
