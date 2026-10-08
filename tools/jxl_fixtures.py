#!/usr/bin/env python3
"""
Writes JxlFixtures.kt: small JPEG XL files, each with what libjxl's djxl reads from it.

The files come from three libjxl 0.11.1 tools. cjxl encodes pictures that this script
draws. jxl_from_tree turns a text description into a file, which reaches what cjxl
never writes on request: splines, blend modes, frames at an offset, an orientation,
float samples and the implicit palette. cjpeg (libjpeg-turbo) writes the JPEG that
cjxl then recompresses.

    python3 tools/jxl_fixtures.py > \\
        imagekodec/src/commonTest/kotlin/io/github/yuroyami/imagekodec/JxlFixtures.kt

A file that decodes exactly carries the FNV-1a hash of djxl's samples. A lossy file
and a blended animation frame carry djxl's samples themselves at 8 bits, because two
decoders agree on them only within a rounding step. djxl dithers its own 8-bit
output, so the script asks it for 16 bits and narrows them itself.
"""
import os
import struct
import subprocess
import sys
import tempfile
import zlib

WORK = tempfile.mkdtemp(prefix="jxl-fixtures-")


def run(*cmd):
    done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if done.returncode != 0:
        sys.exit("failed: " + " ".join(cmd) + "\n" + done.stdout.decode(errors="replace"))


def path(name):
    return os.path.join(WORK, name)


