## Why

One event that Bugsink refuses as too large silences a process's crash reporting. sentry-cocoa 8.58.2 deletes a
cached envelope only on a `200` and always sends the oldest one first. So a refused envelope stays in
`Caches/io.sentry`, is re-sent on every trigger, and holds back every later report from that process, across
launches, until 30 newer envelopes evict it. Anything evicted with it is lost. Phase 8d measured this against a
loopback ingest (M7 in `changes/archive/2026-09-23-diagnostics-reporter-contracts`), and the SDK source confirms
it (`SentryHttpTransport.m`: `sendAllCachedEnvelopes` → `getOldestEnvelope`; `statusCode == 200` →
`deleteEnvelopeAndSendNext`; otherwise `finishedSending`).

Today only the diagnostic dump's two log tails are bounded (700,000 B against Bugsink's measured 1 MiB
`MAX_EVENT_SIZE`). Nothing bounds the rest of an event. Breadcrumbs carry every Kermit line below `Error` at
whatever length the line has, up to 100 of them. An automatic event's message and exception values are also
unbounded. No path over the cap is known today, but nothing prevents one. The failure it would cause is silent:
a device that stops reporting looks like a device that never crashes.

## What Changes

- Every event either process sends is bounded **by construction** below the ingest's maximum event size,
  judged on the decoded body. Each variable part gets its own cap, and the caps sum to less than the ceiling:
  - each breadcrumb's message and string data values are capped in length;
  - the breadcrumb count is pinned rather than left to the SDK default;
  - an automatic event's message and exception values are capped in length;
  - the dump keeps its log budget, and the arithmetic now accounts for the whole event it rides in.
- A capped text says it was cut (a fixed truncation marker), so a reader never mistakes a cut line for the
  whole line. The device log keeps every line in full.
- A new `DiagnosticsReporter` contract clause proves the arithmetic on the real SDK. The worst-case dump,
  sent after the process has logged a full set of over-long lines, reaches the loopback ingest, which already
  refuses anything over 1 MiB decoded.
- The specs stop saying an over-cap event is "silently lost". It is retained and blocks the queue, which is
  why the bound is a hard one.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `crash-reporting`: adds a requirement that every outgoing event is bounded below the ingest's maximum
  event size, with each part's cap stated.
- `diagnostic-logging`: the requirement "Diagnostic dump contents and byte budget" corrects what an
  over-budget dump does (retained and blocking, not lost), and ties the log budget to the whole-event bound.

## Impact

- `:adapter:ios:ext-safe`: `SentryDiagnosticsReporter` (the breadcrumb and event scrubs gain the length caps;
  `maxBreadcrumbs` is pinned) and `SentryLogWriter` if the cap is applied there as well. Its `iosTest`
  gains the new contract clause's live binding.
- `:domain` `model/`: `DiagnosticDump.kt` gains the event-size constants the arithmetic uses, beside
  `DIAGNOSTIC_LOG_BUDGET_BYTES`, whose value does not change. The truncation helper is pure and lives here
  too, so it is covered in `commonTest`.
- `:test:contracts`: `DiagnosticsReporterContract` gains a clause.
- `:adapter:generic:fake`: no change; its binding already answers `Unreachable` for the wire state the new clause runs in.
- No new port, no new dependency, no shell change. The device logs are untouched.
