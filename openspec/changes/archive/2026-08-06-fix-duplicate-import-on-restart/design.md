# Fix design — duplicate foreign-photo import → echo upload (SNAPSYNC-6)

Agreed in interview. **Nothing implemented.** Companion to `duplicate-import-rca.md`.

## Base: `hold-os-receipts-until-work-completes` (merged `22f782bd`)

Rebased onto it. It **raises the stakes and completes the case** for this change:

- `IosPhotoLibraryImporter` now bounds the **wait** at `IMPORT_DEADLINE = 5 s` via `withTimeoutOrNull`,
  yielding a new `ImportResult.TimedOut`. The change block has already run by then — the handle is
  written — and the commit **may still land**. Its own comment says so: *"every abandoned transaction
  may still commit, **which is a duplicate photo**"*.
- So `PENDING + handle` is no longer only a crash artifact. It is a **routine, designed outcome** of a
  deadline, on every tier, under full access. Our guard is what repairs it, and that change deliberately
  left the repair to this one.
- `DownloadController.importReadyLocked` gained a `TimedOut` arm that **stops the wake's drain** so only
  one transaction is ever abandoned at a time. Our guard is a batched pre-pass *before* the loop, so the
  two compose without interacting.
- **Untouched by that merge, so every premise below still holds verbatim:** `DownloadStore.sq`,
  `SqlDelightDownloadStore`, `InMemoryDownloadStore`, and the `DownloadStore` port have zero diff —
  `selectImportableAssets` still ignores `createdLocalId`, `markImported` still overwrites it, and
  `deleteNonTerminalAssets` still deletes handle-carrying rows.

## The invariant

> An asset already created for a `(sourceDeviceId, sourceAssetId)` is never created again, and the
> record that it was created is never destroyed.

## Why no new schema state is needed

`state = PENDING AND createdLocalId IS NOT NULL` **already is** the unconfirmed state, and can arise
exactly one way — the change block wrote the handle and confirmation never followed:

- the handle is written **inside** the change block, before the block returns; PhotoKit commits only
  **after** it returns ⇒ **asset created ⇒ handle recorded** (proven on device: at 09:06:46, after the
  watchdog kill and before the re-import, `BB4F7765` was still suppressed — `admitted 0 of 5`, `N=0`);
- `markImported` is the only writer of `IMPORTED`, `plan()` is `INSERT OR IGNORE`, nothing downgrades
  ⇒ a confirmed row is never revisited.

So there is **no column, no third state, and no migration** — and devices already stuck in this window
heal on their next import pass, because a stuck row out there today is already exactly this shape.

This also dissolves the "absent could mean the user deleted it" problem: a user-deleted photo's row is
`IMPORTED`, and the guard never looks at those.

## The five parts

### 1. The unconfirmed state — as above, no change

### 2. The importer undoes its own record on observed failure
In the `performChanges` completion, when `success == false`, **clear `createdLocalId`** — the exact
mirror of the in-block write, in the same callback. Preserves today's retry-a-failed-import behaviour
(`DownloadControllerTest.a_failed_import_stays_importable_for_retry` must keep passing) and keeps
*known* failures out of the unresolved bucket.

**This composes with the merged deadline in a way worth stating, because it is subtle and load-bearing.**
`performChanges`' completion is an ObjC block, not tied to the coroutine — so after a `TimedOut` it still
runs, even though `cont.resume` is then a no-op. Therefore:

| late outcome of an abandoned import | what happens |
|---|---|
| commit **succeeded** | handle stands → next pass the guard sees `PRESENT` → settles. No duplicate. |
| commit **failed** | the undo fires from the abandoned callback → handle cleared → row importable → retried. |

So a timed-out import is self-correcting in both directions, and the guard only ever has to adjudicate
rows whose callback never arrived at all (process death). Do **not** clear the handle on `TimedOut`
itself — the transaction may still commit, and clearing it there is precisely how the first copy gets
orphaned.

### 3. A presence guard on the import path — TWO PHASES, the fetch outside the lock

