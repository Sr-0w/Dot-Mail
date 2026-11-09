#!/usr/bin/env python3
"""Inspect a shrunk APK and its matching R8/manifest outputs; no execution, signing or device access."""
import argparse
import json
import re
import struct
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path


def dex_types_and_constructors(data):
    if data[:4] != b"dex\n" or data[7] != 0:
        raise ValueError("Unsupported DEX header")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    u16 = lambda offset: struct.unpack_from("<H", data, offset)[0]
    def leb(offset):
        result = shift = 0
        while True:
            value = data[offset]; offset += 1
            result |= (value & 127) << shift
            if not value & 128:
                return result, offset
            shift += 7
            if shift > 35:
                raise ValueError("Invalid DEX ULEB128")
    def string(index):
        offset = u32(u32(60) + 4 * index)
        _, offset = leb(offset)
        return data[offset:data.index(0, offset)].decode("utf-8", errors="replace")
    def typename(index):
        return string(u32(u32(68) + 4 * index))
    classes = set(); constructors = {}
    for n in range(u32(96)):
        row = u32(100) + n * 32
        name = typename(u32(row))
        classes.add(name)
        cursor = u32(row + 24)
        if not cursor:
            continue
        counts = []
        for _ in range(4):
            count, cursor = leb(cursor); counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, cursor = leb(cursor); _, cursor = leb(cursor)
        for count in counts[2:]:
            method = 0
            for _ in range(count):
                delta, cursor = leb(cursor); method += delta
                flags, cursor = leb(cursor); _, cursor = leb(cursor)
                definition = u32(92) + method * 8
                if string(u32(definition + 4)) != "<init>":
                    continue
                proto = u32(76) + u16(definition + 2) * 12
                params = u32(proto + 8)
                args = [] if not params else [typename(u16(params + 4 + 2 * p)) for p in range(u32(params))]
                constructors.setdefault(name, []).append({"public": bool(flags & 1), "parameters": args})
    return classes, constructors


def inspect(apk, mapping, manifest):
    text = Path(mapping).read_text()
    renamed = dict(re.findall(r"^([^ #].*?) -> ([^:]+):$", text, re.M))
    root = ET.parse(manifest).getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    application = root.find("application")
    assert application is not None
    assert application.get(ns + "debuggable", "false") == "false", "Release is debuggable"
    components = []
    for tag in ("application", "activity", "receiver", "service", "provider"):
        for item in root.iter(tag):
            name = item.get(ns + "name", "")
            if name.startswith("."):
                name = root.get("package") + name
            if name:
                components.append({"kind": tag, "name": name, "exported": item.get(ns + "exported"), "permission": item.get(ns + "permission")})
    classes = set(); constructors = {}; providers = set(); native = {}
    with zipfile.ZipFile(apk) as archive:
        assert "AndroidManifest.xml" in archive.namelist()
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                found, ctors = dex_types_and_constructors(archive.read(name)); classes |= found; constructors.update(ctors)
            if name.startswith("META-INF/") and any(x in name for x in ("providers", "mailcap", "services/")):
                content = archive.read(name).decode("utf-8", errors="replace")
                providers.update(re.findall(r"(?:class=|x-java-content-handler=)([\w.$]+)", content))
                if "/services/" in name:
                    providers.update(line.split("#", 1)[0].strip() for line in content.splitlines() if line.split("#", 1)[0].strip())
            if name.startswith("lib/") and name.endswith(".so"):
                data = archive.read(name)
                assert data[:4] == b"\x7fELF"
                endian = "<" if data[5] == 1 else ">"; wide = data[4] == 2
                offset = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
                size, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
                alignments = []
                for p in range(count):
                    at = offset + p * size
                    if struct.unpack_from(endian + "I", data, at)[0] == 1:
                        alignments.append(struct.unpack_from(endian + ("Q" if wide else "I"), data, at + (48 if wide else 28))[0])
                assert alignments and all(a >= 16384 for a in alignments), "Native ELF does not support 16 KiB pages"
                native[name] = alignments
    descriptor = lambda name: "L" + name.replace(".", "/") + ";"
    for name in providers:
        assert descriptor(name) in classes, f"Missing service/mailcap provider: {name}"
    workers = {name: target for name, target in renamed.items() if name.startswith("app.dot.mail.") and name.endswith("Worker")}
    required = ("SyncWorker", "OutboxWorker", "SnoozeWorker", "DraftMirrorWorker", "SnippetRepairWorker", "SearchIndexWorker", "OfflineWorker", "StorageCleanupWorker", "ThreadRepairWorker", "WidgetRefreshWorker")
    for suffix in required:
        candidates = [name for name in workers if name.endswith("." + suffix)]
        assert len(candidates) == 1, f"Missing persisted worker: {suffix}"
        name = candidates[0]
        assert workers[name] == name, f"Persisted worker name changed: {name}"
        assert any(c["public"] and c["parameters"] == ["Landroid/content/Context;", "Landroidx/work/WorkerParameters;"] for c in constructors.get(descriptor(name), [])), f"WorkerFactory constructor missing: {name}"
    for item in components:
        if item["name"].startswith("app.dot.mail."):
            assert descriptor(item["name"]) in classes, f"Manifest component missing: {item['name']}"
    for name in ("app.dot.mail.data.local.MailDatabase", "app.dot.mail.data.local.MailDatabase_Impl"):
        assert renamed.get(name) == name and descriptor(name) in classes, f"Room reflection name missing: {name}"
    return {"workers": workers, "manifest_components": components, "provider_classes": sorted(providers), "native_elf_load_alignments": native,
            "scope": "Static shrunk-artifact inspection; does not replace signed-device smoke or provider interoperability tests."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path); parser.add_argument("mapping", type=Path); parser.add_argument("manifest", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = inspect(args.apk, args.mapping, args.manifest)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {len(report['workers'])} persisted workers, {len(report['provider_classes'])} providers, manifest/Room names and native ELF alignment")


if __name__ == "__main__":
    main()
