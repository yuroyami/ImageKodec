# Reading images

Every entry point takes the whole file as a `ByteArray` and finds the format from its magic
bytes. A file that cannot be decoded throws `ImageDecodeException`. A file that uses a feature
this build lacks throws its subtype, `UnsupportedImageException`. The functions that throw carry
`@Throws`, so Swift and Objective-C see them as throwing functions and the exception arrives as an
`NSError`.

## Read the header first

`probe` parses the header alone. It allocates nothing image-sized, so it stays cheap on a
50-megapixel file. Use it to size a layout, or to refuse an upload by its dimensions before any
memory is spent on it.

```kotlin
val info = ImageKodec.probe(bytes)
info.width; info.height          // as stored
info.displayWidth                // after the EXIF orientation tag
info.frameCount; info.hasAlpha; info.bitDepth
info.isDecodable                 // and info.unsupportedReason when it is false
```

`isDecodable` is a statement about features. It is false when the file uses something this build
does not implement, such as a CgBI PNG or a TIFF with floating-point samples, and
`unsupportedReason` names it. The Coil decoder uses this flag to decide which files to claim.

`isDecodable` stays true for a file that declares only supported features and is then truncated
or corrupt, so a decode can still fail after a clean probe. One case is different: WebP data that
ends before its first image chunk probes as not decodable, because until that chunk nothing says
which codec the image uses.

`probeOrNull` returns null where `probe` would throw.

## Decode a still

```kotlin
val bitmap = ImageKodec.decode(bytes)
val upright = ImageKodec.decode(bytes, applyOrientation = true)   // honor EXIF
val pixel = bitmap[10, 20]                                        // 0xAARRGGBB
```

A `KiteBitmap` holds non-premultiplied ARGB_8888 pixels in a plain `IntArray`. Non-premultiplied
means the red, green and blue channels hold the original color, not the color already multiplied
by the alpha value.

`probe` reports the orientation that a file declares as `info.orientation`: the EXIF tag of a JPEG
or TIFF, the rotation and mirror of an AVIF, and the header of a JPEG XL. `decode` applies it only
when you pass `applyOrientation = true`.

## What each format decodes

| Format | What decodes |
| --- | --- |
| PNG | color types 0/2/3/4/6, depths 1/2/4/8/16, all five filters, `tRNS` palette alpha and color-key, Adam7 interlace |
| APNG | dispose none/background/previous, blend source/over, frame rects, loop count |
| JPEG | 8- and 12-bit DCT: baseline SOF0, extended sequential SOF1, progressive SOF2, arithmetic coding SOF9 and SOF10 with DAC conditioning, lossless SOF3 and SOF11 at 2 to 16 bits with every predictor and point transform, hierarchical SOF5 to SOF7 and SOF13 to SOF15 over any of them, restart intervals, sampling factors 1..4 (4:2:0, 4:2:2, 4:4:4, 4:1:1), gray, YCbCr, RGB, CMYK and YCCK |
| GIF | 87a and 89a, full LZW, interlace, all four disposal methods, per-frame delays, NETSCAPE and ANIMEXTS loop counts |
| BMP | header versions 12/40/52/56/64/108/124, depths 1/2/4/8/16/24/32, BI_RGB, RLE4, RLE8, BITFIELDS with arbitrary masks, top-down and bottom-up, and BI_JPEG and BI_PNG, whose embedded file decodes as itself |
| WebP | lossless VP8L and lossy VP8 with its ALPH opacity, still and animated, including frames that mix the two |
| TIFF | strips and tiles, raw/PackBits/LZW/Deflate/CCITT G3 (1D/mixed 2D)/G4/JPEG (Technical Note 2 and old-style), photometric 0/1/2/3/5/6 including subsampled YCbCr and CMYK, bits 1/2/4/8/16, predictor 2, both planar configurations, every page |
| JPEG 2000 | JP2 container and raw J2K codestream, all of Part 1: every code-block style, progression order changes (POC), packed packet headers (PPM and PPT) and regions of interest (RGN) |
| AVIF | still images and animated image sequences: every AV1 coding tool, intra and inter, with the loop filter, CDEF, superres, loop restoration and film grain, 8 to 12 bits in 4:0:0, 4:2:0, 4:2:2 and 4:4:4, alpha straight or premultiplied, grids, sample transforms, overlays, clean aperture, rotation and mirror |
| JPEG XL | the bare codestream and the container, both coding modes: lossy VarDCT with every transform size, and Modular, lossless or lossy. Progressive passes, LF frames, patches, splines, noise, both smoothing filters, upsampling, samples of up to 16 bits and float samples, alpha straight or premultiplied, extra channels, spot colors, recompressed JPEGs, layers with every blend mode, animations |

