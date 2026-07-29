## Context

`debug.log` is the canonical un-redacted account of a run (capability `diagnostic-logging`), and it
is reachable over USB only. The crash-reporting channel (capability `crash-reporting`) already
carries `Error`/`Assert` lines and crashes off-device, but the report that actually arrives from a
user is "my photos never showed up", which logs no error: the ledger says `COMPLETED`, the cycle
enumerates nothing, and every line is `Info`. Nothing about that state ever leaves the device.

The channel this rides on is a **hosted** Bugsink instance (project 1, free plan: 15K events/month,
`retention_max_event_count: 1000` on the project). Hosted means its ingest settings are not ours to
change, so the payload must fit the server as configured. Three facts were measured against the real
instance on 2026-07-29 rather than assumed:

| probe | payload | result |
|---|---|---|
| attachments | — | Bugsink drops the `attachment` envelope item entirely (`bugsink/bugsink#268`, maintainer-confirmed); sentry-cocoa sends `attachment_type=event.attachment`, precisely the value its ingest skips |
| 2 × 340 KB context strings + two small maps | event JSON 686,923 B (0.66 MiB) | `HTTP 200`; read-back returned both texts at **339,929 B each — byte-identical**, all start/mid/end markers intact. Bugsink applies none of Sentry's databag trimming |
| 2 × 560 KB context strings | event JSON 1,130,997 B (1.08 MiB) | `HTTP 413 Max length (MAX_EVENT_SIZE: 1048576) exceeded` — a clean rejection, nothing stored |

So the event body is the only viable carrier, its ceiling is 1 MiB, and within that ceiling context
strings survive verbatim. Re-verify at the expiry trigger below if Bugsink's ingest changes.

Two structural constraints from `module-architecture` shape the rest: presentation may only observe
read-models and fire commands from the `model/` `UserCommands` bundle, and `:app:*` Kotlin is wiring
with zero conditionals. The extension is a separate process whose `Documents/` the app cannot read;
the App Group container is shared but not USB-pullable (both verified, capability
`diagnostic-logging` D1).

## Goals / Non-Goals

**Goals:**

- Get the recent log of both processes off an untethered device, on deliberate user action.
- Keep the affordance invisible: no button, no menu, no settings entry, nothing that reads as
  interactive to someone who is not looking for it.
- Keep the existing USB dev loop working — the pull commands in the runbook are used many times a
  day and must not need extra launches.
- Stay inside the shipped channel: no new backend endpoint, no new storage, no new credential.

**Non-Goals:**

- **No automatic dumps on error.** Crash and error events keep carrying only their own breadcrumbs.
  Attaching log tails to them is a separate decision with a very different volume profile against a
  15K/month plan.
- **No extension-initiated dumps.** The extension never sends a dump; the app reads its log and
  sends both halves. One sender, one code path.
- **No backend or storage route for logs.** Bugsink is the only destination, bounded by the event
  cap. The single bunny zone holds real users' photos and stays out of this.
- **No share-sheet fallback.** A build without a reporter does nothing; USB export covers the dev
  case.

## Decisions

### D1 — The trigger is a double-tap on the app-name label

`ScreenLayout`'s small `title` label is the only element rendered in **every** state — create, join
gate (all phases), joined, reconfigure — which matters because a stuck join or a denied permission is
exactly when a dump is wanted. It is non-interactive today.

The gesture is a raw `pointerInput`/`detectTapGestures(onDoubleTap = …)`, **not** `combinedClickable`:
the latter adds click semantics and a ripple, which would make the label look like a control and
expose it to accessibility traversal. Invisibility is the requirement, so the absence of semantics is
the design, not an oversight.

*Alternatives considered.* A dedicated Universal Link (`/diag#…`) the operator texts: no UI change and
works cold, but it needs a second link grammar beside `event-link`'s and anyone who ever sees the link
can re-fire it. A backend silent push: fully remote, but it creates a genuine remote-control
capability over users' devices, needs an endpoint, and APNs delivery is best-effort. Automatic
attachment on error: rejected as a non-goal above. Seven taps or a long-press: both fine; double-tap
plus a confirm dialog is easier to talk a non-technical tester through, and the dialog absorbs the
accidental-fire risk that makes a bare double-tap unsafe.

### D2 — A confirm dialog is the consent moment, and the only feedback

The gesture opens a confirm dialog; `Send` fires and the dialog closes. There is **no** post-send
confirmation, no spinner, no error state, and no rate limit.

Delivery cannot be honestly confirmed anyway — the SDK caches envelopes and retries on a later
launch, so "sent" would mean "handed to the SDK" no matter how it is worded. The dialog also carries
the disclosure, since this change adds no privacy-policy entry (D9): it is the only place a user is
told what leaves the device, so it names the payload rather than asking a bare yes/no.

### D3 — Availability is a nullable command, not a read-model

`UserCommands.sendDiagnostics: (suspend () -> Unit)?` is `null` when the build has no reporter
configured, and the screen wires no gesture at all — so on every dev, sideload, and simulator build
the double-tap does nothing and **no dialog can open**. A build-time constant that can never change
at runtime does not deserve a `StateFlow` threaded through the container host, nor a field on
`UiState`, which is about what the user is doing.

