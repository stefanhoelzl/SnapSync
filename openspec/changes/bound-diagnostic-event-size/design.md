## Context

Bugsink refuses any event whose **decoded** body is over `MAX_EVENT_SIZE` = 1,048,576 B. This was measured
against the hosted instance on 2026-07-29, and the gzip wire size says nothing about it (M8 in
`changes/archive/2026-09-23-diagnostics-reporter-contracts`). What sentry-cocoa 8.58.2 does with a refusal
comes from its source, `Sources/Sentry/SentryHttpTransport.m` at tag `8.58.2`:

- `sendEnvelope:` stores every envelope to disk first, then calls `sendAllCachedEnvelopes`.
- `sendAllCachedEnvelopes` sends `getOldestEnvelope` and nothing else until that one resolves.
- The completion deletes the file only when `response.statusCode == 200` (via `deleteEnvelopeAndSendNext`).
  Any other status goes to `finishedSending`, and the file stays.
- `SentryFileManager handleEnvelopesLimit` evicts the oldest files beyond `maxCacheItems`, which defaults to
  30 (`SentryOptions.m`). sentry-kmp 0.27.0 does not expose that option.

So one refused envelope blocks its process's reporting, across launches, until 30 newer envelopes evict it.
M7 measured exactly this against the loopback ingest. The queue is per process (`Caches/` is per
container), and only the app sends the dump, so the dump endangers the app's queue.

What is bounded today:

| part | bound | where |
|---|---|---|
| dump log tails | 700,000 B UTF-8 total | `DIAGNOSTIC_LOG_BUDGET_BYTES`, `CollectDiagnosticDump` |
| dump note | a couple of hundred bytes | the sheet that collects it |
| dump state / ledger | fixed keys; the longest value is the event name, ≤ 100 chars (`MAX_EVENT_NAME_LENGTH`) | `CollectDiagnosticDump` |
| `process_metrics` context | fixed OS metadata fields | `ProcessMetricReport` |
| breadcrumb count | 100 (SDK default, not set by us) | sentry-cocoa |
| **breadcrumb message / data** | **none** — every Kermit line below `Error`, at full length | `SentryLogWriter`, SDK auto-breadcrumbs |
| **automatic event message, exception values** | **none** | `SentryLogWriter` |

The last two rows are the gap. A dump rides the scope's breadcrumbs, so about 330 KB of breadcrumbs (≈3.3 KB
per line over 100 lines) would push a full-budget dump over the ceiling.

## Goals / Non-Goals

**Goals:**
- Every event either process sends stays below `MAX_EVENT_SIZE` by construction: each variable part has a
  cap, and the caps sum below the ceiling with stated slack.
- A cut is visible to whoever reads the event.
- The arithmetic is proved once against the real SDK and an ingest that enforces the ceiling, so an SDK
  upgrade that makes events bigger fails a clause rather than a device.

**Non-Goals:**
- **Purging a stuck envelope** (see D4).
- **Changing the dump's log budget.** 700,000 B stays. The arithmetic below fits it.
- **Bounding the SDK's own contributions** (stack frames, debug images, device contexts). We do not own
  them. They get an allowance, and the contract clause is what catches their growth.
- Changing the device logs. Every line stays in full in `debug.log` / `ext-debug.log`.

## Decisions

### D1. Bound by construction, not by a pre-send size check

Each part the process controls gets a fixed cap, applied where that part enters the SDK. The alternative
was a last-gate check in `beforeSend` that estimates the event's size and trims until it fits. It was
rejected for three reasons:
- the SDK adds debug images, threads and contexts that the kmp `SentryEvent` does not expose, so the estimate
  would be partial anyway;
- a trim inside `beforeSend` depends on kmp writing `contexts` back to the native event, which nothing
  measures today;
- a trimming loop is behaviour that is hard to reason about in exactly the path meant to be dull.

Per-part caps are deterministic, testable in `commonTest`, and sum on paper.

### D2. The caps, and the sum

