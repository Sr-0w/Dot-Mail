# Release build (R8) audit

`scripts/check_r8_release.py` checks a release build for things R8 could strip or rename that the app (or a library) reaches
**by name** at runtime. No device is needed:

```sh
scripts/gw :app:assembleRelease          # builds on the build host; outputs stay in app/build/outputs there
python3 scripts/check_r8_release.py --apk app/build/outputs/apk/release/app-release-unsigned.apk   # run where those outputs are
```

It derives what to check from the sources (so new code is covered) and exits 1 listing every finding.

| Reached by name | Why it matters | Result (alpha.2 config) |
|---|---|---|
| WorkManager workers | the stored class name is loaded with `Class.forName`, also across app updates | all 10 unchanged (library consumer rules keep `ListenableWorker` subclasses) |
| Room `MailDatabase` + `MailDatabase_Impl` | `Class.forName(<db>_Impl)` | unchanged (Room consumer rule keeps `RoomDatabase` subclasses) |
| Navigation type-safe routes (`@Serializable`) | serializers must exist | all routes and `$$serializer` classes present; renamed is fine, the route string comes from the baked-in serial name |
| ServiceLoader (`META-INF/services`) | provider must still be a class | R8 rewrites the services file when it renames; none stripped |
| Manifest components (activities, receivers, services, startup initializers) | referenced by name | kept by AAPT2 rules, not by this script |
| WebView JavaScript interfaces | `addJavascriptInterface` | none: JavaScript is disabled in the message WebView |
| Resources by name | `getIdentifier` | none used, so resource shrinking is safe |
| Jakarta Mail / Angus | providers and handlers loaded reflectively | explicit keep rules in `app/proguard-rules.pro` (unchanged) |

No keep rules were added. Not covered: runtime behaviour of the minified build on a device (smoke test the release APK).

## DEX, constructors and release notices

The complementary `scripts/audit_release.py <apk> <mapping.txt> <manifest.xml> --report <json>` reads actual DEX class definitions/public constructor prototypes. It verifies WorkerFactory signatures, mailcap/provider reflection, Room implementations, generated navigation serializers, merged manifest components/Startup constructors, bundled notices and ELF alignment. Extract the manifest from the same APK with apkanalyzer; keep R8 outputs with that exact APK. See docs/reports/codex-release-readiness.md for evidence and limitations.

Every assembleRelease also runs verifyReleaseNotices (resolved-coordinate coverage) and verifyShippedNotices (current DEX/mapping/usage/resources versus reviewed exclusion statuses). Unknown attribution stays included. Newly reachable excluded dependencies block the build until full notices are restored; no package exclusion list or extra crypto keep rules are hidden in the auditor. See THIRD_PARTY_NOTICES.md. Final signed-device crypto/mail tests remain separate.
