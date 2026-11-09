#!/usr/bin/env python3
"""Prove notice exclusions from the current release DEX, R8 mapping/usage and APK resources.
Unknown or incomplete attribution is included. No coordinate/package exclusion list is used.
"""
import argparse
import io
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from audit_release import dex_types_and_constructors

LEGAL = re.compile(r'(?:license|licence|notice|copying|copyright)(?:[._-].*)?$', re.I)
SIGNATURE = re.compile(r'META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$', re.I)
BUILD_FILES = {'AndroidManifest.xml', 'R.txt', 'public.txt', 'proguard.txt', 'annotations.zip', 'lint.jar',
               'baseline-prof.txt', 'baseline-prof.profm', 'META-INF/MANIFEST.MF', 'module-info.class'}


def contents(data):
    classes, resources = set(), set()
    uncertain = False
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for name in z.namelist():
            if name.endswith('/'):
                continue
            if name.endswith('.class'):
                source = re.sub(r'^META-INF/versions/\d+/', '', name)
                if source != 'module-info.class':
                    classes.add(source[:-6].replace('/', '.'))
            elif name.endswith('.jar') and name != 'lint.jar':
                c, r, u = contents(z.read(name)); classes |= c; resources |= r; uncertain |= u
            elif name in BUILD_FILES:
                if name == 'AndroidManifest.xml':
                    # AAR manifests can contribute components/permissions even without code.
                    raw = z.read(name)
                    if any(token in raw for token in (b'<uses-permission', b'<activity', b'<service', b'<receiver', b'<provider')):
                        uncertain = True
            elif name.startswith('META-INF/com/android/build/') or LEGAL.fullmatch(Path(name).name) or SIGNATURE.fullmatch(name):
                continue
            elif name.startswith('res/'):
                # Merged/renamed Android resources cannot be assigned to a library by filename alone.
                uncertain = True
            else:
                resources.add('lib/' + name[4:] if name.startswith('jni/') else name)
    return classes, resources, uncertain


def analyze(inventory, apk, mapping, usage):
    mapping_text = Path(mapping).read_text()
    renamed = dict(re.findall(r'^([^ #].*?) -> ([^:]+):$', mapping_text, re.M))
    inlined = set(re.findall(r'\b([\w.$]+)\.[\w$<>]+\(', mapping_text))
    removed = {line.strip() for line in Path(usage).read_text().splitlines()
               if line and not line[0].isspace() and not line.endswith(':')}
    dex_classes = set()
    with zipfile.ZipFile(apk) as z:
        entries = set(z.namelist())
        for name in entries:
            if re.fullmatch(r'classes\d*\.dex', name):
                found, _ = dex_types_and_constructors(z.read(name))
                dex_classes |= {x[1:-1].replace('/', '.') for x in found}
    if not dex_classes:
        raise ValueError('Cannot prove exclusions without release DEX class definitions')
    artifacts = {}
    for item in inventory['artifacts']:
        artifacts.setdefault(item['component'], []).append(Path(item['path']))
    result = {}
    for coordinate in inventory['components']:
        binaries = artifacts.get(coordinate, [])
        if not binaries:
            result[coordinate] = {'status': 'metadata-only', 'reason': 'No selected runtime binary artifact'}
            continue
        classes, resources = set(), set(); uncertain = False
        for path in binaries:
            c, r, u = contents(path.read_bytes()); classes |= c; resources |= r; uncertain |= u
        kept = classes & (renamed.keys() | inlined | dex_classes)
        unknown = classes - removed - kept
        resource_hits = set()
        for name in resources:
            candidates = {name}
            if name.startswith('META-INF/services/'):
                service = name[len('META-INF/services/'):]
                candidates.add('META-INF/services/' + renamed.get(service, service))
            resource_hits |= candidates & entries
        status = 'included' if kept or unknown or uncertain or resource_hits else 'stripped'
        result[coordinate] = {'status': status, 'classes': len(classes), 'kept_or_inlined': len(kept),
                              'unknown_classes': len(unknown), 'uncertain_resources_or_manifest': uncertain,
                              'resource_hits': sorted(resource_hits)}
    return result


def verify(manifest, footprint):
    if set(manifest['components']) != set(footprint):
        raise ValueError('Resolved notice coverage changed; regenerate the reviewed inventory')
    for coordinate, actual in footprint.items():
        reviewed = manifest['components'][coordinate].get('status', 'included')
        # An unproven exclusion may never pass. Extra conservative notices remain safe.
        if reviewed != 'included' and actual['status'] == 'included':
            raise ValueError('Notice exclusion no longer provable: ' + coordinate + '; regenerate notices before release')
        if reviewed == 'metadata-only' and actual['status'] != 'metadata-only':
            raise ValueError('Runtime artifact now selected for metadata-only entry: ' + coordinate)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--usage', type=Path, required=True)
    parser.add_argument('--reviewed', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    footprint = analyze(json.loads(args.inventory.read_text()), args.apk, args.mapping, args.usage)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(footprint, indent=2) + '\n')
    if args.reviewed:
        reviewed = json.loads(args.reviewed.read_text())
        with zipfile.ZipFile(args.apk) as archive:
            packaged = json.loads(archive.read('assets/third-party-components.json'))
            bundle = archive.read('assets/third-party-notices.txt')
        if packaged != reviewed or hashlib.sha256(bundle).hexdigest() != reviewed['bundle_sha256']:
            raise ValueError('Packaged notices differ from the reviewed inventory/bundle')
        text = bundle.decode('utf-8')
        for coordinate, entry in reviewed['components'].items():
            if entry.get('status', 'included') == 'included' and coordinate not in text:
                raise ValueError('Included dependency has no displayed notice: ' + coordinate)
        verify(reviewed, footprint)
    counts = {status: sum(x['status'] == status for x in footprint.values()) for status in ('included', 'stripped', 'metadata-only')}
    print('PASS: release notice footprint:', counts)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