```
   phase 1  ── NO LOCK ──   read handle-carrying importable rows
                            ask the presence port (batched, one call)   ← blocking XPC lives HERE
   phase 2  ── withLock ──  apply the verdicts, then run the import drain
```

**The split is not tidiness; it is the difference between this change and the bug next to it.**
`hold-os-receipts-until-work-completes` could bound the import wait only because `performChanges`
returns immediately and suspends nothing but the coroutine — *"abandoning the wait frees a continuation,
not a thread, and `withTimeoutOrNull` is safe here in a way it would NOT be around a blocking call like
the change-feed fetch"*. `fetchAssetsWithLocalIdentifiers` **is** that other kind of call: a synchronous
XPC round-trip that blocks its thread, which no timeout can rescue (cancellation is cooperative). Run it
under `DownloadController.mutex` and a wedged `photolibraryd` re-creates exactly the field pathology that
change just removed — every reconcile, import, leave and switch queued behind it, permanently.

Off the lock, a wedged library parks one background thread instead. Staleness between the phases is
harmless: `markImported` is idempotent, and a row confirmed in between is simply no longer importable.

The adapter also **owns its dispatcher hop** (`withContext(Dispatchers.Default)`) — the stated law for
sync-I/O port impls, and what `IosDiscovery` does with a forcing proof attached (build 521 died on main
inside `fetchPersistentChangesSinceToken`).

The port is asked **once per import pass**, batched — and not at all when no row carries a handle, which
is the normal case, so steady-state cost is one SQL query returning no rows.

| verdict | action |
|---|---|
| `PRESENT` | `markImported(ref, existingHandle)` — settle, do not import |
| `ABSENT` | **clear the handle**, then import |
| `UNKNOWN` / absent from the map | skip entirely; retry next pass |

Clearing before the `ABSENT` import matters: an import that fails *before* reaching the change block
(e.g. an unmapped resource type) would otherwise leave the bogus handle in place and skip forever.

### 4. The port answers by grant — because a miss is only TRUSTWORTHY under `GRANTED`

| grant | source | verdicts producible |
|---|---|---|
| `GRANTED` | `PHAsset.fetchAssetsWithLocalIdentifiers` | `PRESENT` · `ABSENT` |
| `LIMITED` | set-membership on `latestSelectionSnapshot` — zero Apple calls | `PRESENT` · `UNKNOWN` |
| `LIMITED`, snapshot still null | — | `UNKNOWN` |
| `DENIED` / `NOT_DETERMINED` | — | `UNKNOWN` |

`ABSENT` is producible **only** under `GRANTED`. Everywhere else a miss is `UNKNOWN`.

**The reason is answer reliability, not alert safety** — and the device probe
(`limited-fetch-probe-findings.md`, 2026-08-05, iOS 26.5.2) is what separates those two:

- The alert objection is **retired**. Measured rule: a `PHAsset` fetch under `.limited` surfaces the
  alert *iff the library gained content outside the app's selection since it last looked* — once per
  change, not per fetch. App-created assets join the selection, so resolving one never arms it. The
  guard could fetch under `LIMITED` at zero marginal cost.
- The reliability objection **stands**. Under `LIMITED` a fetch sees only the selection, and
  `PROBE-FINDINGS.md` measured that auto-add is **creation-time only and does not survive a full→limited
  downgrade**. So an asset imported under `GRANTED`, after the grant narrows, is real but invisible: the
  fetch answers *absent* about a photo that exists. Acting on that clears the handle, re-imports, and
  orphans the first copy — the exact bug this change fixes.

Since a `LIMITED` fetch and the snapshot lookup see the *same* set, fetching buys nothing there. Keep
the snapshot lookup: same answer, no XPC round-trip.

**`DENIED` / `NOT_DETERMINED` is a real case, not a formality.** A row can carry a handle written while
access was granted and then have access revoked. A fetch then returns empty for an asset that exists —
`ABSENT` would be a lie, the handle would be cleared, and once access is restored the unsuppressed asset
echoes back into the event. Imports cannot succeed without a grant anyway, so `UNKNOWN → skip` is both
correct and free.

