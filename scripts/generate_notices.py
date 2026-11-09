#!/usr/bin/env python3
"""Bundle notices from the resolved release inventory and cached Maven metadata/binaries.
Run on the build host after :app:exportReleaseLicenseInventory; never reads private Gradle properties.
"""
import argparse
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
NOTICE = re.compile(r'(?:license|licence|notice|copying|copyright)(?:[._-].*)?$', re.I)


def pom_licenses(cache, coordinate, seen=None):
    seen = set() if seen is None else seen
    if coordinate in seen:
        raise ValueError('POM inheritance loop: ' + coordinate)
    seen.add(coordinate)
    group, name, version = coordinate.split(':')
    paths = list((cache / group / name / version).glob('*/*.pom'))
    if len(paths) != 1:
        raise ValueError('Expected one cached POM: ' + coordinate)
    doc = ET.parse(paths[0]).getroot()
    ns = NS if doc.tag.startswith('{') else {'m': ''}
    licenses = [{'name': e.findtext('m:name', default='', namespaces=ns),
                 'url': e.findtext('m:url', default='', namespaces=ns)}
                for e in doc.findall('m:licenses/m:license', ns)]
    if licenses:
        return licenses
    parent = doc.find('m:parent', ns)
    if parent is None:
        raise ValueError('No declared license: ' + coordinate)
    return pom_licenses(cache, ':'.join(parent.findtext('m:' + key, namespaces=ns)
                                      for key in ('groupId', 'artifactId', 'version')), seen)


def archive_notices(data, prefix=''):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or entry.filename.endswith('.class'):
                continue
            if NOTICE.fullmatch(Path(entry.filename).name):
                text = archive.read(entry).decode('utf-8-sig').replace('\r\n', '\n').strip()
                if text:
                    result.append((prefix + entry.filename, text))
            elif entry.filename.endswith('.jar'):
                result.extend(archive_notices(archive.read(entry), prefix + entry.filename + '!/'))
    return result


def generate(inventory, cache, root, footprint=None):
    blocks = {}
    components = {}
    def add(text, source):
        digest = hashlib.sha256(text.encode()).hexdigest()
        block = blocks.setdefault(digest, {'text': text, 'sources': []})
        if source not in block['sources']:
            block['sources'].append(source)
        return digest
    apache = add((root / 'openpgp/LICENSE').read_text().strip(), 'Apache License 2.0; vendored OpenPGP API LICENSE')
    artifacts = {}
    for artifact in inventory['artifacts']:
        artifacts.setdefault(artifact['component'], []).append(Path(artifact['path']))
    for coordinate in inventory['components']:
        licenses = pom_licenses(cache, coordinate)
        status = (footprint or {}).get(coordinate, {}).get('status', 'included' if artifacts.get(coordinate) else 'metadata-only')
        if status != 'included':
            components[coordinate] = {'declared_licenses': licenses, 'status': status, 'text_sha256': []}
            continue
        hashes = []
        has_full_license = False
        for artifact in artifacts.get(coordinate, []):
            for name, text in archive_notices(artifact.read_bytes()):
                hashes.append(add(text, coordinate + ' / ' + name))
                if re.match(r'(?:license|licence|copying)(?:[._-]|$)', Path(name).name, re.I):
                    has_full_license = True
        # Apache's complete text is bundled once, even when the binary omits it.
        apache_declared = any('apache' in (x['name'] + x['url']).lower() for x in licenses)
        if apache_declared:
            hashes.append(apache)
        elif not has_full_license:
            raise ValueError('Non-Apache component needs its full license/copyright text: ' + coordinate)
        components[coordinate] = {'declared_licenses': licenses, 'status': status, 'text_sha256': sorted(set(hashes))}
    add((root / 'designsystem/FONT_LICENSE-Inter.txt').read_text().strip(), 'Inter typeface / designsystem/FONT_LICENSE-Inter.txt')
    add((root / 'LICENSE').read_text().strip(), 'Dot Mail / GPL-3.0-or-later / LICENSE')
    lines = ['Dot Mail — licenses and third-party notices', '',
             'Generated from the release runtime classpath and reviewed shrinker footprint. Metadata-only and proven stripped modules are omitted.',
             'Project: GPL-3.0-or-later. OpenPGP API sources: Apache-2.0. Inter font: OFL-1.1.', '', 'Resolved components:']
    for coordinate, item in sorted(components.items()):
        if item['status'] != 'included':
            continue
        lines.append(coordinate + ' — ' + '; '.join(x['name'] for x in item['declared_licenses']))
    for digest, block in sorted(blocks.items()):
        lines.extend(['', '=' * 72, 'Text SHA-256: ' + digest, 'Sources:', *sorted(block['sources']), '', block['text']])
    bundle = '\n'.join(lines) + '\n'
    manifest = {'schema': 1, 'components': components,
                'bundle_sha256': hashlib.sha256(bundle.encode()).hexdigest(),
                'extra_sources': ['LICENSE', 'openpgp/LICENSE', 'designsystem/FONT_LICENSE-Inter.txt']}
    return bundle, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', type=Path, default=Path('app/build/reports/notices/resolved.json'))
    parser.add_argument('--footprint', type=Path, help='Current release footprint report; without it all binary modules are conservatively included')
    parser.add_argument('--cache', type=Path, default=Path.home()/'.gradle/caches/modules-2/files-2.1')
    parser.add_argument('--output', type=Path, default=Path('app/build/reports/notices/bundled'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    bundle, manifest = generate(json.loads(args.inventory.read_text()), args.cache, root, json.loads(args.footprint.read_text()) if args.footprint else None)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output/'third-party-notices.txt').write_text(bundle)
    (args.output/'third-party-components.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False)+'\n')
    print('Bundled notices for', len(manifest['components']), 'resolved release components')


if __name__ == '__main__':
    main()
