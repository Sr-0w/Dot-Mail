# Unicode previews and stored-snippet repair

## Reproduced cause

The opt-in read-only Infomaniak test reproduced the reported shape without printing any subject/body/address/credential. The affected preview and its MIME text/plain alternative both started with a question-mark pair and contained zero supplementary characters. The HTML alternative had no damaged prefix and retained one supplementary character. This particular corruption was already present in the sender's plain alternative; it was not introduced by Room charset conversion.

The HTML's first 2/8/16 KiB of decoded markup contained no visible snippet; the first 32 KiB did. The fallback therefore uses one bounded HTML-part prefix (at most 64 KiB encoded bytes), only when the plain preview starts with `??` or contains a replacement character. It chooses healthy non-ASCII HTML text in that case, retaining ordinary plain-text preference and literal questions when no healthy Unicode alternative exists. It never fetches an unrestricted MIME body or sets Seen. Large HTML heads beyond that cap can still need the on-demand cached body; the cap is intentional.

Post-fix live result: original/plain prefix question pair=true, HTML prefix=false, repaired prefix=false, repaired supplementary count=1. The diagnostic only prints aggregate booleans/counts. No live writes or phone access.

## Unicode handling

Snippets.clip uses character/grapheme boundaries while retaining the existing UTF-16 maximum-length budget including ellipsis. A cut cannot leave half a surrogate pair or detach a combining accent. The bounded MIME decoder uses an incremental CharsetDecoder without end-of-input, dropping an incomplete trailing encoded character instead of generating a replacement. Body-on-demand previews and partial previews share the same plain/HTML selection policy.

## Healing existing cache

A per-account snippet revision marks old cached previews for recomputation on the next sync. Work runs after the requested mailbox header/flag readiness callback, preserving the fast Inbox spinner. Cached bodies are reused locally; uncached messages use bounded PEEK prefixes. Repair handles up to 200 cached bodies and 200 remote previews per folder per pass, prioritizing newest content. Old nonempty snippets below the newest-200 window remain eligible instead of being stranded.

SnippetRepairWorker resumes pending batches with network constraints/backoff and startup recovery. Authentication/permanent connection failures stop the worker until an explicit retry/new sync; transient failures retain pending flags. The revision marker is written after the SQL reset, so a process death can cause a harmless repeat, not skip repair. Only existing snippet/fetched columns plus private bookkeeping preferences are used: no Room version bump, new public UI API or dependency.

## Validation

- `scripts/gw :mail:test :data:testDebugUnitTest :app:assembleDebug` passed.
- Opt-in read-only live fallback plus focused Unicode/network/cache repair tests passed.
- Regressions cover emoji, CJK, combining accents, surrogate-boundary clipping, a cut UTF-8 prefix, long HTML style prefixes, unread preservation, healthy plain preference and conservative fallback.
- Room/GreenMail tests corrupt a saved preview then verify next-sync healing, cached-body repair without another preview download, idempotent subsequent sync, and bounded continuation past 200 messages. Older remote repair candidates remain selectable below the normal newest window.

Physical-device confirmation of the corrected rows remains Claude's smoke test. The full-content search/storage/hardening queue follows this PR separately.
