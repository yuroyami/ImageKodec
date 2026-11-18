# Limits

Every unsupported feature fails at a named point instead of silently. This page lists what
ImageKodec does not do, and the bounds it puts on hostile input.

## Input and output

- Every entry point takes a complete `ByteArray`. There is no streaming or partial-decode API.
  `decodeScaled`, `decodeDownscaledTo` and `decodeReduced` shrink a JPEG, JPEG 2000 or JPEG XR image
  inside the decoder, and `scaled` works after a full-size decode. `decodeScaled` and
  `decodeDownscaledTo` also reduce a HEIC or AVIF `grid` while it decodes. The decoder converts and
  scales one row of tiles at a time, so memory holds two rows of tiles and the output, not the
  picture, and the pixels equal those of `decode` then `scaled`. The tiles still decode at full size,
  so this saves memory, not time. An alpha image that is one coded item, not a grid, decodes whole
  beside the rows. A grid whose alpha is neither one coded item nor a grid of the same layout, and
  any other HEIF image, decodes in full first (#151).
- `decode` keeps only the high byte of a 16-bit sample, because `KiteBitmap` holds 8 bits a
  channel. `decode16` keeps the whole sample for PNG, TIFF, JPEG 2000, JPEG XL, JPEG XR and 12-bit or
  lossless JPEG, and it converts AVIF and HEIC at 16 bits. Other formats come through it at 8 bits,
  widened. An AVIF, HEIC or JPEG 2000 sample that converts to RGB stays inside the 8-bit level that
  `decode` rounds it to, so that `toBitmap` gives what `decode` gives: a sample of more than 8 bits
  can move by up to 255 sixteen-bit units to stay in that level.
- The AVIF encoder writes still intra pictures only (see [AVIF](#avif)), and the JPEG XL
  encoder writes a part of the format. See
  [Writing images](encoding.md#what-the-encoders-do-not-write).
- `encodeCcitt` and `CcittFax.encode` write rows of up to 65536 pixels and refuse a wider page
  with `IllegalArgumentException`; T.4 pages are 1728 to 4864 wide. `encodeJbig2` writes one generic
  region a page, with no symbol coding.
- The PNG encoder writes a palette only for 256 colours or fewer, and APNG at 8 bits a sample at
  most. Its rows must fit one array: a file whose filtered rows pass 2 GB, such as a
  16-bit RGBA picture of more than 268 million pixels, throws `IllegalArgumentException`.
- The JPEG encoder writes baseline, progressive, arithmetic, 12-bit and lossless files (2 to 16
  bits), but no hierarchical, CMYK or alpha file. It keeps every coefficient of an image in memory,
  two bytes each, so a 4:2:0 encode takes about three bytes a pixel besides the bitmap.
- The TIFF encoder's CCITT strips and tiles are at most 65536 pixels wide, its JPEG pages at most
  65535 on a side, and a strip or tile, or a band of rows that tiles are cut from, at most 2^31 bytes
  of samples. A JPEG strip that is not the last holds a multiple of 16 rows (8 at 4:4:4 or in gray).
  A palette holds 256 colours at most and no alpha, in TIFF and BMP alike; a BMP of 1, 4, 8, 16 or 24
  bits holds no alpha. Each is refused with `IllegalArgumentException` that names it (#184).

## Metadata

- `readMetadata` reads the EXIF block, XMP packet and ICC profile of JPEG, PNG, WebP, HEIC, AVIF,
  JPEG 2000, TIFF, JPEG XL, JPEG XR, GIF and BMP files (#186). It never throws: a damaged segment,
  chunk, box or directory is left out. It reads IPTC and Photoshop resources of no format, and the
  metadata of a TIFF's first page only. A BigTIFF gives none.
- A TIFF or JPEG XR file holds EXIF as directories of its own, so the block `readMetadata` gives is
  rebuilt from them, little endian, without the thumbnail directory (IFD1), and only when the file
  points to an EXIF or GPS directory. Writing one into those formats takes the same directories
  apart. Values are copied as their types say, so a maker note with offsets of its own points to the
  wrong place once moved. A hostile directory whose values overlap stops being copied at twice the
  file's size.
- A JPEG XL `brob` box is decompressed up to 16 MB, as an XMP packet in a PNG is. The Brotli decoder
  refuses the large-window extension, which RFC 7932 does not define.
- GIF keeps no EXIF and BMP neither EXIF nor XMP, so their encoders leave those blocks out. A JPEG
  segment holds at most about 64 KB, so a longer EXIF block or XMP packet is left out of a JPEG; the
  extended XMP of XMP Part 3 is not written or read.

## Size ceilings

- All image decoders cap their output at 2^28 pixels (about 1 GiB of ARGB).
- WebP, AVIF, HEIC, JPEG XL, JPEG XR, fax-compressed TIFF, `CcittFax` and `Jbig2Decoder` use that ceiling alone.
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

- A malformed header names the marker, the field and the byte position. A damaged code-block, of
  Part 1 or of the HT block coder, throws `ImageDecodeException`: the HT decoder's MagSgn, MEL, VLC,
  SigProp and MagRef readers stop at their segment and read fill bits past it, its loops run over the
  block's samples, and a magnitude past 30 bits is refused before it is formed.
- The public nullable `JpxDecoder.decode` overloads return null on failure instead of throwing.
- Files with more than 1000 layers, or more than 32 progression order changes in one header, throw
  `UnsupportedImageException`, which is the limit of OpenJPEG.
- HTJ2K (ITU-T T.814) decodes, but two of its forms throw `UnsupportedImageException`: code-block
  style 0x80, which mixes HT and Part 1 code-blocks (MIXED), and an HT code-block of more than three
  coding passes, which holds placeholder passes or more than one HT set. OpenJPEG 2.5 refuses the
  first and does not read the second, which ffmpeg 6.1 reads another way, so no reading of either
  could be checked against a decoder.
- The encoder writes at most 65535 tiles and 100 layers, 8 bits a sample from a `KiteBitmap` and 16
  from a `KiteBitmap16`. It pads a flat picture that codes to fewer bytes than the decoder's expansion
  guard expects of its size with a `COM` segment, so ImageKodec reads back every file it writes. A
  quality target the finest quantization step cannot reach, near quality 100 on a noisy picture,
  comes out a little below it.
- The HT encoder (`highThroughput`) writes one quality layer and one HT set a code-block, a cleanup
  pass and at most the SigProp and MagRef passes below it, and no MIXED code-blocks.
- Color comes from the JP2 header, without a color engine. The palette, the channel definitions and
  the color specification apply in the order of T.800 Annex I. sRGB, grayscale, bi-level, sYCC,
  e-sYCC, CMYK, CMY and CIELab convert to RGB or gray. CIELab converts relative colorimetric, as
  lcms2 converts a Lab profile: its illuminant's white becomes sRGB's white. An ICC profile picks
  gray, RGB or CMYK, and it is applied only with a `colorTarget`.
- The other enumerated colour spaces of JPX (YCbCr(1) to YCbCr(3), PhotoYCC, YCCK, CIEJab, e-sRGB,
  ROMM-RGB and the two YPbPr spaces), and an ICC profile of another data colour space, throw
  `UnsupportedImageException` that names them. No installed tool converts them to check a
  conversion against, and the ranges and offsets that T.801 gives them were not at hand.

## AVIF

- AV1 matrices that are not linear (BT.2020 and chromaticity-derived constant luminance, SMPTE ST
  2085 and ICtCp) throw `UnsupportedImageException`, as libavif refuses them.
- An ICC profile or a transfer function is reported, and applied only with a `colorTarget`.
- An image sequence without an edit list loops forever, as browsers play it.
- A file with both a track and a primary item shows the primary item when its major brand is `avif`
  and the track otherwise, as libavif chooses.
- AV1 allows a frame coded at another size than the first. Such a frame is scaled to the size of
  the first frame.
- Large scale tile lists, which no image uses, are refused.
- The encoder (`encodeAvif`) writes one intra key frame a picture, 8, 10 or 12 bits, at most 65535 on
  a side. It writes no image sequence, film grain, 128 by 128 superblock, intra block copy, delta
  quantizer, Wiener restoration, chroma palette, or 4:1 and three-way partition.
- The encoder is slower than libaom; see [Encoding AVIF](#encoding-avif) below.

### Encoding AVIF

`Av1EncoderBenchmarkTest` (run with `IMAGEKODEC_AV1_BENCHMARK=1`) encodes the six photo-like test scenes
(0.08 to 0.2 megapixels) at 8-bit 4:2:0 and compares `encodeAvif` with libavif 1.0.4's `avifenc` (libaom
3.8.2, one thread) at `-q 50` and `-q 80`: for each avifenc file, the size ImageKodec needs for the same
SSIMULACRA 2 score, read between its sizes at qualities 5 apart. The table gives the mean ratio of the six
scenes; below 1, ImageKodec's file is smaller. Times are on the JVM after a warm-up, on a 4-core Intel Xeon
at 2.30 GHz shared with other builds, so they are slower than on an idle machine.

| ImageKodec effort | Against `-s 6 -q 50` | `-s 6 -q 80` | `-s 9 -q 50` | `-s 9 -q 80` | Time per scene at `-q 50`'s score |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.40 | 1.34 | 0.99 | 1.01 | 0.36 s |
| 2 | 1.18 | 1.11 | 0.82 | 0.86 | 0.75 s |
| 3 | 0.98 | 0.96 | 0.71 | 0.76 | 4.9 s |

avifenc takes 0.062 s a file at speed 6 and 0.025 s at speed 9, each a fresh process. So effort 2 makes files 11 to
18 percent larger than libaom's speed 6 at equal score and 14 to 18 percent smaller than speed 9's, and takes 12 times
as long as speed 6; effort 1 takes 6 times as long, effort 3 79 times, for files 2 to 4 percent smaller than speed
6's. At efforts 1 to 3, ImageKodec reaches the scores of avifenc's `-q 50` and `-q 80` at qualities 48 to 50 and 79 to 80. A 256 by 256 scene takes, at efforts 1, 2 and 3, 240, 367 and 1,600 ms on the JVM, 618, 1,582 and
10,411 ms on JS (Node), and 1,157, 2,649 and 17,206 ms on a debug linuxX64 binary (`AvifEncodeTimingTest`).

## HEIC

- Decoding needs the `imagekodec-heic` module and a call to `ImageKodecHeic.install()`. Without it,
  `decode` throws `UnsupportedImageException`, and `probe` reports the file as not decodable.
- An image sequence plays from its first sample: `maxFrames` stops it early, and the frames of the
  whole sequence count together against the output ceiling, as for AVIF. A frame lasts until the
  next frame's composition time, and the last frame for its own `stts` duration. A `ctts` that does
  not give each sample one offset, or that puts a sample before the start of the track, is a decode
  error (#157).
- Without the module, a HEVC image sequence is reported as not decodable, with its size from its
  sample entry, and `decodeAnimation` throws `UnsupportedImageException` that names the module.
- An inter picture whose reference picture set names a picture the decoder does not hold is a
  decode error, except for the RASL pictures of a CRA picture that starts the stream, which are
  skipped as H.265 allows. All the pictures the decoded picture buffer holds count together
  against the pixel ceiling.
- CTBs of 8 samples, which no profile of H.265 allows, are refused, and `unsupportedReason` names
  the field that asks for them. So is a parameter set whose screen content coding extension follows a
  multilayer or 3D extension, which only a decoder of those layers reads.
- Three separate colour planes are each decoded as luma, as H.265 does, so all three take the luma
  bit depth, and `bit_depth_chroma_minus8` is not used.
- Chroma is upsampled bilinearly, as for AVIF. libheif's `heif-convert` takes the nearest sample,
  so the two differ at sharp colour edges of a subsampled image.

### Speed and memory

`HeicBenchmarkTest` codes two 4096 by 3072 pictures (12.6 megapixels) with x265 at test time, so
nothing large is committed. One is a grid of 48 tiles of 512 by 512 at
8 bits 4:2:0, and one picture with wavefront rows at 10 bits 4:2:0, both near 2.8 MB. It reports the
fastest of five decodes to RGB after two to warm up, and libde265 1.0.15's `dec265 -q -t 0`, one
thread, on the same Annex B stream, fastest of three, starting the tool included and writing no
output. The dec265 column is from the JVM run; the other runs read within 100 ms of it. The machine:
a 4-core Intel Xeon at 2.10 GHz (KVM), one thread unless the column says four.

| Picture | dec265 | JVM | JVM, 4 threads (#152) | Peak heap, JVM | linuxX64 release | Peak resident, linuxX64 | JS (Node 22) | wasmJs release | wasmJs debug |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Grid of 8x6 tiles, 8 bits | 614 ms | 1,469 ms | 584 ms | 188 MB | 1,505 ms | 102 MB | 2,363 ms | 4,513 ms | 6,214 ms |
| One picture with WPP, 10 bits | 1,409 ms | 1,447 ms | | 144 MB | 1,416 ms | 128 MB | 2,111 ms | 4,500 ms | 6,115 ms |

The JVM peak is the growth of the heap during the first measured decode, and the linuxX64 peak that
of the resident memory, as `JxlBenchmarkTest` and `JxlNativeBenchmarkTest` read them. The ARGB
result alone is 50 MB and the YUV planes 38 MB. The decoder reads the wavefront rows of a picture one
after another, so a single picture gains nothing from a tile runner. On four threads the grid's
peak heap is 194 MB, 6 MB more than on one: a tile at a time for each thread.
The tests fail past 30 seconds or 1,500 MB on the JVM and linuxX64, and past 120 seconds on JS and
wasmJs: an order of magnitude, which catches a regression and not a loaded machine. On JS, wasmJs
and linuxX64 the benchmark skips, printing so, where ffmpeg has no libx265, as on CI's runners for
those targets.

### Encoding

`encodeHeic` writes intra HEVC still images only (#174): no image sequence, depth map, gain map,
premultiplied alpha, limited range, HDR transfer or matrix other than BT.601 (identity when lossless).
HEVC holds 12 bits at most, so `encodeHeic16` rounds to 8, 10 or 12 bits. A side may be 65535 at most
and a grid 256 by 256 tiles; a single picture (`tileSize = null`) is held to HEVC's levels.

`HeicEncodeBenchmarkTest` (run with `IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1`) encodes the 4000 by 3000
landscape of the photo-like test scenes at quality 50 and 80, effort 2, 8-bit 4:2:0 in tiles of 512,
on one thread and with a tile runner on a pool of four, which writes the same bytes. libheif's
`heif-enc` (x265, its own default threads and its own quality scale) is beside it for scale. The score
is SSIMULACRA 2 of the decoded file against the scene. The machine: a 4-core Intel Xeon at 2.30 GHz.

| Encoder | Quality | Threads | Size | Bits a pixel | Time | SSIMULACRA 2 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| ImageKodec | 50 | 1 | 140,868 B | 0.094 | 15.9 s | 71.00 |
| ImageKodec | 50 | 4 | 140,868 B | 0.094 | 4.1 s | 71.00 |
| ImageKodec | 80 | 1 | 3,327,078 B | 2.218 | 77.4 s | 82.74 |
| ImageKodec | 80 | 4 | 3,327,078 B | 2.218 | 20.1 s | 82.74 |
| heif-enc 1.17.6 (x265) | 50 | its own | 664,519 B | 0.443 | 3.0 s | 77.01 |
| heif-enc 1.17.6 (x265) | 80 | its own | 5,383,163 B | 3.589 | 5.8 s | 83.44 |

The two quality scales differ, so the rows do not compare at equal quality; `HevcEncoderBenchmarkTest`
compares the HEVC encoder with x265 at equal score. On one thread, ImageKodec takes 5 and 13 times as
long as heif-enc with its own threads; with four threads, 1.4 and 3.5 times as long.

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

### Speed and memory

The decoder keeps every channel of a frame as a full plane of `Float` samples, so it needs about 50
bytes of heap for each pixel at its peak. `JxlBenchmarkTest` measures a 3000 by 2000 picture on the
JVM, with 4 cores and libjxl 0.7:

| File | Bytes | djxl | ImageKodec | Peak heap |
| --- | ---: | ---: | ---: | ---: |
| Lossy, `-d 1` | 769,489 | 2,002 ms | 1,296 ms | 320 MB |
| Lossless, `-d 0` | 11,800,802 | 971 ms | 797 ms | 238 MB |

The djxl times include starting the tool and writing its output. A 6-megapixel lossy photo
therefore needs about 300 MB of heap to decode, which is more than many Android apps get.

`JxlNativeBenchmarkTest` decodes the same two files on linuxX64. It ran on a 4-core Intel Xeon at
2.3 GHz with libjxl 0.7, in the release test build (`-v release`) and in the debug one, next to
the JVM test on the same machine and day. Its peak is how far the resident memory of the process
grew during the decode.

| File | djxl | linuxX64 release | linuxX64 debug | JVM | Peak resident, linuxX64 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Lossy, `-d 1` | 1,748 ms | 812 ms | 3,469 ms | 1,003 ms | 232 MB |
| Lossless, `-d 0` | 806 ms | 579 ms | 3,825 ms | 670 ms | 178 MB |

An optimized native build decodes about as fast as the JVM, and a debug build four to six times
slower.

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

## JPEG XR

- CMYK, the N-channel formats and RGBE (32bppRGBE) throw `UnsupportedImageException`, and so do
  pixel formats that T.832 does not list. `probe` names the format in `unsupportedReason`.
- An image may hold 2^30 samples over all of its channels, and a macroblock row of all its
  channels may hold 2^30 samples, so a 128-bit format has to be smaller than the shared ceiling.
- `decodeReduced` samples the thumbnails of jxrlib rather than averaging, and where jxrlib would
  round a reduced side otherwise than `decodeReduced` promises, it decodes the full image and
  averages it. A reduction of a sixteenth or less, jxrlib's DC-only decode, is not offered.
- A region of interest, jxrlib's post-processing filters and the codestream's own orientation
  field are not read. Like jxrlib, which follows Windows Vista, the orientation comes from the
  container's Transformation tag alone.
- A file whose image codestream runs past its end fails, and so does a tile layout that starts a
  tile nowhere. JxrEncApp writes the size of the whole file as the size of a planar alpha plane,
  so that size is not held to the file.
- The encoder (#181) takes at most 2^28 pixels, writes at most 4096 tiles across and down, and keeps
  about two working planes of `Int` a channel besides the bitmap. It writes soft tiles only, of one
  square size, 4:4:4 or 4:2:0 chroma, and the gray, BGR, BGRA, 16-bit gray, RGB and RGBA formats.
  16-bit and lossless encodes keep 4:4:4. With two levels of overlap, 4:2:0 needs an image two
  macroblocks wide, as jxrlib does, so a narrower one gets one level.

## Orientation

`probe` reports the orientation that a file declares: the EXIF tag of a JPEG or TIFF, the rotation
and mirror of an AVIF, the header of a JPEG XL and the Transformation tag of a JPEG XR. `decode` applies it only when you pass
`applyOrientation = true`. The Compose binding applies it for you.
