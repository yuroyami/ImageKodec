# Module imagekodec-heic

The HEVC codec of HEIC files, for the HEIF reader of `imagekodec`.

Call `ImageKodecHeic.install()` once, before the first decode. The core then hands the coded data
of every `hvc1` item and of every `hvc1` or `hev1` track to this module. The decoder is written
from ITU-T H.265 alone. Today it reads the parameter sets and slice headers, so `probe` reports the
coded size, depths, chroma format and colour of a HEIC file; decoding the pictures is not
implemented yet, and every item reports so in `ImageInfo.unsupportedReason`.

HEVC is covered by patents that pools such as Access Advance and Via LA license. Check what
applies to your product before you ship this module.
