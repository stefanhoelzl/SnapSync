# Accept limited (partial) photo access

## Why

Today a guest who grants SnapSync **limited** photo access (iOS `.limited`) is treated as having
granted nothing: `permission-gate` collapses `.limited → DENIED` and the app sits on the "Turn on
photo access" affordance forever. For a guest at a stranger's event, hand-picking the photos to share
is the *more* natural grant, not a failure mode — and the original objection ("a partial library
cannot answer *is everything shared?*") dissolves under the reframe that **the selection defines the
scope**: "In sync" over the chosen set is true.

Two days of on-device probes (SE2, iOS 26.5 — `PROBE-FINDINGS.md`, `LIMITED-ACCESS-DESIGN.md` on this
branch) established the platform facts that shape the design: the ≥26.1 OS-driven PhotoKit upload tier
is **never invoked** under `.limited` (registration succeeds and lies); the app-driven URLSession tier
**uploads fine** under `.limited`; asset/album **creation is unrestricted** (downloads work today);
autonomous `PHAsset` fetches across repeated foregrounds **storm** iOS's limited-access alert into an
app-killing queue, while **in-flow** (observer/cold-launch-triggered) reads are clean; and the album
denylist is a **silent no-op** under `.limited`.

## What Changes

- `PermissionStatus` gains a fourth value **`LIMITED`**; the iOS adapter maps `.limited → LIMITED`
  (today `DENIED`). Every `!= GRANTED` boolean gate is audited so `LIMITED` lands on the granted side
  where that is the honest reading.
- **Receive-only under limited is a valid resting state**: a member may allow limited access, receive
  the event's photos (imports and the event album work unrestricted), and never select anything to
  upload.
- **Uploads under limited use the app-driven URLSession mechanism on every OS version** — on iOS ≥26.1
  the OS-driven PhotoKit tier is not invoked under `.limited` (measured), so **both** producers are
  composed there and the tier-neutral `UploadArm` starts exactly one by *current* permission
  (full → PhotoKit, limited → URLSession). **BREAKING (internal invariant):** the two tiers' mutual
  exclusion moves from structural (only one producer constructed) to behavioral (exactly one
  *started*), enforced by a new `:test:architecture` guard.
- **Library reads become selection-driven under limited**: the autonomous read triggers (foreground
  pump, provision/status refresh, silent-push upload receiver) skip their `PHAsset`-fetching parts
  under `LIMITED`. Reads happen only on a cold-launch baseline and on `PHPhotoLibraryChangeObserver`
  fires (a new `PhotoSelectionChangeSource` port), reading the pushed `fetchResultAfterChanges` and
  letting the ledger dedup. All non-`PHAsset` work (HTTP reconcile, downloads, ledger polling,
  attestation) keeps running.
- The status screen gains a **"Choose more photos"** row under `LIMITED`, driving the system
  limited-library picker (`presentLimitedLibraryPicker`); the automatic limited-access alert is
  suppressed via `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` (both halves are mandatory —
  measured: suppression without the picker strands the user; no suppression is an app-killing alert
  storm).
- The own-device status total `N` under limited is derived from the upload cycle's discovery (one read
  serves both the total and the enqueue), and "In sync" over the selected set is the honest resting
  state.
- The **album denylist is documented inert** under `.limited` (album structure is unreadable; the
  resolution floors — which do work — remain the primary received-media exclusion, as the policy spec
  already states).
- First-join explainer copy presents limited as a first-class choice ("allow all photos, or pick them
  yourself").

## Capabilities

### New Capabilities

- `limited-photo-access`: how the app behaves under a partial (`.limited`) photo grant — the
  selection-defines-scope contract, the read discipline (no autonomous `PHAsset` reads; cold-launch
  baseline + selection-change-observer reads only), the `PhotoSelectionChangeSource` port, app
  ownership of the limited-library picker (alert suppression + the picker affordance), and
  receive-only as a valid resting state.

### Modified Capabilities

- `permission-gate`: `PermissionStatus` gains `LIMITED`; the iOS mapping `.limited → LIMITED`; the
  Purpose's "full grant or nothing" rationale is rewritten (selection defines scope). The
  first-join explainer copy requirement moves to present limited as first-class.
- `upload-lifecycle`: on ≥26.1 both producers are composed; the `UploadArm` becomes permission-aware
  and starts exactly one producer by current permission (stop-then-start, atomic); the
  exactly-one-started invariant replaces structural exclusivity.
- `ios-url-session-upload`: the app-driven tier also serves `LIMITED` memberships on ≥26.1; under
  limited its cycle is triggered by selection changes/cold launch (not foreground/push), sourcing
  discovery from the observer's pushed fetch result with ledger dedup.
- `ios-photokit-upload`: records the measured platform constraint — the extension is never invoked
  while the app holds `.limited` (the forcing proof for the tier fallback) — and that the arm stops
  (deregisters) it under limited.
- `photo-selection-policy`: the album-denylist requirement documents inertness under `.limited`; the
  own-device total under limited is sourced from the cycle's discovery.
- `sync-status`: the status projection treats `LIMITED` as active (syncing) rather than the
  permission-attention state.
- `sync-status-screen`: `LIMITED` renders the normal joined layer plus the persistent
  "Choose more photos" row (a resting affordance, never the attention state); `NeedsAccess` no longer
  covers limited.
- `join-event`: the photo-access explainer presents limited as a first-class choice, and a `LIMITED`
  snapshot skips the explainer like `GRANTED` (the grant exists; there is no dialog to explain).
- `architecture-guards`: a new guard — no code path starts both upload producers; the switch stops the
  outgoing producer before starting the incoming one.
- `desktop-test-harness`: the permission preset group gains `LIMITED` (the partial-grant joined layer
  is reviewable offscreen).
- `full-stack-harness`: the inspector's permission segment becomes 4-state.

## Impact

- `:domain` `model/` (`PermissionStatus`), `ports/` (new `PhotoSelectionChangeSource`), `feature/upload`
  (`UploadArm` permission-awareness), `feature/status` (limited-mode total), `flow/` (read-gating under
  `LIMITED`), `compose/` (compose-both wiring, observer subscription).
- `:adapter:ios:app-only`: `PhotoLibraryPermission` mapping, `PhotoSelectionObserver` (new),
  `presentLimitedLibraryPicker` (new).
- `:app:ios` shell wiring + `iosApp/iosApp/Info.plist`
  (`PHPhotoLibraryPreventAutomaticLimitedAccessAlert`).
- `:ui:presentation` / `:ui:screens` / `:ui:components`: `LIMITED` rendering + the picker row +
  explainer copy; forge harness permission preset gains `LIMITED`.
- `:test:architecture` (new guard), `:test:world` + `:test:integration` (fake selection-change source;
  seam→UI tests for limited scenarios).
- No backend, no data-model/persistence, no protocol changes. The probe branch's throwaway
  `.limited → GRANTED` hack is replaced by the real `LIMITED` state.
