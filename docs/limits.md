# Limits

Every unsupported feature fails at a named point instead of silently. This page lists what
ImageKodec does not do, and the bounds it puts on hostile input.

## Input and output

- Every entry point takes a complete `ByteArray`. There is no streaming or partial-decode API.
  `decodeScaled`, `decodeDownscaledTo` and `decodeReduced` shrink a JPEG or a JPEG 2000 image
  inside the decoder, and `scaled` works after a full-size decode.
- `decode` keeps only the high byte of a 16-bit sample, because `KiteBitmap` holds 8 bits a
  channel. `decode16` keeps the whole sample for PNG, TIFF, JPEG 2000, JPEG XL and 12-bit or
  lossless JPEG, and it converts AVIF at 16 bits. Other formats come through it at 8 bits, widened.
- There is no encoder for lossy WebP, JPEG 2000, AVIF or JPEG XL. See
  [Writing images](encoding.md#what-the-encoders-do-not-write).

## Size ceilings

- All image decoders cap their output at 2^28 pixels (about 1 GiB of ARGB).
- WebP, AVIF, JPEG XL, fax-compressed TIFF, `CcittFax` and `Jbig2Decoder` use that ceiling alone.
  Constant VP8L pixels and fax reference rows can take no entropy bits, or one bit a row, so a
  ratio to the input size would refuse valid files. The frames of a WebP, AVIF or JPEG XL
  animation together must fit the same ceiling.
- Other paths also keep a loose bound relative to the input: 4096 pixels for each input byte,
  scaled by eight divided by the stored bits per pixel for PNG and TIFF pixels smaller than a byte.
  That bound can still refuse a file that compresses unusually well. PNG and GIF animations count
  their full composited canvases against it.
- JPEG 2000 probe and decode make the same size decision.
- The ceiling bounds the output size. It does not promise that a device has enough free memory.

## Probe

`probe` covers declared features, not pixel integrity. For JPEG 2000 it checks the main and
tile-part headers with the coding and quantization readers of the decoder, and it skips packet
bytes by their declared lengths. A corrupt packet payload can therefore still probe as decodable
and fail during the decode.

## JPEG 2000

- The high-throughput block coder of Part 15 (HTJ2K) throws `UnsupportedImageException`.
- So do files with more than 1000 layers, or more than 32 progression order changes in one header,
  which is the limit of OpenJPEG.
- A malformed header names the marker, the field and the byte position.
- The public nullable `JpxDecoder.decode` overloads return null on failure instead of throwing.
- Color comes from the JP2 header, without a color engine. The palette, the channel definitions and
  the color specification apply in the order of T.800 Annex I. sRGB, grayscale, bi-level, sYCC,
  e-sYCC, CMYK and CMY convert to RGB or gray. An ICC profile picks gray, RGB or CMYK, and it is
  applied only with a `colorTarget`. Other color spaces, such as CIELab, throw
  `UnsupportedImageException` that names them.

## AVIF

- AV1 matrices that are not linear (BT.2020 and chromaticity-derived constant luminance, SMPTE ST
  2085 and ICtCp) throw `UnsupportedImageException`, as libavif refuses them.
- An ICC profile or a transfer function is reported, and applied only with a `colorTarget`.
- An image sequence without an edit list loops forever, as browsers play it.
- AV1 allows a frame coded at another size than the first. Such a frame is scaled to the size of
  the first frame.
- Large scale tile lists, which no image uses, are refused.

## JPEG XL

### Color

- A lossy file stores its color in the XYB space. It converts to the encoding that its header
  names: the white point, the primaries, and the sRGB, linear, gamma, BT.709, DCI, PQ or HLG curve.
- A header that has only an ICC profile gives sRGB, and `probe` reports sRGB. libjxl converts to
  the profile with a color engine instead.
- A file that is not XYB keeps the samples it stores. Its ICC profile is reported, not applied.
- No tone mapping runs by default. With `colorTarget = ColorTarget.Srgb()`, the shared color
  conversion applies supported profiles or code points and tone-maps HDR.
- That conversion takes the peak of an HDR file as 1000 cd/m². It does not read the intensity
  target of the header, which libjxl sets to 10000 when the encoder was told nothing.
- A header with primaries of its own, or with a gamma other than 2.2 or 2.8, has no code points and
  converts as sRGB.
- A sample outside the range of 0 to 1, which a float file can hold, is clipped.
- The black channel of a CMYK file is read and not applied.

### Work bounds

ImageKodec bounds the work of a JPEG XL file more tightly than libjxl. libjxl limits how many
splines and patches a frame has, which lets a small file ask for a very long decode.

- Splines may cover 64 times the area of the image plus 2^27 pixels, by the estimate of libjxl.
- Patches may cover 16 times the area of the image plus 2^20 pixels.
- An image may hold 2^30 samples over all of its channels. That is color and alpha at the shared
  ceiling of 2^28 pixels, so an image with more extra channels has to be smaller.
- A lossy frame is padded to whole blocks of 8 pixels, and the padded frame may be 2^29 pixels at
  most.

A file past any of these bounds throws `ImageDecodeException`.

### Frames

A preview frame is passed over. `decode` gives the first shown frame, with the layers before it
blended in. Every frame decodes whole, with all of its passes, so there is no early, coarser
picture.

## Orientation

`probe` reports the orientation that a file declares: the EXIF tag of a JPEG or TIFF, the rotation
and mirror of an AVIF, and the header of a JPEG XL. `decode` applies it only when you pass
`applyOrientation = true`. The Compose binding applies it for you.
