# Design — accept limited photo access

## Context

`permission-gate` collapses iOS `.limited → DENIED` on purpose: v1's rationale was that a partial
library cannot answer "is everything shared?", so a screen built on it would report "In sync" over a
library it cannot see. The reframe that dissolves this: **under limited, the user's selection *is* the
definition of "everything"** — "In sync" over the chosen set is true, and the selection becomes the way
a member hand-picks what they share.

The design is grounded in two days of on-device measurement (SE2, iOS 26.5) recorded in
`PROBE-FINDINGS.md` and `LIMITED-ACCESS-DESIGN.md` at the repo root of branch `partial-access`
(those two files are the extended decision record for this change; the probe commits carry the raw
logs). The measured platform facts, each load-bearing:

1. **PhotoKit narrows silently.** Under `.limited`, `PHAsset.fetchAssetsWithOptions` returns only the
   selection — no error, no signal. The enumerator is already authorization-blind, so the whole policy
   pipeline (cutoff, origin exclusions, echo suppression) works unchanged over the narrowed input.
2. **The ≥26.1 OS-driven tier never runs under `.limited`.** 22 minutes with real pending work and two
   registrations produced zero `process()` invocations; the extension fired within seconds of restoring
   full access. Registration succeeds and lies. (⏰ re-evaluate at iOS 27 GM, with the existing
   `PHBackgroundResourceUploadJobExtension` trigger.)
3. **The app-driven URLSession tier uploads fine under `.limited`** — first attempt, full cycle
   (bytes, manifest PUT 201, notify 202).
4. **Creation is unrestricted.** `PHAssetCreationRequest` and album create/placement work under
   `.limited`; created assets auto-join the selection (creation-time only — a later downgrade does not
   restore previously-imported assets). The download half needs no changes at all.
5. **Autonomous fetches storm; in-flow reads are clean.** iOS's automatic limited-access alert queues
   roughly per foreground-with-library-access; SnapSync's autonomous fetch loop (every foreground +
   reconcile + push) queued an app-killing storm that survived process death. But a single cold-launch
   read, several reads within one stable foreground, and observer-callback reads (including a coalesced
   burst) are all clean — verified repeatedly. `PHPhotoLibraryPreventAutomaticLimitedAccessAlert`
   suppresses the routine prompt but not selection-change-window prompts (Apple-engineer-confirmed
   forum behavior, reproduced).
6. **The album denylist is a no-op under `.limited`** — `PHAssetCollection.fetchAssetCollectionsWithType`
   does not surface user albums even when a selected asset is a member (verified with a real
   WhatsApp-album setup).
7. **Bulk library changes arrive non-incremental.** `changeDetailsForFetchResult` reported
   `hasIncrementalChanges=false` with empty `insertedObjects` for a 5-asset batch even against a sorted
   baseline — so a reliable consumer must reload from `fetchResultAfterChanges` and dedup, not lean on
   itemized inserts.

## Goals / Non-Goals

**Goals:**
- A member who grants limited access is a working member: they receive the event's photos, and the
  photos they select are shared to the event (post-cutoff, origin-filtered, as ever).
- "Come back anytime and select more" — selection changes propagate without reinstalling or re-joining.
- The alert storm is structurally impossible: no autonomous `PHAsset` read ever happens under `LIMITED`.
- Full-access behavior is byte-for-byte unchanged.

**Non-Goals:**
- No change to what the selection policy admits (cutoff, origin exclusions, floors all apply to picked
  photos unchanged — settled in interview 1).
