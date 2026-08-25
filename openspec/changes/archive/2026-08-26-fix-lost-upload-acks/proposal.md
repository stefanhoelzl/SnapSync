## Why

On the app-driven tier (iOS 18–26.0) a finished `URLSession` upload is recorded **only in memory** —
`IosUrlSessionUploadPlatform.terminal`, an `ArrayList` a *later* `UploadCycle` drains. The ledger row stays
`REQUESTED` until that cycle runs. If the process dies first the fact is gone: iOS never re-delivers a
completion it has already delivered, `getAllTasks` no longer lists the finished task, and the next cycle
classifies the key as stranded and **re-uploads bytes that already landed**.

The window is not small. It is bounded by the next `fetchAckJobs` call, which is gated on a single-flight
`UploadCycle` measured in the field at 27 minutes, 65 minutes, and 4h49m. Bugsink `SNAPSYNC-11` — *"why are
uploads ongoing? I haven't taken pictures since it was in sync the last time"* — is this defect end to end:
two photos uploaded successfully three times over two days, the ledger learning 27h30m after the first
successful PUT, and the status screen reporting them as pending the whole time because
`LedgerBackedSyncStatusSource` counts completed rows against a live gallery total.

Every genuine stranding in both field dumps (23 of 23 in SNAPSYNC-11, 5 of 5 in SNAPSYNC-16 once the
over-report below is discounted) was a **lost acknowledgement**, not a lost upload.

A second, independent defect makes the first hard to see. `pendingKeys` is wired to
`LedgerStore.pendingResources()`, which returns **every non-`COMPLETED` row** — while `strandedKeys`, its
KDoc, and the spec all say `REQUESTED`. So a `FAILED` row is re-surfaced as newly stranded on every cycle
forever. In SNAPSYNC-16 one key was reported stranded **12 times inside a single process**, seven of them
within 16 seconds.

## What Changes

- **The ledger gains one state, `UPLOADED`** — the bytes are stored, the announce/album step has not run.
  It is written by whoever the platform tells, at the moment it is told; the cycle promotes it to
  `COMPLETED`. No SQL migration is required: `state` is `TEXT AS LedgerState`, so existing databases simply
  contain no rows in the new state.
- **A guarded, non-suspending `markTerminal(key, state): Boolean`** on `LedgerStore` —
  `UPDATE … WHERE key = :key AND state = 'REQUESTED'`, reporting via `changes()` whether it applied. Both
  the `URLSession` delegate and the cycle's stranded pass write through it, so neither can clobber the
  other. It is called **synchronously from the delegate callback, before it returns**.
- **`pendingKeys` narrows to `REQUESTED` rows only.** A prerequisite, not a cleanup: with `UPLOADED` inside
  `selectPending`, a freshly-uploaded row would be handed to `strandedKeys`, found to have no live task,
  and written back to `FAILED`.
- **State predicates stop comparing to string literals.** `LedgerState.isDone` becomes an exhaustive
  `when` bound into `selectPending`, `aggregates`, and `selectCompletedManifestRows` as `:doneStates`, so a
  future state must be classified once, loudly, instead of landing silently on one side of three
  predicates.
- **BREAKING (internal port): `BackgroundTransfer.fetchAckJobs()` becomes `drainTerminals()`**, which
  records terminal facts into the ledger and returns **only** retry-spent failures the cycle must
  re-create. Successes no longer cross the port. `PlatformUploadJob` shrinks to `key`, `contentType`,
  `data`; `PlatformJobState` is deleted; `acknowledge` folds into the drain.
- **The PhotoKit tier uses the same two-phase rule** — it writes `UPLOADED` and acknowledges in place,
  then the shared promotion pass promotes. Behaviour there is unchanged (it has no callback outside the
  cycle), but the ledger gains one state machine rather than two.
- **`inFlight` and `terminal` are deleted from the app-driven adapter.** Every field is recoverable: the
  staging path is a pure function of the key, `contentType` is a ledger column, tasks come from
  `getAllTasks()`, and the resource from PhotoKit by `assetId`. This also fixes a latent bug — the
  concurrency cap is gated on `inFlight.size`, which is **empty after a relaunch** while the OS still holds
  live tasks, so a relaunch can run 8 transfers against a cap of 4.
