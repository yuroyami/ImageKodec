#!/usr/bin/env python3
"""
Writes Av1Tables.kt, the constant tables of the AV1 decoder behind AVIF, from the
AV1 Bitstream & Decoding Process Specification itself, so no table is typed by hand.

AV1 defines its default CDFs, quantizer lookups, quantizer matrices, scan orders,
filter taps and the film grain Gaussian sequence as data; nothing derives them,
so they are copied, and this script is the only way they enter the tree. It reads
every array the specification declares, resolves the named constants a few of them
use through the specification's own enumerations, checks each array's element count
against its declared dimensions, and writes them with the SHA-256 of the document
it read and the CRC-32 of every table. Av1TablesTest holds the Kotlin file to those
CRCs, so an edit by hand fails.

    curl -sSL -o av1-spec.html https://aomediacodec.github.io/av1-spec/
    python3 tools/av1_tables.py av1-spec.html > \\
        imagekodec/src/commonMain/kotlin/io/github/yuroyami/imagekodec/codec/avif/Av1Tables.kt

The tables hold 135 thousand numbers, too many for array literals: a JVM method
holds 64 KB of bytecode. They are written as one stream of zigzag varints, deflated
and in Base64, which the Kotlin file inflates once with the library's own inflate.
"""
import base64
import hashlib
import html.parser
import re
import sys
import zlib

# Enumerations the specification defines in its semantics, for the arrays that name them.
BLOCK = ["4X4", "4X8", "8X4", "8X8", "8X16", "16X8", "16X16", "16X32", "32X16", "32X32", "32X64",
         "64X32", "64X64", "64X128", "128X64", "128X128", "4X16", "16X4", "8X32", "32X8", "16X64", "64X16"]
TX = ["4X4", "8X8", "16X16", "32X32", "64X64", "4X8", "8X4", "8X16", "16X8", "16X32", "32X16",
      "32X64", "64X32", "4X16", "16X4", "8X32", "32X8", "16X64", "64X16"]
TX_TYPE = ["DCT_DCT", "ADST_DCT", "DCT_ADST", "ADST_ADST", "FLIPADST_DCT", "DCT_FLIPADST",
           "FLIPADST_FLIPADST", "ADST_FLIPADST", "FLIPADST_ADST", "IDTX", "V_DCT", "H_DCT",
           "V_ADST", "H_ADST", "V_FLIPADST", "H_FLIPADST"]
MODES = ["DC_PRED", "V_PRED", "H_PRED", "D45_PRED", "D135_PRED", "D113_PRED", "D157_PRED",
         "D203_PRED", "D67_PRED", "SMOOTH_PRED", "SMOOTH_V_PRED", "SMOOTH_H_PRED", "PAETH_PRED"]
REFS = ["INTRA_FRAME", "LAST_FRAME", "LAST2_FRAME", "LAST3_FRAME", "GOLDEN_FRAME",
        "BWDREF_FRAME", "ALTREF2_FRAME", "ALTREF_FRAME"]
WEDGES = ["WEDGE_HORIZONTAL", "WEDGE_VERTICAL", "WEDGE_OBLIQUE27", "WEDGE_OBLIQUE63",
          "WEDGE_OBLIQUE117", "WEDGE_OBLIQUE153"]


class Text(html.parser.HTMLParser):
    """The document's text, one line for each block element, scripts and styles left out."""

    def __init__(self):
        super().__init__()
        self.out = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.skip += 1
        if tag in ("p", "div", "br", "tr", "h1", "h2", "h3", "h4", "h5", "li", "pre"):
            self.out.append("\n")
        if tag in ("td", "th"):
            self.out.append("\t")

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.skip -= 1

    def handle_data(self, data):
        if not self.skip:
            self.out.append(data)


def constants(text):
    """The specification's constants section: a name on one line, its value on the next."""
    lines = [l.strip() for l in text.split("\n") if l.strip()]
    found = {}
    start = lines.index("REFS_PER_FRAME")
    for i in range(start, len(lines) - 1):
        if lines[i] == "Conventions":
            break
        if re.fullmatch(r"[A-Z][A-Z0-9_]+", lines[i]) and re.match(r"[-0-9(]", lines[i + 1]):
            found[lines[i]] = lines[i + 1]
    return found


def symbols(text):
    table = {}
    for i, name in enumerate(BLOCK):
        table["BLOCK_" + name] = i
    table["BLOCK_INVALID"] = len(BLOCK)
    for i, name in enumerate(TX):
        table["TX_" + name] = i
    for group in (TX_TYPE, MODES, REFS, WEDGES):
        for i, name in enumerate(group):
            table[name] = i
    table.update(RESTORE_NONE=0, RESTORE_WIENER=1, RESTORE_SGRPROJ=2, RESTORE_SWITCHABLE=3, NONE=-1)
    raw = constants(text)

    def value(name, depth=0):
        if name in table:
            return table[name]
        expression = raw[name]
        for other in sorted(set(re.findall(r"[A-Z][A-Z0-9_]+", expression)), key=len, reverse=True):
            expression = re.sub(r"\b%s\b" % other, str(value(other, depth + 1)), expression)
        table[name] = int(eval(expression, {"__builtins__": {}}))
        return table[name]

    for name in raw:
        value(name)
    return table