Chroma subsampling, in the JPEG and AVIF rows, means that the file stores color at a lower
resolution than brightness. ImageKodec upsamples it before it returns the pixels.

### WebP

Lossy WebP decodes to the pixels that libwebp's `dwebp` writes. Its VP8 decoder is a port of
libwebp's, and the chroma is upsampled as dwebp's default output does.

### AVIF

AVIF decodes with an AV1 decoder written from the AV1 specification, whose planes match dav1d's
sample for sample, film grain included. YUV converts to RGB with the matrix and range that the
`colr` box or the AV1 sequence header gives, with chroma upsampled bilinearly as libavif's own
float path does. The result matches libavif to within one level, and mostly exactly.

Rotation and mirroring are reported as `ImageInfo.orientation`, as EXIF orientation is for a
JPEG, and `applyOrientation` applies them. The clean aperture always crops. An image sequence
plays through `decodeAnimation` with the duration its track gives each frame and the loop count
its edit list gives, alpha track included. `decode` shows its first frame.

### JPEG XL

JPEG XL decodes with a port of the decoder of libjxl 0.11. A lossless file reads exactly as
libjxl's `djxl` reads it. A lossy file gives the 8-bit level nearest to djxl's own sample, and a
16-bit file stays within a few 65535ths of it. djxl dithers its 8-bit output and this decoder does
not, so the two can differ by one level there.

Layers are blended into one image. The orientation is reported as `ImageInfo.orientation`, and
`applyOrientation` applies it. An animation plays through `decodeAnimation` with the duration of
each frame and the loop count of the file, and `decode` shows its first frame. A recompressed JPEG
decodes to its pixels. The data that rebuilds the original JPEG file is passed over.

## Pages of a TIFF

A TIFF can hold any number of pages. `decode` returns the first, `probe(bytes).pageCount` says how
many there are, and `decodePage` and `probePage` reach the others:

```kotlin
val pages = ImageKodec.probe(bytes).pageCount
val third = ImageKodec.decodePage(bytes, page = 2)
```

Pages are separate images with their own size and orientation, not animation frames. So
`frameCount` stays 1 and `decodeAnimation` returns the first page.

## Keep 16-bit samples

`decode` returns 8 bits a channel and keeps only the high byte of a deeper sample. For a depth map,
a scientific or medical image or a graded photograph, `decode16` keeps the whole sample:

- every bit that a 16-bit PNG or TIFF stores
- every bit of a JPEG 2000 component up to 16 bits deep
- every bit of a 12-bit JPEG, or of a lossless JPEG of 9 to 16 bits, as medical images carry them
- a 10- or 12-bit AVIF, converted to RGB at 16 bits
- a JPEG XL sample of up to 16 bits (a deeper sample or a float sample is rounded to 16 bits)

The samples come in the channels that the file has once a palette is looked up: gray, gray and
alpha, RGB or RGBA.

```kotlin
val wide = ImageKodec.decode16(bytes)
wide.channels                  // 1 for a gray depth map, which stays 2 bytes a pixel
wide[x, y, 0]                  // 0..65535
wide.toBitmap()                // the high byte of each sample: exactly what decode returns
```

Narrower samples replicate up, so an 8-bit value `v` becomes `v * 257` and a 12-bit one
`v shl 4 or (v shr 8)`. Every other format decodes as `decode` does and widens the same way.

## Read a JPEG's stored samples

Some callers know better than the decoder what the samples of a JPEG mean. The CMYK images of a PDF
are the usual case, because the PDF says whether the samples are inverted and whether they hold
YCCK. `decodeJpegComponents` stops before the color conversion and returns every component as the
file stores it:

```kotlin
val c = ImageKodec.decodeJpegComponents(bytes, reduction = 2)
c.componentCount   // 1, 3 or 4
c.adobeTransform   // 0, 1 or 2 from the Adobe marker, -1 without one
c[x, y, 3]         // the K sample of a CMYK pixel, 0..255
```

It runs the same decode and upsampling as `decodeReduced`, at about the same cost. The color
conversion of the decoder, applied to these samples, gives exactly the pixels that `decodeReduced`
returns.

## Format sniffing

`ImageFormat.sniff` (and `ImageKodec.detect`) recognize PNG, JPEG, GIF, BMP, WEBP, TIFF, JP2, AVIF
and JXL from the magic bytes alone. Every one of them has a decoder, but a feature inside a format
can still be unsupported. `probe` is the call that reports that, through `isDecodable`.

## JBIG2 and CCITT fax

`Jbig2Decoder` and `CcittFax` are public as well. Neither format carries magic bytes or dimensions
of its own, so both take their parameters explicitly and both return packed 1-bit rows instead of
going through `decode`. A PDF reader is the usual caller.
