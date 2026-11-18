# Writing images

ImageKodec writes PNG and APNG, JPEG, GIF, BMP, TIFF, WebP (lossless and lossy, still and
animated), JPEG 2000, JPEG XL, AVIF (still and animated), JPEG XR, HEIC with the `imagekodec-heic`
module, and the CCITT fax and JBIG2 data that PDF and TIFF embed. Every encoder takes a `KiteBitmap` (or a
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
ImageKodec.encodeTiff(page, TiffEncodeOptions(TiffCompression.CcittG4)) // a bi-level page in Group 4
ImageKodec.encodeTiffCmyk(width, height, ink)   // CMYK separations from ink samples
ImageKodec.encodeBmp(bitmap, BmpEncodeOptions(8, rle = true)) // a palette of up to 256 colours, RLE8
ImageKodec.encodeWebp(bitmap)                   // lossless VP8L, smaller than the PNG
ImageKodec.encodeWebp(bitmap, 75)               // lossy VP8 at cwebp's quality 75, alpha kept exact
ImageKodec.encodeWebp(anim, WebpEncodeOptions(lossless = true)) // animated, lossless or lossy frames
ImageKodec.encodeAvif(bitmap, quality = 50)     // AV1 in HEIF, built in
ImageKodec.encodeAvif(anim, HeifEncodeOptions()) // an AV1 image sequence of inter frames
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

## TIFF and BMP layouts

`encodeTiff`, `encodeTiff16` and `encodeBmp` without options write what they always wrote. With
`TiffEncodeOptions` and `BmpEncodeOptions` they write the other layouts their decoders read (#184):

```kotlin
val fax = ImageKodec.encodeTiff(page, TiffEncodeOptions(TiffCompression.CcittG4))
val tiled = ImageKodec.encodeTiff(bitmap, TiffEncodeOptions(TiffCompression.Lzw, tileWidth = 256, tileLength = 256))
val jpeg = ImageKodec.encodeTiff(photo, TiffEncodeOptions(TiffCompression.Jpeg, quality = 85))
val palette = ImageKodec.encodeTiff(chart, TiffEncodeOptions(photometric = TiffPhotometric.Palette))
val cmyk = ImageKodec.encodeTiffCmyk(width, height, ink, metadata = ImageMetadata(icc = pressProfile))
val bmp4 = ImageKodec.encodeBmp(icon, BmpEncodeOptions(bitsPerPixel = 4, rle = true))
```

- TIFF compressions: none (1), PackBits (32773), LZW (5), Deflate (8, the default), CCITT Group 4
  (4) and Group 3 one-dimensional with EOLs (3) for a bi-level page, and JPEG (7) as Technical Note
  2 defines it. `predictor` adds the horizontal differencing predictor to LZW and Deflate on samples
  of 8 or 16 bits.
- JPEG writes the quantization and Huffman tables once, in JPEGTables, and each strip or tile as an
  abbreviated stream of the core's baseline encoder, at `quality` as `encodeJpeg` reads it. Colour is
  YCbCr (photometric 6), 4:2:0 at quality 90 and below and 4:4:4 above; a gray page is one gray
  component. It takes 8-bit pages without alpha. libtiff and ImageIO read it within 3 levels of
  ImageKodec's decoder in colour and 1 in gray, as JPEG decoders round each their own way.
- `rowsPerStrip` sets the strips, 0 keeping strips of about 8 KiB. `tileWidth` and `tileLength`,
  multiples of 16, write tiles instead; edge tiles are written whole, padded with zeros.
- `photometric`: `Auto` writes gray when every pixel is gray and RGB otherwise, or bi-level under a
  CCITT compression. `BiLevel` writes one bit a pixel, WhiteIsZero, black where the luma laid over
  white is below half, as `encodeCcitt` decides. `Gray` writes one sample, the luma of a colour
  pixel. `Palette` writes the page's colours at 1, 2, 4 or 8 bits, for an opaque page of at most
  256 colours. `Rgb` writes three samples even for a gray page. Alpha goes along as an
  unassociated extra sample, except under a palette, bi-level or JPEG.
- `encodeTiffCmyk` takes four ink samples a pixel, cyan, magenta, yellow and black, from no ink to
  full, at 8 bits in a `ByteArray` or 16 in a `ShortArray`, and writes photometric 5 with InkSet 1.
  Pass the press's ICC profile in the metadata, so that a reader can convert the ink.
- The metadata's ICC profile goes into every page (tag 34675) and its XMP packet into the first
  (tag 700). The EXIF block goes into the first page as a TIFF holds one (#186): see
  [Keeping a photo's metadata](#keeping-a-photos-metadata).
- The CCITT strips are `encodeCcitt`'s data, each strip coded alone: libtiff's `tiffcp -c g4` writes
  the same strips. G4 is a third of the size of the same page as RGB with Deflate on the text pages
  of the tests, and LZW comes out 4 to 19 percent larger than Deflate on the photo-like scenes and
  two to six times faster; libtiff's own LZW files in the same strips are within 14 bytes of ours.
- BMP `bitsPerPixel`: null keeps the 24-bit or 32-bit choice. 1, 4 and 8 write a palette of the
  bitmap's colours, `rle` codes 4 and 8 bits with BI_RLE4 and BI_RLE8, every pixel written so no
  reader leaves a hole. 16 writes 5-5-5 BI_RGB, each channel rounded to the nearest of 32 levels,
  the only lossy depth. 24 and the palettes need an opaque bitmap, a palette at most as many colours
  as its depth holds; anything else is refused with `IllegalArgumentException`.
- libtiff (`tiffinfo`, `tiff2rgba`, `tiffcmp`) and ImageIO read every TIFF variant to the samples
  ImageKodec's decoder gives, and ImageIO and ImageMagick read every BMP variant.

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

A decode returns pixels only, so a plain re-encode drops the capture date, the place, the camera and the colour profile. `readMetadata` reads the EXIF block, the XMP packet and the ICC profile of every format that carries them, and every encoder with a place for them takes them back (#129, #186):

```kotlin
val metadata = ImageKodec.readMetadata(photo)
val upright = ImageKodec.decode(photo, applyOrientation = true)
// The pixels are upright now, so the copied orientation must say so.
val kept = metadata.upright()
val jpeg = ImageKodec.encodeJpeg(upright, 85, kept)
val png = ImageKodec.encodePng(upright, kept)
val webp = ImageKodec.encodeWebp(upright, WebpEncodeOptions(quality = 80), kept)
val tiff = ImageKodec.encodeTiff(upright, TiffEncodeOptions(), kept)
val jxl = ImageKodec.encodeJxl(upright, JxlEncodeOptions(), kept)
val gif = ImageKodec.encodeGif(upright, kept)                        // XMP and ICC only
val bmp = ImageKodec.encodeBmp(upright, BmpEncodeOptions(), kept)    // ICC only
```

| Format | EXIF | XMP | ICC |
|---|---|---|---|
| JPEG | APP1 `Exif` segment | APP1 Adobe XMP segment | APP2 `ICC_PROFILE` chunks |
| PNG | `eXIf` | `iTXt` `XML:com.adobe.xmp` | `iCCP` |
| WebP | `EXIF` | `XMP ` | `ICCP` |
| HEIC, AVIF | `Exif` item | `mime` item | `colr` `prof` |
| JPEG 2000 (JP2) | UUID box `JpgTiffExif->JP2` | XMP UUID box | colour specification, when gray or RGB as the image |
| JPEG XL | `Exif` box | `xml ` box | the codestream |
| TIFF | directories of the first page, rebuilt | tag 700 | tag 34675, every page |
| JPEG XR | directories of the file, rebuilt | tag 0x02BC | tag 0x8773 |
| GIF | none | `XMP DataXMP` extension | `ICCRGBG1` `012` extension |
| BMP | none | none | BITMAPV5HEADER, `PROFILE_EMBEDDED` |

- Every format but TIFF and JPEG XR stores the blocks whole, and `readMetadata` gives back each byte for byte.
- A TIFF or JPEG XR file has no place for an EXIF block as such. Its descriptive tags (make, model, dates, orientation, and in a TIFF the resolution) join the file's directory, and its EXIF, GPS and Interoperability directories are copied after it, little endian, at new offsets. `readMetadata` builds a block again from the same directories, so a block read back from such a file returns byte for byte the next time through. The thumbnail directory (IFD1) is left out, and a maker note that holds offsets of its own points to the wrong place once moved, as after any editor that moves it.
- `readMetadata` gives a TIFF or JPEG XR file an EXIF block only when the file points to an EXIF or GPS directory; the tags of a plain scan are not EXIF.
- JPEG writes an EXIF block or an XMP packet only when it fits one segment, about 64 KB. A profile of any size up to 255 segments goes in.
- JPEG XL reads `Exif` and `xml ` boxes also when a `brob` box holds them compressed with Brotli, as `exiftool -api Compress=1` and libjxl write them; the Brotli decoder carries the 122 KB dictionary of RFC 7932, compressed to 58 KB.
- GIF and BMP have no place for EXIF, and BMP none for XMP: those blocks are left out. A GIF's pixels and a BMP's pixels are those the calls without metadata write, and with empty metadata the bytes are the same.
- HEIC and AVIF set the EXIF orientation to the orientation the options write as `irot` and `imir`, and JPEG XR writes it as its Transformation tag.
- `upright()` sets the EXIF orientation to 1. Keep the original orientation only when you write the pixels as they were stored.
- ExifTool reads every block every encoder writes, and `readMetadata` reads every block ExifTool writes into a file of each format, ImageMagick's BMP and libjxl's codestream profile (`MetadataExifToolOracleTest`).

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
  size allows) and the levels, which a greedy search lowers where the rate saved pays for the error.
- A picture that looks like screen content (few colours in sharp strokes) turns on the screen content
  tools, from effort 2. A block whose colours are few may code a palette: of luma values, of pairs of U
  and V, or both. Where an eighth of the picture's 8 by 8 blocks repeat earlier in it, the frame allows
  intra block copy: a hash of every 8 by 8 window of the source finds, for each block, the nearest
  earlier place that matches it sample for sample and that a conformant vector may reach, and the block
  may copy it, with a residual or without. The frame then has no loop filter, CDEF or restoration, as the
  specification requires. On screen content in 4:4:4 at effort 2, intra block copy makes the files 26
  percent smaller at equal SSIMULACRA 2 score, and the chroma palette 3 percent (`Av1ToolGainTest`).
- After the blocks, the loop filter levels, the CDEF strengths of each 64 by 64 block, and, from effort
  2, the loop restoration of each unit are chosen on the reconstruction: none, the self-guided filter
  or the Wiener filter, whose taps are fitted by least squares, each measured through the decoder's own
  filter and priced by the bits its parameters take, and each plane takes whichever of the four frame
  types costs least. The Wiener filter wins few units of the photo scenes (0.1 percent smaller files).
- `effort` 1 tries one luma and one chroma mode at their own transform size, square partitions down to
  8 by 8, and the DCT or the mode's own transform type; 2 adds filter intra up to 16 by 16, one
  transform split, 4 by 4 blocks, the identity type while searching and every type of the set at the
  end, the level search, loop restoration and intra block copy; 3 adds a second luma and chroma mode,
  angle deltas, rectangular partitions, two transform splits, every transform type throughout and more
  filter strengths. Effort 2 was set by measuring what each step of effort 3 brings against its time;
  see [Limits](limits.md#encoding-avif).
- With `tileSize = null`, a picture wider than 4096 codes as one frame of uniform AV1 tiles, the fewest
  the specification allows. Grid items, the default, are what both libavif and libheif read best, and
  the tile runner encodes them in parallel.

The encoder runs the core's own AV1 decoder in a coding mode: the decoder walks the syntax, predicts
and reconstructs, and the encoder supplies each decision. So the encoder's reconstruction is what any
decoder rebuilds: `Av1EncoderOracleTest` holds dav1d and ImageKodec's decoder to it, sample for sample.
The output is the same on every platform; `AvifEncodeTest` keeps hashes of fixed inputs.

### Animated AVIF

`encodeAvif(animation, options)` writes an AV1 image sequence (#177): a track of every frame with its
delay, which `decodeAnimation` plays and libavif and ffmpeg read. `encodeHeic(animation, options)` writes
the same track around HEVC P and B pictures with the `imagekodec-heic` module, and without it throws
`UnsupportedImageException` that names the module.

```kotlin
val animated = ImageKodec.encodeAvif(animation, HeifEncodeOptions(quality = 60))
val seekable = ImageKodec.encodeAvif(animation, HeifEncodeOptions(quality = 60, keyFrameInterval = 30))
val exact = ImageKodec.encodeAvif(animation, HeifEncodeOptions(lossless = true))
```

What the file holds:

- `moov` with `mvhd` and a `trak` of the colour: `tkhd`, an edit list (`edts`, `elst`) and `mdia` with
  `mdhd`, `hdlr` `pict`, `vmhd`, `dinf` and the sample table: an `av01` sample entry with `av1C`, `colr`,
  `ccst` and, for a subsampled picture of odd size, `clap`; `stts`, `stsc`, `stsz`, `stco` and, when some
  frames are not key frames, `stss`. Each frame lasts its `delayMillis` on a timescale of 1000 (1 ms for
  a delay of 0). The edit list repeats the track, as libavif writes a play count: the track lasts the
  loop count times the edit, or for ever (a duration of all ones) for a loop count of 0.
- A frame with alpha below 255 adds a second track of alpha (`hdlr` `auxv`, `auxi` in its sample entry
  and a `tref` `auxl` to the colour track).
- The first frame is also the primary item, and its alpha an auxiliary item, each sharing the bytes of
  the first sample, as libavif writes a sequence, so a reader of still images shows it. `ftyp` names
  `avis`, `avif`, `msf1`, `iso8`, `mif1` and `miaf`. Metadata and orientation go on the item and the
  sample entry; `tileSize` and `thumbnailSize` apply to stills only.
- The first frame is a key frame and every later one an inter frame, unless `keyFrameInterval` asks
  for a key frame at least every that many frames. An inter frame predicts from the frame before it
  (LAST) and, from effort 2, the one before that (LAST2); each block chooses, by coding it through the
  decoder's syntax, between NEARESTMV, NEARMV, GLOBALMV, NEWMV with a vector from a motion search (whole
  samples by the sum of absolute differences, then half and quarter samples through the decoder's own
  prediction filters), the two averaged (compound, from effort 2) and the intra modes of a still. Its
  CDFs start from the previous frame's, and the loop filter, CDEF and restoration are chosen per frame.
- `effort` 1 searches LAST alone in square steps of 4, 2 and 1 samples, codes the two best candidates
  with their residual, and skips the intra modes of a block that inter prediction codes with no
  residual; 2 adds LAST2, compound prediction, steps from 16, more NEARMV indices, a third candidate and
  a transform split; 3 adds steps from 32, a near pair and five candidates.

dav1d decodes every frame to the encoder's own reconstruction (`AvifSequenceOracleTest`), and
`AvifSequenceEncodeTest` keeps hashes of fixed animations. Sizes against libaom are in
[Limits](limits.md#encoding-animated-avif).

## JPEG 2000

`encodeJp2` writes JPEG 2000 Part 1 (ITU-T T.800), in a JP2 file or as a bare codestream, the form a
PDF embeds (#180). `encodeJp2(bitmap, quality)` is lossy; `Jp2EncodeOptions` takes every other choice,
and `encodeJp2Wide` takes a `KiteBitmap16`:

```kotlin
ImageKodec.encodeJp2(bitmap, 75)                                          // about 41 dB PSNR
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(lossless = true))           // 5/3 and RCT, exact
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(ratios = listOf(40.0, 20.0, 10.0))) // three layers
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(codestreamOnly = true))     // J2K, for a PDF
ImageKodec.encodeJp2(bitmap, Jp2EncodeOptions(highThroughput = true))     // HTJ2K, in a JPH file
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
- `highThroughput = true` codes the code-blocks with the HT block coder of HTJ2K (ITU-T T.814, #187),
  which has no arithmetic coder: the codestream gets a `CAP` segment and code-block style 0x40, and a
  JP2 file the `jph ` brand. Each code-block is one HT set, its cleanup pass at the bit plane the rate
  control picks and, lossy, the SigProp and MagRef passes below it; the file has one quality layer.
  `opj_decompress` 2.5 reads every file to ImageKodec's samples. On the photo scenes the files are 4 to
  35 percent larger than Part 1 files at the same PSNR, and ImageKodec decodes them 1.3 to 4 times as
  fast; a lossless one encodes about three times as fast, a lossy one up to twice as slowly
  (`Jp2HtBenchmarkTest`).

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
- The AV1 encoder writes no film grain, no 128 by 128 superblocks, no superres, no delta quantizer, no
  quantizer matrices, no segmentation and no 4:1 or three-way partitions. Intra block copy copies only
  places whose source matches the block exactly, and not at effort 1. Its sequences hold shown key and
  inter frames in display order: no hidden or alternate reference frames, no backward references, no
  skip mode, no global or warped motion, no OBMC, no inter-intra or masked compound, no distance
  weights, no switchable or dual interpolation filters, no eighth-sample vectors and no motion field
  projection.
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
  predictable termination and no Part 2 extension. Its HTJ2K files have one quality layer and one HT
  set a code-block, and mix no Part 1 code-blocks among HT ones. A bare codestream carries no metadata
  and no channel definitions.
- The JBIG2 encoder writes generic regions only. Symbol dictionaries and text regions, which pay
  only on text and which encoders that merge look-alike glyphs make lossy, are not written, and
  neither are refinement, halftone or striped pages. The CCITT encoder writes no uncompressed mode.
- The TIFF encoder writes chunky samples only (no separate planes), little endian, without
  old-style JPEG (6), Group 3 two-dimensional coding, CCITT compression 2, floating-point or signed
  samples, depths other than 1, 2, 4, 8 and 16 bits, YCbCr outside JPEG, or an EXIF thumbnail
  directory. JPEG takes no 16-bit pages and no alpha. The BMP encoder writes no 2-bit palette, no
  BITFIELDS layout but the 32-bit one, no top-down rows, no linked profile, and no embedded JPEG or
  PNG; a V5 header only to embed an ICC profile.
- The PNG encoder does not quantize: a palette is written only for a picture of 256 colours or
  fewer, and `encodeGif` is the encoder that reduces colours. It writes no `sBIT`, `gAMA`, `cHRM`,
  `sRGB`, `cICP`, `pHYs` or text chunks other than XMP. APNG is written at 8 bits a sample at
  most, since a `KiteAnimation` holds 8-bit frames.
- The JPEG encoder writes no hierarchical file, no CMYK or YCCK file and no alpha, and subsamples
  only by 2 across or by 2 across and down. Jpegli's XYB colour space is not written.
- The GIF writer writes no EXIF, which a GIF has no place for, and no comment extension. It accepts
  a play count in 0..65536 and refuses a larger one. See
  [Animation](animation.md#play-counts).