PhotoKit offers no non-fetch existence query (`fetchAssetsWithLocalIdentifiers(...).count > 0` *is* the
true/false form; the change feed is forward-only off a shared cursor and answers "inserted since T",
not "exists now"; the event album is readable only via another fetch). The snapshot lookup is the query,
and the ids are exactly comparable — both sides normalize through `normalizeAssetId` (`/`→`_`), already
load-bearing for echo suppression.

### 5. Handle-carrying rows survive the prune
`deleteNonTerminalAssets` gains `AND createdLocalId IS NULL`; same in `InMemoryDownloadStore`. Covers
leave, event switch, and `SNAPSYNC_RESET_STATE`. The `download-store` requirement is rewritten from
*"terminal rows are permanent"* to **"handle-carrying rows are permanent"** — the handle, not the
state, is the record of an irreversible act. `ResetDeviceState`'s KDoc already argues this for imported
rows and stops one step short; it gets the same correction.

### 6. Staged bytes are released on confirmation — and only then

Nothing in the download path deletes a staged file today. The only `removeItemAtPath` is inside
`moveToStaging`, clearing the destination before a re-download. So every foreign photo a device receives
is stored **twice, permanently**: as the `PHAsset`, and as its staged bytes under
`<AppGroup>/download-staging/` — which is **not** under `Library/Caches`, so iOS never reclaims it.
SNAPSYNC-2's device reports `downloads_imported: 102`; at the 1.4–3 MB per resource the union listing
shows, that is **~200 MB** of dead bytes, growing with every event.

**The store already knows where the bytes are** (`downloadResource.stagedPath`), and the controller
already reads them to feed the importer — so no path derivation is involved and `PhotoDownloadJobs`,
`DownloadTransport` and `QueuedPhotoDownloadJobs` are all untouched. One new port, named for the need:

```kotlin
interface StagedBytes { suspend fun release(paths: List<String>) }
```

Three release moments, all best-effort (`runCatching` — a failed unlink must never fail an import):

| moment | why |
|---|---|
| after `markImported` — from a successful import **and** from the guard's `PRESENT` | confirmation is the first instant the bytes are provably redundant |
| before `pruneNonTerminal` — in `DownloadController.onLeaveOrSwitch` **and** in `ResetDeviceState` | the rows about to be dropped are the last reference to those files |
| a backlog pass over `IMPORTED` assets that still hold resource rows | reclaims what pre-upgrade installs already leaked |

**Releasing also deletes that asset's `downloadResource` rows**, which makes the backlog pass
**self-extinguishing** — a second run finds nothing, because the rows that made the work findable are
gone. No flag, no migration, no run-once bookkeeping. Safe because nothing reads an `IMPORTED` row's
resources: `selectImportableAssets`, `selectPendingResources` and `countInFlightAssets` all exclude
`IMPORTED`. It also removes the lie of a `stagedPath` pointing at a deleted file.

#### The ordering rule, which is load-bearing

```
   markImported → release      crash between → bytes remain under an IMPORTED row
                               → the backlog pass reclaims them.  RECOVERABLE
   ─────────────────────────────────────────────────────────────────────────────
   release → markImported      crash between → bytes GONE, row not IMPORTED
                               → selectPendingResources needs stagedPath IS NULL,
                                 and it is not → never re-downloaded, never
                                 importable.  PHOTO LOST PERMANENTLY
```

Same discipline as the write-split this change exists to fix: the recoverable order is the one where a
crash leaves *extra* state, never missing state.

#### A difference-sweep is possible and safe — but out of scope on size, not safety

Care is needed, and the naive form *is* unsafe: a keep-set built from recorded `stagedPath` values
races staging, because the transport moves a file into place **before** `onStaged` records the path, so
a file in flight looks unreferenced and deleting it loses that photo permanently (a staged resource is
never re-downloaded).