- No withdrawal semantics: deselecting an already-uploaded photo does not remove it from the event
  (upload is a publish; the ledger's `COMPLETED` stays terminal).
- No attempt to make the ≥26.1 OS-driven tier work under limited (measured impossible today).
- No denylist replacement under limited (the resolution floors remain the primary received-media
  exclusion, per `photo-selection-policy` R13).
- No background-regime hardening beyond flagging it (see Open Questions).

## Decisions

### D1 — Fourth `PermissionStatus` value `LIMITED` (not a scope side-channel)

The UI must distinguish "syncing your chosen photos" from denied and from full, and the arm must select
a mechanism by it — a separate `libraryScope` flag beside a 3-value enum would put one decision in two
places. The enum's exhaustiveness makes the compiler drive most call sites; the four `!= GRANTED`
**boolean** gates (`StatusContainerHost` health ranking, the two `isGranted` lambdas in `compose/`, the
status source's `active`) compile unchanged and are audited by hand — each is an explicit task, since a
missed one silently treats `LIMITED` as denied.

### D2 — Limited reuses the URLSession *mechanism* with a selection-driven *trigger model*

Not a third upload tier. The mechanism (background `URLSession` + pump + shared `UploadCycle` + ledger)
is proven under limited (fact 3); what must change is only *when* the cycle reads the library. A new
tier would duplicate a working engine to change its ignition.

### D3 — Compose both producers on ≥26.1; the arm starts exactly one, by current permission

**The invariant is preserved; its enforcement moves.** The one-ledger-writer law's essence is
behavioral — *one writer at a time*. The migration enforced it structurally (only one producer
constructed) because the tier choice was static (OS version). Fact 2 makes the choice
**runtime-dependent** (permission), which no once-per-process structural decision can express. The only
structural-preserving alternative — recompose-per-crossing via a "reopen SnapSync" prompt — turns a
Settings-side permission flip into silent not-uploading until a manual relaunch: a worse failure mode
than the guarded behavioral invariant.

So on ≥26.1 both producers are composed; the tier-neutral `UploadArm` reads current permission at each
membership/permission transition and starts PhotoKit (full) or URLSession (limited), **stop-then-start**
(the outgoing producer's `stop()` completes first — PhotoKit's `stop()` deregisters the OS extension,
which is what actually prevents the second writer). On 18–26.0 nothing changes (URLSession only).
`NOT_DETERMINED`/`DENIED` start nothing; the first grant resolves the mechanism via the existing
permission subscription. A new `:test:architecture` guard pins "no path starts both / switch is
stop-then-start"; this design decision is the rationale record for the structural→behavioral move.

### D4 — Under `LIMITED`, reads are selection-driven: cold-launch baseline + observer, nothing else

The flows' `PHAsset`-touching calls (`pumpForeground()`, the upload half of the silent-push fan-out,
`refreshStatus()`'s gallery walk) are gated on `GRANTED`; under `LIMITED` they no-op while everything
non-`PHAsset` (HTTP reconcile, downloads/imports, ledger-count polling, attestation) keeps running.
Reads happen at exactly two moments, both in-flow (fact 5):

- **One baseline read on cold foreground launch** — opening the app is a user action; this establishes
  N and catches backlog (verified storm-free).
- **On `PhotoSelectionChangeSource` emissions** — a new port; its iOS adapter registers
  `PHPhotoLibraryChangeObserver` (registered only while `LIMITED`). This covers the in-app picker,
  Settings-side edits, and iCloud sync in one seam.

### D5 — The observer consumes the pushed result; ledger dedup is the load-bearing path

The observer callback reads `change.changeDetailsForFetchResult(held)` and enumerates
**`fetchResultAfterChanges`** — a handed-to-you result object, not a fresh scope-query — then lets the
**ledger** drop already-known assets, exactly as the existing walk does. `insertedObjects` is an
optional fast-path only: bulk changes arrive non-incremental (fact 7), so itemized inserts cannot be
relied on. This also makes debounce/self-caused-change filtering unnecessary: an app-side import shows
up in the reload and dies at the ledger check (creation itself is alert-safe, fact 4; the observer
firing on own writes was measured and is harmless — one cheap dedup pass).

### D6 — One read serves both N and the enqueue under limited

Under `LIMITED` the own-device total is derived from the same discovery the cycle enqueues from,
instead of `OwnDeviceGalleryStatusSource` running its own parallel walk. One read per event; N and the
upload set are provably consistent (the identity the policy spec already demands of the two walks).
Under `GRANTED` nothing changes.

### D7 — The app owns the picker; the automatic alert is suppressed

`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in the app's Info.plist, and the status
screen's persistent **"Choose more photos"** row (a resting affordance under `LIMITED`, never the
attention state — interview 1) drives `presentLimitedLibraryPickerFromViewController` (a PhotosUI
category; app-only by the extension-safety gate). Both halves are mandatory and measured: without the
key the alert storm is app-killing; with the key but no picker a limited user has no in-app route to
widen their selection.

### D8 — Receive-only under limited is a valid resting state

A member may allow limited access, receive everything (downloads and the event album are unrestricted —
fact 4), and never select an upload. This is specced as a legitimate steady state, not a transitional
artifact ("product call, interview 2/thread 4").

### D9 — One change, task order mirrors the rejected split

A two-change split (LIMITED+receive-only first, selection-driven upload second) was considered — and
each half *is* independently shippable given D8 — but the halves are implemented back-to-back, which
evaporates the split's ship-early/revert benefits against its costs (interim UI copy, double
ceremony). Mitigation inside the one change: the `LIMITED` state + gate audit land as the first tasks
and are device-validated before the read-path work stacks on top.

## Risks / Trade-offs

- **[Behavioral instead of structural writer-exclusion]** → the arm is the single start/stop authority
  (stop-then-start), a `:test:architecture` guard pins it, and D3 records the rationale + the
  relaunch-alternative rejection. Residual risk accepted: a guard is weaker than a compile error.
- **[A missed `!= GRANTED` gate silently treats LIMITED as denied]** → the audit is an explicit
  enumerated task (all four sites named), plus seam→UI integration tests assert limited-mode behavior
  end-to-end.
- **[The alert reframing rests on foreground evidence]** → all storm-free measurements are in-flow /
  foreground. The background regime (observer firing during a silent-push import) is unmeasured; under
  `LIMITED` the upload half of the push fan-out is gated off anyway, and imports themselves are
  alert-safe, so the exposure is the observer's *read* — kept off any background path until measured
  (Open Questions).
- **[`fetchResultAfterChanges` reload is O(selection) per change]** → selections are small by nature
  (hand-picked); the reload feeds the same ledger dedup the walk already does. Accepted.
- **[Selection changes while the app is dead]** → no observer fires; caught by the cold-launch baseline
  read on next open. Uploads under limited are therefore at-next-open latency at worst — consistent
  with the app-driven tier's existing posture on 18–26.0.
- **[Downgrade full→limited mid-membership]** → previously-imported assets drop out of the visible set
  (creation-time auto-add only, fact 4); harmless (they are echo-suppressed anyway) but surprising —
  documented in the spec rather than engineered around.
- **[Deprecated-protocol re-eval]** → the ≥26.1 "never invoked under limited" fact was measured on the
  26.1 `PHBackgroundResourceUploadExtension`; the iOS 27 GM re-evaluation (~Sept 2026) must re-test it
  against the async protocol before assuming the constraint persists.

## Migration Plan

No data migration; no backend change. Rollout is by app update: existing full-access members see no
change; existing limited-grant installs transition from the dead-end "Turn on photo access" state to a
working member on first launch after update. The probe branch's `.limited → GRANTED` adapter hack and
the probe scaffolding in `SnapSyncRoot` are **removed** by this change (the plist key, the picker
presenter, and the observer adapter graduate from the probe into production form). Rollback is an app
release re-mapping `.limited → DENIED`; no persisted state depends on `LIMITED`.

## Open Questions

- **Background-regime measurement**: does an observer-triggered read during a background wake queue
  alerts? Not blocking (no background read path exists under this design), but measure before ever
  adding one. Tracked as an implementation-phase check.
- **Forge/world coverage details**: the forge permission preset gains `LIMITED`; the world harness
  needs a fake `PhotoSelectionChangeSource` lever. Shapes are settled; exact panel affordances are
  implementer's choice.
- **iOS 27 GM re-eval** (existing trigger, extended): re-test fact 2 on the async job extension.
