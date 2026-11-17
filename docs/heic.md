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

- The HEVC pictures of the Main, Main 10, Main Still Picture and format range extensions profiles
  that use no range extension coding tool: 4:0:0, 4:2:0, 4:2:2 and 4:4:4, at 8 to 12 bits, with
  scaling lists, transform skip, lossless coding, PCM, wavefront rows, tiles, several slices and
  dependent slice segments, deblocking and sample adaptive offset.
- Support follows the fields of the parameter sets, not the profile number, so a file whose
  profile says more than it uses still decodes.
- The HEIF structure that the core reads for AVIF too: grids, overlays, identity items, alpha, the
  clean aperture, rotation and mirror, EXIF, XMP and ICC metadata, thumbnails, the pages of a file
  with several images, stereo pairs and Motion Photos.

## Platforms

The module builds for the same 22 targets as the core. CI runs its tests on the JVM, linuxX64, the
Apple targets of the core's matrix, JS, wasmJs and wasmWasi.

## Patents

HEVC is covered by patents that are licensed through patent pools. As of 15 December 2025, Access
Advance administers the former Via LA HEVC/VVC program as well as its own. ImageKodec's Apache-2.0
licence grants no patent rights. A product that ships this module must check its own position. This
page states facts and gives no legal advice.
