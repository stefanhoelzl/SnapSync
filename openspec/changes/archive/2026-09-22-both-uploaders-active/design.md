## Context

Phase 5 of the seven-phase upload-path rework (phases 0–4 and 6 shipped; phase 4 is
`changes/archive/2026-09-21-retire-the-upload-arm`). The phase was handed over as "repair is one rule":
collapse the three repairs of "a `REQUESTED` row whose transfer is gone" to the start-time one, and drop the
capacity guess. Verifying that against the tree produced two contradiction reports to the design session:

1. **A re-provision of the joined event forces the ritual without a ledger reset.** `Provision.run` takes
   `SwitchDecision.Stay` (no `MembershipEntry`, no `resetTo`) and still calls `reconcileUploads()` →
   `UploadTransitions.onJoin()` → `reconcile(forced = true)` → `register()`. The disable wipes the extension's
   live OS jobs; only the ritual's demote hands their rows back.
2. **`demoteRequested` cannot be dropped under the one-writer premise.** Every covering for the two remaining
   orphaning cases is the demote under another name:
   - *(a)* a download-only membership is deregistered at the **next launch or permission change** (not at the
     reconfigure — `ReconfigureEvent` calls nothing when upload is turned off); the wiped jobs' rows stay
     `REQUESTED`; reconfiguring back re-registers through the ritual, whose demote is the only repair;
   - *(b)* `LIMITED → GRANTED` with a surviving registration: no ritual runs, the app is disarmed
     (`cancelAll()`), and a `-999` that never lands leaves a row `REQUESTED` under PhotoKit, where the start
     rule is inert (`liveKeys() = null`).

The user resolved both by removing the premise rather than adding a rule. Current tree, as it bears on this:

- **Admission** (`UploadConfig.kt`): `appAdmission(resolveUploadMechanism(...))` admits only when the resolved
  kind is `URL_SESSION`, else `NotResolved` (touch nothing); `extensionAdmission(permission)` admits under
  `GRANTED`, else `Withheld` (acknowledge + record, create nothing, publish nothing).
- **Download-only is already the policy.** `selectionRulesFor(includesUpload = false)` yields `[DenyAll]`;
  the cycle checks `policy.contributes` (`UploadCycle.kt:200-218`), settles, returns `Declined` → `SKIPPED`
  (the pump never re-arms on `SKIPPED`) and publishes the empty manifest. No `Contribution` type remains. The
  remaining separate direction reads are `UploadTransitions.membershipIncludesUpload` and the join preview.
- **Repairs:** `strandedEachCycle` / `strandedAtStart` (`StrandedKeys.kt`, called from
  `UploadCycle.reconcileStranded`, armed by `signalRestart()` in `UrlSessionUploadController.arm()`), and
  `demoteRequestedOffMain({ ledgerStore.demoteRequested() })` inside `OsDrivenRegistration.register()`.
- **Completions:** `IosUrlSessionUploadPlatform.recordTerminal` → guarded `markTerminal` (`FAILED` maps to
  `DISCOVERED`) → delete staged file → `onTerminal()` → `pump.onUploadCompleted()`, unconditionally.
- **Capacity:** `UploadCycle.enqueue` bounds its resolve by `platform.remainingCapacity() ?: 16`; the URLSession
  adapter answers `cap(4) − live`, PhotoKit and the simulator queue answer `null`.
- **Rig:** `/device/upload-mechanism` pins `resolveUploadMechanism`'s override (app memory only) and triggers
  `onOverrideChanged()`; `/os/photokit-ext` refuses unless the resolved kind is `PHOTOKIT`, "or it would create a
  second `LedgerWriter`".

## Goals / Non-Goals

**Goals:**
- Delete every repair of `REQUESTED` rows by making nothing orphan them.
- Both uploaders active; the extension creates only under `GRANTED`, the app under any usable grant.
- Registration spans the membership (join → leave), never touched by reconfigure or permission changes.
- A re-provision of the joined event does nothing to uploads.
- Creation bounded only by the platform's own refusal.
- Restate the ledger's writer rule as code ownership + guarded one-transaction writes.

**Non-Goals:**
- Recovering a transfer the OS loses silently (never observed), or cleaning its staged file.
- Measuring or tuning cross-process SQLite contention.
- Deciding whether the rest of a `Stay` re-provision (save, refresh, album, downloads) should also be skipped —
  only its upload registration is decided.
