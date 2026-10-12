# Color management

A decode gives the samples as the file stores them. A Display P3 photo or an Adobe RGB scan then
draws dull on a screen that shows sRGB, unless something converts it as a browser does. ImageKodec
can do that conversion for you.

## What the file declares

`ImageInfo.colorProfile` reports what the file declares about its color space: the embedded ICC
profile in any format, byte for byte, and the `sRGB`, `gAMA`, `cHRM` and `cICP` chunks of a PNG.
It is null when the file declares nothing, which for nearly every image means sRGB.

```kotlin
val info = ImageKodec.probe(bytes)
info.colorProfile?.iccDescription    // "Display P3", "Adobe RGB (1998)", ...
info.colorProfile?.icc               // the profile's bytes
```

## Convert to sRGB

Every decode function takes a `colorTarget`. The default, `ColorTarget.Source`, keeps the samples
as stored. `ColorTarget.Srgb()` converts through the embedded profile of the file:

```kotlin
val bitmap = ImageKodec.decode(bytes, colorTarget = ColorTarget.Srgb())
val exact = ImageKodec.decode16(bytes, colorTarget = ColorTarget.Srgb(RenderingIntent.RelativeColorimetric))
```

Two more functions convert pixels that came from somewhere else, such as an image inside a PDF
whose profile the PDF carries:

```kotlin
val again = ImageKodec.convertToSrgb(bitmap, profile)
val ink = ImageKodec.convertCmykToSrgb(width, height, cmyk, profile)   // CMYK samples, 0 is no ink
```

`convertCmykToSrgb` returns null when the profile is not a CMYK profile that ImageKodec can apply.

The Compose and Coil bindings draw for a person, so they convert to sRGB by default.
`KiteImage(colorTarget = ColorTarget.Source)` and
`KiteImageDecoder.Factory(colorTarget = ColorTarget.Source)` keep the samples as stored.

## Which profiles convert

Every kind of input profile converts as lcms2 converts it, to within one level:

- matrix and TRC profiles, v2 and v4, with every curve type
- gray profiles
- profiles built from lookup tables (`lut8`, `lut16` and `lutAToB`, with a Lab or XYZ connection
  space), as CMYK printer profiles and many camera profiles are

The conversion is perceptual by default, as browsers and the lcms2 tools convert, with black point
compensation where lcms2 applies it. `RenderingIntent` picks another intent.

A CMYK or YCCK JPEG and a CMYK TIFF convert from their ink through their profile. A decode without
a target draws that ink with the usual naive formula instead.

A file deeper than 8 bits converts at full precision before an 8-bit decode narrows it.

A file that declares nothing, declares sRGB, or carries a profile for another color space than its
own comes back untouched, as browsers leave it.

## Color information outside ICC

PNG's own chunks convert too, in the order of the PNG third edition: `cICP`, then `iCCP`, then
`sRGB`, then `gAMA` with `cHRM`. When `gAMA` or `cHRM` stands alone, the missing half means the
primaries or the curve of sRGB, as Chrome reads them.

An AVIF converts through its `nclx` code points after its profile.

A JPEG XL file converts through its profile, or through the color encoding that its header names.
Without a target, a lossy JPEG XL file still converts from its internal XYB space to that encoding.
See [Limits](limits.md#jpeg-xl) for the cases this does not cover.

## HDR

HDR pictures (PQ or HLG) are tone-mapped to SDR as the libplacebo library of mpv maps them:

- 203 cd/m² diffuse white (BT.2408) becomes sRGB white.
- Highlights roll off with the curve of BT.2390, from the peak of the content. The peak comes from
  `cLLi` or `clli`, else from the mastering display, else it is 1000 cd/m².
- HLG goes through the OOTF of BT.2100 for a 1000 cd/m² display.
- A color that sRGB cannot show desaturates toward its own luminance instead of clipping.
