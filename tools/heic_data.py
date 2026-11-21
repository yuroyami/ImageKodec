#!/usr/bin/env python3
"""Fetch the HEIC test data that the repository does not commit, and check it.

The data is the JCT-VC conformance packages of ITU-T H.265.1 and a few public HEIC sample
files. Neither states a licence, so they are fetched, never committed. Each file must match
the SHA-256 in tools/heic_data.manifest, or the script fails.

    tools/heic_data.py                 # everything, into reference/heic
    tools/heic_data.py --only samples  # only the sample files
    tools/heic_data.py --print-hash    # the hash of the manifest, for a CI cache key

The tests read reference/heic, or the folder in IMAGEKODEC_HEIC_DATA.
"""
import argparse
import hashlib
import os
import sys
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFEST = os.path.join(ROOT, "tools", "heic_data.manifest")


def entries():
    with open(MANIFEST) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            sha, path, url = line.split()
            yield sha, path, url


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, dest):
    tmp = dest + ".part"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(tmp, dest)
            return
        except OSError as e:
            if attempt == 3:
                raise SystemExit(f"cannot fetch {url}: {e}")
            time.sleep(2 ** attempt)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default=os.environ.get("IMAGEKODEC_HEIC_DATA", os.path.join(ROOT, "reference", "heic")))
    ap.add_argument("--only", choices=["samples", "conformance"])
    ap.add_argument("--print-hash", action="store_true")
    args = ap.parse_args()
    if args.print_hash:
        print(sha256(MANIFEST))
        return
    bad = 0
    for sha, path, url in entries():
        if args.only and not path.startswith(args.only + "/"):
            continue
        dest = os.path.join(args.dest, path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if not (os.path.exists(dest) and sha256(dest) == sha):
            fetch(url, dest)
        if sha256(dest) != sha:
            print(f"checksum mismatch: {path}", file=sys.stderr)
            bad += 1
            continue
        if dest.endswith(".zip"):
            folder = dest[:-4]
            if not os.path.isdir(folder):
                with zipfile.ZipFile(dest) as z:
                    for name in z.namelist():
                        # Some packages put their files in a folder, some do not: flatten both.
                        base = os.path.basename(name)
                        # The decoded .yuv and the syntax .trc files are large, and the .md5 files say the same.
                        if base and ".." not in name and not base.endswith((".yuv", ".trc")):
                            os.makedirs(folder, exist_ok=True)
                            with z.open(name) as src, open(os.path.join(folder, base), "wb") as out:
                                out.write(src.read())
    if bad:
        raise SystemExit(f"{bad} file(s) do not match the manifest")


if __name__ == "__main__":
    main()
