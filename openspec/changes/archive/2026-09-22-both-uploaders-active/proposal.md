## Why

The upload path is built on a premise that is false: that exactly one process may write the ledger at a time.
The ledger already has writers in both processes — the join's `resetTo`, the leave's clear, the device reset,
the ritual's `demoteRequested`, the app's completion callback, and whichever cycle is running — and each is a
guarded, one-transaction change. Enforcing "one writer" is what made every hand-off between the two uploaders
cancel or orphan in-flight work, and every orphan needed a repair: three of them for one fact (a per-cycle
stranded rule, a start-time stranded rule, and the registration ritual's bulk demote). Phase 5 set out to cut
those to one and found it could not (the handoff's second contradiction report): the demote is the only thing
that repairs rows orphaned by a **deregistration** (a download-only membership deregistered at the next launch)
or by an **app disarm** whose `-999` never lands (`LIMITED → GRANTED`). The user resolved it by removing the
premise, not by adding a rule: both uploaders are active, nothing hands off, so nothing is orphaned and nothing
needs repair.

The same cycle still bounds job creation by guessing: `remainingCapacity()` with a fixed batch of 16 when the
platform will not say. Both transports refuse honestly (`LIMIT_EXCEEDED`), so the guess goes too.

## What Changes

- **Both uploaders are active at once.** Each runs only when triggered (the OS launches the extension; the app
  runs on its own triggers), so concurrent cycles are the exception. A cycle picks only `DISCOVERED` rows and
  records `REQUESTED` only after `createJob` answers `CREATED` (write-after-act, unchanged); there is no claim
  step and no owner column. A photo is **preferably** uploaded by one uploader; an overlap duplicate is accepted
  (same destination object, idempotent PUT, completions converge through the guarded `markTerminal`).
- **Who creates.** The extension creates only under `GRANTED`; otherwise it acknowledges and records finished
  jobs only (today's withheld settle). The app creates whenever photo access is usable (`GRANTED` or
  `LIMITED`) — **new on iOS ≥26.1 under a full grant**, where its cycle declines today.
- **Registration spans the membership.** The extension is registered from join to leave wherever the OS allows
  it (iOS ≥26.1 and a full grant), download-only memberships included. It is never deregistered for a
  reconfigure or a permission change; it is deregistered only at a leave (a switch leaves). A launch or a
  permission upgrade registers a missing record; the join keeps the disable → enable toggle (it repairs the
  `3202` stale record) **without** the demote.
- **A re-provision of the joined event is a no-op for uploads** (`SwitchDecision.Stay`): no registration
  step at all, so a re-scan no longer wipes the extension's in-flight jobs.
- **Nothing is cancelled on a revoke or a reconfigure.** Revocation stops *new* creation only; in-flight
  transfers finish and record. **Leave** still cancels the app's transfers, deregisters the extension, and
  clears the ledger. `disarm` becomes "stop the heartbeat" and cancels nothing.
- **A late completion always records** (the guarded write), and re-pumps the app's uploader only if the app
  may create right now — so late `-999`s after a revoke or a leave no longer drive cycles (the 2026-09-16
  field observation).
- **Download-only is the selection policy admitting nothing.** No separate direction check in either uploader
  or in the transitions; the cycle's existing `policy.contributes` decline (no walk, empty manifest, `SKIPPED`,
  no heartbeat re-arm) is that check.
- **Removed (BREAKING, internal):** `LedgerStore.demoteRequested` and `demoteRequestedOffMain`; the ritual's
  demote; `strandedEachCycle`, `strandedAtStart`, `signalRestart` and the stranded pass;
  `BackgroundTransfer.liveKeys` / `lostKeys` / `discard`; `disarm`'s `cancelAll`; `UploadAdmission.NotResolved`;
  `resolveUploadMechanism` / `UploadMechanism` as "which single uploader runs" — replaced by the fact
  "may the extension be registered" (iOS ≥26.1 and `GRANTED`).
- **Enqueue creates until refused (half B).** `BackgroundTransfer.remainingCapacity` and `enqueueBatchSize`
  go. The cycle resolves admitted rows in small chunks and creates jobs until `createJob` answers
  `LIMIT_EXCEEDED`; a cycle is truncated exactly when the platform refused.
- **Rig.** The mechanism pin becomes a per-uploader switch for testing (the app's creation on/off; the
  extension's registration on/off), and `/os/photokit-ext` loses its "refused unless photokit" gate.
- `ProducerExclusivityTest` is re-pointed from "never two writers" to "no duplicate job starts in a normal
  sequence" plus the registration invariants that survive (never below 26.1, never written under a non-full
  grant).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `upload-lifecycle`: "exactly one mechanism writes the ledger" is removed; both uploaders' admission, the
  registration-spans-membership transitions, the Stay no-op, and "no destructive verb" without cancel/demote;
  the mechanism resolver and its override are replaced.
- `sync-ledger`: the single-record-writer invariant is restated as code ownership + guarded one-transaction
  writes; `demoteRequested` and the requested-state reset are removed; stranded/demote paths leave
  `DISCOVERED`'s sources.
- `module-architecture`: "exactly one writer feature per durable port" and the mechanism-identity selection in
  "One shared composition" are reworded to code ownership and the registration fact.
- `architecture-guards`: "The upload producers are never both started" is re-pointed.
- `ios-photokit-upload`: the extension is no longer the sole `LedgerWriter` or sole manifest writer; the
  registration toggle loses its repair and spans join→leave; the demote requirement is removed; the partial-grant
  and withheld requirements lose their one-writer framing.
- `ios-url-session-upload`: the app uploader runs on every OS; per-version tier selection, stranded
  reconciliation, the capacity and live-set transport reads are removed; disarm cancels nothing; the top-up
  creates until refused; a late completion re-pumps only when the app may create.
- `limited-photo-access`: the app runs under a partial grant because it runs under every usable grant, not by
  resolution; the surviving registration's extension records but never creates.
- `reconfigure-membership`: Save never touches the registration.
- `join-event`: a download-only join registers like any join; a re-provision of the joined event does nothing
  to uploads.
- `leave-event`: the leave cancels the app's transfers itself (disarm no longer does).
- `upload-state-reconciliation`: drops the extension-only-writer framing and the direction-gate citation.
- `device-manifest`: both processes' cycles publish the full-state snapshot; last write wins.
- `sync-status`: drops "the extension remains the sole writer".
- `harness-world-model`: the fake transport loses the live/lost sets.
- `ios-app-shell`: the composition root supplies the registration fact, not a resolved mechanism; the
  OS-driven tier's app does create.
- `diagnostic-logging`: the dump reports the registration fact and the app's admission instead of a resolved
  tier.

## Impact

- **Code:** `:domain` — `model/UploadMechanism.kt` (resolver → registration fact), `ports/BackgroundTransfer`
  (−4 members), `ports/LedgerStore` (−`demoteRequested`), `ports/TransferRecord` KDoc, `feature/upload`
  (`UploadCycle` stranded pass + capacity bound out, chunked create-until-refused in; `StrandedKeys.kt`,
  `DemoteRequested.kt` deleted; `OsDrivenRegistration` without the demote; `UploadConfig` admission;
  `UploadTransitions` new desired-state table + Stay; `BackgroundUploadPump` gated re-pump), `compose/`
  (`SnapSyncApp` wiring), `feature/membership/MembershipEntry` (gains save + start uploads), `flow/Provision`
  (`Stay` only saves). Adapters:
  `IosUrlSessionUploadPlatform` (−capacity/−live/−lost/−discard), `IosPhotoKitUploadPlatform`,
  `SimulatorUploadJobQueue`, `SqlDelightLedgerStore` + `Ledger.sq` (drop two queries), the fakes.
  Shells: `UrlSessionUploadController` (disarm, leave cancel), `SnapSyncRoot`, `UploadExtensionRoot`
  (unchanged admission). `:test:rig` (pin, `/os/photokit-ext`), `:test:world`, `:test:architecture`
  (`ProducerExclusivityTest`, `CompositionSeamTest` pinned reasons). `./gradlew architectureDiagrams` re-run.
- **Data:** no schema change and no migration. `demoteRequested` and `requestedKeys` are queries, not tables.
  Rollback is a revert; the only durable residue is the OS registration record, which a rolled-back build
  compares at its next UI launch.
- **Behavior visible to users:** on iOS ≥26.1 with full access the app now also uploads while open or woken, so
  photos can land sooner; a re-scan of the joined event no longer restarts in-flight uploads; revoking or
  narrowing access no longer cancels uploads already in flight. Changelog label `enhancement`.
- **Accepted, not built for:** the OS silently losing a registration or a transfer (never observed; a force-quit
  was measured to deliver `-999` at relaunch); two processes writing the ledger at the same moment (SQLite
  busy timeout; contention unmeasured); staged files left by a transfer that vanished with no completion.