def arrays(text, table):
    """Every array the specification declares, in order: (name, dimensions, values)."""
    declaration = re.compile(r"^[ \t]*([A-Z][A-Za-z0-9_]*)[ \t]*((?:\[[^\]\n]*\][ \t]*)+)=\s*\{", re.M)
    for m in declaration.finditer(text):
        start = m.end() - 1
        depth = 0
        end = start
        while True:
            if text[end] == "{":
                depth += 1
            elif text[end] == "}":
                depth -= 1
                if depth == 0:
                    break
            end += 1
        body = re.sub(r"/\*.*?\*/|//[^\n]*", "", text[start:end + 1], flags=re.S)
        values = []
        # Each element is a number, a named constant or a small expression of them, such as 128 * 128.
        for element in re.split(r"[{},]", body):
            element = element.strip()
            if not element:
                continue
            element = re.sub(r"[A-Za-z_][A-Za-z0-9_]*", lambda s: str(table[s.group(0)]), element)
            if not re.fullmatch(r"[-+*() 0-9<>\s]+", element):
                sys.exit("%s has an element %r" % (m.group(1), element))
            # The document misses a comma in Split_Tx_Size ("TX_32X32   TX_4X8"): two values with
            # only white space between them are two elements.
            for part in re.split(r"(?<=[0-9)])\s+(?=[-0-9(])", element):
                values.append(int(eval(part, {"__builtins__": {}})))
        dims = []
        for d in re.findall(r"\[([^\]]*)\]", m.group(2)):
            expression = d.strip()
            for other in sorted(set(re.findall(r"[A-Z][A-Z0-9_]+", expression)), key=len, reverse=True):
                expression = re.sub(r"\b%s\b" % other, str(table[other]), expression)
            dims.append(int(eval(expression, {"__builtins__": {}})))
        yield m.group(1), dims, values


def kotlin_name(name):
    return re.sub(r"_+(.)", lambda m: m.group(1).upper(), name[0].lower() + name[1:])


def varint(v, out):
    z = (v << 1) ^ (v >> 63)
    while True:
        b = z & 0x7F
        z >>= 7
        if z:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def main(path):
    raw = open(path, "rb").read()
    parser = Text()
    parser.feed(raw.decode("utf-8"))
    text = "".join(parser.out)
    table = symbols(text)
    seen = set()
    entries = []
    for name, dims, values in arrays(text, table):
        if name in seen:
            continue
        seen.add(name)
        count = 1
        for d in dims:
            count *= d
        if count != len(values):
            # A few arrays declare an outer bound larger than the rows they list (the inverse
            # transform-type sets list only their used entries): keep what the document lists.
            if len(values) > count:
                sys.exit("%s declares %d values and lists %d" % (name, count, len(values)))
        entries.append((name, dims, values))
    stream = bytearray()
    for _, _, values in entries:
        for v in values:
            varint(v, stream)
    packed = base64.b64encode(zlib.compress(bytes(stream), 9)).decode("ascii")
    chunks = [packed[i:i + 60000] for i in range(0, len(packed), 60000)]
    w = sys.stdout.write
    w("// Generated by tools/av1_tables.py from the AV1 Bitstream & Decoding Process Specification\n")
    w("// (SHA-256 %s). Do not edit: run the script.\n" % hashlib.sha256(raw).hexdigest())
    w("package io.github.yuroyami.imagekodec.codec.avif\n\n")
    w("import io.github.yuroyami.imagekodec.internal.flate.Zlib\n\n")
    w("/**\n * Every array the AV1 specification declares, flattened in row-major order, under its\n")
    w(" * own name in camel case: the default CDFs, the quantizer lookups and matrices, the scan\n")
    w(" * orders, the filter taps and the film grain Gaussian sequence. [CRC32] holds each one's\n")
    w(" * CRC-32 over its values as 32-bit little-endian integers, which Av1TablesTest checks.\n */\n")
    w("internal object Av1Tables {\n")
    for i, c in enumerate(chunks):
        w('    private const val PACKED_%d = "%s"\n' % (i, c))
    w("\n    private val all: Array<IntArray> = unpack(%s, intArrayOf(%s))\n\n" % (
        " + ".join("PACKED_%d" % i for i in range(len(chunks))),
        ", ".join(str(len(v)) for _, _, v in entries)))
    for i, (name, dims, values) in enumerate(entries):
        w("    /** `%s%s` */\n" % (name, "".join("[%d]" % d for d in dims)))
        w("    val %s: IntArray = all[%d]\n" % (kotlin_name(name), i))
    w("\n    /** The CRC-32 of each table above, in the same order. */\n")
    w("    val CRC32: LongArray = longArrayOf(\n")
    for name, _, values in entries:
        crc = zlib.crc32(b"".join(int(v).to_bytes(4, "little", signed=True) for v in values))
        w("        0x%08XL, // %s\n" % (crc, name))
    w("    )\n\n")
    w("    /** The names of the tables, in the same order. */\n")
    w("    val NAMES: List<String> = listOf(\n")
    for name, _, _ in entries:
        w('        "%s",\n' % name)
    w("    )\n\n")
    w("""    internal val tables: List<IntArray> get() = all.toList()

    private fun unpack(text: String, counts: IntArray): Array<IntArray> {
        val bytes = Zlib.decompress(base64(text), maximumSize = 1L shl 22)
        var at = 0
        fun next(): Int {
            var z = 0L
            var shift = 0
            while (true) {
                val b = bytes[at++].toInt() and 0xFF
                z = z or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            return ((z ushr 1) xor -(z and 1)).toInt()
        }
        return Array(counts.size) { t -> IntArray(counts[t]) { next() } }
    }

    private fun base64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val digits = IntArray(128) { -1 }
        for (i in alphabet.indices) digits[alphabet[i].code] = i
        val clean = text.trimEnd('=')
        val out = ByteArray(clean.length * 3 / 4)
        var bits = 0
        var count = 0
        var o = 0
        for (ch in clean) {
            bits = (bits shl 6) or digits[ch.code]
            count += 6
            if (count >= 8) {
                count -= 8
                out[o++] = (bits shr count).toByte()
            }
        }
        return out
    }
}
""")


if __name__ == "__main__":
    main(sys.argv[1])
