## Context

The ledger state machine today:

```
DISCOVERED ─▶ REQUESTED ─▶ UPLOADED ─(cycle: place in album, promote)─▶ COMPLETED
                  └──────▶ FAILED
```

`UPLOADED` was added by `changes/archive/2026-08-26-fix-lost-upload-acks` because iOS delivers a
background-`URLSession` completion exactly once, so the terminal fact had to be written durably inside the
delegate. That record rejected writing `COMPLETED` from the delegate outright (its D15), for three reasons:

1. the completion notify fired off a false→true `COMPLETED` transition;
2. event-album placement fired off the same transition;
3. the delegate had no `eventId`, no `attempt`, no engine, and no construction-order path to the
   `LedgerWriter`.

Checked against the tree at `efe95c8c`:

1. **Overtaken.** There is no notify. `UploadCycle` documents the removal (the manifest write *is* the
   announcement on `/api/v2`), and `writeDeviceManifest`'s Boolean answer is discarded.
2. **Overtaken by this change**, which moves placement off the completion path (D2). Folding the state
   without moving placement would silently drop own-photo placement — so the two halves ship together.
3. **Overtaken.** Both tiers already record terminal facts through `LedgerStore.markTerminal`, a guarded,
   state-only `UPDATE` needing none of those.

What still reads `UPLOADED`: `isDone`, `needsJob`, `bytesBelievedStored`, `SyncEngine.decide`, the promotion
pass and its two store verbs, three fakes plus a test stub, and the tests. Nothing in the UI, the rig's
state endpoint, the desktop inspector, or the diagnostic dump names it (the dump reads `aggregates()`).

## Goals / Non-Goals

**Goals:**

- One terminal success state, written where the platform reports it.
- Own-photo album placement that depends on no upload outcome and cannot be lost to a crash.
- Existing `UPLOADED` rows settle without re-placing anything, and a staged revert stays shippable.
- The one verb platform callbacks record through can express only terminal outcomes.
- Remove the engine completion path nothing calls.

**Non-Goals:**

- Download import's album placement (already atomic at import).
- `markTerminal`'s guard (`WHERE state = 'REQUESTED'`) — unchanged.
- Back-placing already-uploaded photos when `saveToAlbum` is turned on (the toggle stays forward-only).
- Repairing a wrong PhotoKit "succeeded" (the foreground ledger audit detects it; repair is a separate topic).
- Any other tierless phase (M0–M8b).

## Decisions

### D1 — A successful upload is recorded `COMPLETED` directly

```
DISCOVERED ─(slice about to be enqueued: place)─▶ REQUESTED ─▶ COMPLETED
                                                       └─────▶ FAILED
```

The `URLSession` delegate, the PhotoKit terminal disposition and the world's fake queue write `COMPLETED`.
`COMPLETED` now means *the platform reported success*; nothing else is owed, because the manifest already
declared the resource at discovery and placement already happened at enqueue.

Readers of `COMPLETED`, before and after:

| reader | today | after |
|---|---|---|
| `SyncEngine.decide` | `UPLOADED` + `COMPLETED` skip | `COMPLETED` skips — same |
| `aggregates` / pending read | pending until the next cycle | done when the store signals the write |
| `recreateRetrySpent` done-skip | `UPLOADED` passes → `FAILED` written over it | skipped — latent re-upload closed |
| ledger audit (`bytesBelievedStored`) | `UPLOADED` + `COMPLETED` | `COMPLETED` — same set |
| left / direction-declined device (D17 of the record) | rests `UPLOADED`, pending in a dump | `COMPLETED` |
| re-join seed (`resetTo`) | writes `COMPLETED` | unchanged |
| manifest projection | state-blind | unchanged |

After this change `COMPLETED` has three writers, each a real fact: `markTerminal` (the platform said so),
`resetTo` (the device listing holds it), and `8.sqm` (legacy rows, once).

*Alternative rejected:* keep `UPLOADED` and only move placement. The state would then carry no obligation
at all — a delay with a name.

### D2 — Own-photo placement happens when the upload is first enqueued, before its job is created

