#!/usr/bin/env python3
"""
Writes HevcTables.kt, the constant tables of the HEVC decoder in imagekodec-heic, from the text of
ITU-T H.265 itself, so no table is typed by hand.

The tables are the ones the specification gives as data, which nothing derives: the default scaling
lists of Table 7-6, the context initialisation values of Tables 9-5 to 9-31, the arithmetic decoder's
Tables 9-50, 9-52 and 9-53, the intra tables 8-3 and 8-5, the chroma QP table 8-10 and the transform
matrices of 8-316, 8-319 and 8-321. The script reads the text that `pdftotext -layout` makes of the
07/2024 edition (V10), checks each table's element count, and writes the tables with the SHA-256 of
the text it read and the CRC-32 of every table. HevcTablesTest holds the Kotlin file to those CRCs,
so an edit by hand fails.

    pdftotext -layout T-REC-H.265-202407-I.pdf h265.txt
    python3 tools/hevc_tables.py h265.txt > \\
        imagekodec-heic/src/io/github/yuroyami/imagekodec/heic/HevcTables.kt
"""
import hashlib
import re
import sys
import zlib

MINUS = "−"


def ints(s):
    """The integers of a line of the text, which writes a minus sign as U+2212."""
    return [int(v) for v in re.findall(r"-?\d+", s.replace(MINUS, "-"))]


def body_after(text, heading, end_marker):
    """The text after the last occurrence of [heading] (the first is the list of tables) up to [end_marker]."""
    starts = [m.end() for m in re.finditer(re.escape(heading), text)]
    if len(starts) < 2:
        raise SystemExit(f"{heading!r} not found")
    end = text.index(end_marker, starts[-1])
    return text[starts[-1]:end]


def table_7_6(text):
    """The two 64-entry default lists of Table 7-6: matrixId 0..2 (intra) and 3..5 (inter)."""
    body = body_after(text, "Table 7-6 – Specification of default values of ScalingList", "The four-dimensional array ScalingFactor")
    intra, inter = [], []
    for line in body.splitlines():
        m = re.match(r"\s*ScalingList\[ 1\.\.3 \]\[ (0\.\.2|3\.\.5) \]\[ i \]\s+(.*)$", line)
        if not m:
            continue
        values = [int(v) for v in m.group(2).split()]
        if len(values) != 16:
            raise SystemExit(f"Table 7-6: a row of {len(values)} values: {line!r}")
        (intra if m.group(1) == "0..2" else inter).extend(values)
    if len(intra) != 64 or len(inter) != 64:
        raise SystemExit(f"Table 7-6: {len(intra)} and {len(inter)} values, not 64 each")
    return intra, inter


# Tables 9-5 to 9-31: the syntax element each gives initValue for, and its number of ctxIdx.
INIT_TABLES = [
    (5, "SAO_MERGE", "sao_merge_left_flag and sao_merge_up_flag", 3),
    (6, "SAO_TYPE", "sao_type_idx_luma and sao_type_idx_chroma", 3),
    (7, "SPLIT_CU", "split_cu_flag", 9),
    (8, "TRANSQUANT_BYPASS", "cu_transquant_bypass_flag", 3),
    (9, "CU_SKIP", "cu_skip_flag", 6),
    (10, "PRED_MODE", "pred_mode_flag", 2),
    (11, "PART_MODE", "part_mode", 9),
    (12, "PREV_INTRA_LUMA_PRED", "prev_intra_luma_pred_flag", 3),
    (13, "INTRA_CHROMA_PRED_MODE", "intra_chroma_pred_mode", 3),
    (14, "RQT_ROOT_CBF", "rqt_root_cbf", 2),
    (15, "MERGE_FLAG", "merge_flag", 2),
    (16, "MERGE_IDX", "merge_idx", 2),
    (17, "INTER_PRED_IDC", "inter_pred_idc", 10),
    (18, "REF_IDX", "ref_idx_l0 and ref_idx_l1", 4),
    (19, "MVP_FLAG", "mvp_l0_flag and mvp_l1_flag", 2),
    (20, "SPLIT_TRANSFORM", "split_transform_flag", 9),
    (21, "CBF_LUMA", "cbf_luma", 6),
    (22, "CBF_CHROMA", "cbf_cb and cbf_cr", 15),
    (23, "ABS_MVD", "abs_mvd_greater0_flag and abs_mvd_greater1_flag", 4),
    (24, "CU_QP_DELTA_ABS", "cu_qp_delta_abs", 6),
    (25, "TRANSFORM_SKIP", "transform_skip_flag", 6),
    (26, "LAST_X_PREFIX", "last_sig_coeff_x_prefix", 54),
    (27, "LAST_Y_PREFIX", "last_sig_coeff_y_prefix", 54),
    (28, "CODED_SUB_BLOCK", "coded_sub_block_flag", 12),
    (29, "SIG_COEFF", "sig_coeff_flag", 132),
    (30, "GREATER1", "coeff_abs_level_greater1_flag", 72),
    (31, "GREATER2", "coeff_abs_level_greater2_flag", 18),
]


