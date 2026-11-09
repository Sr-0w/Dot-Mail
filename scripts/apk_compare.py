#!/usr/bin/env python3
"""Compare unsigned APK bytes; optionally diagnose signed APK entry differences without claiming signature verification."""
import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

SIGNATURE = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.I)


def digest_file(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def entries(path, ignore_signatures=False):
    result = {}
    with zipfile.ZipFile(path) as archive:
        listing = archive.infolist()
        names = [item.filename for item in listing]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entry names")
        if len(listing) > 10000 or sum(i.file_size for i in listing) > 1024**3:
            raise ValueError("APK exceeds comparison limits")
        for item in listing:
            if item.is_dir() or (ignore_signatures and SIGNATURE.fullmatch(item.filename)):
                continue
            hasher = hashlib.sha256()
            with archive.open(item) as stream:
                while chunk := stream.read(65536):
                    hasher.update(chunk)
            result[item.filename] = {
                "sha256": hasher.hexdigest(), "size": item.file_size,
                "compressed_size": item.compress_size, "compression": item.compress_type,
                "timestamp": item.date_time, "external_attr": item.external_attr,
                "flags": item.flag_bits, "extra": item.extra.hex(),
            }
    return result


def compare(left, right, ignore_signatures=False):
    a, b = entries(left, ignore_signatures), entries(right, ignore_signatures)
    differences = []
    for name in sorted(a.keys() | b.keys()):
        x, y = a.get(name), b.get(name)
        if x != y:
            differences.append({"entry": name, "left": x, "right": y})
    same_bytes = digest_file(left) == digest_file(right)
    same_contents = a.keys() == b.keys() and all(a[n]["sha256"] == b[n]["sha256"] for n in a)
    return {
        "left_sha256": digest_file(left), "right_sha256": digest_file(right),
        "byte_identical": same_bytes, "entry_contents_identical": same_contents,
        "signature_entries_ignored": ignore_signatures,
        "verdict": ("CONTENTS_MATCH_ONLY" if same_contents else "DIFF") if ignore_signatures else ("PASS" if same_bytes else "DIFF"),
        "differences": differences,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    parser.add_argument("--ignore-v1-signatures", action="store_true", help="diagnostics only; does not verify signature copying/reproducibility")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = compare(args.left, args.right, args.ignore_v1_signatures)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"APK comparison failed: {type(error).__name__}", file=sys.stderr)
        return 1
    serialized = json.dumps(report, indent=2, ensure_ascii=True) + "\n"
    if args.report:
        args.report.write_text(serialized)
    print(report["verdict"])
    return 0 if report["verdict"] in ("PASS", "CONTENTS_MATCH_ONLY") else 2


if __name__ == "__main__":
    sys.exit(main())
