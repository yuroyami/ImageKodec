# HEIC

HEIC is the photo format of recent iPhones and of many Android phones: HEVC (ITU-T H.265)
pictures in the HEIF container (ISO/IEC 23008-12). The core of ImageKodec reads the container on
every platform. The HEVC decoder lives in a separate module, `imagekodec-heic`, so an app that reads
no HEIC carries none of its code.

## Install

Add the module beside the core, then install its codec once, before the first decode:

```kotlin
commonMain.dependencies {
    implementation("io.github.yuroyami:imagekodec:<version>")
    implementation("io.github.yuroyami:imagekodec-heic:<version>")
}
```

```kotlin
ImageKodecHeic.install()
val bitmap = ImageKodec.decode(heicBytes)
```

`install()` registers the HEVC codec with the core, and the HEVC encoder that `encodeHeic` uses. A
second call changes nothing. After it, every entry point of `ImageKodec` reads HEIC like any other
format.

The module is on Maven Central from 0.4.0 on. HEVC is covered by patents, so read
[Patents](#patents) before you ship it.

## Without the module

The core still recognises a HEIC file. `ImageKodec.detect` returns `ImageFormat.HEIC`, and `probe`
reports its size, bit depth, alpha, orientation, thumbnails and pages from the container. Its
`isDecodable` is false, and `unsupportedReason` names `imagekodec-heic`. `decode` and `encodeHeic`
throw `UnsupportedImageException`. `readMetadata` returns the EXIF, XMP and ICC profile, and `motionPhoto`
finds the video of a Motion Photo, with or without the module.

## What decodes

- The HEVC pictures of the Main, Main 10, Main Still Picture, format range extensions and screen
  content coding extensions profiles, in 4:0:0, 4:2:0, 4:2:2 and 4:4:4 at 8 to 16 bits.
- Scaling lists, transform skip, lossless coding, PCM, wavefront rows, tiles, several slices,
  dependent slice segments, deblocking and sample adaptive offset.
- The coding tools of the range extensions: residual DPCM, cross-component prediction, extended
  precision, persistent Rice adaptation, CABAC bypass alignment, chroma QP offset lists, transform
  skip rotation, context and sizes above 4, disabled intra smoothing and scaled SAO offsets.
- P and B pictures as well as intra ones: the picture order count, short-term and long-term
  reference picture sets, reference picture lists with their modification, a decoded picture buffer
  with its output order, CRA and BLA pictures with their RASL pictures, merge mode, AMVP, temporal
  motion vector prediction, every partition mode with the asymmetric ones, fractional sample
  interpolation and default and explicit weighted prediction. Every picture of the HEVC_v1 and RExt
  conformance streams of H.265.1 matches its hash SEI where it has one, and the output of each
  stream matches the MD5 its package gives.
- The coding tools of the screen content coding extensions (#188): palette mode with its predictor
  palette, the predictor initializers of the SPS and the PPS, escape samples and transposed index
  maps; current picture referencing (intra block copy), whose vectors are in whole samples, with
  `use_integer_mv_flag` of motion vector resolution control; the adaptive colour transform with its
  QP offsets; and disabled intra boundary filtering. Every picture of the 15 SCC conformance streams
  matches its hash SEI where it has one, and each stream the MD5 of its package, so all 211 streams
  of the HEVC_v1, RExt and SCC packages match.
- Three separate colour planes (`separate_colour_plane_flag`), each decoded as a monochrome picture
  of its own with motion of its own, which together give a 4:4:4 picture (#188).
- HEVC image sequences (brands `msf1`, `hevc`, `hevx`): `decodeAnimation` returns every frame of a
  track whose sample entry is `hvc1` or `hev1` and whose handler is `pict` or `vide`, as ffmpeg writes
  an MP4. The frames come in presentation order, the order of the composition times of `ctts`, which
  for B pictures is not the order they decode in. Each frame has its duration and the loop count
  follows the edit list, as for AVIF, and an alpha track tied to the colour track through `auxl`
  applies to each frame. `probe` reports the frame count and the size (#157).
- Support follows the fields of the parameter sets, not the profile number, so a file whose
  profile says more than it uses still decodes.
- Which of a track and the primary item a file shows follows libavif: the major brand `avif` shows
  the primary item, `avis` the track, and any other brand, `heic` and `msf1` among them, the track
  when the file has one.
- The HEIF structure that the core reads for AVIF too: grids, overlays, identity items, alpha, the
  clean aperture, rotation and mirror, EXIF, XMP and ICC metadata, thumbnails, the pages of a file
  with several images, stereo pairs, Motion Photos, auxiliary images and gain maps.

## Pages, stereo pairs and thumbnails

Each top-level image of a HEIF file is a page, the primary first, as each directory of a TIFF is.
An Apple spatial photo stores one image for each eye and names the two in a `ster` group:

```kotlin
val info = ImageKodec.probe(bytes)
if (info.stereoLeftPage >= 0) {
    val left = ImageKodec.decodePage(bytes, info.stereoLeftPage)
    val right = ImageKodec.decodePage(bytes, info.stereoRightPage)
}
```

`decodeThumbnail` gives one of the thumbnails a camera stores beside the photo, without decoding
the photo itself. [Resizing](resizing.md#use-the-thumbnail-the-file-carries) has the details.

## Depth maps, mattes and gain maps

A portrait photo carries a depth map, and often mattes, as auxiliary images of the primary image.
`auxiliaryImages` lists them without decoding a picture, and `decodeAuxiliaryImage` decodes one:

```kotlin
val list = ImageKodec.auxiliaryImages(bytes)
val i = list.indexOfFirst { it.urn == "urn:mpeg:hevc:2015:auxid:2" }    // HEVC depth
if (i >= 0) {
    val depth = ImageKodec.decodeAuxiliaryImage(bytes, i)   // one channel, 16 bits
    val v = depth[x, y, 0] / 257                             // the 8-bit coded sample
}
```

A sample keeps its coded value, widened to 16 bits: an n-bit `v` becomes `v * 65535 / (2^n - 1)`,
rounded, with no range stretch and no clean aperture, rotation or mirror. What a depth sample
measures is in the entry's `subtype` bytes, which carry a HEVC depth image's depth representation
information SEI message unparsed, and in Apple's XMP.

`decodeGainMap` returns the HDR gain map: a `tmap` item's gain map image with its ISO 21496-1
metadata, or an Apple photo's `urn:com:apple:photo:2020:aux:hdrgainmap` image with MakerNote tags
33 and 48 of its EXIF. Apple's headroom formula needs both tags; `pug.heic`, written by iOS 16.2, has
only tag 33, so its `appleHeadroom` is null. The map is returned, not applied.

## Speed and threads

A 12-megapixel photo decodes in about 1.4 seconds on one core of a 2.1 GHz Xeon, on the JVM and as
a native release build; [Limits](limits.md#speed-and-memory) has the measured table beside
libde265. The decoder starts no threads. A phone photo is a grid of tiles that decode
independently, and a caller can hand them to its own threads with one runner, set once:

```kotlin
val pool = Executors.newFixedThreadPool(4)
ImageKodec.setTileRunner { jobs -> jobs.map { pool.submit(it::run) }.forEach { it.get() } }
```

Each job decodes one tile with a codec session of its own and copies it into the picture at once,
so a decode holds the picture and one tile for each thread. The result and the error do not depend
on the runner: of several tiles that fail, the first in tile order gives the exception. The same
runner decodes AVIF grids, and a reduced decode (`decodeScaled`, `decodeDownscaledTo`) gives it one
row of tiles at a time, which keeps its memory bound. On four threads the 48-tile grid of the
benchmark decodes in 584 ms where one thread takes 1,469 ms. JS and Wasm have no threads, so the
default runner, `TileRunner.Serial`, is the one to keep there.

## Writing HEIC

`ImageKodec.encodeHeic` writes a HEIC file (#174). The core writes the HEIF container and converts
RGB to planes; the module's intra HEVC encoder, which `install()` registers beside the decoder, codes
each picture. The encoder is written from ITU-T H.265: one IDR picture of one I slice, with the
decoder's own reconstruction, deblocking and SAO.

```kotlin
ImageKodecHeic.install()
val heic = ImageKodec.encodeHeic(bitmap, quality = 60)
val options = HeifEncodeOptions(quality = 80, chroma = HeifChroma.Yuv444, thumbnailSize = 320, orientation = Orientation.Rotate90)
val withMetadata = ImageKodec.encodeHeic(bitmap, options, ImageKodec.readMetadata(original))
val tenBits = ImageKodec.encodeHeic16(bitmap16, HeifEncodeOptions(quality = 70))
```

`HeifEncodeOptions` holds every setting, each with a default:

| Option | Default | What it does |
| --- | --- | --- |
| `quality` | 50 | 0 to 100. The QP is 46 less half the quality, rounded up, and at least 1: 46 at 0, 21 at 50, 6 at 80, 1 from 90 on. Equal quality gives about the SSIMULACRA 2 score of libheif's `heif-enc -q` (x265), as `encodeAvif`'s follows `avifenc -q`; see below. |
| `lossless` | false | Codes RGB with the identity matrix in 4:4:4, or gray in 4:0:0, so `decode` returns the bitmap exactly. |
| `chroma` | `Yuv420` | The chroma subsampling of a lossy colour picture: `Yuv420`, `Yuv422` or `Yuv444`. |
| `bitDepth` | 8, or 10 from `encodeHeic16` (12 lossless) | 8, 10 or 12 bits a sample. |
| `effort` | 2 | 1 (fast) to 3 (thorough): how many intra modes and transform splits the encoder tries. |
| `tileSize` | 512 | A picture wider or taller than this is a `grid` of tiles of this size. Null writes one picture. |
| `thumbnailSize` | null | The longest side of a thumbnail item, scaled down from the picture. |
| `orientation` | `Normal` | Written as `irot` and `imir`, so a reader shows `bitmap.oriented(orientation)`. |

What the file holds:

- `ftyp` names the brand of the primary picture's profile (ISO/IEC 23008-12 Annex B: `heic` for
  8-bit 4:2:0, `heix` for the others), then `mif1`, the brands of the other pictures, and `miaf`.
- Colour converts with BT.601's matrix in full range and is signalled as sRGB (`colr` `nclx` 1, 13,
  6, full range), as libheif and libavif write it, in the `nclx` box and in the HEVC VUI. With an ICC
  profile in the metadata, the profile goes in a `colr` `prof` box, and the `nclx` box leaves the
  primaries and transfer unspecified. A gray bitmap codes luma alone (4:0:0).
- 4:2:0 chroma is the mean of each 2 by 2 block, centred between its luma samples, where the
  decoder's bilinear upsampling expects it. A subsampled picture of odd size codes one more column
  or row, repeating the last, and a `clap` box crops it.
- A bitmap with any alpha below 255 gets an alpha auxiliary image: a 4:0:0 picture in full range,
  straight, with `auxC` `urn:mpeg:hevc:2015:auxid:1` and an `auxl` reference, coded at the same
  quality. A grid's alpha is a grid of the same layout.
- A picture wider or taller than `tileSize` is a `grid` item whose tiles are hidden `hvc1` items of
  `tileSize` by `tileSize`, the edge tiles padded by repeating the last row and column. The tiles
  share one `hvcC`, `ispe`, `pixi` and `colr` in `ipco`. They go through the tile runner of
  `setTileRunner`, so a pool of threads encodes them in parallel, and the bytes do not depend on
  the runner.
- Every image item has `ispe` and `pixi`; `irot` and `imir` follow, marked essential, in MIAF's
  order. A thumbnail item is tied to the image by `thmb`, the EXIF and XMP items by `cdsc`.

`encodeHeic16` keeps up to 12 bits, the most the encoder codes. A lossless file gives back through
`decode16` exactly every value that a file of its depth can give, such as what `decode16` read from
one. Other 16-bit values move to the nearest of those: `decode16` widens a coded sample into the
8-bit level that `decode` rounds it to, so a 12-bit file gives 3,366 distinct values, not 4,096.

`quality` means what it means to `heif-enc -q` of libheif 1.17.6 with x265 (#176). `HeicQualityTest` (run with
`IMAGEKODEC_HEIC_ENCODE_BENCHMARK=1`) codes the six photo-like test scenes in 8-bit 4:2:0 with both and
compares the mean SSIMULACRA 2 score at each tenth quality. The QP that matched heif-enc's score at qualities
0 to 80 was 46.4, 41.2, 36.4, 31.6, 26.2, 20.9, 16.2, 11.7 and 7.1, which the mapping above follows to within
1.1; above 80 both scores level off near 91. With it:

| Quality | `encodeHeic` bits a pixel | Its mean score | `heif-enc` bits a pixel | Its mean score |
| ---: | ---: | ---: | ---: | ---: |
| 10 | 0.073 | 18.26 | 0.063 | 16.91 |
| 30 | 0.153 | 60.39 | 0.143 | 58.56 |
| 50 | 0.646 | 80.47 | 0.687 | 80.58 |
| 70 | 2.610 | 89.79 | 2.640 | 89.50 |
| 90 | 4.678 | 91.65 | 4.893 | 91.48 |

From 20 to 90 the two mean scores stay within 1.9 of each other. Before #176 the QP was 51 less half the quality,
and quality 50 gave 0.270 bits a pixel at a score of 71.6, where heif-enc gives 80.6.

The output is the same on every platform. `HeicEncodeOracleTest` checks it against libheif 1.17.6
(`heif-info` lists the structure; `heif-convert` gives the same RGB within one level on 4:4:4 and
4:0:0), libde265 and ffmpeg (each coded item decodes to the same samples) and `exiftool` (EXIF,
XMP and ICC). On 4:2:0 and 4:2:2, heif-convert repeats each chroma sample where ImageKodec
interpolates, so the two differ by up to 17 levels at colour edges and by 0.3 to 1.5 on average.

Not written: image sequences (the HEVC side is below, the tracks are #177's other half), depth maps,
gain maps, premultiplied alpha, limited range, other matrices or primaries than sRGB's and HDR
transfer functions. A grid holds at most 256 by 256 tiles, and a side of 65535 is the most a reader
accepts.

### HEVC sequences

The module also codes sequences of P and B pictures (#177), for the image sequence tracks that the
core's HEIF writer adds. The entry point is internal: `HevcHeifEncoder.encodeSequence(frames,
durations, parameters, sequence, cancellationCheck)` takes the planes of each frame, all of one size
and format, with each frame's duration, and returns a `HevcSequence`:

| Field | What a track writer does with it |
| --- | --- |
| `hvcC` | The `hvcC` box of the `hvc1` sample entry. The parameter sets are there only, not in the samples. |
| `samples` | One `HevcSample` per access unit, in decoding order: `data` (the NAL units behind 4-byte lengths) for `mdat`, `stsz` and `stco`; `sync` for `stss`; `duration` for `stts`; `compositionOffset` for `ctts`; `frame`, the frame's index in presentation order. |
| `reordered` | True when a sample's composition offset is not 0, so the track needs `ctts`. |
| `presentationOffset` | The composition time of the first frame: the `media_time` of the edit list that shows it at time 0. 0 without B pictures. |
| `width`, `height` | The decoded size, as `encode` gives it; a `clap` crops the padding of odd subsampled sizes. |
| `profile` | `HevcHeifEncoder.sequenceBrand` maps it to `hevc` (Main) or `hevx` (Main 10 and the range extensions profiles). |

Decoding times are the presentation times in order, and each composition time is its frame's, later
by the most that reordering delays any frame, so no composition offset is negative and the decoding
durations add up to the presentation's. Alpha is a second sequence of the alpha planes as 4:0:0,
coded the same way.

What the stream holds:

- An IDR picture first, then groups of `gopSize` frames (4 by default): the last frame of a group is
  a P picture, and the frames before it B pictures of a dyadic pyramid, coded after it, the middle
  one first. A group of 1 codes IPPP. `irapPeriod` starts another IDR picture every so many frames,
  and no picture refers across one, so each period decodes on its own.
- Each picture refers to the nearest reference pictures on each side: one in each list, two at
  effort 3. The reference picture sets keep every picture a later one needs; the SPS holds each
  distinct set once, and `sps_max_dec_pic_buffering_minus1` and `sps_max_num_reorder_pics` come from
  running the plan through C.5.2's buffer. The level takes the buffer into account (A-2).
- P and B pictures code their QP 3 above an IDR picture's, and B pictures 4 more plus their depth in
  the pyramid, so the pictures others refer to keep more detail. Temporal motion vector prediction is
  on, from the nearest later picture in a B slice. Main and Main 10 replace the still picture profiles.
- Each coding unit is weighed as an inter unit and, where it pays, an intra one: the merging
  candidates of least SATD, skipped and with a residual; AMVP, whose motion search runs a square
  pattern over whole samples by SAD from up to two AMVP predictors and the zero vector, then half and
  quarter samples by SATD through the decoder's own interpolation, in one list or both; and 2NxN,
  Nx2N and, at effort 3, the asymmetric partitions of the direction whose symmetric split won. The
  residual goes through the intra encoder's transforms, RDOQ and transform skip, with a rounding
  offset of a sixth instead of a third where RDOQ is off.
- The reconstruction runs through the decoder's own merging candidates, AMVP, reference lists,
  decoded picture buffer and interpolation, so the encoder decides on what a decoder makes.

`HevcInterOracleTest` codes 84 sequences over sizes from 8x8 to 130x66, the four chroma formats, 8, 10
and 12 bits, groups of 1 to 8, periodic IDR pictures, the three efforts and lossless coding. libde265
(`dec265 -c`) and ffmpeg (`-err_detect crccheck`) check every picture's MD5 hash SEI and decode every
stream to the encoder's reconstruction, sample for sample; lossless sequences give their input back.
HM's decoder is not installed where this was written, so it did not run. The decoder counts the tools
the streams use: skipped, merged and AMVP units, bi-prediction, intra units in P and B pictures and
every partition.

`HevcInterBenchmarkTest` (run with `IMAGEKODEC_HEVC_INTER_BENCH=1`) compares the size of the slice data
at an equal mean SSIMULACRA 2 score over 24 frames of 384 by 256 in 8-bit 4:2:0 against x265 3.5
(`ffmpeg -c:v libx265 -preset medium`, its own GOP of B frames, and again with `-tune ssim`, which turns
its psycho-visual tuning off). The scenes pan over a photo-like landscape by 1.5 and 0.5 samples a
frame, move two objects over a still city, and pan with sensor grain that changes every frame.

| Scene | Setting | Bytes | Mean score | ms | x265 medium (ratio) | x265 `-tune ssim` (ratio) |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| pan | quality 50, effort 2, groups of 4 (default) | 3530 | 78.24 | 5035 | 7134 (0.495) | 7334 (0.481) |
| pan | quality 75, effort 2, groups of 4 | 46744 | 88.30 | 8324 | 60015 (0.779) | 64275 (0.727) |
| pan | quality 50, effort 1, groups of 4 | 6051 | 77.59 | 979 | 6472 (0.935) | 6557 (0.923) |
| pan | quality 50, effort 3, groups of 4 | 3568 | 78.36 | 60058 | 7299 (0.489) | 7490 (0.476) |
| pan | quality 50, effort 2, IPPP | 4871 | 77.89 | 2783 | 6696 (0.727) | 6900 (0.706) |
| pan | quality 50, effort 2, groups of 8 | 3363 | 78.10 | 2658 | 6951 (0.484) | 7161 (0.470) |
| objects | quality 50, effort 2, groups of 4 (default) | 12974 | 81.42 | 5141 | 24608 (0.527) | 25244 (0.514) |
| objects | quality 75, effort 2, groups of 4 | 54910 | 87.93 | 6390 | 75067 (0.731) | 76153 (0.721) |
| objects | quality 50, effort 1, groups of 4 | 20511 | 81.01 | 1244 | 23717 (0.865) | 24353 (0.842) |
| objects | quality 50, effort 3, groups of 4 | 12546 | 81.51 | 66100 | 24817 (0.506) | 25453 (0.493) |
| objects | quality 50, effort 2, IPPP | 13648 | 81.42 | 3528 | 24609 (0.555) | 25245 (0.541) |
| objects | quality 50, effort 2, groups of 8 | 12974 | 81.07 | 4610 | 23849 (0.544) | 24485 (0.530) |
| grain | quality 50, effort 2, groups of 4 (default) | 9366 | 70.98 | 2869 | 14245 (0.657) | 13651 (0.686) |
| grain | quality 75, effort 2, groups of 4 | 625051 | 87.42 | 53566 | out of range | out of range |
| grain | quality 50, effort 1, groups of 4 | 11779 | 70.34 | 753 | 11257 (1.046) | 11400 (1.033) |
| grain | quality 50, effort 3, groups of 4 | 9801 | 71.83 | 62007 | 19422 (0.505) | 17687 (0.554) |
| grain | quality 50, effort 2, IPPP | 10836 | 70.55 | 3388 | 12175 (0.890) | 12105 (0.895) |
| grain | quality 50, effort 2, groups of 8 | 9248 | 70.85 | 3346 | 13616 (0.679) | 13187 (0.701) |

| Setting | Geometric mean ratio, x265 medium | x265 `-tune ssim` |
| --- | ---: | ---: |
| quality 50, effort 2, groups of 4 (default) | 0.556 | 0.554 |
| quality 50, effort 1, groups of 4 | 0.946 | 0.930 |
| quality 50, effort 3, groups of 4 | 0.500 | 0.507 |
| quality 50, effort 2, IPPP | 0.711 | 0.699 |
| quality 50, effort 2, groups of 8 | 0.563 | 0.559 |
| quality 75, effort 2, groups of 4 (two scenes) | 0.755 | 0.724 |

The x265 columns hold x265's size at this encoder's score, interpolated between its constant rate
factors; times are on one core of the JVM, x265's with its own threads and SIMD. The QP offsets come
from the same benchmark: with none, groups of 4 measured 0.74 of x265's size, with 1 and 1 0.62, with
2 and 2 0.59, with 3 and 4 0.56, and with 5 and 5 0.56. The test holds the default to a ratio of
0.75, which catches a regression of about a third. At quality 75 the grain scene scores above
anything x265 reached, so that row has no ratio. Effort 3 takes 12 to 20 times effort 2's time for
about a tenth less size, and effort 1 a quarter of it for about two thirds more. SSIMULACRA 2 is not
x265's target, but `-tune ssim`, which turns its psycho-visual tuning off, moves the ratios by less
than 0.04. The scenes are synthetic and short; no camera footage was measured.

`encodeHeic(animation, options)` is in place for image sequences (#177): the core writes the track as it
does for an animated AVIF, and asks the `hvc1` encoder for a sequence encoder through
`HeifEncoder.openSequence`. The module's sequence encoder keeps every picture until the last, as the
B pictures of a group refer to the pictures after them, then codes them with `encodeSequence` and hands
the samples back in decoding order, each naming the picture it codes. The core writes the composition
offsets (`ctts`) that reordered pictures need, and `keyFrameInterval` becomes the IDR period. Without
the module the call throws `UnsupportedImageException` that names the module, as `encodeHeic` of a bitmap
does.

The file leads with the brand of its first picture, `heic` or `heix`, since that picture is also the
primary item, and lists `hevc` or `hevx` among the compatible brands: libheif 1.17 refuses a file whose
major brand is `hevc`. `HeicSequenceEncodeOracleTest` holds the files to ffmpeg, which must read every
frame of the track at its presentation time, and exactly in a lossless file, and to heif-convert, which
must read the first frame as the primary item.

## Platforms

The module builds for the same 22 targets as the core. CI runs its tests on the JVM, linuxX64, the
Apple targets of the core's matrix, JS, wasmJs and wasmWasi.

## Patents

HEVC is covered by patents that are licensed through patent pools. As of 15 December 2025, Access
Advance administers the former Via LA HEVC/VVC program as well as its own. ImageKodec's Apache-2.0
licence grants no patent rights. A product that ships this module must check its own position. This
page states facts and gives no legal advice.