### D4 — One event, `captureMessage` plus four contexts

`Sentry.captureMessage("diagnostic dump")` with a scope carrying `state`, `ledger`, `app_log`,
`ext_log` as contexts. The constant message is what groups every dump into a single issue as
occurrences — verified by the probe, which landed as one issue titled `Log Message: 'diagnostic dump
probe'` — so dumps never bury real crashes in the unresolved list.

*Alternatives considered.* A file attachment is the obvious shape and is **impossible**: Bugsink drops
the item type (measured above), silently, so the event would arrive and the log would not. Chunking
into N sequenced events carries the whole 10 MB file but turns one dump into many occurrences and a
partial delivery leaves a hole. gzip+base64 into one context fits ~5-8 MB but needs a compression
path on Kotlin/Native and yields a payload unreadable without tooling. Breadcrumbs are the natural
carrier for log lines but the SDK caps them (default 100), which is ~2% of the available budget. The
whole dump in the message body would make every dump its own issue.

### D5 — A 700 KB log budget, split greedily, as a named constant

The two log tails share a fixed budget: each may take up to half, and whatever one does not use the
other may. A device whose extension has barely run therefore still yields a full-budget app tail
rather than wasting half the event.

The number comes from the measurements: JSON overhead on log text is ~1%, so 700 KB of log serialises
to ~707 KB; the SDK adds its own device/OS/app contexts, release, and up to 100 breadcrumbs
(~20-40 KB), landing a dump near 760 KB against the 1,048,576 B cap. ~280 KB of headroom. The budget
is one constant citing `MAX_EVENT_SIZE`, because the two must move together and the failure mode of
getting it wrong is invisible (D-risk below).

Tails are cut at a line boundary and taken from the current file only — not `debug.log.1`. A roll
file is stale by the time anyone dumps, and including it would halve the live tail.

### D6 — Only the extension's log moves to the App Group

The app writes `Documents/debug.log` exactly as today; the extension writes `ext-debug.log` into the
shared App Group container.

Exactly one read is impossible today — app → extension log — and it is the only capability worth
buying. Relocating the *app's* log buys nothing (a process can always read its own `Documents/`)
while breaking every `apps pull app.snapsync Documents/debug.log` in the runbook and adding a second
launch to the create-then-read-`created eventId=` loop.

|                       | move both | **move extension only** | move neither (mirror) |
|-----------------------|-----------|-------------------------|-----------------------|
| app reads ext log     | yes | **yes** | bounded tail only |
| app pull unchanged    | no | **yes** | yes |
| ext history available | full | **full** | capped at mirror |
| extra writes          | none | **none** | duplicated tail |
| new launch trigger    | yes | **yes** | none |

`FileLogWriter` gains a destination parameter and stays one class with one behaviour; the two
composition roots pass different values, which is exactly where a per-binary difference belongs
(`module-architecture`, "Shells are wiring only"). This supersedes `diagnostic-logging` D1's
"parameter-free writer": that decision was about the writer not needing *process identity*, which
remains true — it needs a path.

The rejected mirror variant (extension keeps `Documents/` and additionally maintains a small
App-Group tail copy) preserves both pull commands, but a per-cycle snapshot misses the tail of a
watchdog-killed cycle and every line logged before `process()` runs, while a per-line mirror doubles
writes in the chattiest process.

### D7 — `SNAPSYNC_EXPORT_LOGS` copies the extension's log out, at boot

A presence-triggered launch directive (capability `ios-app-shell`), read once per process and inert
in production for the same reason as every sibling: a launch env var is only injectable via a
developer launch. With it set, the app copies `ext-debug.log` (and its `.1`) into its own
`Documents/`, where `pymobiledevice3 apps pull` can reach them.

The extension can never see a launch env var — the OS launches it — so the app must do the copying.
The copy happens at boot only. It therefore hands you the extension's history up to the *previous*
extension invocation, which is what an extension log is anyway: the extension is not running while
you pull.

Ordering: this is not a membership trigger. It applies independently of the
`reset → leave → create → event-link` sequence and of `SNAPSYNC_FORGE_STATE`.

### D8 — The `ledger` context is five integers

`pending` and `completed` photos from `LedgerStore.aggregates()`, plus `importedCount`, `assetCount`
and `inFlightCount` from the download store. All five are already read by shipped code; **no new port
method is added**.

Row lists were considered and dropped. Completed rows are only readable via
`completedManifestRows()`, which loads every one of them. The backlog (`pendingResources()`) is
unbounded exactly on the stuck device worth dumping from — 4,000 outstanding rows is ~400 KB, over
half the log budget — and it carries neither state nor attempt (the store is deliberately dumb), so
it cannot distinguish "issued, never answered" from "failing on attempt 7". A `stateHistogram()`
port read would answer that in three integers, but it is new port surface added for diagnostics
alone; the log already names failures with context a row dump cannot carry. If counts repeatedly
prove insufficient in practice, add the histogram then, with evidence.

