# Writing images

ImageKodec writes PNG and APNG, JPEG, GIF, BMP, TIFF, WebP (lossless and lossy, still and
animated), JPEG 2000, JPEG XL, AVIF, JPEG XR, HEIC with the `imagekodec-heic` module, and the CCITT
fax and JBIG2 data that PDF and TIFF embed. Every encoder takes a `KiteBitmap` (or a
`KiteAnimation`, a list of pages or a `KiteBitmap16` where the format allows it) and returns the
file as a `ByteArray`.

```kotlin
ImageKodec.encodePng(bitmap)                    // 8-bit RGB, or RGBA when alpha is present
ImageKodec.encodePng(bitmap, PngEncodeOptions()) // the smallest exact type: gray, palette, RGB or RGBA
ImageKodec.encodePng16(bitmap16)                // up to 16 bits a sample, read back with decode16
ImageKodec.encodeJpeg(bitmap, quality = 85)     // baseline; 4:2:0 at quality <= 90, 4:4:4 above
ImageKodec.encodeJpeg(bitmap, 85, JpegEncoderKind.Jpegli) // smaller files at the same score, slower
ImageKodec.encodeJpeg(bitmap, JpegEncodeOptions(quality = 85, progressive = true)) // see "JPEG options"
ImageKodec.encodeJpeg16(bitmap16, JpegEncodeOptions(lossless = true, precision = 16)) // every bit back
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
ImageKodec.encodeJxl(bitmap, quality = 90)      // lossy JPEG XL, VarDCT in XYB
ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true)) // decodes to exactly the input
ImageKodec.encodeCcitt(bitmap)                  // CCITT Group 4 fax data, for a PDF or a TIFF
ImageKodec.encodeJbig2(bitmap)                  // a JBIG2 generic region, for a PDF
```

`encodeJpeg` uses a quality of 90 when you pass none.

## JPEG options

