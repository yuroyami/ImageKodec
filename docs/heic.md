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

The module is not published yet. Its publishing waits on the owner's decision about the patents
on HEVC, so the coordinates above do not resolve until a release names them.

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
| `quality` | 50 | 0 to 100. The QP is 51 at 0, 26 at 50 and 1 at 100, straight between. |
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

The output is the same on every platform. `HeicEncodeOracleTest` checks it against libheif 1.17.6
(`heif-info` lists the structure; `heif-convert` gives the same RGB within one level on 4:4:4 and
4:0:0), libde265 and ffmpeg (each coded item decodes to the same samples) and `exiftool` (EXIF,
XMP and ICC). On 4:2:0 and 4:2:2, heif-convert repeats each chroma sample where ImageKodec
interpolates, so the two differ by up to 17 levels at colour edges and by 0.3 to 1.5 on average.

Not written: image sequences, depth maps, gain maps, premultiplied alpha, limited range, other
matrices or primaries than sRGB's, HDR transfer functions, and HEVC inter pictures. A grid holds at
most 256 by 256 tiles, and a side of 65535 is the most a reader accepts.

## Platforms

The module builds for the same 22 targets as the core. CI runs its tests on the JVM, linuxX64, the
Apple targets of the core's matrix, JS, wasmJs and wasmWasi.

## Patents

HEVC is covered by patents that are licensed through patent pools. As of 15 December 2025, Access
Advance administers the former Via LA HEVC/VVC program as well as its own. ImageKodec's Apache-2.0
licence grants no patent rights. A product that ships this module must check its own position. This
page states facts and gives no legal advice.