The context labels its units: `pending`/`completed` count **photos** (a photo with any outstanding
resource is pending), while the log speaks of resource rows. The two disagreeing numerically is
correct, and unlabelled it reads as a bug at 2am.

### D9 — Dump text is verbatim; the scrub stays in force everywhere else

`crash-reporting` requires every UUID-shaped token to be scrubbed before transmission. The dump is
carved out of that requirement: it is user-initiated and confirmed, and its ids — event id, asset id,
device id — are most of its diagnostic value. Automatic events and breadcrumbs, which fire without
anyone's knowledge or consent, keep `redactUuids` unchanged.

**Accepted risk, deliberately.** An eventId *is* the upload capability, and these dumps sit in a
third party's database on its own retention schedule. Accepted because the realistic dump population
is the operator and a handful of testers, on events the operator owns. No privacy-policy entry is
added; the dialog copy (D2) carries the disclosure and drops the "identifiers are removed" claim that
would otherwise be false.

Implementation note that must not stay incidental: today's `scrubbedEvent` covers message, exception
values, and breadcrumbs — **not contexts** — so verbatim delivery needs no change to the scrub path
at all. That is a convenient accident, and an accident is exactly what someone later "fixes" by
extending the scrub to contexts, silently gutting every future dump. The carve-out is therefore
stated in the spec and pinned by a `commonTest` over `scrubbedEvent`.

### D10 — `CrashReporting` becomes `DiagnosticsReporter`

The port grows a send operation beside `start()`, which no longer fits a name about crashes. Its
existing contract — configured by the build, idempotent, complete no-op without configuration —
carries over unchanged, and the same Sentry adapter seats both operations. Capture of *automatic*
events still does not cross the port; it rides the Kermit seam as before. A deliberate one-shot dump
is a different need, and a port named for the need is the law.

### D11 — Placement

- The dump's assembly is a `feature/` concern in `:domain`, reading through ports and producing a
  `model/` value; budget arithmetic, line-boundary cutting, and greedy splitting are pure and tested.
- Reading a log tail crosses a new port named for the need (a bounded tail read), implemented in
  `:adapter:ios:ext-safe` beside the writers — both processes link it, and the app-side read has no
  extension-unsafe dependency.
- The live command is built in `compose/` and injected into presentation through `UserCommands`;
  the confirm dialog is local screen state, exactly as `confirmingLeave` already is.

### D12 — Evidence

`commonTest` covers the pure assembly (budget never exceeded, tails start on a line boundary, greedy
slack borrowing, state contents) and the `scrubbedEvent` contexts pin. `:test:integration` drives the
command over the world and asserts one in-budget dump carrying both logs and the counts.
`:ui:screens` `jvmTest` (headless) covers the gesture: double-tap opens the dialog, `Cancel` sends
nothing, `Send` fires the command once, and a `null` command opens no dialog. Device verification
uses a dev IPA with `SENTRY_DSN` injected on the ssh-mac line — the documented on-device path — since
no dev build carries a DSN by default.

## Risks / Trade-offs

- **An over-cap dump is silently lost.** Ingest answers `413` and the SDK swallows transport errors,
  so the gesture completes and nothing ever arrives → the budget keeps ~280 KB of headroom rather
  than maximising history, and the constant names `MAX_EVENT_SIZE` so the two are changed together.
- **Bugsink's ingest behaviour could change.** The whole payload shape rests on context strings not
  being trimmed, which is true of this implementation and not of Sentry's → expiry trigger: re-run
  the probe if dumps start arriving short, or before relying on a larger budget.
- **Live event ids reach a third party.** Accepted in D9; recorded here so it is not re-litigated as
  an oversight.
- **The extension's log is no longer directly pullable.** `apps pull app.snapsync.BackgroundUpload
  Documents/debug.log` keeps succeeding while returning a **frozen** file → the extension deletes
  that stale file once on launch, so the command fails honestly instead of lying, and CLAUDE.md is
  updated to the export route. (This supersedes the earlier "leave stale files alone" call, which was
  made when *both* logs were moving and both old paths were obviously dead.)
- **The App Group container URL can be null.** A writer that resolved to null would log nothing at
  all, invisibly → the extension's writer falls back to `Documents/` and says so in its boot banner.
- **A dump's breadcrumbs duplicate its own log tail.** ~4% of the budget, and suppressing them for a
  single event is more machinery than it is worth → accepted.
- **The app may read `ext-debug.log` while the extension is appending.** Writes are single atomic
  `O_APPEND` calls, so the worst case is a missing final line, never a torn one → accepted.

## Migration Plan

No data migration and no backend change. On first launch of the new build the extension begins
writing to the App Group and deletes its stale `Documents/debug.log`; the app's log path is
unchanged. The `UserCommands` field is nullable, so any composition that does not supply it —
forge, desktop harnesses — is correct by construction with no edit.

Rollback is a revert: nothing durable is written that an older build would misread.

## Open Questions

None outstanding. The two that gated the design — whether Bugsink accepts attachments, and whether a
340 KB context survives ingest — were settled by measurement (Context above).
