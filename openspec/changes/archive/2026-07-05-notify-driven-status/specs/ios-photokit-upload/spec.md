## ADDED Requirements

### Requirement: Post a cross-process liveness notification after each cycle

The extension SHALL post a **named cross-process Darwin notification** (via `CFNotificationCenter`'s
Darwin notify center) after every `process()` run — once `cycle.run()` returns, regardless of the
tri-state result (`completed` / `processing` / `failure`) — to signal the main app that the ledger may
have changed and status should be re-read. The post SHALL be **payload-free** (its only
promise is "re-read the truth", so coalescing and missed signals are harmless) and SHALL be made from
the **extension composition root** (`UploadExtensionRoot`), **not** from `LedgerBackend` — the ledger
backend continues to post no cross-process notification (its change flow stays in-process). The post
SHALL be **unconditional** (fired on every run, so both a rising in-flight count and a drain are
signalled) and best-effort (a post failure SHALL NOT affect the returned processing result).

This is the extension→app half of the notify-driven status refresh; the app-side observer that
re-reads the ledger on this notification is specified in `ios-app-shell`, and the status source's
response is specified in `sync-status`.

#### Scenario: A completed cycle posts the liveness notification
- **WHEN** `cycle.run()` returns and `process()` is about to return `completed`
- **THEN** the extension has posted the payload-free Darwin liveness notification

#### Scenario: A processing (still-draining) cycle also posts
- **WHEN** `cycle.run()` returns and `process()` is about to return `processing` (pending rows remain)
- **THEN** the extension has posted the Darwin liveness notification (so the app reflects the rising /
  in-flight state), independent of the result

#### Scenario: The backend still posts no cross-process ding
- **WHEN** the extension writes the ledger during the cycle
- **THEN** `LedgerBackend` posts no cross-process notification; the only cross-process post is the
  composition-root liveness notification after the cycle
