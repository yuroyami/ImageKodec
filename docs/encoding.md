# Writing images

ImageKodec writes PNG and APNG, JPEG, GIF, BMP, TIFF, WebP (lossless and lossy, still and
animated), JPEG 2000, AVIF, JPEG XR and, with the `imagekodec-heic` module, HEIC. Every encoder takes a
`KiteBitmap` (or a `KiteAnimation`, a list of pages or a `KiteBitmap16` where the format allows
it) and returns the file as a `ByteArray`.

```kotlin
ImageKodec.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
ImageKodec.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
ImageKodec.encodeJpeg(bitmap, 85, JpegEncoderKind.Jpegli) // smaller files at the same score, slower
ImageKodec.encodeGif(bitmap, dither = true)     // median cut + Floyd-Steinberg, or exact under 256 colors
ImageKodec.encodeGif(anim)                      // animated, delays and loop count preserved
ImageKodec.encodePng(anim)                      // APNG: lossless frames, alpha, delays and loop count
ImageKodec.encodeBmp(bitmap)                    // 24-bit BI_RGB, or 32-bit V4 BITFIELDS with alpha
ImageKodec.encodeTiff(bitmap)                   // gray or RGB, alpha when present, Deflate with the predictor
ImageKodec.encodeTiff(pages)                    // one page a bitmap, read back with decodePage
ImageKodec.encodeTiff16(bitmap16)               // the same at 16 bits, read back with decode16
ImageKodec.encodeWebp(bitmap)                   // lossless VP8L, smaller than the PNG
ImageKodec.encodeWebp(bitmap, 75)               // lossy VP8 at cwebp's quality 75, alpha kept exact
ImageKodec.encodeWebp(anim, WebpEncodeOptions(lossless = true)) // animated, lossless or lossy frames
ImageKodec.encodeAvif(bitmap, quality = 50)     // AV1 in HEIF, built in
ImageKodec.encodeHeic(bitmap, quality = 50)     // HEVC in HEIF, needs ImageKodecHeic.install()
ImageKodec.encodeJxr(bitmap, quality = 80)      // lossy JPEG XR, as JxrEncApp -q 0.8
ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true)) // lossless JPEG XR
ImageKodec.encodeJp2(bitmap, quality = 75)      // JPEG 2000, lossy 9/7, in a JP2 file
```

`encodeJpeg` uses a quality of 90 when you pass none.

## Smaller JPEGs with Jpegli

`encodeJpeg` follows stb_image_write by default: simple and fast, but its files are large for what they show. `JpegEncoderKind.Jpegli` uses a port of Google's [Jpegli](https://github.com/google/jpegli) instead (#133):

```kotlin
val jpeg = ImageKodec.encodeJpeg(bitmap, 75, JpegEncoderKind.Jpegli)
val withMetadata = ImageKodec.encodeJpeg(bitmap, 75, metadata, JpegEncoderKind.Jpegli)
```

- It writes what Jpegli's library writes for an RGB image by default (`jpegli_set_defaults`, then `jpegli_set_quality`): a baseline YCbCr JPEG that every reader opens, with 4:2:0 chroma at every quality.
- The quality maps to a Jpegli distance, which scales Jpegli's own quantization matrices. Adaptive quantization then sets each block's zero bias from how much the luma around it masks errors, so flat areas keep detail that busy areas drop.
- It uses the example Huffman tables of the JPEG standard and writes no JFIF header, as Jpegli's defaults do. The metadata goes right after SOI, as with the default encoder, and so does a gain map's MPF segment.
- A quality number means something else to each encoder, so compare files at the same score. On the six synthetic photo-like scenes of the tests, at Jpegli quality 75, its files are 12 to 34 percent smaller than the default encoder's at the lowest quality that scores as well in SSIMULACRA 2 ([Measuring quality](quality.md)). It takes three to four times as long.
- `repackJpeg` makes its output smaller still, by 6 to 17 percent on the same scenes, with Huffman tables built for the image. `repackJpeg(jpeg, progressive = true)` writes it progressive.
- The output is the same on every platform. On the JVM it matches pyjpegli 0.3.2, a build of Jpegli, byte for byte on most images and within 0.1 percent in size on the rest. Jpegli works in float32 with fused multiply-adds, this port in Double, so a coefficient now and then rounds the other way.
- Jpegli's XYB colour space is not written: it needs a reader that applies the ICC profile.

