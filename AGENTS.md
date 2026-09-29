# Working on KiteImageCodec

How to build, the test gate and the style rules: [CONTRIBUTING.md](CONTRIBUTING.md).
Open work: [GitHub Issues](https://github.com/yuroyami/KiteImageCodec/issues).
Format support and known limits: the Limits section of [README.md](README.md).

## Gotchas

Things that already cost someone time. One line each. Delete a line when it stops
being true.

- The OpenJPEG, libtiff and libwebp suites skip themselves when their binary is
  missing, and a skipped test reports as a pass, so read the skip count and not
  only the green run (#14).
- The JPEG 2000 probe does not check the shared `Budget` guard that the decoder
  checks, and both keep their own pixel ceiling, so the probe can call a file
  decodable that the decoder refuses (#2).
- A format with no file in the `FuzzTest` corpus is not fuzzed at all. JPEG 2000
  had none, so a packet header cut off by the end of the data looped forever and
  no test noticed.
- On WebAssembly an index out of bounds or a huge `ByteArray` is a trap, not an
  exception, so a reader has to check its bounds and a size has to be checked
  before the allocation, or a hostile file stops the whole program (#22, #33).
- `FuzzTest` stops at a PNG chunk's checksum and cannot make two fields agree, so
  a fault that needs either has to be found with a test built by hand (#28, #29).
- `CcittFax` and `Jbig2Decoder` are not in the `FuzzTest` corpus, because
  `KiteImageCodec.decode` does not reach them (#33).
- `JpxOracleTest.the_corpus_fixture_jp2_decodes_exactly` looks for a PDF in a
  folder beside this repo and skips without it, so the JVM run always reports one
  skip, even with every oracle tool installed (#14).
- `jsNodeTest`, `wasmJsNodeTest` and `wasmWasiNodeTest` fail offline on a fresh
  clone, because `kotlin-js-store/` is ignored and yarn has no lockfile to work from.
