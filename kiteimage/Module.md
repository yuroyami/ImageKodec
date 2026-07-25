# Module kiteimage

Image decoding and encoding in pure common Kotlin, with no platform image APIs
underneath.

Decodes PNG, APNG, JPEG, GIF, BMP, WebP (lossless), TIFF and JPEG 2000; encodes
PNG, JPEG, GIF and BMP. Everything normalises to non-premultiplied ARGB_8888 in
a plain `IntArray`. `probe` reads a header without allocating a pixel buffer.
Depends on `kotlin-stdlib` alone.