def init_table(text, number, count):
    heading = f"Table 9-{number} – Values of initValue for ctxIdx of"
    starts = [m.end() for m in re.finditer(re.escape(heading), text)]
    if len(starts) < 2:
        raise SystemExit(f"Table 9-{number} not found")
    start = starts[-1]
    end = text.index(f"Table 9-{number + 1} – Values of initValue", start)
    values = []
    for line in text[start:end].splitlines():
        m = re.match(r"\s*initValue\s+(.*)$", line)
        if m:
            values.extend(ints(m.group(1)))
    if len(values) != count:
        raise SystemExit(f"Table 9-{number}: {len(values)} values, not {count}")
    return values


def table_9_50(text):
    body = body_after(text, "Table 9-50 – Specification of ctxIdxMap[ i ]", "9.3.4.2.6")
    for line in body.splitlines():
        m = re.match(r"\s*ctxIdxMap\[ i \]\s+(.*)$", line)
        if m:
            values = ints(m.group(1))
            if len(values) != 15:
                raise SystemExit(f"Table 9-50: {len(values)} values")
            return values
    raise SystemExit("Table 9-50: no row")


def table_9_52(text):
    """rangeTabLps[ pStateIdx ][ qRangeIdx ], 64 states of 4, from rows that hold two states each."""
    body = body_after(text, "Table 9-52 – Specification of rangeTabLps depending on the values of pStateIdx and qRangeIdx", "Table 9-53")
    rows = {}
    for line in body.splitlines():
        v = ints(line)
        if len(v) == 10:
            rows[v[0]] = v[1:5]
            rows[v[5]] = v[6:10]
    if sorted(rows) != list(range(64)):
        raise SystemExit(f"Table 9-52: states {sorted(rows)}")
    return [x for s in range(64) for x in rows[s]]


def table_9_53(text):
    body = body_after(text, "Table 9-53 – State transition table", "9.3.4.3.3")
    lps, mps, states = [], [], []
    for line in body.splitlines():
        s = line.strip()
        if s.startswith("pStateIdx"):
            states.extend(ints(s))
        elif s.startswith("transIdxLps"):
            lps.extend(ints(s))
        elif s.startswith("transIdxMps"):
            mps.extend(ints(s))
    if states != list(range(64)) or len(lps) != 64 or len(mps) != 64:
        raise SystemExit("Table 9-53: not 64 states")
    return lps, mps


def table_8_3(text):
    """IntraPredModeC of 4:2:2 for each modeIdx 0..34; the table gives X itself for modeIdx up to 2."""
    body = body_after(text, "Table 8-3 – Specification of IntraPredModeC when ChromaArrayType is equal to 2", "8.4.4")
    rows = [ints(line) for line in body.splitlines() if line.strip().startswith("IntraPredModeC")]
    if len(rows) != 2 or len(rows[0]) != 15 or len(rows[1]) != 17:
        raise SystemExit(f"Table 8-3: rows {rows}")
    return [0, 1, 2] + rows[0] + rows[1]


def table_8_5(text):
    """intraPredAngle for predModeIntra 2..34; the table has no angle for mode 1."""
    body = body_after(text, "Table 8-5 – Specification of intraPredAngle", "Table 8-6")
    values = []
    for line in body.splitlines():
        if line.strip().startswith("intraPredAngle"):
            values.extend(ints(line))
    if len(values) != 33:
        raise SystemExit(f"Table 8-5: {len(values)} angles")
    return values


def table_8_10(text):
    """QpC for qPi 30..43; below 30 it is qPi, above 43 qPi - 6."""
    body = body_after(text, "Table 8-10 – Specification of QpC as a function of qPi for ChromaArrayType equal to 1", "8.6.2")
    for line in body.splitlines():
        s = line.strip()
        if s.startswith("QpC"):
            values = ints(s.replace("qPi − 6", ""))
            if len(values) != 14:
                raise SystemExit(f"Table 8-10: {len(values)} values")
            return values
    raise SystemExit("Table 8-10: no row")


