# Security policy

KiteImage parses bytes it did not write. That is the whole point of the library,
and it is also the whole of its attack surface: every decoder here is fed input
from somewhere else, often over a network, often chosen by someone else.

## Reporting a vulnerability

Report privately through GitHub's
[security advisories](https://github.com/yuroyami/KiteImage/security/advisories/new)
rather than a public issue. A useful report includes the input file (or a
generator for it), the target it happens on, and what the decoder does with it.

Expect a first response within a week.

## What counts as a vulnerability

A malformed image must fail as an `ImageDecodeException`. Anything else from a
decoder entry point is a bug worth reporting:

- an index, cast, arithmetic or null fault escaping the library
- a decode that does not terminate, or that runs for a time unrelated to the
  input size
- memory use disproportionate to the input: the guards described below being
  bypassed
- a read of image data outside the buffer that was handed in

## What does not

- **A refusal.** Rejecting a file KiteImage cannot decode is the documented
  behavior, not a denial of service.
- **Wrong pixels.** A decode that produces the wrong picture is a correctness
  bug; file it as a normal issue with the input attached.
- **Resource use inside the documented budget.** A legitimate 100-megapixel
  image really does need hundreds of megabytes. Check the input size against
  `KiteImage.probe` before decoding if that matters to you.

## Guards already in place

- **Dimension and pixel-count ceilings** on every decoder.
- **An input-relative budget**: declared dimensions are also checked against what
  the input size could plausibly produce, so a corrupt header in a small file
  cannot reserve a large buffer.
- **Exact-size inflate caps** on PNG and TIFF: the expected decompressed size is
  computed from the headers and enforced during inflation, not after.
- **Bounds-checked readers** throughout, so truncation surfaces as a decode error
  with a position rather than as a crash.
- **A fuzz suite in CI** that mutates valid files thousands of ways per run and
  fails on any exception type other than `ImageDecodeException`.

## Reducing your own exposure

`KiteImage.probe(bytes)` reads only the header. Use it to reject images by
dimension, frame count or format before committing to a decode, and use
`KiteBitmap.scaled` (or the Coil integration, which does it for you) so a large
source does not stay resident at full size.