| part | cap | worst case in a dump event |
|---|---|---|
| dump log tails | 700,000 B (unchanged) + JSON escaping, measured ~1% on log text | ≈ 707,000 |
| dump note, state, ledger, message | as today (see Context) | ≤ 4,000 |
| breadcrumbs | **count pinned at 100** (`options.maxBreadcrumbs`); **each crumb's message plus string data values ≤ 1,024 B combined**, message first; plus ~300 B of per-crumb JSON (timestamp, level, category, type, keys) | ≤ 133,000 |
| `process_metrics` + tags + user + release | fixed fields | ≤ 8,000 |
| SDK contributions (device/os/app contexts, current-thread stack, its debug images, sdk info) | allowance, not a cap | ≤ 100,000 |
| **total** | | **≈ 952,000**, leaving ≈ 96 KB below 1,048,576 |

An automatic event carries no log tails. Its message and each exception value are capped at **8,192 B**. A
cause chain is a handful of links, so it sits far below the ceiling even with a full crash stack. The cap
exists to stop one runaway string (an exception quoting a response body, say) rather than to fit a budget.

The combined per-crumb cap is what makes the row bounded. A per-field cap (message ≤ 1 KB, each data value
≤ 1 KB) would leave the row open-ended, because an SDK auto-breadcrumb can carry several data keys.

The crumb cap is applied in `scrubbedBreadcrumb`, which is already `beforeBreadcrumb` and so covers our crumbs
and the SDK's alike. The event caps are applied in `scrubbedEvent`, for automatic events only. The dump is
exempt from the scrub (`non-redacted`), and its parts are bounded upstream.

### D3. A cut says so, and cuts on a code-point boundary

A capped text ends in a fixed marker, `…[+<n> B]`, naming how many bytes were dropped. The cut never splits a
UTF-8 sequence. The helper is a pure function in `model/`, beside `redactUuids`, with tests in `commonTest`.
Caps are counted in UTF-8 bytes, not chars, because the ceiling is bytes and a char-count cap under-counts
non-ASCII text by up to 4×.

### D4. No purge of the envelope cache

Deleting a stuck envelope at `start()` would make a poisoned queue recoverable instead of just unreachable.
It is rejected:
- it depends on sentry-cocoa's private cache layout (`io.sentry/<dsn-hash>/envelopes/`), which a patch
  release may move with no signal;
- sentry-kmp exposes neither the cache nor `maxCacheItems`, so it means reaching past the SDK into its
  files;
- with D1–D2, no event this app composes can exceed the ceiling, so the purge would guard against the SDK
  itself. Guarding against the SDK is the contract clause's job (D5), which fails in CI rather than
  deleting data on a device.

If a stuck queue is ever observed in the field, this is the decision to revisit.

### D5. One new contract clause proves the sum on the real SDK

`DiagnosticsReporterContract` gains `WIRE_WORST_CASE_DUMP_ARRIVES`:
- the clause logs 100 lines, each well over the crumb cap, through the Kermit seam;
- it then sends a dump whose log tails fill the whole budget, carry a maximal event name and note, and
  include many characters that need JSON escaping;
- it asserts the dump arrives at the ingest, followed by the sentinel.

`LoopbackIngest` already answers `413` without recording anything over 1 MiB decoded. So a sum that stops
holding (an SDK upgrade adding contexts, a cap removed) turns into `NotWithin`, and since
`fc7712a1` a `NotWithin` fails the run.

The fake answers `NotRunHere`, like the other wire clauses. The clause names no SDK: it speaks through the
port and the Kermit seam only.

## Risks / Trade-offs

- **[Risk] The SDK allowance is an estimate.** → D5 measures the real total on every `ios-test` run.
  Measure the actual decoded size once during apply and record it here, so the slack is a number rather than
  a claim.
- **[Risk] Adversarial log text escapes worse than 1%** (each `"` or `\` doubles, control characters become
  six bytes). → The logs are our own text, and D5 uses an escape-heavy tail to measure that the ~96 KB slack
  absorbs a realistic mix. A dump of pure quotes is not a realistic input.
- **[Trade-off] A crumb over 1 KB loses its tail on the channel.** → The device log keeps it in full, and the
  dump carries that log. The marker says the crumb was cut.
- **[Trade-off] A refused envelope still blocks the queue if something unforeseen exceeds the ceiling.** →
  Accepted (D4). The clause is the tripwire.

## Open Questions

- Does sentry-kmp's `beforeBreadcrumb` write a changed **data** map back to the native breadcrumb? The
  existing scrub relies on it for data strings, but only the message round-trip is measured (M3). Apply
  measures it in D5's clause. If data does not round-trip, the cap falls back to the message alone and the
  per-crumb allowance in D2 is re-derived from the SDK's own auto-breadcrumb data.
