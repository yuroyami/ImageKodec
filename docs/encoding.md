# Writing images

ImageKodec writes PNG and APNG, JPEG, GIF, BMP, TIFF and lossless WebP. Every encoder takes a
`KiteBitmap` (or a `KiteAnimation`, a list of pages or a `KiteBitmap16` where the format allows
it) and returns the file as a `ByteArray`.

```kotlin
ImageKodec.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
ImageKodec.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
ImageKodec.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colors
ImageKodec.encodeGif(anim)                      // animated, delays and loop count preserved
ImageKodec.encodePng(anim)                      // APNG: lossless frames, alpha, delays and loop count
ImageKodec.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
ImageKodec.encodeTiff(bitmap)                   // gray or RGB, alpha when present, Deflate with the predictor
ImageKodec.encodeTiff(pages)                    // one page a bitmap, read back with decodePage
ImageKodec.encodeTiff16(bitmap16)               // the same at 16 bits, read back with decode16
ImageKodec.encodeWebp(bitmap)                   // lossless VP8L, smaller than the PNG
```

`encodeJpeg` uses a quality of 90 when you pass none.

## What the encoders do not write

- There is no encoder for lossy WebP, JPEG 2000, AVIF or JPEG XL.
- The PNG encoder writes 8-bit RGB or RGBA only, with no interlace, no palette and no 16-bit
  output. It picks a filter for each row, which is a compression choice and not a format
  capability. Use `encodeTiff16` for 16-bit output.
- The GIF writer accepts a play count in 0..65536 and refuses a larger one. See
  [Animation](animation.md#play-counts).
