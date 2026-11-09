# Bundled third-party notices

The release APK includes `assets/third-party-notices.txt` and its reviewed coverage manifest, `assets/third-party-components.json`. Settings → Licences opens the complete text offline, with selectable paragraphs. This replaces the old manually maintained list of license names. Duplicate META-INF packaging exclusions remain; they do not remove the bundled assets.

## Source and coverage

The inventory is exported from Gradle's **resolved release runtime classpath**, including transitive modules and BOM/platform coordinates. Project modules are source in this repository; the bundle additionally includes Dot Mail's GPL-3.0 text, the vendored OpenPGP API's Apache-2.0 license, and Inter's OFL-1.1 with its copyright. No new runtime library is added by this tooling.

For each external coordinate the generator reads its cached Maven POM, following parent inheritance for declared licenses. It scans each resolved JAR/AAR, including nested JARs, for LICENSE/NOTICE/COPYING/COPYRIGHT files and preserves their complete UTF-8 text with the originating coordinate/path. Identical texts are deduplicated by SHA-256, retaining every attribution source. The complete Apache-2.0 text is shared; upstream variants or combined licenses remain intact rather than stripping their copyright/terms. Non-Apache modules without an embedded full license fail generation and require explicit review, rather than receiving a guessed license. POM names are declarations, not independent legal certification.

The checked-in manifest covers all 209 resolved external coordinates at this candidate; the visible bundle lists selected binary modules whose absence is not proven. Metadata-only/BOM coordinates remain in coverage inventory but are not advertised as shipped libraries. Notable non-Apache declarations/texts include jsoup's MIT copyright, Glance's external protobuf BSD-3-Clause, Jakarta Mail/Angus's combined EPL/GPL-with-Classpath-Exception/EDL terms and activation's EDL/BSD terms. All shipped NOTICE contents are included, not merely links to them. Do not reduce combined upstream license texts to a summary.

Exclusions are generated, never assigned by a coordinate/package allowlist. `scripts/notice_footprint.py` compares each original binary's classes against current release DEX definitions, R8 mappings (including inlined owners) and R8's complete removed-class report. It also checks packaged resources/service descriptors. Android merged resources or manifest contributions that cannot be assigned conclusively force inclusion. A class absent from both mapping and usage is unknown, not evidence of removal. Complete absence is proven only when all classes are accounted for as removed and no runtime resource footprint or ambiguity remains.

Every `assembleRelease` recomputes this footprint after packaging and compares it to the reviewed statuses. A previously excluded library becoming reachable, inlined, resource-bearing or uncertain **fails the build until its notices are regenerated and reviewed**. This is deliberate: the first future S/MIME integration cannot silently ship Bouncy Castle without attribution. No post-R8 rewriting of the APK/assets is performed. The packaged coverage inventory, bundle hash and presence of every included coordinate are checked too. Conservative extra notices are permitted; uncertain libraries are always included during generation.

Every `preReleaseBuild` invokes `verifyReleaseNotices`: the set of resolved coordinates must exactly equal the checked-in inventory, and the bundled text must match its reviewed SHA-256. A new or changed transitive dependency fails the release build until regenerated and reviewed. This checks coverage, not whether an upstream license declaration is legally correct. Changes in font/vendored sources require review too.

## Regenerate on the build host

```sh
scripts/gw :app:exportReleaseLicenseInventory
# In this worktree's synchronized directory ON THE BUILD HOST:
python3 scripts/generate_notices.py
# First conservative bundle: copy it locally and build once, then compute the release footprint:
python3 scripts/notice_footprint.py --inventory app/build/reports/notices/resolved.json \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --mapping app/build/outputs/mapping/release/mapping.txt \
  --usage app/build/outputs/mapping/release/usage.txt \
  --report app/build/reports/notices/footprint.json
python3 scripts/generate_notices.py --footprint app/build/reports/notices/footprint.json
# Copy app/build/reports/notices/bundled/{third-party-notices.txt,third-party-components.json}
# back to this worktree's app/src/main/assets/ using rsync. Review both diffs.
scripts/gw :app:verifyReleaseNotices :app:testDebugUnitTest --tests '*LicenceNoticesTest' :app:assembleRelease
python3 -m unittest discover -s scripts/tests
```

The generator uses downloaded dependency artifacts/POMs, not Gradle properties, signing material or mail data. Missing POMs, missing declarations and unknown non-Apache texts fail closed. The inventory export includes local artifact paths only in ignored build reports; the committed manifest and bundle contain coordinates, upstream paths within archives, declarations, texts and hashes, with no machine-specific cache paths.

Robolectric tests check asset integrity, attribution/full text and the offline viewer. `scripts/audit_release.py` separately inspects the **shrunk release APK** to ensure both assets survived packaging and all reviewed coordinates are present in the text. Before publication, rerun these checks on the actual tagged release; F-Droid's own buildserver review remains separate.
