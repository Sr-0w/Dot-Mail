# Mailbox-scoped conversation movement

Conversation move/Trash/Spam previously selected every folder row, including Sent replies and All Mail copies. New MailRepository overloads accept `sourceMailbox: Mailbox` for `apply(action, threadIds, sourceMailbox)` and `move(threadIds, targetFolderId, sourceMailbox)`. InFolder intersects with that folder; Unified intersects with the role in each account. Virtual single-message ids are intersected too.

InboxViewModel captures the displayed mailbox before launching either action. Claude will propagate that context into ThreadRoute/ThreadViewModel. Legacy overloads are deprecated: Archive retains Inbox scope, and other movement refuses ambiguous multi-folder conversations with typed SOURCE_REQUIRED. Flag-only legacy calls retain conversation-wide behavior. Cross-account explicit moves still fail atomically.

Regression tests cover Inbox/Sent/All Mail copies, Trash/Spam, multi-account unified Archive, stale selection, ambiguous legacy calls, and virtual message ids. Existing optimistic move/undo tests remain in place. No schema change, new dependency, or phone access.

Validation: scripts/gw :data:testDebugUnitTest :app:compileDebugKotlin (rerun on rebased main before PR).
