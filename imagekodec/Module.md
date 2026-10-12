# Module imagekodec

Image decoding and encoding in common Kotlin, with no platform image APIs underneath.

It decodes PNG and APNG, JPEG, GIF, BMP, TIFF, JPEG 2000, WebP (lossy, lossless and animated), AVIF
and JPEG XL. It encodes PNG and APNG, JPEG, GIF, BMP, TIFF and lossless WebP. Every format comes
back as non-premultiplied ARGB_8888 in a plain `IntArray`, and `probe` reads a header without
allocating a pixel buffer. The module depends on `kotlin-stdlib` alone.