But the ordering rescues it. `reconcile` calls `store.plan(...)` — creating the asset and resource
rows — **before** `jobs.enqueue(...)` starts any transfer, so **rows always precede files**. A keep-set
built from **rows**, deriving each path via `stagingPath(root, ref, key)` rather than reading
`stagedPath`, therefore covers in-flight files too, and a file matching no row at all can only be an
orphan from a past prune.

Left out anyway: releasing before prune means nothing new is orphaned, so this would only reclaim
orphans from prunes that ran before this change — a set bounded by past leaves and switches, far
smaller than the confirmed-import backlog the self-extinguishing pass already reclaims. It costs a
directory-listing port operation and a keep-set derivation for that. Revisit if field dumps show the
staging directory holding bytes the backlog pass does not account for.

## Placement

| piece | where |
|---|---|
| `Presence` verdict (`PRESENT` · `ABSENT` · `UNKNOWN`) | `:domain` `model/` |
| `ImportedAssetPresence` port — `suspend fun presence(localIds: Set<String>): Map<String, Presence>` | `:domain` `ports/` (named for the need) |
| permission-aware impl (owns grant **and** the snapshot cell) | `:domain` `compose/`, mirroring `PermissionAwareCandidateSource` — the download feature gains no permission knowledge |
| the `GRANTED` fetch impl | `:adapter:ios:app-only` — never linked by the extension |
| honest in-memory impl | `:adapter:generic:fake` |
| `StagedBytes` port — `suspend fun release(paths: List<String>)` | `:domain` `ports/` |
| its file-deleting impl | `:adapter:ios:app-only` — the extension never downloads, so it stays structurally un-linkable |
| store reads `stagedPathsOfImportedAssets()` · `stagedPathsOfNonTerminalAssets()` · `deleteResourcesForAsset(ref)` | `DownloadStore` + `DownloadStore.sq` — **queries only, no schema change** |
| world impl over `WorldGallery`; fake importer's record-then-confirm seam + crash lever | `:test:world` (`DownloadFakes.kt` — outside the honesty gate) |

The extension is untouched: it reads only `suppressedLocalIds()`, whose predicate is unchanged.

## Logging

One line per **non-trivial** outcome — a handle-carrying row found, and which verdict it got (settled /
cleared and retried / left unresolved). Silent when no such row exists. This bug was diagnosable only
because `debug.log` showed which imports entered and never exited; these lines would have named it in
one grep.

## OpenSpec

One change, deltas on two capabilities:

- **`download-store`** — handle-carrying rows are permanent; a marker written for a change that
  PhotoKit reports as failed is undone rather than left stale.
- **`photo-download`** — an asset already created for a ref is never created again; the guard's three
  outcomes; the grant-dependent presence source; **staging lifetime** — staged bytes are released when
  the row is confirmed or dropped, never while it is still `Failed`/`TimedOut`/unconfirmed, and never
  before the confirming write commits.

## Tests

- **`DownloadStoreContract`** (`:test:world` commonMain, both impls): a `PENDING` row with a handle is
  in `suppressedLocalIds()`, and **survives `pruneNonTerminal()`**.
- **`DownloadControllerTest`** (`:adapter:generic:fake` commonTest): the three guard outcomes;
  `a_failed_import_stays_importable_for_retry` must still pass — it is the test that fails if the
  failure undo is missing.
- **Staging lifetime** (`DownloadControllerTest` + `DownloadStoreContract`): bytes **survive**
  `Failed`, `TimedOut`, and an `ABSENT`-triggered re-import; bytes are **released** on confirmation
  (from a successful import *and* from a `PRESENT` verdict) and before a prune; the backlog pass
  releases once and finds nothing on a second run. The `ABSENT → import` case is the one that catches a
  release fired too early — without the bytes it cannot import, and `selectPendingResources` will never
  re-download it.