- **The unimplemented storage clause is removed rather than implemented.** `ios-url-session-upload`
  currently requires a stranded row to be checked against storage ("*not present in storage*"); the code
  has never done it. It existed to compensate for a missing durable record. With the record present, the
  remaining stranded population is force-quits and dropped transfers — where iOS delivers no callback and
  the bytes genuinely did not land — so the check would pay a full per-device LIST to be told "no".
- **The state-and-authority law is corrected.** Its scenario says *"a **core object** holds state whose
  loss on process death loses a fact no port can restore"* — both this defect and its download-side twin
  live in **adapters**, which satisfy *"authority SHALL live behind ports"* vacuously: the fact is behind a
  port, the port cannot restore it. The law also gains the obligation this defect violated — an entry point
  receiving a delivery the platform makes **once** persists it before returning, and cites the proof that
  the delivery is once-only.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-ledger`: adds the `UPLOADED` state and its meaning; adds `markTerminal` (guarded, non-suspending,
  reports whether it applied) and `uploadedRows`; binds the done-state set from Kotlin instead of SQL
  literals; restates the single-record-writer invariant as **process-level**, since the adapter now records
  through `LedgerStore` from the delegate queue.
- `ios-url-session-upload`: the delegate records the terminal fact durably before returning; `pendingKeys`
  narrows to `REQUESTED`; the storage clause is removed from stranded reconciliation; `fetchAckJobs`
  becomes `drainTerminals`; the in-memory task registry and terminal list are removed and the concurrency
  cap is taken from the live task set; the at-least-once framing is restated.
- `ios-photokit-upload`: the adapter records `UPLOADED`/`FAILED` and acknowledges in place; the terminal
  disposition table moves to the shared promotion pass.
- `upload-completion-notify`: the fan-out trigger becomes the promotion of `UPLOADED` rows rather than a
  per-cycle `wasCompleted` delta — which makes duplicate-notify suppression structural (a re-presented
  success cannot re-enter `UPLOADED`) instead of a read-before-write.
- `module-architecture`: the state-and-authority law's scope is corrected to include port implementations,
  and the once-only-delivery obligation is added.

## Impact

**Code**

- `:domain` `model/` — `LedgerState`, `isDone`
- `:domain` `ports/` — `LedgerStore`, `BackgroundTransfer`, `PlatformUploadJob`, `PlatformJobState`
- `:domain` `feature/upload/` — `UploadCycle` (promotion pass, stranded pass), `LedgerWriter`
- `:domain` `compose/` — `UploadPorts`/`uploadCore` wiring for the adapter's ledger access
- `:adapter:generic:app` — `Ledger.sq`, `SqlDelightLedgerStore`
- `:adapter:generic:fake` — `InMemoryLedgerStore`
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform`, `SessionDelegate`
- `:adapter:ios:ext-safe` — `IosPhotoKitUploadPlatform`
- `:app:ios` — `UrlSessionUploadController` wiring
- `:test:world`, `:test:integration` — the ledger contract and the regression test

**No migration.** Adding an enum value to `state TEXT AS LedgerState` changes no schema, so the
`verify` task's migrated-vs-created comparison has nothing to match and no `6.sqm` is written.

**Verification.** `:test:integration` over `:test:world`: drive a cycle to a terminal callback, discard the
core and rebuild it over the same fake ledger — a process boundary the world can simulate — then assert the
next cycle promotes to `COMPLETED` and creates no upload job. Runs on JVM and `iosSimulatorArm64`, gates
`./gradlew build`.

**Closes** Bugsink `SNAPSYNC-11`. Does **not** address SNAPSYNC-16's status defect (owned by
`sync-then-ongoing`) or the multi-hour cycle latency (SNAPSYNC-17/18, owned by `upload-latency`) — the
latter is the amplifier that widened this window, and shrinking it narrows the hole without closing it.

**Related but out of scope.** The download arm has the same defect one layer down: `moveToStaging` makes
the bytes durable inside the callback, but `markStaged` — the record — is launched asynchronously, and
nothing consults the derivable staging path after a crash. Its window is milliseconds rather than hours
and 217 of 217 field staging events were clean, so it is left for evidence rather than built on suspicion.