In `UploadCycle.enqueue`, after the admitted slice is resolved (`resourcesFor`) and **before** the
job-creation loop, a `saveToAlbum` membership places — in one best-effort call — the assets of the slice's
rows that are still `DISCOVERED` and resolved to a live resource.

Placement points considered:

| | at admission (walk) | after `CREATED` | **before the job loop (chosen)** |
|---|---|---|---|
| offline, a new photo reaches the album | ✓ | ✓ | ✓ |
| respects the **current** policy | ✗ admission-time policy | ✓ | ✓ enqueue re-admits |
| batch size | whole first walk | ≤ slice | ≤ slice (URLSession `cap`; PhotoKit 16) |
| crash between the durable write and placement | n/a | **loses** placement (row is `REQUESTED`) | cannot lose it (row stays `DISCOVERED`) |
| `LIMIT_EXCEEDED` early return | n/a | needs a second call site | nothing to duplicate |

- **Admission** was the handoff's proposal. Rejected: it places photos a later narrowing excludes before any
  attempt, and a first join places the whole in-window library in one `addAssets` call from the extension,
  whose runtime the OS caps at ~3 minutes.
- **After `CREATED`** is closest to "on its way", but `UploadStarted` writes `REQUESTED` durably per row, so a
  process death before placement leaves rows no later pass ever places; and the loop's `LIMIT_EXCEEDED`
  return would need its own placement.
- **Before the loop** places intent one step earlier: a row whose creation fails or hits the limit stays
  `DISCOVERED` and is placed again next cycle, which is free — `addAssets` on an asset already in the
  collection is a no-op (measured, simulator, iOS 26.5; recorded in the `fix-lost-upload-acks` design).

Consequences, each intended:

- `FAILED` rows being re-created (the enqueue read, `recreateRetrySpent`, the `.retry` phase) are not placed
  again: their first attempt passed through as `DISCOVERED`.
- A direction-declined, unreadable, not-joined or seed-deferred cycle never reaches `enqueue`, so places
  nothing — as today.
- An unresolvable row is marked absent and not placed.
- Placement stays best-effort and never gates job creation (D10 of the record), and still needs an existing
  album (`AlbumCoordinator.place` skips when none exists; the app is the sole creator).

Placement is an **update-stage** effect — a write to the member's own library, not something the event sees —
so it leaves the cycle's publication decision (capability `upload-lifecycle`).

### D3 — `8.sqm`: a data-only migration rewrites `UPLOADED` to `COMPLETED`

```sql
UPDATE ledgerRow SET state = 'COMPLETED' WHERE state = 'UPLOADED';
```

Removing the enum value makes a stored `'UPLOADED'` fatal twice over: the stock `EnumColumnAdapter` throws on
decode, and the state-scoped SQL reads compare raw text against the bound done/needs-job sets — so
`aggregates()` would count such a photo pending forever with no error.

Alternatives considered:

- **Decode alias** (a custom column adapter reading `'UPLOADED'` as `COMPLETED`). Rejected: fixes Kotlin reads
  only; `aggregates` and the pending read still see the raw text.
- **Sweep on every store open.** Revert-safe with no version bump, but a full scan per open in both
  processes, possibly on the main thread, and an undocumented write in the store's construction.
- **Sweep inside the cycle** (beside `backfillEventId`). Runs only after a settled seed, so stale rows stay
  visible to status and dumps until then, and it would need the decode alias as well.
- **Keep `UPLOADED` as a never-written legacy value classified done.** Zero SQL, since the done set is bound
  from Kotlin — but it leaves a dead value in the `model/` vocabulary every feature reads, which is what this
  change exists to remove.

The migration is chosen because it runs once, inside the driver's migration transaction, at the version
bump every earlier ledger migration already made.

**It is the first data-only ledger migration**, so the SQLDelight `verify` task (migrated schema vs created
schema) cannot catch a wrong one. A migration test is required: a v8 database with a raw `'UPLOADED'` row,
migrated to current, decodes the row as `COMPLETED` **and** `aggregates()` counts that photo completed.

### D4 — Rows converted by the migration are not placed

