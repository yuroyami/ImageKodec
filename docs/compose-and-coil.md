# Compose and Coil

Two optional modules put ImageKodec on screen:

- `imagekodec-compose` gives the `KiteImage` composable, which decodes an image and plays it when
  it is animated.
- `imagekodec-coil` plugs ImageKodec into Coil 3 as a decoder, and gives `KiteAsyncImage`.

Both modules build for seven targets only: Android, `jvm`, `iosArm64`, `iosSimulatorArm64`,
`macosArm64`, `js` and `wasmJs`. Keep them out of any source set that includes another target, or
that source set fails to resolve them. On the web, Compose draws in a browser, so the two modules
are for browser apps.

Both depend on Compose Multiplatform `1.13.0-alpha02`, so dependency resolution can raise the
Compose version of an app to that release. Android apps that use them need `compileSdk` 37.1 or
newer.

## Show an image in Compose

```kotlin
import io.github.yuroyami.imagekodec.compose.KiteImage

KiteImage(
    data = bytes,
    contentDescription = "avatar",
    modifier = Modifier.size(96.dp),
    animate = true,                    // false decodes and shows only the first frame
    onError = { log(it) },             // malformed input draws nothing
)
```

The decode runs on `Dispatchers.Default` after the first layout, and runs again when `data`
changes. A still decodes at the size it draws at in that layout, as `contentScale` fits it, through
`ImageKodec.decodeDownscaledTo`: a JPEG, JPEG 2000 or JPEG XR reduces inside its inverse transform,
and a HEIC or AVIF grid one row of tiles at a time, so a large photo in a small slot never exists
at full size. A larger box later decodes it again at the new size; a smaller one keeps the pixels
it has. `ContentScale.None` decodes the full size, and so does a PNG, as before.

The decode stops once the composable leaves composition: a still at the decoder's next step, such
as a row of JPEG blocks or a HEIC tile, and an animation at the next frame. A list scrolled past
large images does not keep decoding them. Frames become `ImageBitmap`s on `Dispatchers.Default` too, ahead of playback,
so the thread that draws never copies a frame. The composable holds its layout slot and draws
nothing until the first frame is ready.

`KiteImage` applies the EXIF orientation, which `ImageKodec.decode` does not do by default. It also
converts the colors to sRGB as a browser does. Pass `colorTarget = ColorTarget.Source` to draw the
samples as stored.

## HEIC

The core reads HEIF files, and decodes AVIF on its own. HEIC holds HEVC pictures, whose decoder is
in the `imagekodec-heic` module. Neither binding depends on it: add it and call
`ImageKodecHeic.install()` once at startup, before the first decode. Without it, `KiteImage`
reports an `UnsupportedImageException` to `onError`, and `KiteImageDecoder.Factory` leaves the file
to the platform decoder of Coil.

Three more declarations are public, for code that runs its own decode:

- an overload of `KiteImage` that takes a `KiteBitmap`
- `KiteAnimatedImage`, which takes a `KiteAnimation`
- `KiteBitmap.toImageBitmap()`

## Use it with Coil

`imagekodec-coil` decodes the image instead of the platform decoder of Coil. Coil keeps network
fetching, disk and memory caching, and the request lifecycle.

```kotlin
val loader = ImageLoader.Builder(context)
    .components { add(KiteImageDecoder.Factory()) }
    .build()

KiteAsyncImage(
    model = "https://example.com/reaction.gif",
    contentDescription = null,
    imageLoader = loader,
)
```

Pass the loader to `KiteAsyncImage`. Without it, the composable uses the singleton loader of Coil,
which does not have `KiteImageDecoder`.

The module exports `coil-core` and the ImageKodec core, so their types are on your compile
classpath. `imagekodec-compose` and the other Coil artifacts arrive at runtime only, so declare
`imagekodec-compose` yourself when you call `KiteImage` or `KiteAnimatedImage` directly.

### Which files the decoder claims

`KiteImageDecoder.Factory` probes the first 64 KiB for supported features.

- For WebP, it walks the complete RIFF and frame chunk headers and skips the payloads, without a
  second copy of the whole file. It accepts VP8L frames and VP8 key frames, and it declines a file
  with any other image chunk, so Coil can choose its platform decoder.
- It claims TIFF and JP2 even when the probe fails. The IFD of a TIFF (the record that describes a
  page) is often further into the file than 64 KiB, and no platform decoder handles either format.
- For HEIC and AVIF whose `meta` box runs past 64 KiB, it reads the top-level boxes up to the end
  of that box, at most 16 MiB and never into `mdat`, and probes them. It claims the file when a
  codec reads its image: AVIF always, HEIC once `ImageKodecHeic.install()` has run.

The decoder converts every image to sRGB, as the platform decoders of Coil do.
`KiteImageDecoder.Factory(colorTarget = ColorTarget.Source)` keeps the samples as stored.

### Animations and the memory cache

`KiteAnimationImage` holds no playback state of its own, because the frame position lives in the
composable. Coil's memory cache holds an animation when its decoded frames fit under a limit of
64 MiB, and `KiteImageDecoder.Factory(maxCacheableAnimationBytes = ...)` changes that limit. A
larger animation decodes again from the disk cache instead of evicting everything else.

`ImageRequest.Builder.maxFrames(n)` decodes at most `n` frames of an animation, and `maxFrames(1)`
returns its first frame as a still. The count is part of the memory cache key, so a still never
answers a request for the animation. `KiteAsyncImage(animate = false)` asks for one frame this
way, so a grid of paused thumbnails decodes one frame of each. Turning `animate` on requests the
rest while that frame stays up.

### Sizing

`KiteAsyncImage` uses its layout constraints as the target size, or the original pixels for
`ContentScale.None`. `Fit` and `Inside` request `Scale.FIT`, and the other content scales request
`Scale.FILL`. A size and a scale set on the request take precedence. The decoder follows the sizing
rules of Coil, including one defined side and `maxBitmapSize`. It box-filters still images and
every animation frame to those dimensions, and it never upscales. A still goes through
`ImageKodec.decodeDownscaledTo` at that size, so a JPEG, JPEG 2000, JPEG XR or HEIC or AVIF grid
reduces while it decodes, and a cancelled request stops it at the decoder's next step.