def picture(width, height, channels, maxval):
    """A card with a ramp, a wave, an edge and a flat patch, as rows of samples."""
    rows = []
    state = 12345
    for y in range(height):
        row = []
        for x in range(width):
            state = (state * 1103515245 + 12345) & 0x7FFFFFFF
            grain = ((state >> 16) % 9 - 4) / 255.0
            wave = 0.12 * ((x * 5 + y * 3) % 11 - 5) / 5.0
            red = x / (width - 1) * 0.8 + wave + grain
            green = y / (height - 1) * 0.7 + 0.15 - grain
            blue = 0.5 + 0.4 * (1 if (x // 6 + y // 5) % 2 == 0 else -1)
            if 4 <= x < 10 and 3 <= y < 8:
                red, green, blue = 0.9, 0.85, 0.15
            alpha = 1.0 if y < height // 3 else x / (width - 1)
            pixel = {1: [red], 2: [red, alpha], 3: [red, green, blue], 4: [red, green, blue, alpha]}[channels]
            row.append([min(maxval, max(0, round(v * maxval))) for v in pixel])
        rows.append(row)
    return rows


def write_pam(name, rows, maxval):
    height, width, channels = len(rows), len(rows[0]), len(rows[0][0])
    kind = {1: "GRAYSCALE", 2: "GRAYSCALE_ALPHA", 3: "RGB", 4: "RGB_ALPHA"}[channels]
    head = f"P7\nWIDTH {width}\nHEIGHT {height}\nDEPTH {channels}\nMAXVAL {maxval}\nTUPLTYPE {kind}\nENDHDR\n"
    body = bytearray()
    for row in rows:
        for pixel in row:
            for v in pixel:
                body += struct.pack(">H", v) if maxval > 255 else bytes([v])
    with open(path(name), "wb") as f:
        f.write(head.encode() + body)
    return path(name)


def write_png(name, rows, icc=None):
    """An 8-bit RGB PNG, with [icc] as its profile when given."""
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    raw = b"".join(b"\0" + bytes(v for pixel in row for v in pixel) for row in rows)
    out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", len(rows[0]), len(rows), 8, 2, 0, 0, 0))
    if icc:
        out += chunk(b"iCCP", b"card\0\0" + zlib.compress(icc))
    out += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path(name), "wb") as f:
        f.write(out)
    return path(name)


def profile():
    """A small ICC version 2 profile that no enumerated colour space matches: wide primaries with a gamma of 1.8."""
    def fixed(v):
        return struct.pack(">i", round(v * 65536))

    def xyz(x, y, z):
        return b"XYZ \0\0\0\0" + fixed(x) + fixed(y) + fixed(z)
    curve = b"curv\0\0\0\0" + struct.pack(">IH", 1, round(1.8 * 256)) + b"\0\0"
    tags = [
        (b"desc", b"desc\0\0\0\0" + struct.pack(">I", 5) + b"card\0" + bytes(79)),
        (b"cprt", b"text\0\0\0\0none\0"),
        (b"wtpt", xyz(0.9642, 1.0, 0.8249)),
        (b"rXYZ", xyz(0.7977, 0.2880, 0.0)),
        (b"gXYZ", xyz(0.1352, 0.7119, 0.0)),
        (b"bXYZ", xyz(0.0313, 0.0001, 0.8249)),
        (b"rTRC", curve), (b"gTRC", curve), (b"bTRC", curve),
    ]
    start = 128 + 4 + 12 * len(tags)
    table, body, placed = struct.pack(">I", len(tags)), b"", {}
    for name, data in tags:
        if data not in placed:
            body += bytes(-len(body) % 4)
            placed[data] = (start + len(body), len(data))
            body += data
        table += name + struct.pack(">II", *placed[data])
    body += bytes(-len(body) % 4)
    head = struct.pack(">I4sI4s4s4s", start + len(body), bytes(4), 0x02400000, b"mntr", b"RGB ", b"XYZ ")
    head += struct.pack(">6H", 2024, 1, 1, 0, 0, 0) + b"acsp" + bytes(28)
    head += fixed(0.9642) + fixed(1.0) + fixed(0.8249)
    return head + bytes(128 - len(head)) + table + body


def page(width, height):
    """A white page with one small glyph repeated over it, which cjxl stores once as a patch."""
    glyph = ["01110", "10001", "10111", "10001", "01110"]
    rows = [[[255, 255, 255] for _ in range(width)] for _ in range(height)]
    for top in range(4, height - 8, 9):
        for left in range(4, width - 8, 8):
            for j, line in enumerate(glyph):
                for i, mark in enumerate(line):
                    if mark == "1":
                        rows[top + j][left + i] = [0, 0, 0]
    return rows


def read_pnm(name):
    """A PGM, PPM or PAM file as (width, height, channels, maxval, samples)."""
    data = open(name, "rb").read()
    if data[:2] == b"P7":
        end = data.index(b"ENDHDR\n") + 7
        fields = dict(line.split(None, 1) for line in data[3:end - 7].decode().strip().split("\n"))
        width, height, channels, maxval = (int(fields[k]) for k in ("WIDTH", "HEIGHT", "DEPTH", "MAXVAL"))
    else:
        tokens, at = [], 2
        while len(tokens) < 3:
            while data[at:at + 1].isspace():
                at += 1
            start = at
            while not data[at:at + 1].isspace():
                at += 1
            tokens.append(int(data[start:at]))
        end = at + 1
        width, height, maxval = tokens
        channels = 1 if data[:2] == b"P5" else 3
    count = width * height * channels
    if maxval > 255:
        samples = list(struct.unpack(f">{count}H", data[end:end + 2 * count]))
    else:
        samples = list(data[end:end + count])
    return width, height, channels, maxval, samples


def read_png(name):
    """A PNG or an APNG from djxl as (width, height, channels, depth, [(delay ms, samples)]). Each frame covers the canvas."""
    data = open(name, "rb").read()
    at, packed, parts, delays = 8, [], [], []
    while at < len(data):
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        body = data[at + 8:at + 8 + size]
        at += 12 + size
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth in (8, 16) and color in (0, 2, 4, 6) and interlace == 0, "djxl wrote a PNG this script does not read"
            channels = {0: 1, 2: 3, 4: 2, 6: 4}[color]
        elif kind == b"fcTL":
            _, w, h, x, y, num, den, dispose, blend = struct.unpack(">IIIIIHHBB", body)
            assert (w, h, x, y, blend) == (width, height, 0, 0, 0), "djxl wrote a frame that does not replace the canvas"
            if parts:
                packed.append(b"".join(parts))
            parts = []
            delays.append(round(1000 * num / (den or 100)))
        elif kind == b"IDAT":
            parts.append(body)
        elif kind == b"fdAT":
            parts.append(body[4:])
    packed.append(b"".join(parts))
    step = channels * depth // 8
    stride = width * step
    frames = []
    for delay, one in zip(delays or [0], packed):
        raw = zlib.decompress(one)
        prev = bytearray(stride)
        out = bytearray()
        for y in range(height):
            kind = raw[y * (stride + 1)]
            line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
            for i in range(stride):
                a = line[i - step] if i >= step else 0
                b = prev[i]
                c = prev[i - step] if i >= step else 0
                if kind == 1:
                    line[i] = (line[i] + a) & 255
                elif kind == 2:
                    line[i] = (line[i] + b) & 255
                elif kind == 3:
                    line[i] = (line[i] + (a + b) // 2) & 255
                elif kind == 4:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            out += line
            prev = line
        count = width * height * channels
        frames.append((delay, list(struct.unpack(f">{count}H", out)) if depth == 16 else list(out)))
    return width, height, channels, depth, frames


def fnv(data):
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def argb8(width, height, channels, samples):
    """Samples of 8 bits as the bytes of KiteBitmap.argb: alpha, red, green, blue."""
    out = bytearray()
    for i in range(width * height):
        p = samples[i * channels:(i + 1) * channels]
        gray = channels < 3
        out += bytes([p[-1] if channels in (2, 4) else 255, p[0], p[0] if gray else p[1], p[0] if gray else p[2]])
    return bytes(out)


def kotlin_long(h):
    return f"0x{h:x}L" if h < 1 << 63 else f"0x{h:x}uL.toLong()"


def kotlin_hex(data, indent="        "):
    text = data.hex()
    lines = [text[i:i + 160] for i in range(0, len(text), 160)] or [""]
    return " +\n".join(f'{indent}"{line}"' for line in lines)


def decode(jxl, bits):
    """What djxl reads, through a PNG, which is the output it gives the asked depth."""
    out = path("out.png")
    run("djxl", jxl, out, f"--bits_per_sample={bits}")
    width, height, channels, depth, frames = read_png(out)
    assert depth == bits
    return width, height, channels, frames[0][1]


def exact8(jxl):
    width, height, channels, samples = decode(jxl, 8)
    return fnv(argb8(width, height, channels, samples)), None


def exact16(jxl, depth):
    """A file deeper than 8 bits: the hash of its samples as decode16 gives them, each widened by repeating its high bits."""
    if depth == 16:
        samples = decode(jxl, 16)[3]
    else:
        # A PNM file holds the samples at their own depth; a PNG would hold them stretched to 16 bits.
        out = path("out.ppm")
        run("djxl", jxl, out)
        _, _, _, maxval, samples = read_pnm(out)
        assert maxval == (1 << depth) - 1, maxval
        samples = [(v << (16 - depth)) | (v >> (2 * depth - 16)) for v in samples]
    return fnv(struct.pack(f">{len(samples)}H", *samples)), None


def lossy(jxl, depth=8):
    """A lossy file: djxl's samples at 8 bits. A file of 16 bits gives the high byte of each, as ImageKodec.decode does."""
    width, height, channels, samples = decode(jxl, 16)
    return None, argb8(width, height, channels, [v >> 8 if depth == 16 else (v + 128) // 257 for v in samples])


def cjxl(name, source, *options):
    out = path(name + ".jxl")
    run("cjxl", source, out, *options)
    return out


def tree(name, text):
    with open(path(name + ".tree"), "w") as f:
        f.write(text)
    out = path(name + ".jxl")
    run("jxl_from_tree", path(name + ".tree"), out)
    return out


ANIMATION = """
Width 16
Height 12
Bitdepth 8
Alpha
Animation
Duration 40
NotLast
if c > 2
  - Set 255
  if y > 5
    - W + 9
    - N + 5

Width 16
Height 12
FramePos 4 3
Duration 120
BlendMode kBlend
NotLast
if c > 2
  if x > 3
    - Set 128
    - Set 255
  - Set 200

Width 16
Height 12
FramePos -3 -2
Duration 300
BlendMode kAdd
if c > 2
  - Set 0
  if x > 7
    - Set 30
    - Set 0
"""

FEATURES = """
Width 32
Height 24
Bitdepth 8
XYB
Gaborish
EPF 2
Noise 0.05 0.1 0.15 0.2 0.25 0.3 0.3 0.3
SplineQuantizationAdjustment 1
Spline
0.02 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
0.4 0.1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
0.1 0 0.05 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
3 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
3 4
16 20
28 6
EndSpline
if c > 0
  - Set 0
  if y > 11
    - Set 300
    - Set 120
"""

ORIENTED = """
Width 20
Height 12
Bitdepth 8
Orientation 5
RCT 6
Squeeze
if c > 0
  if x > 9
    - Set 40
    - N - 3
  if y > 0
    - N + 14
    - W + 9
"""

HDR = """
Width 16
Height 8
Bitdepth 10
PQ
Rec2100
if c > 1
  - Set 700
  if y > 3
    - W + 50
    - W + 20
"""

# Alpha takes the size given so far, and it has to be the size of the upsampled picture.
PALETTE = """
Width 20
Height 12
Bitdepth 8
DeltaPalette
if x > 9
  if y > 5
    - Set 30
    - W + 3
  - N + 1
"""

# The samples are the bit patterns of the floats: 1056964608 is 0.5.
FLOAT = """
Width 16
Height 8
Bitdepth 32
FloatExpBits 8
if c > 1
  - Set 1056964608
  if y > 3
    - W + 4000000
    - Set 1040000000
"""

HALF = """
Width 16
Height 8
Bitdepth 16
FloatExpBits 5
if c > 1
  - Set 14336
  if y > 3
    - W + 100
    - Set 13000
"""

CBYCR = """
Width 16
Height 8
Bitdepth 8
CbYCr
if c > 0
  - Set 128
  if y > 3
    - W + 9
    - Set 60
"""

HIDDEN = """
Width 16
Height 8
Bitdepth 8
Alpha
HiddenChannel 2
if c > 3
  - Set 77
  if c > 2
    - W + 10
    - N + 7
"""

UPSAMPLED = """
Width 24
Height 16
Alpha
Width 12
Height 8
Bitdepth 8
Upsample 2
Upsample_EC 4
if c > 2
  if x > 1
    - Set 255
    - Set 90
  if x > 5
    - N + 20
    - W + 30
"""


def main():
    rgb8 = write_pam("rgb8.pam", picture(24, 16, 3, 255), 255)
    rgba8 = write_pam("rgba8.pam", picture(24, 16, 4, 255), 255)
    wide8 = write_pam("wide8.pam", picture(40, 24, 3, 255), 255)
    deep = write_pam("deep.pam", picture(20, 12, 2, 65535), 65535)
    rgba16 = write_pam("rgba16.pam", picture(24, 16, 4, 65535), 65535)
    ppm = path("rgb8.ppm")
    with open(ppm, "wb") as f:
        rows = picture(24, 16, 3, 255)
        f.write(b"P6\n24 16\n255\n" + bytes(v for row in rows for pixel in row for v in pixel))
    jpeg = path("rgb8.jpg")
    run("cjpeg", "-sample", "2x2", "-quality", "85", "-outfile", jpeg, ppm)
    profiled = write_png("profiled.png", picture(24, 16, 3, 255), profile())
    plain = write_png("plain.png", picture(24, 16, 3, 255))
    glyphs = write_png("glyphs.png", page(32, 24))

    fixtures = [
        ("lossless", "A 24 by 16 card, lossless, as a bare codestream.", exact8(cjxl("lossless", rgb8, "-d", "0"))),
        ("alpha", "The card with an alpha ramp, lossless at the highest effort, in the ISO container.",
         exact8(cjxl("alpha", rgba8, "-d", "0", "-e", "9", "--container=1"))),
        ("deep", "A 20 by 12 gray card with alpha at 16 bits, lossless.", exact16(cjxl("deep", deep, "-d", "0"), 16)),
        ("lossy", "A 40 by 24 card in the lossy mode, VarDCT.", lossy(cjxl("lossy", wide8, "-d", "1.5"))),
        ("lossy16", "The card with alpha at 16 bits, lossy, in several passes and with noise.",
         lossy(cjxl("lossy16", rgba16, "-d", "1", "-p", "--photon_noise_iso=6400"), 16)),
        ("modular", "The card with alpha in the lossy Modular mode, stored at half its size.",
         lossy(cjxl("modular", rgba8, "-d", "1", "-m", "1", "--resampling=2", "--ec_resampling=2"))),
        ("jpeg", "A 4:2:0 JPEG of the card, recompressed with its reconstruction data.",
         lossy(cjxl("jpeg", jpeg, "--lossless_jpeg=1"))),
        ("features", "From jxl_from_tree: 32 by 24 in XYB with a spline, noise and both smoothing filters.",
         lossy(tree("features", FEATURES))),
        ("oriented", "From jxl_from_tree: 20 by 12, to be shown transposed; the hash is of the 12 by 20 picture.",
         exact8(tree("oriented", ORIENTED))),
        ("hdr", "From jxl_from_tree: 16 by 8 at 10 bits, Rec. 2100 primaries with the PQ curve.", exact16(tree("hdr", HDR), 10)),
        ("upsampled", "From jxl_from_tree: 12 by 8 stored, shown at 24 by 16, with alpha stored at a quarter of that.",
         lossy(tree("upsampled", UPSAMPLED))),
        ("icc", "The card, lossless, with an ICC profile of 448 bytes that no enumerated colour space matches.",
         exact8(cjxl("icc", profiled, "-d", "0"))),
        ("lfframe", "The card, lossy, with its low-frequency image in a frame of its own.",
         lossy(cjxl("lfframe", plain, "-d", "2", "--progressive_dc=1"))),
        ("patches", "A 32 by 24 page of one repeated glyph, lossy: a reference frame holds the glyph and patches place it.",
         lossy(cjxl("patches", glyphs, "-d", "1", "--patches=1"))),
        ("palette", "From jxl_from_tree: 20 by 12 through the palette that has no stored colours.",
         exact8(tree("palette", PALETTE))),
        ("float", "From jxl_from_tree: 16 by 8 of 32-bit float samples; the hash is of the 16-bit samples.",
         exact16(tree("float", FLOAT), 16)),
        ("half", "From jxl_from_tree: 16 by 8 of 16-bit float samples; the hash is of the 16-bit samples.",
         exact16(tree("half", HALF), 16)),
        ("cbycr", "From jxl_from_tree: 16 by 8 stored as YCbCr in the Modular mode.", lossy(tree("cbycr", CBYCR))),
        ("hidden", "From jxl_from_tree: 16 by 8 with alpha and two more extra channels that no viewer shows.",
         exact8(tree("hidden", HIDDEN))),
    ]
    profile_out = path("icc.out.icc")
    run("djxl", path("icc.jxl"), path("icc.out.png"), "--icc_out=" + profile_out)
    profile_hash = fnv(open(profile_out, "rb").read())

    animation = tree("animation", ANIMATION)
    apng = path("animation.apng")
    run("djxl", animation, apng, "--bits_per_sample=16")
    width, height, channels, depth, frames = read_png(apng)
    assert (channels, depth) == (4, 16)

    print("package io.github.yuroyami.imagekodec")
    print()
    print("/**")
    print(" * Small JPEG XL files (#42), each with what libjxl 0.11.1's djxl reads from it. A file that")
    print(" * decodes exactly has the FNV-1a hash of its pixels in [hashes]. A lossy file has djxl's own")
    print(" * pixels in [pixels], as alpha, red, green and blue bytes, narrowed from 16 bits.")
    print(" * `tools/jxl_fixtures.py` writes this file. `FuzzTest` mutates the files as well.")
    print(" */")
    print("internal object JxlFixtures {")
    for name, doc, (digest, reference) in fixtures:
        data = open(path(name + ".jxl"), "rb").read()
        print(f"    /** {doc} */")
        print(f"    val {name}: ByteArray = hex(\n{kotlin_hex(data)},\n    )\n")
    data = open(animation, "rb").read()
    print("    /** From jxl_from_tree: 16 by 12 with alpha, three frames; the last two are blended at an offset. */")
    print(f"    val animation: ByteArray = hex(\n{kotlin_hex(data)},\n    )\n")
    print("    val all: List<Pair<String, ByteArray>> = listOf(")
    for name, _, _ in fixtures:
        print(f'        "{name}" to {name},')
    print('        "animation" to animation,')
    print("    )\n")
    print("    /** The FNV-1a hash of djxl's pixels, for each file that decodes exactly. */")
    print("    val hashes: Map<String, Long> = mapOf(")
    for name, _, (digest, reference) in fixtures:
        if digest is not None:
            print(f'        "{name}" to {kotlin_long(digest)},')
    print("    )\n")
    print("    /** djxl's pixels, for each lossy file. */")
    print("    val pixels: Map<String, ByteArray> = mapOf(")
    for name, _, (digest, reference) in fixtures:
        if reference is not None:
            print(f'        "{name}" to hex(\n{kotlin_hex(reference, "            ")},\n        ),')
    print("    )\n")
    print("    /** The FNV-1a hash of the ICC profile that djxl reads from [icc]. */")
    print(f"    val iccHash: Long = {kotlin_long(profile_hash)}\n")
    print("    /** Each frame of [animation] as djxl composes it: its delay in milliseconds and its pixels. */")
    print("    val animationFrames: List<Pair<Int, ByteArray>> = listOf(")
    for delay, samples in frames:
        reference = argb8(width, height, 4, [(v + 128) // 257 for v in samples])
        print(f'        {delay} to hex(\n{kotlin_hex(reference, "            ")},\n        ),')
    print("    )")
    print("}")


main()