SQL cannot call PhotoKit, and `UPLOADED` is transient — written by a delegate and settled by the next cycle —
so few rows exist at upgrade. A one-time placement path would run once per device and then be dead code
forever. Losing placement for that handful is accepted, and the migration is therefore a pure state rewrite,
which is also what rules out the failure D1 of the record warned against (re-placing a whole library on first
launch after an upgrade).

### D5 — `bytesBelievedStored` stays a separate classification

After the change `isDone` and `bytesBelievedStored` both reduce to `state == COMPLETED`. They are kept
separate: they answer different questions ("is anything owed?" vs "does this row claim the bytes landed?"),
their exhaustive `when`s force a future state to be classified on each, and the ledger audit should not
couple to what "settled" means. Cost: one four-line `when`.

### D6 — `markTerminal` takes a `TerminalOutcome`

```kotlin
enum class TerminalOutcome { COMPLETED, FAILED }   // model/
fun markTerminal(key: String, outcome: TerminalOutcome): Boolean
```

With the engine no longer recording completion (D7), `markTerminal` is the only way an upload outcome enters
the ledger, so its type is where the set of outcomes is stated. Passing `DISCOVERED` or `REQUESTED` through
the guarded write becomes a compile error. `TerminalDisposition` carries the outcome. The SQL statement and
its guard are unchanged; the adapter maps the outcome to the stored state.

*Alternative rejected:* keep `LedgerState` and document the allowed two. A convention on the one verb that a
platform callback reaches through `LedgerStore` — deliberately outside the writer's type-level protection
(`sync-ledger`, "Reader and writer capability split") — is the place a type is worth most.

### D7 — The dead completion path is deleted

`SyncEvent.UploadCompleted` → `SyncEngine.complete` → `LedgerWriter.recordCompleted` has no production
caller: both platforms record through `markTerminal`, and the seed through `resetTo`. After D1 the
`sync-engine` requirement "Completion recording" would describe a path that never runs.

The ~28 test call sites that use `recordCompleted` as a shortcut to seed a `COMPLETED` row move to a
test-only helper in `:test:world` `commonMain` over the guarded `LedgerStore.recordUnlessSettled` (this
change was rebased onto `changes/archive/2026-09-15-record-never-overwrites-settled-row`, which removed `put`),
so no production surface exists for tests and no call site re-states a row's columns by hand.

## Risks / Trade-offs

- **The album can hold own photos that never upload** (a narrowing change after placement, or an upload that
  keeps failing) → accepted and consistent with "admits on doubt": the album is already wider than the current
  share today, because nothing removes from it; the failing case is still being retried and shows as pending.
- **The album-on boundary shifts slightly**: a photo whose job was created while the album was off, finishing
  after the toggle, was placed today and is not after → accepted; the spec restates the forward-only rule in
  enqueue terms and the reconfigure helper text stays true.
- **A wrong PhotoKit "succeeded" is recorded `COMPLETED`** → same exposure as today (`UPLOADED` was skipped by
  the engine too); the foreground ledger audit still detects it.
- **`8.sqm` is invisible to the migration verify task** → the migration test in D3.
- **`markTerminal`'s port shape is likely redesigned by phase M7** → the change is one enum and one parameter;
  it constrains nothing M7 must keep.
- **Placement runs one PhotoKit call per enqueuing cycle for a `saveToAlbum` membership**, including repeats
  for rows whose creation keeps failing → bounded by the slice, idempotent, best-effort.

## Migration Plan

- Ship `8.sqm` with the Kotlin change. Every device upgrades on first open of either process; the rewrite
  matches nothing on a device with no `UPLOADED` rows.
- **Downgrade stance:** the native driver refuses a database newer than the binary's schema, so a revert
  SHALL be **staged** — keep `8.sqm`, revert only the Kotlin — as with `4.sqm`/`5.sqm`. The reverted build
  understands `COMPLETED`, and re-gains `UPLOADED` and its promotion. Re-applying this change after such a
  revert SHALL carry a fresh migration with the same rewrite, because `8.sqm` has already run on those
  devices.

## Open Questions

None.