- `FileLogWriter`'s silent give-up; the post-switch app-driven hang phase 1 recorded; a walk-less top-up.
- Multi-event membership, Android.

## Decisions

### D1 — The ledger's writer rule is code ownership, not process exclusivity

Every ledger write is either the running cycle's `LedgerWriter` record family, a transport's guarded
`markTerminal`, or a named reset-family use case (`ShareSetLoad`'s `resetTo`, `LeaveEvent`'s clear,
`ResetDeviceState`). Each is one transaction with its guard in the statement. That is the invariant: which
**code** may perform which write, and that each write is safe against any other landing between its read and its
write. How many **processes** hold a `LedgerWriter` at once is not an invariant — on iOS ≥26.1 under a full grant
both do. `module-architecture` "Rules in features, order in flows" ("exactly one writer feature per durable
port") and `sync-ledger` "exactly one process records" are reworded accordingly.

*Alternative rejected:* an owner column on each row plus claim-first creation. It would make overlap impossible
rather than harmless, at the cost of a migration, an ownership hand-off at every transition (the exact thing that
orphaned rows), and a row that can be claimed by a process that then dies. Overlap is a duplicate PUT to the same
object; a lost claim is a photo that never uploads.

### D2 — Write-after-act is what makes overlap safe

A cycle selects only `DISCOVERED` rows and records `REQUESTED` only after `createJob` answered `CREATED`
(`sync-engine`, unchanged). So: a `REQUESTED` row always had a real job; two cycles can at worst each create a job
for the same `DISCOVERED` key before either records, which is a duplicate upload of identical bytes to the
destination `(deviceId, assetId, role)`; each completion goes through the guarded `markTerminal`, which applies to
a `REQUESTED` row and to nothing else, so the second completion of a pair is a logged no-op and the row
converges. Concurrent cycles are rare by construction: the extension runs when the OS launches it, the app on its
own triggers.

### D3 — Admission per process

| process | admits (creates) | otherwise |
|---|---|---|
| extension | `GRANTED` | `Withheld`: acknowledge + record presented jobs, create nothing, publish nothing |
| app | `GRANTED` or `LIMITED` (and not pinned off — D8) | `Withheld`, same narrow settle |

`UploadAdmission.NotResolved` is deleted; the app's "no usable access" case becomes `Withheld`. Admission is still
decided before the policy is built, so a `NOT_DETERMINED` grant never reaches the album reader that raises the
permission dialog. The narrow settle is safe for the app too: it creates nothing and runs no stranded pass (there
is none left). `appAdmission` takes the permission (and the pin), not a resolved kind.

### D4 — The resolver becomes one fact: may the extension be registered

`resolveUploadMechanism` / `UploadMechanism` answered "which single uploader runs". What survives is
`extensionRegistrable(osSupportsOsDrivenUpload, permission) = osSupports && permission == GRANTED` — total, pure,
never true below 26.1 (the selector does not exist there and would trap), and the only thing the transitions and
the diagnostic dump need. The "presence ≠ runnability" and "transport binding is not an input" rules of
`upload-lifecycle` carry over to it. `UploadMechanism.IDLE`'s job ("an OS trigger still gets answered") is
already done by every trigger being a `suspend` function under an `OsReceipt`; nothing routes to "no mechanism"
any more.

### D5 — Transitions: registration spans the membership

`UploadTransitions` keeps its shape (stateless, one tested place) with a new desired-state table. Inputs:
`joined(): Boolean` (three-valued posture collapses — direction is no longer an input), `permission()`,
`extensionRegistrable()`, the optional `ExtensionRegistration`, the app engine.

| transition | registration | app engine |
|---|---|---|
| join (first or switch, after the share-set load) | **forced** disable → enable where registrable; nothing otherwise | arm if access usable |
| re-provision of the joined event (`Stay`) | **nothing** | nothing |
| reconfigure (any direction) | nothing | arm if access usable (a kick; the policy decides) |
| permission change, launch | register if registrable **and** the OS reads `false` under `GRANTED`; never deregister | arm if access usable, else disarm |
| leave (incl. a switch's leave) | deregister | disarm **and cancel transfers** |

Why each cell:

- **Download-only is registered.** The extension's cycle declines on `policy.contributes` like the app's, so a
  registration costs an OS launch that settles and returns `SKIPPED`. Deregistering it is what orphaned rows in
  case *(a)*; keeping it means a later reconfigure to upload needs no ritual.
- **Never deregister on a permission change.** Under `LIMITED` every registration write is refused (`3311`) and
  the surviving registration is invoked by the OS anyway (measured SE2/26.6, 2026-09-21); its cycle withholds and
  records. Under `NOT_DETERMINED`/`DENIED` the extension withholds too. Nothing is gained by deregistering, and a
  deregistration wipes jobs.
- **Register on upgrade or launch.** A registration is wanted wherever registrable; a record that reads absent
  under `GRANTED` is registered (through the toggle, since a bare enable over a stale record fails `3202`). A
  compared register runs only when the OS reads **no** record, so there are no jobs to wipe.
- **`Stay` does nothing.** A re-scan changes nothing about the membership, the share-set load is already skipped
  there, and the stale-record repair does not need it: a reinstall wipes the config (the App Group goes with the
  app), so a reinstalled device always arrives as a real join. The join transition moves into
  `MembershipEntry` (feature/membership), whose order becomes leave-previous → load → **save** → **start uploads**;
  `Provision`'s `when` over the switch decision runs that entry on `Enter` and only `saveConfig` on `Stay`. One call
  per branch, so the flow stays inside the transcriber's closed grammar (it admits no local `val`, which is why the
  first attempt — passing the decision into a later `reconcileUploads(decision)` — was rejected by the
  generator) and inside its ceilings; the ordering rule lives in the feature, tested in `MembershipEntryTest`.
- **A switch.** `MembershipEntry` still calls the leave verb first (deregister + cancel + disarm), then the load
  replaces the ledger, then the join registers. The join's toggle on a switch therefore meets no live job of the
  new membership. This is the one place the design still cancels on a membership change, because a switch *is* a
  leave; the rows are replaced anyway, so nothing is orphaned.

### D6 — Disarm stops the heartbeat; only leave cancels

`AppUploadEngine.disarm()` = `scheduler.cancel()`. A new `AppUploadEngine.cancelTransfers()` (the old
`cancelAll`, which also deletes staged files) is called only from the leave. Revocation therefore stops new
creation (admission) and new wakes (heartbeat), and lets in-flight transfers finish and record — the user's
"let them finish". Rows those completions touch are already the current membership's.

### D7 — A late completion records always, re-pumps only when the app may create

`recordTerminal` is unchanged (guarded write, delete staged file). The `onTerminal` hook stops being an
unconditional `pump.onUploadCompleted()`: the decision moves into tested `:domain` code — the pump's
`onUploadCompleted` takes the app's current admission and drives a cycle only on `Admit`. This fixes the
2026-09-16 observation (late `-999`s after a hand-off kept driving app cycles) and keeps the shell wiring-only.

### D8 — The rig pin becomes a per-uploader switch

The override no longer has a resolver to pin. `/device/upload-mechanism` becomes
`/device/uploaders?app=on|off&extension=on|off` (rig builds only, app memory only):

- `app=off` → the app's admission answers `Withheld` (it records, never creates);
- `extension=off` → `extensionRegistrable()` answers `false` and the transitions deregister now (the extension
  cannot read app memory, so off must be a deregistration; accepted: that wipes its in-flight jobs, which is the
  test's intent and a rig-only path);
- the command triggers `onOverrideChanged()` as today.

`/os/photokit-ext` drops "refused unless photokit": running the extension's cycle in the app process is now just
another cycle over the same ledger, gated by `extensionAdmission` like the real one. Both are `:test:rig`, non-gating
dev infra with no spec; `upload-lifecycle`'s override requirement is rewritten to the switch.

### D9 — Half B: create until refused

`enqueue` reads the whole backlog (unchanged — bounding the read starves), admits it through the policy
(unchanged), then walks the admitted rows in chunks of a small constant (`resolveChunk = 4`): resolve the chunk
(`library.resourcesFor`), create each resolved row's job, stop the whole pass at the first `LIMIT_EXCEEDED`.
`truncated` becomes "the platform refused" (plus the settle's `capHit`), replacing
`rows.size < eligible.size`. `BackgroundTransfer.remainingCapacity` and `enqueueBatchSize` are deleted.

The chunk bounds wasted resolves, not creation: at most `resolveChunk − 1` resolved keys are thrown away when
the refusal lands mid-chunk (≈33 ms at the measured ~11 ms/key), against the 54 ms measured for 16 keys against a
cap of 4 that motivated `remainingCapacity`. A chunk is a granularity, not a guess at a cap: both transports refuse
honestly (PhotoKit's `jobLimit`; the URLSession adapter's `createJob` counts the session's live tasks, so its cap
binds across a relaunch). A time budget was considered and declined by the user.

### D10 — What the cycle keeps and loses

- Loses `reconcileStranded`, `strandedCandidates`, `signalRestart`, `restartSignalled`; `recreateRetrySpent`
  keeps its retry re-creation.
- Keeps the `policy.contributes` decline exactly: no walk (so a deny-all policy can never count as an
  authoritative walk and delete rows), a settle, the empty manifest, `SKIPPED` (so the pump never re-arms a
  download-only device).
- `BackgroundTransfer` loses `remainingCapacity`, `liveKeys`, `lostKeys`, `discard`; `LedgerStore` loses
  `demoteRequested` and `requestedKeys` (its only reader was the stranded pass); `Ledger.sq` drops both queries.

### D11 — `ProducerExclusivityTest` follows the new risk

Re-pointed, not retired. It drives the real admission functions, `extensionRegistrable` and `UploadTransitions`
over a fake registration and fake app engine through join / re-provision / reconfigure / permission / launch /
leave / pin scripts, asserting: never registered below 26.1; no registration write under a non-`GRANTED` grant;
**no transition deregisters except a leave or an `extension=off` pin**; **no transition cancels transfers except a
leave**; `Stay` performs no registration call. The "no duplicate job starts in a normal sequence" property is a
cycle property and is asserted in `UploadCycleTest` (two cycles over one ledger, sequential: the second creates
nothing for a key the first recorded `REQUESTED`) rather than in the architecture guard.

## Risks / Trade-offs

- **Overlap duplicate uploads** on ≥26.1 full grant when both cycles run at once, and on `GRANTED → LIMITED`
  (the surviving extension's jobs keep running while the app creates for other rows only — `REQUESTED` rows are
  never re-picked, so the old "app demotes and re-uploads the extension's rows" duplicate is actually gone).
  → Accepted: same object, idempotent PUT; bounded by what both cycles pick in the same window.
- **The extension asks to be re-invoked for the app's work.** `requeueWhilePending` counts every non-settled
  row, including rows the app requested. → Accepted: the OS throttles re-invocation, the app's rows settle within
  its transfers' lifetime, and a re-invoked extension that finds nothing admitted returns quickly.
- **Two writers of `device.json` and its skip marker.** Both cycles project the same ledger through the same
  policy; the PUT is a full-state snapshot, last write wins. A crossed pair (A PUTs S1, B PUTs S2, A's PUT lands
  last, B's marker lands last) can leave the server one snapshot behind with the marker claiming current, until the
  next ledger change re-PUTs. → Accepted as bounded and self-healing; stated in `device-manifest`.
- **Lost transfers are never recovered.** A URLSession task the OS drops with no completion, or a PhotoKit job
  the OS loses, leaves its row `REQUESTED` forever (the photo never uploads) — the stranded pass was the only
  recovery. → Accepted by the user: never observed; a force-quit was measured to deliver `-999` at relaunch, which
  the guarded write maps to `DISCOVERED`. If it is ever observed, the recovery is a start-time rule on the app
  transport — the one that exists today.
- **Cross-process write contention.** Two processes may write in the same instant. → SQLite's busy timeout;
  contention unmeasured, accepted.
- **Staged files left by a vanished transfer** stay in the App Group until the leave's cancel (which deletes
  staged files of live tasks only). → Accepted; bounded by the transfers that vanished.
- **A download-only member's extension launches** (it stays registered). → Each launch settles and returns
  `SKIPPED`; the OS invokes on library changes, so the cost is bounded by photo-taking.
- **In-flight extension jobs across `GRANTED → LIMITED`** (measured SE2/26.6, 2026-09-22): they survive and
  settle when access returns — none was presented during the `.limited` interval, all were presented as
  succeeded to the first invocation after the regrant. While access stays `.limited` their rows stay
  `REQUESTED`, the same exposure as "the OS loses a job", accepted above.

## Migration Plan

One PR (the user chose to include half B), commits split so each half reverts alone:
1. Half B — create until refused (cycle, port, adapters, fakes, specs' capacity requirement).
2. The redesign — admission, transitions + `Stay`, disarm/cancel split, gated re-pump, deletions, rig, guard.

No schema change and no data migration: two queries are removed from `Ledger.sq`, not a table or column.
Rollback is a revert. The only durable residue is the OS registration record: a rolled-back build compares it at
its next UI launch and deregisters it where its old resolver says so. `REQUESTED` rows left by the new build are
repaired by the rolled-back build's own stranded/demote rules.

Device verification before merge (SE2, `rig-channel`): on ≥26.1 full grant, a new photo is uploaded by either
process with one `COMPLETED` row; a re-scan of the joined event leaves registration and jobs intact; a
`GRANTED → LIMITED → GRANTED` round trip cancels nothing and ends with every row settled.

## Device verification (SE2, iOS 26.6, 2026-09-22, rig build of this branch)

- **Both uploaders, full grant.** A fresh upload-only event: the join logged `photokit.register` then
  `url-session.arm`; the app's cycle created both admitted photos' jobs, both completed through its delegate, and
  the event read `InSync`. A **real overlap** happened: the OS launched the extension at the same second the app's
  first cycle ran; it found both keys already `REQUESTED` and created nothing — no duplicate.
- **Re-scan of the joined event.** The link gate absorbs a repeated link before any provision runs; no
  registration call was logged and the record stayed registered.
- **Download-only.** The reconfigure touched no registration; a forced extension cycle declined on the policy,
  published the empty manifest (200) and returned `SKIPPED`.
- **`GRANTED → LIMITED → GRANTED`.** With the app switched off and four extension jobs in flight: the `LIMITED`
  relaunch made no registration write and cancelled nothing; the app's cycle left the `REQUESTED` rows alone; a
  withheld extension cycle was presented none; after the regrant (no registration write — the record read
  present) the first extension invocation settled all four `COMPLETED`.

## Open Questions

- ~~Does the OS complete and report a surviving registration's in-flight jobs under `.limited`?~~ **Answered on
  device (2026-09-22):** they survive the round trip and settle at the first invocation after the regrant; none
  is presented while `.limited`. Recorded in `ios-photokit-upload` "The registration cannot be changed under a
  partial grant".
- **`resolveChunk` value.** 4 matches the URLSession cap; measure the PhotoKit per-chunk overhead on device and
  adjust if a larger chunk is materially cheaper there.
- **Does a device reset deregister?** `ResetDeviceState` clears the config but not the registration; under D5
  registration spans the membership, so arguably a reset is a leave. Not decided here; the extension's gate sees no
  membership after a reset and declines (`NotJoined`), so the residue is an inert record.

## Archive gates (2026-09-22)

1. **Placeholder Purpose** — none in the tree. The Purposes this change made false were rewritten at archive:
   `upload-lifecycle`, `sync-ledger`, `ios-photokit-upload`, `ios-url-session-upload`, `device-manifest`,
   `limited-photo-access` (task 9.1).
2. **Delta completeness** — modules the diff touched, and the capability that accounts for each:
   `:adapter:generic:app` (`SqlDelightLedgerStore`, `Ledger.sq`) → `sync-ledger`; `:adapter:generic:fake` →
   `sync-ledger`, `diagnostic-logging`; `:adapter:ios:app-only` → `ios-url-session-upload`; `:adapter:ios:ext-safe`
   → `ios-photokit-upload`; `:app:ios` → `ios-app-shell`, `ios-url-session-upload`; `:domain:compose` →
   `upload-lifecycle`, `diagnostic-logging`; `:domain:feature` → `upload-lifecycle`, `join-event`,
   `reconfigure-membership`, `diagnostic-logging`; `:domain:flow` → `join-event`; `:domain:model` →
   `upload-lifecycle`, `diagnostic-logging`; `:domain:ports` → `sync-ledger`, `ios-url-session-upload`;
   `:test:architecture` → `architecture-guards`; `:test:world` → `harness-world-model`. No delta needed:
   `:test:integration` (tests only — behavior is specified by the capabilities above) and `:test:rig`
   (non-gating dev infrastructure with no spec, per `CLAUDE.md`; its switch is specified in `upload-lifecycle`).
3. **Dead types** — removed and absent elsewhere: `UploadMechanism` (named in `upload-lifecycle` only in the
   sentence recording its replacement — accounted), `UploadAdmission.NotResolved`, `UploadMechanismPin`,
   `Desired`/`Registration` (private to the old `UploadTransitions`; the spec hits for "Registration" are the
   generic word), and test-only types. No spec names a removed type as live.
