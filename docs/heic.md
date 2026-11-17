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

`install()` registers the HEVC codec with the core. A second call changes nothing. After it, every
entry point of `ImageKodec` reads HEIC like any other format.

The module is not published yet. Its publishing waits on the owner's decision about the patents
on HEVC, so the coordinates above do not resolve until a release names them.

## Without the module

The core still recognises a HEIC file. `ImageKodec.detect` returns `ImageFormat.HEIC`, and `probe`
reports its size, bit depth, alpha, orientation, thumbnails and pages from the container. Its
`isDecodable` is false, and `unsupportedReason` names `imagekodec-heic`. `decode` throws
`UnsupportedImageException`. `readMetadata` returns the EXIF, XMP and ICC profile, and `motionPhoto`
finds the video of a Motion Photo, with or without the module.

## What decodes

- The HEVC pictures of the Main, Main 10, Main Still Picture and format range extensions profiles:
  4:0:0, 4:2:0, 4:2:2 and 4:4:4, at 8 to 16 bits, with scaling lists, transform skip, lossless
  coding, PCM, wavefront rows, tiles, several slices and dependent slice segments, deblocking and
  sample adaptive offset, and the coding tools of the range extensions: residual DPCM (implicit and
  explicit), cross-component prediction, extended precision, persistent Rice adaptation, CABAC bypass
  alignment, chroma QP offset lists, transform skip rotation, context and sizes above 4, disabled
  intra smoothing and scaled SAO offsets.
- P and B pictures as well as intra ones: the picture order count, short-term and long-term
  reference picture sets, reference picture lists with their modification, a decoded picture buffer
  with its output order, CRA and BLA pictures with their RASL pictures, merge mode, AMVP, temporal
  motion vector prediction, every partition mode with the asymmetric ones, fractional sample
  interpolation and default and explicit weighted prediction. Every stream of the HEVC_v1 and RExt
  conformance packages of H.265.1 decodes to its hash, in every picture.
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

## Platforms

The module builds for the same 22 targets as the core. CI runs its tests on the JVM, linuxX64, the
Apple targets of the core's matrix, JS, wasmJs and wasmWasi.

## Patents

HEVC is covered by patents that are licensed through patent pools. As of 15 December 2025, Access
Advance administers the former Via LA HEVC/VVC program as well as its own. ImageKodec's Apache-2.0
licence grants no patent rights. A product that ships this module must check its own position. This
page states facts and gives no legal advice.
