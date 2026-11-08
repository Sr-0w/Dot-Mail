# Acting lead: phone regressions and frosted headers

Owner explicitly authorized Sol to take the lead while Opus is quota-limited, install/test on the real phone, and implement UI requests. Main working tree's pre-existing staged changes were left untouched; implementation is in wt-codex on codex/acting-lead. Luna finished aliases and independently reviewed storage #55 (no blocker); #55 is merged as dff5150. Luna's electric-blue #56 is separate. Owner explicitly requested Sol alone implement the blur; Luna stopped without changing screen files.

## Confirmed crash and fix

The owner's S25 Ultra / Android 17 reported native WebView SIGTRAP on RenderThread. A synthetic long table-based newsletter reproduced it on the actual phone in ReaderRenderingTest. ThreadScreen recorded the AndroidView WebView inside Haze's source; the retained hardware draw functor could outlive WebView disposal. HTML and unloaded bodies now bypass Haze capture, with neutral frosted capsules and the page gradient; native text can still blur. The same test passed after this change, including repeated slow/fast scrolling, four system-Back cycles, recreation and in-app Back. PixelCopy checks sampled visible newsletter bands after each scroll; a frame disappearing to blank fails the test.

## Owner's refined UI requirement

The blur replaces the opaque top panel on every scrolling page. Content must scroll behind it; controls and titles stay crisp, toolbar icon buttons use existing frosted capsules, and the entire blur fades smoothly from the screen top. Inbox/search/attachment filters and sorting collapse while scrolling down, return on upward intent or at the top, and honor reduced motion. The owner's reference image is for blur intensity only.

Implemented shared FrostedHeaderLayout; migrated inbox, search, compose, settings root/account/identity/smart-folder list+editor/categories/remote-image pages, account setup, outbox, attachment browser, PDF pages/text, and reader scrim. Dialogs/bottom sheets retain their own presentation; they are not full-page top panels.

The header's full effect is masked rather than just fading its tint. The strong blur remains opaque through the control area and fades below. Avoid progressive AGSL shaders (Robolectric's native Skia rejects their shader syntax); uniform blur plus an alpha mask also uses less compositing. The mask now uses Haze's built-in path rather than a second offscreen layer. Input is downsampled, no noise. Header glass icons reuse the shared blurred backdrop visually without each adding another live blur. Expanded header insets/blur dimensions stay stable during filter collapse, preventing content jumps and repeated texture reallocations. HTML always uses the non-capturing fallback.

## Validation/status at checkpoint

Initial full build passed mail/data/openpgp/app JVM suites, debug, R8 release and AndroidTest assembly through scripts/gw. Real v9 APK installed with install -r; no uninstall/clear. No mail was sent or moved. Tests run in DemoTestRunner, with synthetic in-memory mail and redirected UI prefs. App has been updated several times while reproducing and fixing issues; final APK verification is still in progress.

Old device tests had stale Archive text expectations and outdated Add account drawer assumptions. DemoMailGraph lacked the scoped search API; it now implements it. Two navigation checks still need final validation: rapid Settings Back→Compose and search must wait for outgoing navigation content to settle. Failure screenshots/semantic trees contain synthetic data only and live in phone app cache/dotmail-ui-tests, copied into /tmp locally. Never commit real mail or credentials.

Current final build log: /tmp/codex-stable-header-final-build.log. Earlier AndroidTest logs: /tmp/dotmail-frosted-device.log and /tmp/dotmail-header-trees-device.log. Reader native crash is fixed in tested scenarios; the final stronger blur/collapse still needs phone smoothness checks, theme screenshots and final full-suite/R8/lint before PR/merge. Do not mark backlog Done yet.

Storage UI draft files are safely stashed in stash named storage-ui-wip (StoragePage.kt + StorageViewModel.kt only; no strings/navigation yet). Older correspondence backup stash is not to reapply. Continue storage Settings UI after the urgent owner blur refinements. Then IMAP hardening closeout, and JMAP design first.
