#!/usr/bin/env python3
"""
Writes JxrFixtures.kt: small JPEG XR files, each with the hash of what jxrlib's JxrDecApp reads.

JxrEncApp of Microsoft's jxrlib (Debian's libjxr-tools) encodes pictures that this script draws,
from uncompressed TIFFs. Two files are rewritten to another codec subversion after encoding: HD
Photo's 0 and JPEG XR's 9, with hard tile edges, which JxrEncApp never writes, and JxrDecApp
reads the rewritten files as the subversion asks.

    python3 tools/jxr_fixtures.py > imagekodec/test/io/github/yuroyami/imagekodec/JxrFixtures.kt

Each file carries the FNV-1a hash of the samples JxrDecApp writes to a TIFF: gray, or red, green,
blue and alpha, in the pixel format's own values (integers, signed fixed point, the bits of a
half), each hashed as four bytes, least significant first. A packed pixel is one value.
"""
import os
import struct
import subprocess
import sys
import tempfile

WORK = tempfile.mkdtemp(prefix="jxr-fixtures-")


def run(*cmd):
    done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if done.returncode != 0:
        sys.exit("failed: " + " ".join(cmd) + "\n" + done.stdout.decode(errors="replace"))


def path(name):
    return os.path.join(WORK, name)


def picture(width, height):
    """A card with ramps, a checker edge and a patch of noise, as four channels from 0 to 1."""
    state = 12345
    out = []
    for y in range(height):
        for x in range(width):
            state = (state * 1103515245 + 12345) & 0x7FFFFFFF
            noise = (state >> 16) % 256 / 255
            if width // 5 <= x < width // 2 and height // 4 <= y < height // 2:
                pixel = [noise, 1 - noise, noise * noise]
            elif x > width * 3 // 4:
                pixel = [0.9 if (x // 3 + y // 4) % 2 == 0 else 0.1] * 3
            else:
                pixel = [((x * (5 + 2 * c) + y * (7 - 2 * c) + 40 * c) % 256) / 255 for c in range(3)]
            out.append(pixel + [((x * 3 + y * 11) % 256) / 255])
    return out


def write_tiff(name, width, height, spp, bits, sample_format, values):
    """An uncompressed little-endian TIFF of one strip, as JxrEncApp reads one."""
    code = {8: "B", 16: "H" if sample_format == 1 else "h", 32: "I"}[bits]
    if bits == 16 and sample_format == 3:
        code = "H"
    data = struct.pack("<%d%s" % (len(values), code), *values)
    entries = [(256, 4, [width]), (257, 4, [height]), (258, 3, [bits] * spp), (259, 3, [1]),
               (262, 3, [2 if spp >= 3 else 1]), (273, 4, [0]), (277, 3, [spp]), (278, 4, [height]),
               (279, 4, [len(data)]), (284, 3, [1])]
    if spp == 4:
        entries.append((338, 3, [2]))
    entries.append((339, 3, [sample_format] * spp))
    extra_at = 8 + 2 + 12 * len(entries) + 4
    extra = b""
    fields = []
    for tag, kind, vals in entries:
        raw = b"".join(struct.pack("<H" if kind == 3 else "<I", v) for v in vals)
        if len(raw) > 4:
            fields.append((tag, kind, len(vals), struct.pack("<I", extra_at + len(extra))))
            extra += raw
        else:
            fields.append((tag, kind, len(vals), raw.ljust(4, b"\0")))
    data_at = extra_at + len(extra)
    ifd = struct.pack("<H", len(fields))
    for tag, kind, count, raw in fields:
        ifd += struct.pack("<HHI", tag, kind, count) + (struct.pack("<I", data_at) if tag == 273 else raw)
    with open(path(name), "wb") as f:
        f.write(b"II*\0" + struct.pack("<I", 8) + ifd + b"\0\0\0\0" + extra + data)
    return path(name)


def half(v):
    return struct.unpack("<H", struct.pack("<e", v))[0]


def source(name, width, height, spp, bits, sample_format):
    pixels = picture(width, height)
    values = []
    for p in pixels:
        for c in range(spp):
            v = p[1] if spp == 1 else p[c]
            linear = c != 3
            if bits == 8:
                values.append(round(v * 255))
            elif sample_format == 1:
                values.append(round(v * 65535))
            elif sample_format == 2:
                values.append(round((v * 1.5 - 0.1 if linear else v) * 8192))
            else:
                values.append(half(v * 1.5 - 0.1 if linear else v))
    return write_tiff(name, width, height, spp, bits, sample_format, values)


def read_tiff(name):
    """The samples JxrDecApp wrote, in its order, as integers."""
    data = open(name, "rb").read()
    ifd = struct.unpack("<I", data[4:8])[0]
    tags = {}
    for i in range(struct.unpack("<H", data[ifd:ifd + 2])[0]):
        tag, kind, count, value = struct.unpack("<HHII", data[ifd + 2 + 12 * i:ifd + 14 + 12 * i])
        size = 2 if kind == 3 else 4
        if count * size > 4:
            raw = data[value:value + count * size]
        else:
            raw = data[ifd + 10 + 12 * i:ifd + 14 + 12 * i]
        tags[tag] = struct.unpack("<%d%s" % (count, "H" if kind == 3 else "I"), raw[:count * size])
    width, height, spp, bits = tags[256][0], tags[257][0], tags[277][0], tags[258][0]
    sf = tags.get(339, (1,))[0]
    start = tags[273][0]
    if bits in (5, 6):
        return list(struct.unpack("<%dH" % (width * height), data[start:start + 2 * width * height]))
    if bits == 10:
        return list(struct.unpack("<%dI" % (width * height), data[start:start + 4 * width * height]))
    n = width * height * spp
    code = {8: "B", 16: "h" if sf == 2 else "H", 32: "i"}[bits]
    return list(struct.unpack("<%d%s" % (n, code), data[start:start + n * bits // 8]))


def fnv(values):
    h = 0xCBF29CE484222325
    for v in values:
        for b in struct.pack("<i", v):
            h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def kotlin_long(h):
    return str(h - (1 << 64)) + "L" if h >= 1 << 63 else str(h) + "L"


def kotlin_hex(data, indent="        "):
    text = data.hex()
    return " +\n".join(indent + '"' + text[i:i + 160] + '"' for i in range(0, len(text), 160))


def subversion(data, version):
    """The file with every codestream's codec subversion set to [version]."""
    data = bytearray(data)
    ifd = struct.unpack("<I", data[4:8])[0]
    for i in range(struct.unpack("<H", data[ifd:ifd + 2])[0]):
        tag, _, _, value = struct.unpack("<HHII", data[ifd + 2 + 12 * i:ifd + 14 + 12 * i])
        if tag in (0xBCC0, 0xBCC2) and value:
            data[value + 8] = (data[value + 8] & 0xF0) | version
    return bytes(data)


# name, doc, size, (spp, bits, sample format), JxrEncApp -c, options, subversion
FIXTURES = [
    ("rgbLossless", "24bppRGB, lossless, frequency order.", (40, 24), (3, 8, 1), 9, ["-q", "1"], None),
    ("rgb420", "24bppRGB at 4:2:0 with both overlap levels.", (40, 24), (3, 8, 1), 9, ["-q", "0.5", "-d", "1", "-l", "2"], None),
    ("rgb422Spatial", "24bppRGB at 4:2:2, spatial order, no overlap.", (40, 24), (3, 8, 1), 9, ["-q", "0.6", "-d", "2", "-l", "0", "-f"], None),
    ("bgrTiles", "24bppBGR in four tiles.", (40, 40), (3, 8, 1), 0, ["-q", "0.7", "-U", "2", "2"], None),
    ("gray", "8bppGray, lossy.", (24, 20), (1, 8, 1), 2, ["-q", "0.8"], None),
    ("bgraPlanar", "32bppBGRA with planar alpha.", (24, 20), (4, 8, 1), 22, ["-q", "0.8"], None),
    ("bgraInterleaved", "32bppBGRA with interleaved alpha, lossless.", (24, 20), (4, 8, 1), 22, ["-q", "1", "-a", "3"], None),
    ("rgba64", "64bppRGBA, lossy.", (24, 20), (4, 16, 1), 23, ["-q", "0.9"], None),
    ("rgbHalf", "48bppRGBHalf, lossy.", (24, 20), (3, 16, 3), 12, ["-q", "0.9"], None),
    ("rgbaFixed", "64bppRGBAFixedPoint, lossless.", (24, 20), (4, 16, 2), 24, ["-q", "1"], None),
    ("rgb565", "16bppRGB565.", (24, 20), (3, 8, 1), 30, ["-q", "1"], None),
    ("hdPhoto", "24bppRGB rewritten to HD Photo's codec subversion 0, with tiles and both overlap levels.",
     (40, 40), (3, 8, 1), 9, ["-q", "0.6", "-l", "2", "-U", "2", "2"], 0),
    ("hardTiles", "24bppRGB at 4:2:0 rewritten to subversion 9, whose tile edges are hard.",
     (40, 40), (3, 8, 1), 9, ["-q", "0.6", "-d", "1", "-l", "2", "-U", "2", "2"], 9),
]


def main():
    results = []
    for name, doc, (w, h), (spp, bits, sf), code, options, version in FIXTURES:
        src = source(name + ".tif", w, h, spp, bits, sf)
        run("JxrEncApp", "-i", src, "-o", path(name + ".jxr"), "-c", str(code), *options)
        data = open(path(name + ".jxr"), "rb").read()
        if version is not None:
            data = subversion(data, version)
            with open(path(name + ".jxr"), "wb") as f:
                f.write(data)
        run("JxrDecApp", "-i", path(name + ".jxr"), "-o", path(name + ".out.tif"))
        results.append((name, doc, data, fnv(read_tiff(path(name + ".out.tif")))))
    print("package io.github.yuroyami.imagekodec")
    print()
    print("/**")
    print(" * Small JPEG XR files (#125) that jxrlib's JxrEncApp wrote, each with the FNV-1a hash of the samples")
    print(" * jxrlib's JxrDecApp reads from it, in the order it writes them to a TIFF. `tools/jxr_fixtures.py`")
    print(" * writes this file. `FuzzTest` mutates the files as well.")
    print(" */")
    print("internal object JxrFixtures {")
    for name, doc, data, _ in results:
        print(f"    /** {doc} */")
        print(f"    val {name}: ByteArray = hex(\n{kotlin_hex(data)},\n    )\n")
    print("    val all: List<Pair<String, ByteArray>> = listOf(")
    for name, _, _, _ in results:
        print(f'        "{name}" to {name},')
    print("    )\n")
    print("    /** The FNV-1a hash of JxrDecApp's samples of each file. */")
    print("    val hashes: Map<String, Long> = mapOf(")
    for name, _, _, digest in results:
        print(f'        "{name}" to {kotlin_long(digest)},')
    print("    )")
    print("}")


main()