- **`:test:integration`** (`commonTest` → JVM + simulator), over `:test:world`:
  1. crash lever: handle recorded, confirmation never arrives;
  2. re-drive `reconcile` / `importReady` → **exactly one** import for that ref;
  3. run an upload cycle → assert **no object under the orphan's key** (this is the assertion that
     pins the actual reported harm, not just the duplicate);
  4. repeat with a `World.leave()` interposed → pins the prune leg;
  5. `downloads_imported` reaches `downloads_assets`.

## Scope: the ungated-fetch sweep is NOT in this change

The investigation found six ungated PhotoKit reads reachable under `LIMITED` (#4 `logImportedDate`,
#5 importer album lookup, #6 `ensureAlbum.exists`, #7/#8 denylist, #9 `place`). The decision to fold
all six in rested on their being a storm risk. **The probe retired that premise**: they fetch either
right after an *app-created* change (which never arms the alert) or on cycles, where they can only
surface an alert the app's own sanctioned observer-driven read surfaces anyway.

So the sweep is **hygiene, not a user-visible fix**, and it is dropped from this change. What replaces
it is a bigger and better-evidenced finding that belongs to `limited-photo-access`, not here:

> `PHPhotoLibraryChangeObserver` fires for changes the app cannot see, and the app's response to an
> emission is the *sanctioned* snapshot re-read — a fetch. So **every photo a limited-access user takes
> arms one alert that the app's own blessed read then surfaces.** During an event that is one alert per
> photo taken. Limited access remains alert-prone on iOS 26.5.2, and the ungated sites are not the
> cause.

`logImportedDate` (#4) may still ride along as a one-line `GRANTED` gate — it is on the import path this
change already edits, and it removes a fetch that buys nothing on the tier where it is not free. Optional.

## Named residuals (accepted, to be stated in the change)

1. **A deleted orphan can come back — once.** On `ABSENT` we import, so if the user deleted the asset
   first it reappears. Do **not** justify this by frequency: the exposure is not the import window but
   the gap from the row being created to the guard next running, which can be hours.

   Justify it by **shape**. Because the failure undo removes the ordinary failed-commit case before the
   guard ever sees it, an `ABSENT` verdict means either (a) the asset existed and the user deleted it,
   or (b) the commit failed *and* its callback never arrived at all. Those are indistinguishable, so one
   of them must be got wrong — and the two ways of being wrong are not symmetric:

   | | `ABSENT → import` | `ABSENT → settle` |
   |---|---|---|
   | wrong case | a deleted photo returns | the photo never arrives |
   | recurrence | **once** — the re-import confirms the row, after which the normal "never re-import a deleted photo" guarantee applies | permanent; cross-event dedup blocks every retry |
   | visibility | visible, the user deletes it again | silent |

   Bounded and self-limiting beats unbounded and invisible. The re-import is also **purely local** — the
   handle is cleared first, so nothing is orphaned and nothing enters the event.

   *Rejected alternative:* PhotoKit's change feed exposes `deletedLocalIdentifiers` (already read by
   `IosDiscovery`), which could separate (a) from (b). Rejected: that feed is forward-only off a cursor
   the **upload** cycle owns and advances, the download side has none of its own, and a deletion while
   the app is dead may never be observed. Racing another feature for a shared cursor to resolve a
   self-limiting residual is a bad trade.
2. **`UNKNOWN` rows stay outstanding.** No duplicate, no echo (the handle keeps suppressing), and they
   resolve the moment the snapshot arrives or the grant widens to full access. Cost: that row shows
   outstanding in the download counter, and a free memory lookup per pass. Reachable three ways, all
   narrow: a `LIMITED` grant whose snapshot has not arrived yet; an asset imported under `GRANTED` before
   a downgrade to `LIMITED`; and photo access revoked entirely after an import. In the last two the photo
   is in the user's library and visible to them — only SnapSync cannot confirm it.
3. **Copies already in the wild are unrecoverable.** Identity is `(sourceDeviceId, sourceAssetId)` with
   no content-level dedup anywhere, and imports are terminal on every member. Flagged in the RCA;
   out of scope here.