## Lossy and animated WebP

`encodeWebp(bitmap, quality)` writes a lossy WebP, and `WebpEncodeOptions` sets everything else (#178):

```kotlin
val webp = ImageKodec.encodeWebp(bitmap, 75)
val thorough = ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(quality = 75, effort = 3))
val withMetadata = ImageKodec.encodeWebp(bitmap, WebpEncodeOptions(quality = 80), metadata)
val animated = ImageKodec.encodeWebp(animation, WebpEncodeOptions(quality = 70))
```

- The encoder is written here from RFC 6386 (VP8) and RFC 9649 (the container). It codes one VP8 key frame in YUV 4:2:0, with the BT.601 studio-range matrix the format names. `dwebp` reads every file to the same pixels as ImageKodec's decoder.
- `quality` runs from 0 to 100 with the meaning of `cwebp -q`: at the same number, the quantizer is about the one libwebp picks, and the SSIMULACRA 2 score lands within two points of cwebp's on most of the test scenes, and within four on all.
- Each macroblock chooses its prediction, sixteen 4x4 modes or one 16x16 mode for luma and one of four for chroma, by distortion plus a lambda times the bits it costs. The macroblocks fall into four segments by how busy their luma is, and a busy segment takes a coarser quantizer. The token probabilities are fitted to the frame, and the loop filter follows each segment's quantizer.
- `effort` 1, 2 and 3 stand in for `cwebp -m 2`, `-m 4` and `-m 6`. Effort 1 codes only the three 4x4 modes that predict best. Effort 2, the default, codes every mode, after a first pass that measures the token statistics. Effort 3 also chooses each coefficient's level by distortion plus bits, in a trellis.
- `sharpYuv` refines Y, U and V against what the decoder's upsampling will show, as `cwebp -sharp_yuv` does. It keeps thin coloured edges and costs a little size on smooth pictures.
- Alpha goes in an `ALPH` chunk, coded with the lossless encoder, and is exact by default. `alphaQuality` below 100 reduces it to fewer levels, down to two at 0, and keeps fully opaque and fully clear pixels exact. `alphaFilter` picks the prediction filter of the alpha plane: none, a quick estimate of the best (the default), or every filter coded in full.
- A lossy image without alpha or metadata is the simple format, one `VP8 ` chunk. Alpha or metadata needs the extended format, with `VP8X`.
- `encodeWebp(animation, options)` writes `ANIM` with the loop count and `options.background`, and an `ANMF` chunk for each frame with its delay. Every frame after the first codes only the rectangle where it differs from what a decoder shows by then. It replaces that rectangle, or, when every changed pixel is opaque, is drawn over it with the unchanged pixels transparent, whichever file is smaller. Lossless frames decode to the exact frames. A lossy frame is compared with the decoded frames before it and leaves a pixel in place only within a few levels of the new frame: 2 at quality 100, 5 at 75, 14 at 0. `anim_dump` renders every frame as ImageKodec does.

### Size and speed

On the six photo-like scenes of the tests, at qualities 50, 75 and 90, `WebpLossyEncodeOracleTest` measures the size each encode takes for its SSIMULACRA 2 score ([Measuring quality](quality.md)) against what `cwebp -m 4` of libwebp 1.3.2 takes for the same score. cwebp's size is read off the front of its own files at qualities 0 to 100 in steps of 5, interpolated, so a cwebp file that scores below a smaller one never counts against it. The geometric mean over the 18 points:

| Effort | Size against `cwebp -m 4` | Encode time, JVM | Encode time, JS (Node 22) |
| --- | --- | --- | --- |
| 1 | 8.5 percent smaller | 26 ms | 87 ms |
| 2 (default) | 9.5 percent smaller | 64 ms | 215 ms |
| 3 | 11.6 percent smaller | 243 ms | 837 ms |

The times are the mean of one encode of a scene of about 0.16 megapixels on one core of a 4-core Intel Xeon, after a warm-up. cwebp takes 12 to 32 ms for the same scene, its process start included, about two to three times as fast as effort 2 on the JVM. The gain is not even. At quality 50 and 75 it is 1 to 7 percent on most scenes, and at 90 it is 1 to 20 percent. On the dark night scene at 50 and 75 it is 25 to 31 percent, where cwebp's files score low for their size, and on the city scene at 75 it is 25 percent, where cwebp's quality 75 scores below its quality 50. Two points are larger than cwebp's: the foliage scene at 50 by 3 percent and the city scene at 50 by 6 percent.

## JPEG XR

`encodeJxr` writes JPEG XR (ITU-T T.832 | ISO/IEC 29199-2), the format of HD Photo and of XPS (#181).
It was written from T.832, with its Annex D as the guide to the encoding side.

```kotlin
val jxr = ImageKodec.encodeJxr(bitmap, 80)
val exact = ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(lossless = true))
val deep = ImageKodec.encodeJxr16(bitmap16, JxrEncodeOptions(quality = 90), metadata)
val tiled = ImageKodec.encodeJxr(bitmap, JxrEncodeOptions(quality = 60, tileSize = 256, frequencyOrder = true))
```

- The pixel format follows the bitmap: 8bppGray when every pixel is gray and opaque, 24bppBGR when
  it is opaque, 32bppBGRA with alpha; `encodeJxr16` writes 16bppGray, 48bppRGB or 64bppRGBA, and gray
  with alpha as 64bppRGBA. Colour goes through the reversible colour transform of T.832 to YUV.
- `quality` (0 to 100, default 90) means what `JxrEncApp -q` means at the quality divided by 100: on the
  six photo scenes of the tests, both score the same in SSIMULACRA 2 at each quality from 10 to 90,
  within half a point. Like `JxrEncApp`, it codes 4:4:4 with one level of overlap filtering from 50
  up, and 4:2:0 with two levels below. `chroma`, `overlap` and `chromaQuality` set them yourself.
- At equal SSIMULACRA 2 the files are 5 percent smaller than `JxrEncApp`'s in total over those
  scenes and the qualities 30 to 90, and between 42 percent smaller and 7 percent larger scene by
  scene. The encodes take 2 to 31 ms there on the JVM, where `JxrEncApp` takes 5 to 37 with its
  process start.
- `lossless = true` codes with a quantizer of 1 and no overlap filtering: the file decodes to the
  input exactly, at 8 and 16 bits, gray, colour and alpha. `overlap = 1` or `2` stay lossless too.
- Alpha is coded losslessly as its own codestream after the image, as `JxrEncApp` does by default;
  `planarAlpha = false` interleaves it with the colour, and `alphaLossless = false` codes it at
  `alphaQuality`.
- `tileSize` cuts the image into tiles of that side, each a packet the index table points to.
  `tileQualities` gives each tile its own quantizers, and `frequencyOrder` writes each tile as its DC,
  lowpass, highpass and flexbits packets, all DC packets first, so a reader can stop early.
- The ICC profile, the XMP packet and the EXIF block of an `ImageMetadata` go into the file's
  directory. The EXIF orientation becomes the Transformation tag, the camera's make, model and
  dates become tags of the directory, and the EXIF and GPS directories are copied after it.
- `cancellationCheck` runs before each row of macroblocks and may throw to stop the encode.
- Every file decodes in jxrlib's `JxrDecApp` to the samples ImageKodec's decoder gives.

## Keeping a photo's metadata

A decode returns pixels only, so a plain re-encode drops the capture date, the place, the camera and the colour profile. `readMetadata` reads the EXIF block, the XMP packet and the ICC profile of a JPEG, PNG or WebP file, and the JPEG, PNG and WebP encoders take them back:

```kotlin
val metadata = ImageKodec.readMetadata(photo)
val upright = ImageKodec.decode(photo, applyOrientation = true)
// The pixels are upright now, so the copied orientation must say so.
val jpeg = ImageKodec.encodeJpeg(upright, 85, metadata.upright())
val png = ImageKodec.encodePng(upright, metadata.upright())
val webp = ImageKodec.encodeWebp(upright, metadata.upright())
val lossy = ImageKodec.encodeWebp(upright, WebpEncodeOptions(quality = 80), metadata.upright())
```

- JPEG writes the EXIF block as an APP1 `Exif` segment, the XMP packet as an APP1 Adobe XMP segment and the ICC profile as APP2 `ICC_PROFILE` chunks. An EXIF block or an XMP packet longer than one segment, about 64 KB, is left out. A profile of any size up to 255 segments goes in.
- PNG writes `iCCP`, `eXIf` and an `iTXt` chunk with the keyword `XML:com.adobe.xmp`.
- WebP writes the extended format: `VP8X`, `ICCP`, the image, `EXIF` and `XMP `.
- HEIC (`encodeHeic` with `ImageMetadata`) writes the ICC profile as a `colr` `prof` property, the
  EXIF block as an `Exif` item and the XMP packet as a `mime` item, each tied to the image by `cdsc`.
  The EXIF orientation is set to the orientation the options write as `irot` and `imir`.
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

## HEIC

`encodeHeic` writes a HEIF file of HEVC pictures (#174). The core writes the container and converts
RGB to Y, Cb and Cr; the intra HEVC encoder of the `imagekodec-heic` module codes the pictures, so
call `ImageKodecHeic.install()` first. Without it, `encodeHeic` throws `UnsupportedImageException`
that names the module.

```kotlin
ImageKodecHeic.install()
val heic = ImageKodec.encodeHeic(bitmap, quality = 60)
val exact = ImageKodec.encodeHeic(bitmap, HeifEncodeOptions(lossless = true))
val deep = ImageKodec.encodeHeic16(bitmap16, HeifEncodeOptions(quality = 70, chroma = HeifChroma.Yuv444))
val kept = ImageKodec.encodeHeic(upright, HeifEncodeOptions(thumbnailSize = 320), metadata.upright())
```

[Writing HEIC](heic.md#writing-heic) describes the file, the options and what is not written.

## AVIF

`encodeAvif` writes an AVIF file: HEIF with AV1 pictures, coded by the core's own intra AV1 encoder
(#176). AV1 is royalty-free, so the encoder needs no module. It takes the same `HeifEncodeOptions` as
`encodeHeic`, and the file has the same structure: colour in BT.601's matrix in full range signalled as
sRGB, alpha as an auxiliary image (`auxC` `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha`), a `grid` of
items for a picture larger than `tileSize`, a thumbnail, `irot` and `imir`, and the ICC profile, EXIF and
XMP of an `ImageMetadata`.

```kotlin
val avif = ImageKodec.encodeAvif(bitmap, quality = 60)
val exact = ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(lossless = true))
val deep = ImageKodec.encodeAvif16(bitmap16, HeifEncodeOptions(quality = 70, bitDepth = 12, chroma = HeifChroma.Yuv444))
val fast = ImageKodec.encodeAvif(bitmap, HeifEncodeOptions(quality = 50, effort = 1))
```

What the encoder writes:

- Each item is the sequence header and one frame OBU: a key frame under a reduced still picture
  header, as libavif writes a still image, with `av1C` holding the sequence header. The profile follows
  the picture: 0 for 4:2:0 and gray up to 10 bits, 1 for 4:4:4 up to 10 bits, 2 for 12 bits and 4:2:2.
- `quality` sets base_q_idx: 240 at 0, 117 at 50, 43 at 80 and 1 from 97 on, straight
  between, so that 50 and 80 score about what libavif's `avifenc -q 50` and `-q 80` score. `lossless` codes at base_q_idx 0, AV1's lossless mode, with
  the identity matrix in 4:4:4 (or gray), so `decode` returns the bitmap exactly.
- The encoder chooses, by rate and distortion: square and, at effort 3, horizontal and vertical
  partitions of 64 by 64 superblocks; the intra mode of each block (DC, the directional modes with their
  angle deltas, the three smooth modes, Paeth and filter intra; for chroma, chroma from luma with its
  alphas); the transform size and type (DCT, ADST, identity and their combinations, as the set of the
  size allows) and the levels, which a greedy search lowers where the rate saved pays for the error. A
  picture that looks like screen content (few colours in sharp strokes) turns on the screen content
  tools, and a block whose luma holds few values may code it as a palette.
- After the blocks, the loop filter levels, the CDEF strengths of each 64 by 64 block, and, from effort
  2, the self-guided restoration filter of each unit are chosen on the reconstruction.
- `effort` 1 tries one luma and one chroma mode at their own transform size, square partitions down to
  8 by 8, and the DCT or the mode's own transform type; 2 adds angle deltas, filter intra, one transform
  split, more transform types, the level search and loop restoration; 3 adds 4 by 4 blocks,
  rectangular partitions, two transform splits, every transform type and more filter strengths.
- With `tileSize = null`, a picture wider than 4096 codes as one frame of uniform AV1 tiles, the fewest
  the specification allows. Grid items, the default, are what both libavif and libheif read best, and
  the tile runner encodes them in parallel.

The encoder runs the core's own AV1 decoder in a coding mode: the decoder walks the syntax, predicts
and reconstructs, and the encoder supplies each decision. So the encoder's reconstruction is what any
decoder rebuilds: `Av1EncoderOracleTest` holds dav1d and ImageKodec's decoder to it, sample for sample.
The output is the same on every platform; `AvifEncodeTest` keeps hashes of fixed inputs.

## JPEG 2000

`encodeJp2` writes JPEG 2000 Part 1 (ITU-T T.800), in a JP2 file or as a bare codestream, the form a
PDF embeds (#180). `encodeJp2(bitmap, quality)` is lossy; `Jp2EncodeOptions` takes every other choice,
and `encodeJp2Wide` takes a `KiteBitmap16`:

```kotlin
ImageKodec.encodeJp2(bitmap, 75)                                          // about 41 dB PSNR
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true))           // 5/3 and RCT, exact
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(40.0, 20.0, 10.0))) // three layers
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(codestreamOnly = true))     // J2K, for a PDF
ImageKodec.encodeJp2Wide(bitmap16, Jp2EncodeOptions(lossless = true, bitsPerSample = 12))
```

- Lossy files use the 9/7 wavelet and the ICT, with expounded step sizes a band. The rate control
  truncates code-block passes after a rate and distortion sort (T.800 Annex J.14). `quality` aims at
  a PSNR of `20 + 0.28 * quality` dB over all components, as `opj_compress -q` aims at the PSNR it is
  given: 34 dB at 50, 41 dB at 75, 48 dB at 100. `ratios` gives each layer's compression ratio over
  the raw samples, as `opj_compress -r` does, and the file never exceeds it.
- Lossless files use the 5/3 wavelet and the RCT, at 1 to 16 bits, gray, gray and alpha, RGB and RGBA.
  The alpha channel is described by a channel definition box (`cdef`).
- The options set tiles, wavelet levels (5 by default, fewer for a small tile), code-block sizes, precincts,
  quality layers, the five progression orders, SOP and EPH markers, and the code-block styles: the
  arithmetic coding bypass, context reset, termination on each pass, vertically causal contexts and
  segmentation symbols. `effort` 1 sizes layers from an estimate, 3 also tries each lossless tile without
  the colour transform.
- A JP2 file carries the metadata: an ICC profile as its colour specification when the profile is gray
  or RGB as the image is, the XMP packet in the UUID box that XMP assigns to JPEG 2000, and the EXIF
  block in the UUID box exiftool reads. `readMetadata` reads all three back.
- On the photo scenes the files match `opj_compress` (OpenJPEG 2.5.0): within 0.05 dB PSNR at ratios
  10, 20 and 40, at most its size, and about 30 bytes smaller lossless. The encode takes about twice the
  time of `opj_compress`.

## What the encoders do not write

- There is no encoder for JPEG XL.
- The AV1 encoder writes still intra pictures only: no image sequence, no inter frames, no film
  grain, no 128 by 128 superblocks, no intra block copy, no delta quantizer, no Wiener restoration
  filter (the self-guided one only), no chroma palette, and no 4:1 or three-way partitions.
- The HEIC encoder writes still images only, with no image sequence, depth map, gain map or
  premultiplied alpha. See [Writing HEIC](heic.md#writing-heic).
- The JPEG XR encoder writes neither hard tiles (the decoder reads them), 4:2:2 chroma, CMYK,
  N-channel, fixed-point, half, float, RGBE, premultiplied or packed 5, 5-6-5 and 10-bit formats,
  nor a gain map. Its tiles are square, and its quantizers are one per band set for the image or
  for each tile, not per macroblock.
- The lossy WebP encoder writes one token partition, no loop filter deltas and no simple loop
  filter, and chooses the filter level from the quantizer without trying others. An animation
  never disposes a frame to the background, and its frames are all lossless or all lossy.
- The JPEG 2000 encoder writes no palette, no component subsampling, no image or tile offset, no signed
  components, no region of interest, no POC, PPM or PPT segments, no tile-parts beyond one a tile, no
  predictable termination, no HTJ2K and no Part 2 extension. A bare codestream carries no metadata and
  no channel definitions.
- The PNG encoder writes 8-bit RGB or RGBA only, with no interlace, no palette and no 16-bit
  output. It picks a filter for each row, which is a compression choice and not a format
  capability. Use `encodeTiff16` for 16-bit output.
- The GIF writer accepts a play count in 0..65536 and refuses a larger one. See
  [Animation](animation.md#play-counts).
