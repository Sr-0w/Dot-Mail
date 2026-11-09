#!/usr/bin/env python3
"""Check the repository's F-Droid/Fastlane text and demo screenshot inventory without network access."""
import hashlib
import json
import struct
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "fastlane/metadata/android"
ALLOWED = {"b", "big", "blockquote", "br", "cite", "em", "i", "li", "ol", "small", "strike", "strong", "sub", "sup", "tt", "u", "ul", "a"}


class Description(HTMLParser):
    def handle_starttag(self, tag, attrs):
        assert tag in ALLOWED, f"Unsupported description tag: {tag}"
        assert all(tag == "a" and key == "href" and value.startswith("https://") for key, value in attrs), "Unsupported HTML attribute"


def validate():
    provenance = json.loads((ROOT / "fastlane/screenshots.json").read_text())
    assert provenance["fixture"] == "DemoMailGraph"
    assert provenance["real_accounts"] is False
    recorded = provenance["sha256"]
    for locale in ("en-US", "fr-FR", "de-DE"):
        directory = BASE / locale
        for name, maximum in (("title.txt", 50), ("short_description.txt", 80), ("full_description.txt", 4000)):
            text = (directory / name).read_text(encoding="utf-8").strip()
            assert 0 < len(text) <= maximum, f"{locale}/{name}: {len(text)} characters"
            if name == "full_description.txt":
                Description().feed(text)
        for changelog in (directory / "changelogs").glob("*.txt"):
            assert changelog.stem.isdecimal(), f"Expected versionCode changelog: {changelog.name}"
            assert len(changelog.read_text().strip()) <= 500, f"Changelog too long: {changelog}"
        pictures = sorted((directory / "images/phoneScreenshots").glob("*.png"))
        assert pictures, f"Missing demo screenshots for {locale}"
        for picture in pictures:
            payload = picture.read_bytes()
            assert payload[:8] == b"\x89PNG\r\n\x1a\n"
            width, height = struct.unpack(">II", payload[16:24])
            assert min(width, height) >= 320 and max(width, height) <= 3840
            offset = 8
            while offset < len(payload):
                length = struct.unpack(">I", payload[offset:offset+4])[0]
                kind = payload[offset+4:offset+8]
                assert kind not in (b"eXIf", b"tEXt", b"zTXt", b"iTXt"), f"Unreviewed PNG metadata: {picture}"
                offset += 12 + length
            relative = picture.relative_to(ROOT).as_posix()
            assert hashlib.sha256(payload).hexdigest() == recorded[relative], f"Capture provenance mismatch: {relative}"
        print(f"{locale}: descriptions, changelogs and {len(pictures)} demo screenshots OK")


if __name__ == "__main__":
    validate()
