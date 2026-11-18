# Module imagekodec-heic

The HEVC codec of HEIC files, for the HEIF reader and writer of `imagekodec`.

Call `ImageKodecHeic.install()` once, before the first decode. The core then hands the coded data
of every `hvc1` item and of every `hvc1` or `hev1` track to this module. The decoder is written
from ITU-T H.265 alone. It decodes intra, P and B pictures in every chroma format at 8 to 16 bits,
with the coding tools of the range and screen content coding extensions, separate colour planes, the
deblocking filter, sample adaptive offset, tiles, wavefront rows and dependent slice segments, and
keeps the decoded picture buffer from one sample of a track to the next. A picture that needs a tool
it lacks, a CTB of 8 samples for one, reports so in `ImageInfo.unsupportedReason`.

`install()` also registers an intra HEVC encoder, written from ITU-T H.265, through which
`ImageKodec.encodeHeic` and `encodeHeic16` write HEIC files: one I picture of 8 to 12 bits in any
chroma format for each item, lossy or lossless.

HEVC is covered by patents that pools such as Access Advance and Via LA license. Check what
applies to your product before you ship this module.