`JpegEncodeOptions` reaches every process of the JPEG standard (ITU-T T.81) but the hierarchical one (#183):

```kotlin
val options = JpegEncodeOptions(
    quality = 80,
    subsampling = JpegSubsampling.S422,   // Auto, S444, S422 or S420
    progressive = true,                   // libjpeg's default scan script
    grayscale = JpegGrayscale.Auto,       // one component when every pixel is gray
    restartInterval = 0,                  // MCUs between restart markers
)
val jpeg = ImageKodec.encodeJpeg(bitmap, options, metadata, cancellationCheck = { ensureActive() })
val twelve = ImageKodec.encodeJpeg16(bitmap16, JpegEncodeOptions(quality = 90, precision = 12))
val lossless = ImageKodec.encodeJpeg16(bitmap16, JpegEncodeOptions(lossless = true, precision = 16, predictor = 7))
```

- The defaults write what `encodeJpeg(bitmap, 90)` writes, byte for byte, except that a gray bitmap becomes one component, which is smaller. `JpegGrayscale.Never` keeps three; `Always` writes the luma of a colour bitmap.
- `progressive` writes the scan script libjpeg uses by default: the DC coefficients at half precision, the AC coefficients in bands and two steps of successive approximation, then the last bits. Its Huffman tables are built for the file, since the example tables have no end-of-band runs.
- `optimizeHuffman` builds the Huffman tables of a sequential file from its own symbol counts in a second pass. A 12-bit or lossless Huffman file always gets them.
- `arithmetic` codes the same coefficients with the QM coder of T.81 Annex D, about a tenth smaller than Huffman coding with built tables. `arithmeticDcLower`, `arithmeticDcUpper` and `arithmeticAcThreshold` set the conditioning of the DAC segment. Browsers do not read arithmetic-coded files; libjpeg-turbo does.
- `precision = 12` with `encodeJpeg16` writes a 12-bit DCT file, extended sequential (SOF1) or progressive. It reads the top 12 bits of each 16-bit sample.
- `lossless` writes T.81 Annex H at 2 to 16 bits, with the `predictor` (1 to 7) and `pointTransform` asked for: gray, or RGB with no colour transform, never subsampled. `decode16` returns the samples, and `decode` the pixels of an 8-bit bitmap given to `encodeJpeg`. A lossless restart interval must hold whole rows.
- `encoder = JpegEncoderKind.Jpegli` makes Jpegli's coefficients and takes every option of an 8-bit DCT file: its progressive, arithmetic and optimized files hold the coefficients of its baseline file. It writes 4:2:0 only, and a gray file is the luma of its colour one.
- Options that exclude each other, such as `lossless` with `progressive`, throw an `IllegalArgumentException` that names them.
- Every coding of the same coefficients decodes to the same pixels in libjpeg-turbo and in ImageKodec, and in ffmpeg, which reads no arithmetic coding, for the Huffman ones. On the six photo-like scenes of the tests at quality 75, the progressive files are 0.9 percent smaller than `cjpeg -progressive -optimize`'s and the arithmetic ones 0.7 percent smaller than `cjpeg -arithmetic`'s, at SSIMULACRA 2 scores within 0.8 of theirs, and the arithmetic files 13 percent smaller than the progressive ones.

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

## Fax and JBIG2 data for PDF and TIFF

`encodeCcitt` and `encodeJbig2` write the two bilevel codings that PDF and TIFF carry for scanned
pages (#185). Neither writes a file of its own: the result goes into a PDF image stream, or a TIFF
strip, with the parameters the caller states there.

```kotlin
val g4 = ImageKodec.encodeCcitt(page)                                       // Group 4, ended with EOFB
val g3 = ImageKodec.encodeCcitt(page, CcittEncodeOptions(k = 4, endOfLine = true, endOfBlock = false))
val jbig2 = ImageKodec.encodeJbig2(page)                                    // template 0, typical prediction
val jb2File = ImageKodec.encodeJbig2(page, Jbig2EncodeOptions(standalone = true))
```

- Both take a `KiteBitmap` and make it bilevel: a pixel is black when its luma, with BT.601 weights,
  laid over white by its alpha, is below half. A transparent pixel is white. The coding of that page
  is lossless, so `CcittFax.decode` and `Jbig2Decoder.decode` return it exactly.
- `CcittEncodeOptions` takes the parameters of PDF's `CCITTFaxDecode`: `k` (negative for Group 4, the
  default; 0 for Group 3 one-dimensional; positive for Group 3 two-dimensional), `encodedByteAlign`,
  `blackIs1`, `endOfLine` and `endOfBlock`. Write the same values into the PDF's `DecodeParms`, with
  `Columns` and `Rows` for the size. For a TIFF, Group 4 is compression 4; Group 3 with `endOfLine`
  and without `endOfBlock` is compression 3, with T4Options bit 0 set when `k` is positive; Group 3
  one-dimensional with `encodedByteAlign` and neither mark is compression 2.
- `CcittFax.encode` takes a page packed as `CcittFax.decode` returns one, with its `CcittOptions`.
- Each run and each two-dimensional step has one code in T.4 and T.6, so the output is the one
  coding of the page: libtiff's `tiffcp -c g4`, `-c g3:1d` and `-c g3:2d` write the same bytes.
- `encodeJbig2` writes one page holding one immediate generic region, coded with the MQ arithmetic
  coder. By default it is the embedded stream of PDF's `JBIG2Decode`: a page information segment
  and the region, with no global stream. `Jbig2EncodeOptions(standalone = true)` writes a `.jb2`
  file instead, with the file header and the end-of-page and end-of-file segments.
- `template` picks one of the four generic templates with its nominal adaptive pixels; 0, the
  default, codes text smallest. `typicalPrediction` codes a row that repeats the row above in one
  bit. `mmr = true` codes the region with T.6 instead, faster to read and about twice as large.
- Without typical prediction, the output matches jbig2enc's generic coder (`jbig2 -p`) byte for
  byte. With it, jbig2enc codes the first row in full where this encoder predicts a white one, so
  the two differ by a few bytes.
- On text-like pages, a JBIG2 generic region is about half the size of Group 4:

| Page | Group 4 | Group 3 2D, K = 4 | JBIG2, template 0 | JBIG2 with prediction | JBIG2 MMR |
| --- | --- | --- | --- | --- | --- |
| 1728 x 2200 | 142,134 | 154,206 | 68,719 | 68,523 | 142,190 |
| 2480 x 3508 | 323,754 | 353,955 | 152,401 | 151,998 | 323,810 |

  Group 4 takes 25 and 55 ms on the JVM for these pages, and JBIG2 60 and 138 ms.

## PNG colour types, bit depths and interlace

`encodePng(bitmap)` writes 8-bit RGB, or RGBA when a pixel has alpha, and keeps writing it byte for byte as before. The overloads that take a `PngEncodeOptions` write every colour type and bit depth of PNG, Third Edition (#182):

```kotlin
val small = ImageKodec.encodePng(bitmap, PngEncodeOptions())                       // Auto
val fixed = ImageKodec.encodePng(bitmap, PngEncodeOptions(colorType = PngColorType.Palette, bitDepth = 8))
val deep = ImageKodec.encodePng16(bitmap16, PngEncodeOptions(interlace = true), metadata)
val apng = ImageKodec.encodePng(animation, PngEncodeOptions(effort = 3))
```

- `PngColorType.Auto`, the default, takes the smallest type that holds the pixels exactly: gray at 1, 2, 4, 8 or 16 bits, a palette of up to 256 colours with their alpha in `tRNS`, RGB, or gray or RGB with an alpha channel. Gray and RGB take a `tRNS` colour key in place of an alpha channel when every transparent pixel is one colour that no opaque pixel has.
- A fixed `colorType`, and a `bitDepth` with it, is written as asked. Every choice is lossless, so a type or depth that cannot hold the pixels throws `IllegalArgumentException` and names why: a colour pixel for gray, more than 256 colours for a palette, partial alpha for a colour key, a sample that needs more bits.
- `encodePng16` takes a `KiteBitmap16` of 1 to 4 channels. With Auto, `decode16` gives back the same channels and samples: 8 bits where every sample is an 8-bit value widened (`v * 257`), fewer for such gray, and a palette or a colour key where they fit. A fixed type gives the channels it has: a gray file reads back as one channel, or two with a colour key.
- `interlace = true` writes Adam7 (PNG, Third Edition, 8.2), which a browser draws coarse first. The file is usually a little larger.
- An ICC profile in the metadata keeps Auto to the profile's colour space, gray or colour, as the specification asks; a fixed type that disagrees with it throws.
- `effort` runs from 1 to 3 and sets the search for the smallest file; the output is the same on every platform at every effort:
  - 1 takes the type of fewest bytes before compression, filters by the rule of PNG, Third Edition, 12.8 (no filter for a palette or samples under 8 bits, one chosen a row otherwise), and compresses with a short match search. It is two to four times as fast as 2, and 5 to 10 percent larger on the photographs.
  - 2, the default, also tries the best type without a palette, and compresses each type with row filters and without, and with Huffman coding alone, keeping the smallest. It is never larger than `encodePng(bitmap)`.
  - 3 tries every type, both palette orders (by frequency and by luminance), six filter choices and three match searches, then compresses the best three again with a long lazy search. It is 7 to 24 times slower than 2.
- An animation takes one colour type for every frame, chosen by Auto's rule over all of them. Gray and palette frames take 8 bits unless `bitDepth` asks for fewer, as ffmpeg 6 reads the frames after the first wrong at fewer. Frames drawn over the canvas need an alpha channel or a palette.

On the photo-like scenes of the tests, and on some pictures of few colours, the sizes are as follows. "Today" is `encodePng(bitmap)`; optipng 0.7.8 ran with `-o2` on that file. Times are from the JVM on one core of the test machine.

| Picture | Colours | Today | Auto, effort 1 | Auto, effort 2 | Auto, effort 3 | optipng -o2 | Type at effort 2 | ms: today, 1, 2, 3 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| landscape | >256 | 190864 | 204044 | 190864 | 174318 | 184451 | RGB 8 | 84, 93, 157, 1522 |
| portrait | >256 | 174100 | 191505 | 174100 | 165428 | 164011 | RGB 8 | 176, 63, 158, 2228 |
| foliage | >256 | 188940 | 199166 | 188940 | 180498 | 180755 | RGB 8 | 73, 34, 113, 960 |
| city | >256 | 121505 | 131683 | 121505 | 117851 | 116346 | RGB 8 | 84, 26, 116, 1218 |
| night | >256 | 208859 | 224422 | 208859 | 201365 | 206289 | RGB 8 | 61, 33, 114, 1034 |
| bokeh | >256 | 92183 | 97074 | 92183 | 85130 | 84959 | RGB 8 | 51, 21, 90, 871 |
| landscape 6 levels | 9 | 30161 | 18465 | 17442 | 16860 | 16972 | palette 4 | 33, 6, 123, 1368 |
| portrait 6 levels | 11 | 24793 | 18145 | 16683 | 15520 | 15538 | palette 4 | 50, 8, 93, 1074 |
| foliage 6 levels | 7 | 29062 | 19473 | 17115 | 16636 | 17073 | palette 4 | 65, 14, 96, 930 |
| landscape gray | 98 | 122350 | 98370 | 83343 | 83343 | 83551 | gray 8 | 59, 35, 49, 1049 |
| portrait gray | 125 | 111672 | 88952 | 73168 | 73063 | 73313 | gray 8 | 97, 19, 67, 1597 |
| chart | 10 | 1630 | 680 | 519 | 440 | 479 | palette 4 | 13, 10, 39, 284 |
| text | 2 | 4157 | 954 | 925 | 893 | 925 | gray 1 | 10, 2, 3, 46 |
| icon | 249 | 1304 | 1764 | 1304 | 1184 | 1304 | RGBA 8 | 7, 2, 11, 137 |
| ramp 200 | 200 | 740 | 1493 | 740 | 739 | 740 | RGB 8 | 5, 6, 46, 339 |
| total | | 1302320 | | 1187690 | 1133268 | 1146706 | | |

At effort 2 the six photographs come out byte for byte as today, since RGB with the same filters and search is still the smallest choice; the pictures that a palette or gray holds are 32 to 78 percent smaller, and the icon and the ramp, whose palettes cost more than they save, stay RGBA and RGB. Effort 3 is 3 to 9 percent smaller than effort 2 on the photographs, and 1.2 percent smaller than optipng -o2 over the whole set.

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

## JPEG XL

`encodeJxl` writes a JPEG XL codestream (ISO/IEC 18181-1), or the ISO BMFF container of 18181-2
when there is EXIF or XMP to carry or `JxlEncodeOptions.container` asks for it (#179).

```kotlin
ImageKodec.encodeJxl(bitmap, 90)                                         // lossy, distance 1.0
ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(distance = 2.5, effort = 3))
ImageKodec.encodeJxl(bitmap, JxlEncodeOptions(lossless = true), metadata) // ICC in the codestream, EXIF and XMP in boxes
ImageKodec.encodeJxl16(bitmap16, JxlEncodeOptions(lossless = true))      // 16 bits, gray to RGBA
ImageKodec.encodeJxl(animation, JxlEncodeOptions(lossless = true))       // delays in milliseconds, play count
```

- Lossy files use VarDCT in XYB. `distance` means what `cjxl -d` means, and `quality` maps to it
  as `cjxl -q` does: `0.1 + (100 - quality) * 0.09` from 30 up, so 90 is distance 1. Effort 1
  uses 8 by 8 transforms, 2 adds 16 by 16, 16 by 8 and 8 by 16, and 3 adds 32 by 32. Chroma from
  luma is set for each 64 by 64 tile, and the edge-preserving filter runs from distance 0.5 up.
  Alpha is coded losslessly with Modular beside the colour.
- Lossless files use Modular: a meta-adaptive tree learned from the image, the predictors of the
  standard, a reversible colour transform chosen for the image, a palette for images of 1024
  colours or fewer, and ANS with clustered distributions. A gray bitmap is coded as one channel,
  and alpha only when a pixel is not opaque.
- An animation's frames share one header. A frame that differs from the one before only inside
  a rectangle is coded as that rectangle over a saved copy of the frame before.
- On the scenes of `PhotoScenes`, lossless files at effort 2 are 11 percent smaller than
  `cjxl -d 0 -e 7` of libjxl 0.7, and lossy files at effort 2 are 11 percent smaller at the
  SSIMULACRA 2 score of `cjxl -d 1 -e 7` and the same size at that of `-d 2.5`.
  `JxlEncodeBenchmarkTest` prints the tables.

## What the encoders do not write

- The JPEG XL encoder writes no lossless JPEG recompression, no progressive passes, no patches,
  splines or noise, no LZ77 and no prefix codes, no Gaborish, no adaptive quantization, no
  squeeze and no float samples. Its lossy files are 8 or 16 bits in sRGB; an ICC profile in the
  metadata is written as the profile of the samples without converting them.
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
- The JBIG2 encoder writes generic regions only. Symbol dictionaries and text regions, which pay
  only on text and which encoders that merge look-alike glyphs make lossy, are not written, and
  neither are refinement, halftone or striped pages. The CCITT encoder writes no uncompressed mode.
- `encodeTiff` does not write fax compression; a TIFF of a fax page is built around `encodeCcitt`.
- The PNG encoder does not quantize: a palette is written only for a picture of 256 colours or
  fewer, and `encodeGif` is the encoder that reduces colours. It writes no `sBIT`, `gAMA`, `cHRM`,
  `sRGB`, `cICP`, `pHYs` or text chunks other than XMP. APNG is written at 8 bits a sample at
  most, since a `KiteAnimation` holds 8-bit frames.
- The JPEG encoder writes no hierarchical file, no CMYK or YCCK file and no alpha, and subsamples
  only by 2 across or by 2 across and down. Jpegli's XYB colour space is not written.
- The GIF writer accepts a play count in 0..65536 and refuses a larger one. See
  [Animation](animation.md#play-counts).
