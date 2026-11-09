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

## Keeping a photo's metadata

A decode returns pixels only, so a plain re-encode drops the capture date, the place, the camera and the colour profile. `readMetadata` reads the EXIF block, the XMP packet and the ICC profile of a JPEG, PNG or WebP file, and the JPEG, PNG and WebP encoders take them back:

```kotlin
val metadata = ImageKodec.readMetadata(photo)
val upright = ImageKodec.decode(photo, applyOrientation = true)
// The pixels are upright now, so the copied orientation must say so.
val jpeg = ImageKodec.encodeJpeg(upright, 85, metadata.upright())
val png = ImageKodec.encodePng(upright, metadata.upright())
val webp = ImageKodec.encodeWebp(upright, metadata.upright())
```

- JPEG writes the EXIF block as an APP1 `Exif` segment, the XMP packet as an APP1 Adobe XMP segment and the ICC profile as APP2 `ICC_PROFILE` chunks. An EXIF block or an XMP packet longer than one segment, about 64 KB, is left out. A profile of any size up to 255 segments goes in.
- PNG writes `iCCP`, `eXIf` and an `iTXt` chunk with the keyword `XML:com.adobe.xmp`.
- WebP writes the extended format: `VP8X`, `ICCP`, the image, `EXIF` and `XMP `.
- `upright()` sets the EXIF orientation to 1. Keep the original orientation only when you write the pixels as they were stored.

### Ultra HDR gain maps

Pixel and Samsung phones save Ultra HDR JPEGs: after the main image comes a small second JPEG, the gain map, that tells an HDR screen how much brighter each area may be drawn. `readMetadata` returns it as `metadata.gainMap`, found through the Multi-Picture Format segment, and `encodeJpeg` with that metadata writes it again:

- The gain map goes unchanged after the new main image, with an MPF segment that points to it and an XMP `Container:Directory` that gives its length. Its `hdrgm` fields or ISO 21496-1 segment, which hold its parameters, stay with it.
- A gain map covers the whole image at any resolution, so a scaled main image keeps it. `upright()` turns it with the pixels.
- A main image of another aspect ratio, a crop for one, drops it together with the `hdrgm` fields of the XMP packet. An HDR screen then shows the photo as an ordinary one, never with a gain map that no longer fits.
- PNG and WebP have no place for a gain map, so their encoders leave it out.

## Removing the video of a Motion Photo

Pixel and Samsung cameras save Motion Photos: a JPEG with a short MP4 video after the image, often as large as the photo. `motionPhoto` says where the video sits, and `removeMotion` returns the still photo without it:

```kotlin
val info = ImageKodec.motionPhoto(photo)          // null for a photo with no video
val still = ImageKodec.removeMotion(photo)        // the photo, the video and its XMP item gone
```

The still keeps every byte of the photo up to the video. In its XMP, `GCamera:MotionPhoto` becomes 0, and the video's directory item and the older MicroVideo fields go. A gain map item and its bytes stay. A Samsung file is cut at the end of its JPEG, with its trailer. The still decodes to the same pixels.

## Making a JPEG smaller without touching its pixels

Most JPEGs, and `encodeJpeg`'s own output, use the example Huffman tables of the JPEG standard. `repackJpeg` writes the same coefficients again with tables built for the image, as `jpegtran -optimize -copy all` does:

```kotlin
val smaller = ImageKodec.repackJpeg(photo)                        // baseline, optimal tables
val progressive = ImageKodec.repackJpeg(photo, progressive = true) // often smaller still
```

- The output decodes to the same pixels as the input, byte for byte, and is never larger: a file the repack would not shrink comes back as it is.
- Every segment outside the entropy coding stays in its order: `APPn` and `COM`, the quantization tables and the frame header. Bytes after the image, such as an Ultra HDR gain map, stay after it, with the MPF offsets moved. The restart interval goes.
- The progressive form sends the DC coefficients first, then two bands of AC coefficients for each component, each band with its own table.
- Arithmetic-coded, lossless and hierarchical files come back unchanged.
- `ImageKodec.repackJpeg(ImageKodec.encodeJpeg(bitmap))` gives the encoder's output with optimal tables.

## What the encoders do not write

- There is no encoder for lossy WebP, JPEG 2000, AVIF or JPEG XL.
- The PNG encoder writes 8-bit RGB or RGBA only, with no interlace, no palette and no 16-bit
  output. It picks a filter for each row, which is a compression choice and not a format
  capability. Use `encodeTiff16` for 16-bit output.
- The GIF writer accepts a play count in 0..65536 and refuses a larger one. See
  [Animation](animation.md#play-counts).
