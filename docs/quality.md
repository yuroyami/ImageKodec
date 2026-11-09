# Measuring quality

An app that shrinks photos needs to know whether the smaller file still looks the same.
`ImageKodec.ssimulacra2` scores a distorted image against its source with SSIMULACRA 2, the
perceptual metric of the JPEG XL project:

```kotlin
val source = ImageKodec.decode(original)
val candidate = ImageKodec.decode(ImageKodec.encodeJpeg(source, quality = 70))
val score = ImageKodec.ssimulacra2(source, candidate)
```

The scale runs to 100, which means identical. The reference's README reads it like this:

| Score | Quality |
| --- | --- |
| 90 | Visually lossless: no difference in a flip test at 1:1 |
| 85 | Excellent: no difference in a flicker test at 1:1 |
| 80 | Very high: no difference side by side at 1:1 |
| 70 | High: artifacts hard to notice without the original |
| 50 | Medium, about a JPEG of quality 50 with 4:2:0 |
| 30 | Low, about a JPEG of quality 25 |
| below 30 | Very low. A score can go below 0. |

## How it works

The call is a port of `tools/ssimulacra2.cc` of libjxl:

- Both images go to linear sRGB. A translucent pixel is first blended onto a mid gray, as the
  reference blends it.
- The linear values go to XYB, the colour space of JPEG XL, rescaled to about 0..1.
- At up to six scales, each half the size of the one before and downsampled in linear light, it
  compares local means, variances and edges of the two images.
- The 108 sums are weighed with the reference's weights and mapped to the score.

The two bitmaps must be of one size, or the call throws `IllegalArgumentException`. An image
smaller than 8 pixels on a side scores 100, as in the reference, because no scale runs.

## Memory and time

The reference keeps about a dozen full-size float planes, more than a gigabyte for a
12-megapixel photo. The port streams rows through the blur and keeps only the half-size image
of the next scale, so a 12-megapixel photo takes about 70 MB besides the two bitmaps. It takes
about 4 seconds on a desktop JVM.

## How close it is to libjxl

Scores differ from those of libjxl's `ssimulacra2` tool by float rounding: the tool uses fused
multiply-adds and vector code. `Ssimulacra2OracleTest` compares the port with libjxl's tool,
within 0.1, wherever that tool is installed.
