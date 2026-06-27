## Context

The ledger (`sync-ledger`) is the engine's only memory of what has been uploaded. The extension is
its **single writer**, codified by construction (`ios-app-shell`: "the app never constructs a
`LedgerWriter`"). The app is a read-only projection: it holds a `LedgerBackend` (it already calls
`clear()` in `resetForReprovision`) but never records.

The ledger lands **empty against a non-empty event** in two ways:

| Event | `ledger.db` (App-Group) | `NSUserDefaults` (App-Group: cursor, deviceId) | Keychain (eventId) |
| --- | --- | --- | --- |
| delete + reinstall | WIPED (no app code runs) | WIPED | SURVIVES |
| app update + destructive migration | `ledgerRow` dropped | survives | survives |
| normal launch | intact | intact | intact |

In both empty cases the extension's next full enumeration re-uploads the entire library, even though
the objects already exist in storage under the old (now-rotated) `deviceId`. The reinstall-stable
identity is the `filename` (the `uploadKey` `<assetId>-<kind>.<ext>`, where `assetId` is the
PHAsset `localIdentifier` normalised so `/`→`_`); `bunny-list-endpoint` exposes those filenames
across all devices. This change consumes that endpoint to pre-seed the ledger.

The engine skips an upload only when a `COMPLETED` row exists **at the same `version`**
(`ResourceChanged` → `AlreadyUploaded`). `version` is the asset modification timestamp; the list
endpoint returns the *upload* time, not that. So the correct `version` exists **only at local
enumeration** — which is why reconciliation must compare against the local library, not just trust
the remote list.

## Goals / Non-Goals

**Goals:**
- A re-joining device does not re-upload photos already stored for the event.
- Status is correct **immediately on join**, before the OS-scheduled extension ever runs.
- Robust to both wipe modes (reinstall, migration) and to an event switch, with no persistent
  "joined" marker that can desync from the ledger.
- Preserve the single-*record*-writer invariant.

**Non-Goals:**
- Forcing a re-upload (re-provision now reconciles; the dev verification loop is handled elsewhere).
- Detecting/handling a photo edited between original upload and re-join (accepted: not re-uploaded).
- Network-reachability-driven auto-retry (re-scan or relaunch is the retry).
- List pagination (bunny Storage LIST is non-paginated; the full directory returns in one call).

## Decisions

### D1 — Seeding runs in the app at join time (not in the extension)
The hard requirement "see current status immediately after joining, without the extension running"
rules out extension-time seeding: `processJobs()` is OS-scheduled and cannot be forced, so an
extension-seeded ledger could stay at `0 backed up` for an unbounded time. Therefore the app writes
the seeded rows synchronously during the join. **Alternative rejected:** extension seeds on its first
cycle (clean for single-writer, but fails the immediacy requirement).

### D2 — Seeding is an app-side *reset*, not a *record*
Seeding asserts "these objects exist in storage" (proven by the LIST), not "I observed an upload" —
a different kind of fact, and a whole-ledger replacement rather than per-key recording. We add
`LedgerBackend.resetTo(entries)` to the **reset family** that already includes `clear()`. The app
still never constructs a `LedgerWriter`; the extension stays the single *record* writer.
- **Atomic** (one transaction, single `changes` ding): an interrupted seed commits nothing → ledger
  stays empty → the gate re-runs next launch. A per-key `put` loop could leave a partial ledger that
  silently re-uploads the remainder.
- **Run with the extension disabled** (disable → `resetTo` → enable): no concurrent writer.
- **Alternative rejected:** hand the app a `LedgerWriter` (breaks the compile-time guarantee for all
  app code) or `backend.put` per row (non-atomic, bypasses the discipline).

### D3 — The gate is ledger-emptiness; there is no persistent "joined" marker
Reconcile iff *event configured ∧ ledger has no rows ∧ join not settled this process*. The ledger's
own emptiness is the only wipe signal that never desyncs — uninstall wipes it with **zero app code
running**, so any external marker (Keychain especially) would survive and falsely claim "joined"
against an empty ledger. **Alternative rejected:** a `joinedEventId` marker in Keychain/NSUserDefaults
(Keychain survives uninstall → re-uploads everything; NSUserDefaults survives the migration → same).

### D4 — Event switch is detected via the Keychain eventId at scan time
A switch only ever arrives through `onOpenUrl` (scan / `SNAPSYNC_DEEPLINK`). Compare the decoded
eventId to `ConfigSource`'s current value: **different** → `resetTo([])` then reconcile for the new
event; **same** against a non-empty ledger → no-op. This is the one job the persistent Keychain
eventId is right for (switch detection), distinct from the "joined?" question (D3). Re-join of an
already-joined event is therefore a true no-op, for free.

### D5 — `joinSettledThisSession` is in-memory only
Set on success **and** failure; cleared by an explicit re-scan. Gives exactly one attempt per
process (a launch makes one attempt; a failure does not auto-retry), and a manual QR re-scan is the
only retry. In-memory means it dies with the process — it can never desync from a wiped ledger.

