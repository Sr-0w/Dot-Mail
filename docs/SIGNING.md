# Project APK signing

The project already has an external signing identity. Its **public** SHA-256 certificate digest is committed in [release-certificate.sha256](release-certificate.sha256). No keystore, private key, password or signing environment belongs in Git, build reports, issue comments or APK metadata.

## Separation from compilation

`scripts/gw :app:assembleRelease` builds an unsigned R8 payload. The project build scripts do not load the external signing environment or request a project signing password. Use the debug build for ordinary developer installs; signing the release payload is an explicit maintainer step after compilation and review.

`scripts/release.sh` uses the existing host's external signing configuration only after Gradle exits. The trusted private environment supplies DOTMAIL_KEYSTORE (absolute external path), DOTMAIL_KEY_ALIAS, DOTMAIL_KEYSTORE_PASSWORD and, if different, DOTMAIL_KEY_PASSWORD. Keep that file owner-readable only (0600), outside the source/build worktrees, and load it with shell tracing disabled. Supply passwords through the signer's environment indirection, not command-line values. Do not paste their contents into a terminal log or ask an agent to inspect them.

The script does not create or rotate keys. A new/rotated certificate requires a separate maintainer decision and a reviewed Android signing lineage/update plan. A fingerprint mismatch fails publication instead of silently changing the project identity. Keep an encrypted offline recovery copy of the existing signing material under the maintainer's control.

## Order of operations

1. Build the exact reviewed/tagged source and check its unsigned reproducibility result.
2. Verify AGP's existing alignment using Build Tools 36.0.0: `zipalign -c -P 16 4 app-release-unsigned.apk`. Do not unnecessarily unpack/repack or realign a valid payload.
3. Sign a copy with the external key. The script uses `apksigner sign --ks-pass env:DOTMAIL_KEYSTORE_PASSWORD` and optional `--key-pass env:DOTMAIL_KEY_PASSWORD`; these arguments name environment variables, not their contents.
4. Run `apksigner verify --verbose --print-certs` on the final file and compare its SHA-256 certificate digest with the committed public reference. Unset password variables after signing.
5. Hash the final signed APK, retain the signature-verification output privately/publicly as appropriate (certificate metadata is public), and publish the verified APK plus checksum only through the reviewed release workflow. Preserve the signed file unchanged.

Android documents [apksigner](https://developer.android.com/tools/apksigner) and [zipalign](https://developer.android.com/tools/zipalign): alignment precedes signing, and modifications afterward invalidate the signature. The committed fingerprint is an identity check; verify artifact hashes and provenance too.

## Independent verification without a key

```sh
apksigner verify --verbose --print-certs DotMail-<version>.apk
sha256sum -c DotMail-<version>.apk.sha256
# Compare the printed certificate SHA-256 against docs/release-certificate.sha256.
```

An unsigned build intentionally fails signature verification and cannot update an installed app. An F-Droid-signed APK can have a different certificate; users should remain on a consistent update channel. Do not sign/install an audit artifact on the owner's phone. The audit does not read private signing configuration, invoke signing, create a release tag or publish an artifact.