def matrix_rows(text, start_marker, rows, cols):
    start = text.index(start_marker)
    out = []
    for line in text[start:].splitlines()[1:]:
        m = re.match(r"\s*\{([^{}]*)\}", line)
        if m:
            v = ints(m.group(1))
            if len(v) != cols:
                raise SystemExit(f"{start_marker}: a row of {len(v)} values: {line!r}")
            out.append(v)
            if len(out) == rows:
                return out
    raise SystemExit(f"{start_marker}: {len(out)} rows")


def dct_matrix(text):
    """The 32x32 matrix of 8-319 and 8-321: row k holds the k-th basis function over the 32 samples."""
    left = matrix_rows(text, "transMatrixCol0to15 =", 32, 16)
    right = matrix_rows(text, "transMatrixCol16to31 =", 32, 16)
    return [x for k in range(32) for x in left[k] + right[k]]


def kotlin_array(name, values, doc, per_row=16):
    rows = [", ".join(str(v) for v in values[i:i + per_row]) for i in range(0, len(values), per_row)]
    crc = zlib.crc32(bytes(v & 0xFF for v in values)) & 0xFFFFFFFF
    body = ",\n        ".join(rows)
    return (f"    /** {doc} CRC-32 of the bytes: 0x{crc:08X}. */\n"
            f"    val {name}: IntArray = intArrayOf(\n        {body},\n    )\n")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: hevc_tables.py h265.txt")
    raw = open(sys.argv[1], "rb").read()
    text = raw.decode("utf-8").replace("\f", "\n")
    intra, inter = table_7_6(text)
    parts = [
        kotlin_array("DEFAULT_SCALING_INTRA", intra,
                     "Table 7-6: ScalingList[ 1..3 ][ 0..2 ][ i ], the default 8x8 list of intra blocks, in up-right diagonal order."),
        kotlin_array("DEFAULT_SCALING_INTER", inter,
                     "Table 7-6: ScalingList[ 1..3 ][ 3..5 ][ i ], the default 8x8 list of inter blocks, in up-right diagonal order."),
    ]
    for number, name, element, count in INIT_TABLES:
        parts.append(kotlin_array(f"INIT_{name}", init_table(text, number, count),
                                  f"Table 9-{number}: initValue of each ctxIdx of {element}."))
    parts.append(kotlin_array("CTX_IDX_MAP", table_9_50(text), "Table 9-50: ctxIdxMap[ i ] of sig_coeff_flag in a 4x4 block."))
    parts.append(kotlin_array("RANGE_TAB_LPS", table_9_52(text), "Table 9-52: rangeTabLps[ pStateIdx ][ qRangeIdx ] at pStateIdx * 4 + qRangeIdx."))
    lps, mps = table_9_53(text)
    parts.append(kotlin_array("TRANS_IDX_LPS", lps, "Table 9-53: transIdxLps( pStateIdx )."))
    parts.append(kotlin_array("TRANS_IDX_MPS", mps, "Table 9-53: transIdxMps( pStateIdx )."))
    parts.append(kotlin_array("CHROMA_422_MODE", table_8_3(text), "Table 8-3: IntraPredModeC of 4:2:2 for modeIdx 0..34."))
    parts.append(kotlin_array("INTRA_PRED_ANGLE", table_8_5(text), "Table 8-5: intraPredAngle of predModeIntra 2..34, at predModeIntra - 2."))
    parts.append(kotlin_array("QPC_30_TO_43", table_8_10(text), "Table 8-10: QpC of 4:2:0 for qPi 30..43."))
    parts.append(kotlin_array("DST_4", [x for row in matrix_rows(text, "transMatrix =  ", 4, 4) for x in row],
                              "8-316: the 4x4 DST, row k the k-th basis function over the four samples."))
    parts.append(kotlin_array("DCT_32", dct_matrix(text),
                              "8-319 and 8-321: the 32x32 DCT, row k the k-th basis function over the 32 samples, at k * 32 + n.", 32))
    print("// Generated by tools/hevc_tables.py from the text of ITU-T H.265 (07/2024).")
    print(f"// SHA-256 of the text: {hashlib.sha256(raw).hexdigest()}. Do not edit by hand.")
    print("package io.github.yuroyami.imagekodec.heic")
    print()
    print("/** The constant tables of ITU-T H.265 that nothing derives. */")
    print("internal object HevcTables {")
    print("\n".join(parts).rstrip("\n"))
    print("}")


if __name__ == "__main__":
    main()