### D6 — Library enumeration is shared in `:domain:gallery`; the extension delegates
The app's seeded `version` must equal what the extension later recomputes, or the match fails and the
photo re-uploads. Make the PhotoKit `Resource` derivation in `:domain:gallery` the single
implementation and refactor `IosUploadJobPlatform`'s full-enumeration path to delegate to it. The new
seam exposes `(filename, assetId, version)` per resource and a settable fake (JVM/tests). To keep the
`gallery-status` compile-classpath rule (gallery types never reach presentation), the enumeration
lives in `:domain:gallery` while `EventStatusSource` lives where presentation can see it.
**Alternatives rejected:** app depends on the extension module (couples two process frameworks);
duplicate the derivation in the app (drift → silent re-uploads).

### D7 — Reconcile clears the discovery cursor
After a *migration* wipe the cursor survives in `NSUserDefaults`; resuming incremental discovery from
it would never re-enumerate the old **never-uploaded** photos (not in the manifest → not seeded →
never discovered → never backed up). Pairing `resetTo(seeds)` with `clearDiscoveryCursor()` forces a
full re-enumeration so un-seeded photos upload normally.

### D8 — Matching is filename-only; block until success; no auto-retry
Match by the encoding-safe `uploadKey` (direct string compare; `encodeURIComponent` is identity for
these chars). Seed `COMPLETED` at the **local** `version`, `updatedAt = manifest.lastModified` (so
"last backed up N ago" is honest). A failed list fetch → `UiState.JoinFailed`; the user re-scans.
The extension is not enabled until a join succeeds.

### D9 — Network via a new `EventFilesSource` (Ktor)
The app makes no HTTP today. Add a multiplatform Ktor client behind an `EventFilesSource` port
(`suspend list(eventId): Result<List<RemoteFile>>`), Darwin engine on iOS, HTTPS-only (default ATS).
The host is the same compile-time device-facing host the upload path uses, injected at the root.

### Join flow (app, extension disabled)
```
onOpenUrl(decoded E):
   if E != ConfigSource.eventId:  resetTo([])        // D4 switch
   save(E); joinSettledThisSession = false

before enabling extension (launch / grant / post-scan):
   if event configured AND ledger has no rows AND !joinSettledThisSession:   // D3/D5
      EventStatus = Joining
      manifest = EventFilesSource.list(eventId)        // D9; fail → JoinFailed; settled
      local    = gallery.enumerate()                   // D6: (key, assetId, version)
      seeds    = local where key ∈ manifest.filenames  // D8: COMPLETED@version, updatedAt=lastModified
      resetTo(seeds); clearDiscoveryCursor()           // D2/D7 atomic, ext disabled
      EventStatus = Joined; joinSettledThisSession = true
   enable extension
```
`resetTo` dings `LedgerWatcher` → the status projection shows seeded counts at `Joined` (immediacy).

## Risks / Trade-offs

- **OS cycle in flight at disable** → the extension might record while the app resets. Mitigation:
  disable before any `resetTo`; WAL keeps SQLite consistent; worst case is one redundant upload,
  self-healed next cycle. Low probability (only on a switch while a cycle happens to run).
- **Photo edited between upload and re-join** → seeded `COMPLETED` at the new local version, so the
  edit is not re-uploaded. Accepted (one-way personal backup; rare). Filename-only matching is the
  deliberate choice.
- **Very large library** → a single device directory can hold tens of thousands of objects; the LIST
  response and the app's enumerate+match are sizable. Mitigation: the `Joining` spinner covers the
  one-time cost; non-paginated LIST means complete (not truncated) data. Note as a scaling edge.
- **Brand-new empty event, repeated opens** → ledger stays empty until the first upload lands, so
  each *new process* re-fetches an empty list. Mitigation: `joinSettledThisSession` suppresses
  re-fetch within a process; the cost is one cheap `[]` GET per cold launch in that brief window.
- **bunny LIST gains pagination** → the manifest could truncate → degraded savings (re-upload the
  remainder), never data loss (unmatched = upload). Mitigation: record the non-pagination assumption
  in `bunny-list-endpoint`; cheap to revisit.

## Migration Plan

- Additive seam + reset op; no ledger schema bump (the `resetTo` op uses the existing columns).
- `IosUploadJobPlatform` delegating to `:domain:gallery` is behavior-preserving (same derivation,
  covered by the shared contract test).
- **Behavior change:** re-provision reconciles instead of forcing a re-upload — update `docs/design.md`
  and `CLAUDE.md`; the `SNAPSYNC_DEEPLINK` dev loop is replaced separately. No data rollback needed
  (the ledger is rebuildable from enumeration + storage).

## Open Questions

- None blocking. The non-pagination assumption (Risks) is the one cheap external fact worth a
  one-line confirmation against live bunny before relying on it for very large events.
