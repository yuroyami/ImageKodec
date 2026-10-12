# Resizing and orientation

## Rotate, flip and crop

```kotlin
bitmap.rotated90(); bitmap.rotated180(); bitmap.rotated270()
bitmap.flippedHorizontal(); bitmap.flippedVertical()
bitmap.transposed(); bitmap.transversed()
bitmap.cropped(x = 10, y = 10, width = 64, height = 64)
bitmap.oriented(info.orientation)
```

`cropped` throws `IllegalArgumentException` when the rectangle extends outside the image. It does
not clamp the rectangle.

`oriented` applies an EXIF orientation that `probe` reported. `decode(bytes, applyOrientation =
true)` does the same in one step.

## Scale a decoded image

```kotlin
bitmap.scaled(maxWidth = 256, maxHeight = 256)
bitmap.downscaledTo(targetWidth = 128, targetHeight = 75)
```

`scaled` is an alpha-weighted box filter. It preserves the aspect ratio and never upscales. When
the image already fits the box, it returns the image unchanged.

`downscaledTo` uses the same filter with exact output dimensions. It leaves the aspect ratio to
the caller, and it throws `IllegalArgumentException` if a side is not positive or exceeds the
source.

Every geometry helper on this page also works on a whole `KiteAnimation`. It keeps the frame
delays and the play count.

## Decode straight to a smaller size

`scaled` works on a decoded image, so the full-size image exists first. To avoid that, decode
straight to the size you need:

```kotlin
ImageKodec.decodeScaled(bytes, maxWidth = 256, maxHeight = 256)   // the size scaled(256, 256) gives
ImageKodec.decodeDownscaledTo(bytes, width = 300, height = 200)   // exactly 300 by 200, as for a center crop
```

Each reads the header, decodes a JPEG or JPEG 2000 at the largest reduction whose output still
covers the target, and finishes with the same box filter. So the result is never smaller than you
asked for and never upscaled. A 6000 by 4000 JPEG asked for at 200 by 200 takes about a fifth of
the time of a full decode followed by `scaled`, and it allocates 2.6 MiB where that allocates
126 MiB. Other formats decode in full and filter once.

To pick the reduction yourself:

```kotlin
ImageKodec.decodeReduced(bytes, reduction = 4)  // each side divided by 4, rounded up
```

The reduction is 1, 2, 4 or 8.

- A JPEG shrinks inside its inverse DCT, as `djpeg -scale` of libjpeg does, so a baseline JPEG
  needs memory only for the smaller image. Each reduced pixel is the average of the full inverse
  DCT over the pixels it stands for. Subsampled chroma reduces across and down separately, so it
  keeps the detail that the smaller image can show.
- A progressive JPEG still keeps the coefficients of the full size until its last scan.
- A JPEG 2000 image drops its finest wavelet levels, as the reduce option of OpenJPEG does.
- Other formats decode in full, then average each block of pixels.
