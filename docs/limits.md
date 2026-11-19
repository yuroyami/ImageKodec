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
  lossless JPEG, a TIFF sample of another depth or a floating-point one at 16 bits, and it converts
  AVIF and HEIC at 16 bits. Other formats come through it at 8 bits, widened. An AVIF, HEIC or JPEG 2000 sample that converts to RGB stays inside the 8-bit level that
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

## TIFF

- A sample that is not an unsigned integer of 1, 2, 4, 8 or 16 bits comes out at 16 bits, as
  ImageMagick reads it through libtiff: an unsigned integer of 1 to 32 bits as v * 65535 / (2^bits - 1),
  rounded; a signed one moved up by half its range first, so its most negative value is black; and an
  IEEE float of 16, 24 (Adobe's DNG form), 32 or 64 bits as a fraction of full scale, clipped to 0 to 1,
  NaN as 0. No transfer function is applied to a float: libtiff, ImageMagick and ImageIO show it as it
  is. ImageMagick moves signed samples up only in 16-bit gray, and ImageIO clips the negative ones, so
  the readers do not agree on signed colour.
- The features below throw `UnsupportedImageException`, and `probe` names them, because no reader
  gives them a picture to check a reading against:
  - Complex samples (SampleFormat 5 and 6), integers of more than 32 bits, floats of another width,
    and samples of several formats on one page. No TIFF reader here shows any of them.
  - YCbCr of other than 8 bits. libtiff's RGBA interface refuses it, ImageMagick hands the samples on
    without converting them, and ImageIO misreads them.
  - A palette indexed by signed or floating-point samples, or by more than 16 bits. TIFF 6.0 indexes a
    palette with unsigned integers, and a `ColorMap` holds 3 * 2^bits entries.
  - Inks other than CMYK (`InkSet` 2, or `NumberOfInks` other than 4). TIFF 6.0 names such inks
    (`InkNames`) without measuring them, so they have no colour, and libtiff's RGBA interface refuses them.
  - The horizontal predictor over samples other than 8, 16, 32 or 64 bits, and the floating-point
    predictor over integers. libtiff refuses both.
  - Old-style lossless JPEG (`JPEGProc` 14). libtiff hands it to libjpeg, which has read no lossless
    process before libjpeg-turbo 3, and ImageIO's JPEG reader has none. Old-style JPEG of other than 1
    or 3 samples, which libtiff refuses, and old-style YCbCr in separate planes with subsampled chroma,
    which libtiff's RGBA interface refuses, stay out too.
- A 12-bit JPEG strip is held to ImageKodec's own 12-bit JPEG decoder, which `JpegTwelveBitOracleTest`
  holds to libjpeg-turbo 3.1: the libtiff here reads 8-bit JPEG only.
- JPEG YCbCr in separate planes, chroma subsampled, is read as libtiff lays it out: each chroma strip or
  tile a JPEG stream at the subsampled size. libtiff's RGBA interface refuses the subsampled form, and
  ImageIO reads one strip of it; the unsubsampled form matches `tiff2rgba`.
- CCITT's uncompressed mode (T.4 4.2.3 and Table 5) decodes in Group 3 and Group 4, which no installed
  reader takes: libtiff and ffmpeg stop at it. Its tests write the codes from T.4 and check the page.

## JBIG2

- A Huffman-coded symbol dictionary that refines or aggregates its symbols (SDHUFF and SDREFAGG
  both 1) throws `UnsupportedImageException`, and `Jbig2Decoder.decode` returns null for it. No
  encoder here writes one, and jbig2dec 0.20, the only other reader, draws the same symbol whatever
  its refinement data holds, and stops at its aggregates with a broken symbol ID table, so nothing
  could check a reading. The arithmetic form of the same dictionaries decodes, held to jbig2dec.
- A segment of unknown length is allowed only for an immediate generic region, as T.88 7.2.7 says;
  its end is the first end marker after the region's header, as jbig2dec finds it.

## PNG

- Apple's CgBI files decode at 8 bits a sample, which is what Xcode writes. A CgBI file of 16-bit
  colour throws `UnsupportedImageException`: CgBI has no public specification, no tool writes such a
  file, and no reader of one exists to check a reading against.

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

- The matrices that are not linear convert as zimg converts them (#189): BT.2020 and chromaticity-derived
  constant luminance through the BT.709 curve (transfer characteristics 1, 6, 14 and 15) or linear light (8),
  and ICtCp through PQ (16) or HLG (18). Like zimg, the light comes out through BT.1886's gamma of 2.4 for the
  SDR curves, and HLG's ICtCp goes through its OOTF one channel at a time. Other curves throw
  `UnsupportedImageException`, as zimg refuses them, and so does SMPTE ST 2085 (11), which neither libavif nor
  zimg converts, so no reading could be checked; matrix coefficients from 15 up are reserved.
- Item data built from other items (`iloc` construction method 2) throws `UnsupportedImageException`: libavif
  refuses it as unsupported, and no tool here writes it, so no file could be checked.
- A sample transform token outside the set the AVIF specification defines (0 to 32, 64 to 67 and 128 to 137)
  throws `UnsupportedImageException`: the others are reserved.
- An ICC profile or a transfer function is reported, and applied only with a `colorTarget`.
- An image sequence without an edit list loops forever, as browsers play it.
- A file with both a track and a primary item shows the primary item when its major brand is `avif`
  and the track otherwise, as libavif chooses.
- AV1 allows a frame coded at another size than the first. Such a frame is scaled to the size of
  the first frame.
- Tile list OBUs are refused. They belong to AV1's large scale tile decoding, whose tiles an
  application picks from outside the stream; no image uses them, and neither dav1d nor libavif decodes them.
- The encoder (`encodeAvif`) writes one intra key frame a picture, 8, 10 or 12 bits, at most 65535 on
  a side. It writes no film grain, 128 by 128 superblock, superres, delta quantizer, segmentation, or
  4:1 and three-way partition.
- An animation (`encodeAvif(animation)`, #177) is one image sequence track of shown frames in display
  order: a key frame, then inter frames that predict from the one or two frames before, single or
  averaged. It codes no hidden or alternate reference frame, backward reference, skip mode, global or
  warped motion, OBMC, inter-intra or masked compound, distance weights, switchable or dual filter,
  eighth-sample vector or motion field projection, and no grid: each frame is one AV1 frame. Every frame
  has the first one's size. The track's timescale is 1000, so a delay is kept to the millisecond, and a
  delay of 0 is written as 1 ms. Intra block copy, from effort 2 on screen content,
  copies only places whose source matches the block exactly, from a hash of the source luma kept for
  pictures of at most 4 Mi samples (a grid tile always is).
- The encoder is slower than libaom; see [Encoding AVIF](#encoding-avif) below.

### Encoding AVIF

`Av1EncoderBenchmarkTest` (run with `IMAGEKODEC_AV1_BENCHMARK=1`) encodes the six photo-like test scenes
(0.08 to 0.2 megapixels) at 8-bit 4:2:0 and compares `encodeAvif` with libavif 1.0.4's `avifenc` (libaom
3.8.2, one thread) at `-q 50` and `-q 80`: for each avifenc file, the size ImageKodec needs for the same
SSIMULACRA 2 score, read between its sizes at qualities 5 apart. The table gives the mean ratio of the six
scenes; below 1, ImageKodec's file is smaller. Times are on the JVM after a warm-up, on a 4-core Intel Xeon
at 2.30 GHz shared with other builds.

| ImageKodec effort | Against `-s 6 -q 50` | `-s 6 -q 80` | `-s 9 -q 50` | `-s 9 -q 80` | Time of the six scenes at quality 50 | Times `-s 6` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.41 | 1.29 | 1.00 | 0.97 | 0.85 s | 3.4 |
| 2 | 1.17 | 1.05 | 0.84 | 0.83 | 1.17 s | 4.7 |
| 3 | 0.97 | 0.95 | 0.71 | 0.75 | 19.6 s | 79 |

avifenc took 0.248 s for the six scenes at speed 6 and 0.078 s at speed 9, each file a fresh process, start
included; ImageKodec's times are the CPU time of its thread, the least of three runs. So effort 2 makes files 5 to
17 percent larger than libaom's speed 6 at equal score and 16 percent smaller than speed 9's, and takes 4.7 times as
long as speed 6; effort 1 takes 3.4 times as long, for files 29 to 41 percent larger; effort 3 makes files 3 to 5
percent smaller than speed 6's in 79 times its time. At efforts 1 to 3, ImageKodec reaches the scores of avifenc's
`-q 50` and `-q 80` at qualities 48 to 51 and 79 to 80.

The figures of the first round of #176's speed work, in another session (avifenc then 0.231 s and 0.077 s):

| ImageKodec effort | Against `-s 6 -q 50` | `-s 6 -q 80` | `-s 9 -q 50` | `-s 9 -q 80` | Time of the six scenes at quality 50 | Times `-s 6` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.46 | 1.30 | 1.03 | 0.98 | 0.98 s | 4.2 |
| 2 | 1.18 | 1.06 | 0.85 | 0.84 | 1.42 s | 6.1 |
| 3 | 0.97 | 0.95 | 0.71 | 0.75 | 21.2 s | 92 |

and before any of it, in that session:

| ImageKodec effort | Against `-s 6 -q 50` | `-s 6 -q 80` | `-s 9 -q 50` | `-s 9 -q 80` | Time of the six scenes at quality 50 | Times `-s 6` |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1.42 | 1.35 | 1.01 | 1.02 | 1.39 s | 5.1 |
| 2 | 1.18 | 1.07 | 0.85 | 0.85 | 2.94 s | 10.8 |
| 3 | 0.97 | 0.95 | 0.71 | 0.75 | 27.5 s | 101 |

with avifenc at 0.272 s and 0.100 s. The first round halved effort 2's time for files no larger at equal score, but
made effort 1's column against `-s 6 -q 50` 0.03 larger, nearly all of it the night scene's (2.28 to 2.45 times
avifenc's size at that score). The second round brought it back to 1.41, the night scene to 2.25, by pricing both of
effort 1's transform types again; it cost effort 1 about 3 percent of its time. Effort 3's files are the same size as
after the first round, scene for scene.

Where the time went, by a sampling profiler on the JVM over the six scenes at quality 50, before the work: at
effort 2 (3.2 s), 74 percent in the block search, of which coding candidates through the decoder took 43 points
(choosing levels 36, of it forward transforms 20, inverse transforms 7), ranking luma modes 14 and chroma modes 7;
and 24 percent in the filter search (CDEF 11, loop restoration 8, deblocking 5). At effort 1 (1.4 s), 93 percent in
the block search: choosing levels 35, ranking luma modes 20 and chroma modes 15. What each change brought, its
time against the state before it, interleaved runs with the least of four to eight, and its size at equal
SSIMULACRA 2 score over qualities 30 to 90 (a mean change within half a percent is within the measure's spread; the
timings themselves spread by about 5 percent on this shared machine):

| Change | Effort 1 time | Effort 2 time | Size |
| --- | ---: | ---: | --- |
| `choose` keeps each result with its inputs until the superblock is committed, and a repeat takes it (one run) | -8 % | -16 % | the same bytes |
| The loop filter level search filters only the plane it measures (one run) | | -11 % | the same bytes |
| The CDEF search filters nothing for strengths of 0, and its passes put the chosen CDEF in place | | -2 % | the same bytes |
| Rank only the three directions the block's gradients follow most | -15 % | -7 % | -0.5 % at both |
| Chroma ranks only those and the luma's mode | -9 % | within the spread | within 0.5 % |
| Price only the transform type of least coefficient magnitude | -7 % | -11 % | +0.5 % at effort 1, +1.2 % at effort 2 |
| The same transform types at the commit as in the search, at effort 2 | | within the spread | -1.2 % |
| No filter intra at effort 2 | | -2 % | -0.2 % |
| No split of a smooth block that keeps its largest transform, in stills at effort 2 | | -24 % | within 0.2 % |
| The Wiener filter alone at effort 2 | | -11 % | within 0.1 % |
| Three CDEF strength pairs at effort 2 | | -10 % | +0.1 % |
| Each block's direction energies summed from its superblock's 4x4 blocks | within the spread | within the spread | the same bytes |
| Second round: a repeated transform block adds the residual its levels made before, not transforming again | within the spread | -9 % | the same bytes |
| The best luma is put back before chroma is ranked, not coded again; the chroma ranking leaves the coded chroma as it was | -3 % | -4 % | within 0.1 % |
| A 64 by 64 block keeps its largest transform at effort 2 | | -6 to -14 % | -0.2 to -0.4 % |
| Effort 1 prices both of its transform types again | +3 % | | `-s 6 -q 50` column 1.45 to 1.41 |

Until the second round the chroma ranking left its last prediction in the block's chroma, and a block whose ranked
chroma mode was the one already coded kept it there for the search of the blocks after it; the commit pass coded
the true chroma, so the files were valid, but the search priced its neighbours on wrong samples.

Tried in the second round and left out: deciding partitions from estimates made before any candidate is coded. An
estimate pass over each superblock ranked the luma modes of every square block from 64 by 64 to 4 by 4, predicting
from the source where the neighbours were not yet coded, and gave each block the least SATD cost of its modes, or a
quick rate and distortion of its best mode (its DCT at the largest transform, quantized with a dead zone, the error in
the transform domain and a model of the rate). Against the exact search's choice of whole or split, the SATD cost of a
block over that of its best split correlated 0.55 to 0.78 with the exact costs' ratio below 64 by 64, and the quick
estimate 0.82 to 0.90 at every size. But the estimate pass took 23 to 38 percent of the search's time, about as much as coding the partitions it
let the search skip: following the quick estimate wherever it was clear and coding both choices elsewhere took effort
2 from 1.32 s to 1.52 s and 1.55 s, for files 0.5 and 1.1 percent larger. Pruning on cheaper signals measured too
little to try: a split given up once its first children cost more than their share of the whole block's cost spares
3 to 7 percent of the children within about 0.1 percent of the cost J, and leaving out the whole 64 by 64 block once two of its
32 by 32 children split spares a quarter of those blocks within 0.14 percent. Also left out: no transform split at
32 by 32 (up to 0.4 percent larger, no time measurably saved) and chroma ranking only DC, chroma from luma and the luma's
mode (0.4 to 0.5 percent larger, likewise).

The aim of #176, effort 2 within 4 times speed 6, is not met: effort 2 takes 4.7 times as long. Its time now goes 76
percent to the block search, of which coding candidates through the decoder takes 45 points (choosing levels 32, of
it forward transforms 18, half of it the DCT's; inverse transforms 8; intra prediction 16 over the whole search),
ranking luma modes 12 and chroma modes 9; and 20 percent to the filter search (CDEF 10, deblocking 5). Each block
still codes its candidates at every partition size the pruning leaves, about three a sample. What is left: a faster
forward DCT (its passes are a partial butterfly, a third of the direct sum's products, where a full butterfly needs
fewer still), and partition decisions that pay for their own estimate, which needs an estimate
that reuses what the exact search computes rather than a pass of its own.

On JS (Node, `AvifEncodeTimingTest`, the faster of two runs), a 256 by 256 scene at quality 50 takes 404, 513 and
9,732 ms at efforts 1, 2 and 3, where the first round's code took 614, 896 and 11,067 ms in the same session, and the
code before any speed work 834, 1,392 and 12,466 ms in an earlier one. Effort 3 gains least there: the decoder's
inverse transform and CDEF direction search, which the encoder runs for every candidate, and the Wiener filter's
least squares work in Long arithmetic, which JS does in software.

The encoder as #176 first committed it (the speed work since, the Wiener filter, chroma palette and intra block
copy not yet in) took 1.52 s at effort 1 (5.2 times speed 6, which took 0.294 s in that run), 4.03 s at effort 2
(13.7 times) and 28.3 s at effort 3, and effort 2's ratios were 1.18, 1.11, 0.82 and 0.86.

### Encoding animated AVIF

`AvifSequenceBenchmarkTest` (run with `IMAGEKODEC_AVIS_BENCHMARK=1`) pans a 256 by 192 window three pixels
across and one down a frame over two photo-like scenes (landscape and city), with a disc crossing it, 24
frames of 40 ms each, and compares `encodeAvif(animation)` with ffmpeg 6.1's libaom 3.8.2
(`-c:v libaom-av1 -cpu-used 6 -crf N -b:v 0 -threads 1 -f avif`, which looks 19 frames ahead and codes hidden
alternate reference frames): for each libaom file, the size ImageKodec needs for the same SSIMULACRA 2 score,
the mean over the frames, read between its sizes at qualities 10 apart. Both write 8-bit 4:2:0. The table
gives the mean ratio of the two scenes; below 1, ImageKodec's file is smaller.

| ImageKodec | Against CRF 30 | CRF 40 | CRF 50 | Time of both scenes at quality 50 |
| --- | ---: | ---: | ---: | ---: |
| effort 1 | 2.11 | 1.63 | 1.32 | 1.6 s |
| effort 2 | 1.02 | 1.04 | 1.02 | 5.2 s |
| effort 2 without compound prediction, before #176's speed work | 1.10 | 1.04 | 1.00 | 7.2 s |
| effort 2 with every frame a key frame, before #176's speed work | 6.43 | 6.26 | 5.98 | 6.2 s |
| effort 3, before #176's speed work | 0.90 | 0.98 | 0.94 | 87.8 s |

libaom took 0.53 s for both scenes at CRF 40, one process each, start included; ImageKodec's times are the CPU
time of its thread on the JVM after a warm-up, one run, on a 4-core Intel Xeon at 2.10 GHz shared with other
builds. So effort 2 writes files within 5 percent of libaom's at equal score, in 11 times its time, and inter frames
make them a sixth of what every frame as a key frame takes. Averaging the two references saves up to 4 percent
where the quality is high and nothing where it is low. Effort 1 is three times faster than effort 2 for files 32 to
111 percent larger than libaom's, most on the finely textured city scene. The test fails when effort 2's ratio at
CRF 40 passes 1.3.

Before the speed work of #176's follow-up, in the same session, effort 1 measured 1.77, 1.58 and 1.35 in 2.3 s and
effort 2 1.05, 1.03 and 0.99 in 6.6 s, with libaom at 0.63 s. Over qualities 30 to 70, effort 2's files are now 0.9
percent larger at equal score and effort 1's 0.8 percent, effort 2 a fifth faster and effort 1 a ninth. Effort 1's
city scene at CRF 30 needs 2.56 times libaom's size where it needed 2.22, which the measure over qualities does not
show. The split pruning of still pictures made a sequence's files 2 to 6 percent larger and effort 2 twice as slow,
as it pruned the key frame that every inter frame predicts from, so it stays out of sequences.

After the second round of that work, in another session, effort 2 measured 1.02, 1.04 and 1.02 in 5.2 s where the
first round's measured 1.02, 1.04 and 1.03 in 5.5 s, and effort 1 2.11, 1.63 and 1.32 in 1.6 s where it measured
1.97, 1.56 and 1.33 in 1.9 s. Effort 1's city scene at CRF 30 is the one that grew, 2.93 times libaom's size where it
was 2.56, at the end of its quality range where reading between qualities 10 apart is least sure.

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
- A lossy picture whose header has only an ICC profile comes out in that profile, as libjxl writes it
  through a colour engine, and `probe` reports the profile. Linear sRGB goes to the PCS and through the
  profile's B2A0 table (lut8Type, lut16Type or lutBToAType, read as lcms2 reads them: its fixed-point
  grid, trilinear for a Lab PCS, v2's Lab ahead of a lut16Type), within 4 of 65535 of lcms2, or,
  for a profile without one, through the inverse of its colorants and curves, within 8 of 65535. B2A0 is
  the perceptual table, which skcms reads; no black point compensation is applied. An RGB or gray profile
  with neither, or of another colour space, gives sRGB. The djxl 0.7 here has no colour engine and writes
  linear sRGB, so the lossy files are held to the picture they were coded from and, for table profiles,
  to djxl's linear sRGB taken into the profile by lcms2, within 0.05 of 255 (mean 0.002).
- A file that is not XYB keeps the samples it stores. Its ICC profile is reported, not applied.
- No tone mapping runs by default. With `colorTarget = ColorTarget.Srgb()`, the shared color
  conversion applies supported profiles or code points and tone-maps HDR.
- That conversion takes the intensity target of a PQ or HLG header as the peak of the picture, which
  `probe` reports as its maximum content light level. libjxl sets it to 10000 cd/m² when the encoder
  was told nothing.
- A header with primaries or a white point of its own, or with a gamma other than 2.2 or 2.8, is
  described by an ICC profile of matrix and curves made from it, as libjxl describes every header, and
  converts through it. With the PQ or HLG curve, the profile holds the curve as a table of 4096 samples,
  PQ's display light over 10000 cd/m² or HLG's scene light, as libjxl 0.7 makes it (colorants equal
  and the curve within 1 of 65535 of djxl's `--icc_out`). Such a profile holds no tone
  mapping, so it only describes the samples: `cicp` gives 2 for the primaries, and the conversion takes
  the curve's code points with the header's own primaries and white, within 0.02 of 255 of the same light coded
  in BT.2020's primaries.
- A sample outside the range of 0 to 1, which a float file can hold, is clipped.
- A CMYK file keeps C, M and Y in the colour channels and K in a black extra channel, with a CMYK
  profile (#189). A sample of 1 is no ink and 0 full ink, as libjxl hands CMYK to lcms2 (100 - 100 v
  percent). `decode` applies the black through that profile, as libjxl converts CMYK through its
  colour engine, at the intent the profile's header names, within a level of lcms2;
  `ColorTarget.Srgb` picks another intent. cjxl 0.7 reads no CMYK source, so ImageKodec's encoder
  writes the test files, lossless, through an internal entry. The djxl 0.7 here has no colour engine:
  it writes the stored samples, colour and black apart, and those are held to it exactly. So neither
  the ink convention nor djxl 0.11's conversion is checked against libjxl here; where djxl is 0.11 or
  newer, as in CI, the test holds its sRGB to `ColorTarget.Srgb` within 3 levels. A black channel
  without a CMYK profile, more than one, or with XYB colour throws `UnsupportedImageException`.

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

- CMYK and the N-channel formats decode to `JxrDecApp`'s samples (#189). `JxrEncApp` writes neither,
  so ImageKodec's encoder writes the test files, and `JxrDecApp` reads them. Of the N-channel formats,
  `JxrDecApp` reads 3 to 8 channels with planar alpha or none, and fails on alpha interleaved with the
  channels, which stays refused. CMYKDIRECT, a codestream whose colour format does not match its pixel
  format (CMYK from anything but YUVK, N channels from anything but as many N channels), and pixel
  formats that T.832 does not list throw `UnsupportedImageException`, and `probe` names them in
  `unsupportedReason`. jxrlib refuses the same pairings and has no CMYKDIRECT pixel format.
- An N-channel format names no colour, so the picture is T.832 Annex C's default: sRGB for three
  channels, CMYK for the first four of more, the rest dropped. No reader at hand draws one.
- `decodeReduced` of a CMYK or N-channel file decodes it in full and averages: jxrlib's CMYK thumbnail
  is not ported, and its N-channel thumbnail writes alpha to the fourth sample whatever the count.
- 32bppRGBE decodes to `JxrDecApp`'s samples. Its picture is a mantissa times 2^(E - 136), as jxrlib's
  RGBE_RGB96Float reads it, in linear light converted to sRGB like the float formats. `JxrDecApp`'s own
  24bppRGB of an RGBE file does not follow from its RGBE samples, so it is not used as a reference.
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
  square size, 4:4:4 or 4:2:0 chroma, and the gray, BGR, BGRA, 16-bit gray, RGB, RGBA, 32bppCMYK
  and 64bppCMYK formats.
  16-bit and lossless encodes keep 4:4:4. With two levels of overlap, 4:2:0 needs an image two
  macroblocks wide, as jxrlib does, so a narrower one gets one level.

## Orientation

`probe` reports the orientation that a file declares: the EXIF tag of a JPEG or TIFF, the rotation
and mirror of an AVIF, the header of a JPEG XL and the Transformation tag of a JPEG XR. `decode` applies it only when you pass
`applyOrientation = true`. The Compose binding applies it for you.
