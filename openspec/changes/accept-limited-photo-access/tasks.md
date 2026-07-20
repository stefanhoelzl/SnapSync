# Tasks — accept-limited-photo-access

Task order deliberately mirrors the rejected two-change split (design D9): groups 1–3 land the
`LIMITED` state with downloads working (receive-only valid, upload simply off) and are device-validated
before the read-path/compose-both machinery (groups 4–7) stacks on top. Group 0 clears the probe
scaffolding out of the way first.

## 1. Clear the probe scaffolding

- [x] 1.1 Remove the probe hack from `PhotoLibraryPermission` (`.limited → GRANTED` + the probe log
      lines), restoring the production mapping shape (still `.limited → DENIED` until task 2.2 lands
      the real state — the hack must never survive into the real change)
- [x] 1.2 Remove the probe blocks from `SnapSyncRoot` (`runLimitedAccessProbe`, `runObserverProbe`,
      `SNAPSYNC_PICK_PHOTOS` / `SNAPSYNC_OBSERVER_SEED` env reads, `heldFetch`/`selectionChangeCount`
      state) — keep `PhotoSelectionObserver` and `presentLimitedLibraryPicker` (they graduate into the
      real seams below); keep the Info.plist key (it is part of the design, D7)
- [x] 1.3 Move `PROBE-FINDINGS.md` and `LIMITED-ACCESS-DESIGN.md` from the repo root into
      `openspec/changes/accept-limited-photo-access/` (they are this change's decision record; the
      root stays clean)

## 2. The LIMITED state (foundational — reviewed before anything stacks on it)

- [x] 2.1 Add `LIMITED` to `PermissionStatus` in `:domain` `model/` with KDoc stating the
      selection-defines-scope semantics; fix every non-exhaustive `when` the compiler surfaces
- [x] 2.2 Map `.limited → LIMITED` in `PhotoLibraryPermission` (import `PHAuthorizationStatusLimited`
      explicitly); update the adapter KDoc
- [x] 2.3 Audit gate 1 of 4 — `SnapSyncApp.kt` `isGranted` lambda (arm path, ~line 242): decided
      GRANTED-only FOR NOW, with a pointer comment — flipping it to usable-access before groups 4–6
      exist would start a producer whose start() walks the library under LIMITED (the app-driven
      tier), i.e. the alert storm. The usable-access flip is group 6's permission-aware arm
- [x] 2.4 Audit gate 2 of 4 — `SnapSyncApp.kt` Provision-flow `isGranted` (~line 377): same decision,
      same split
- [x] 2.5 Audit gate 3 of 4 — `LedgerBackedSyncStatusSource` `active` (~line 58): `GRANTED || LIMITED`
      per the sync-status delta
- [x] 2.6 Audit gate 4 of 4 — `StatusContainerHost` health reduction (~line 545): `NeedsAccess` only
      for `NOT_DETERMINED`/`DENIED`; `LIMITED` falls through to the snapshot-derived health per the
      sync-status-screen delta
- [x] 2.7 Join gate: `LIMITED` snapshot skips the explainer like `GRANTED` (`neverAsked` read,
      `StatusContainerHost` ~line 419) per the join-event delta
- [x] 2.8 `commonTest` coverage for the four gates + the explainer skip under `LIMITED` (presentation
      + status-source tests over forged permission values)

## 3. Receive-only limited works (device-validated checkpoint)

- [x] 3.1 Gate the autonomous `PHAsset` paths on `GRANTED` exactly: `pumpForeground()` invocation,
      the upload half of the silent-push fan-out, and `OwnDeviceGalleryStatusSource.refresh` /
      `refreshStatusSources()`'s gallery walk — each a no-op under `LIMITED`; everything non-`PHAsset`
      (reconcile, downloads, ledger poll, attestation) untouched
- [x] 3.2 `installPermissionSubscriptions`: fire the arm + `ensureAlbum` on any usable-access
      transition (`GRANTED` or `LIMITED`), not `GRANTED` only (album creation works under limited —
      measured, finding 4)
- [x] 3.3 `:test:integration` seam→UI test: a `LIMITED` `DownloadOnly`/`Both` membership imports
      foreign photos, populates the album, creates no upload work, shows no `NeedsAccess`
- [x] 3.4 Forge harness: permission preset gains `LIMITED`; verify the joined layer renders the normal
      health line (headless via `:test:harness-driver`)
- [x] 3.5 **Device checkpoint (SE2) — PASSED** (2026-07-20, headless; the limited grant persisted
      from the probe era so no manual grant was needed): download-only join under real `.limited` →
      joined layer reads "In sync" (never the access affordance), ZERO library walks and zero
      producer starts across three cold-launch cycles, no alert on any screenshot. Caveat: the
      import half ran against an empty union (the event's bytes were reaped on last-member leave;
      foreign content has no headless route) — import-under-limited is instead evidenced by probe
      finding 4a (creation measured unrestricted on this device) + the group-3 integration test
      over the real core. Re-verify imports on device opportunistically when foreign content next
      exists (e.g. during 9.2's two-permission flow)

## 4. The selection-change seam

- [x] 4.1 New port `ports/PhotoSelectionChangeSource` (`:domain`): a change stream the composition
      collects; KDoc records the reload-and-ledger-dedup consumption contract (design D5)
- [x] 4.2 iOS adapter in `:adapter:ios:app-only`: graduate `PhotoSelectionObserver` into the port's
      adapter — holds the baseline `PHFetchResult` (sorted), emits the pushed
      `fetchResultAfterChanges` per change, registers only while permission is `LIMITED`
      (subscribes to the permission StateFlow), retained for adapter lifetime
- [x] 4.3 Fake in `:adapter:generic:fake` (honest double) + operator lever in `:test:world`
      (`FakeHonestyTest` compliance: rigging lives in the world wrapper)
- [x] 4.4 `compose/`: collect the port under `LIMITED` → drive one discovery+enqueue pass per emission;
      cold-launch baseline read wired as the launch-time pass (design D4)

## 5. Selection-driven upload under LIMITED

- [x] 5.1 Satisfied **by construction** — no discover flag needed: `SelectionScopedTransfer` means NO
      cycle under `LIMITED` ever touches the library (discovery reads the in-memory snapshot cell), so
      continuation triggers are read-free automatically and the ledger dedups re-seen snapshot
      entries. The one read per change is the adapter's (one enumeration per emission). Existing
      tiers byte-identical via the `Unrestricted` default
- [x] 5.2 Status total under `LIMITED` derives from the same discovery pass (design D6) — no separate
      gallery walk; `GRANTED` path unchanged
- [x] 5.3 `:test:integration`: selection-change emission → N rises and the enqueued uploads complete
      through the world's app-driven tier; the app's own import (echo-suppressed) enqueues nothing
- [x] 5.4 `:test:integration`: pre-cutoff and origin-excluded selected photos are excluded — the policy
      applies unchanged to the selection

## 6. Compose-both and the arm (the law move, guarded)

- [x] 6.1 `UploadArm`: permission-aware producer selection per the upload-lifecycle delta table —
      usable-access arming, permission-selected producer, `GRANTED`↔`LIMITED` switch stop-then-start;
      `commonTest` over fake producers covering every table row
- [x] 6.2 `SnapSyncRoot` (≥26.1): compose both producers; the arm receives both + a current-permission
      read; 18–26.0 and the tier-force flag unchanged (force still never registers the extension)
- [x] 6.3 `:test:architecture` guard per the architecture-guards delta: drives the orchestrator through
      every transition row + permission flips, asserts at-most-one-started and stop-before-start
- [x] 6.4 Confirm `PhotoKitUploadProducer.stop()` deregisters the extension on the `GRANTED → LIMITED`
      switch (existing disable→enable toggle semantics; assert in the arm tests via the fake)

## 7. UX

- [x] 7.1 "Choose more photos" affordance: `UiState`/screen support per the sync-status-screen delta —
      persistent under `LIMITED`, outside the status-line slot, absent otherwise; wired to a new
      `UserCommands` entry → `presentLimitedLibraryPicker` (built in `compose/`, shell-injected)
- [x] 7.2 Explainer copy per the join-event delta (all-photos vs pick-specific-photos as a real choice);
      `AppExplainer` copy update + screen test
- [x] 7.3 Forge preset review of the new affordance (headless screenshots via `:test:harness-driver`)

## 8. Specs, diagrams, docs

- [ ] 8.1 Sync the delta specs into `openspec/specs/` (including the permission-gate Purpose rewrite —
      the "selection defines scope" framing replaces the "full or nothing" rationale prose)
- [ ] 8.2 `./gradlew architectureDiagrams` + commit (the new port changes the ports inventory);
      LawsDigestTest/laws digest untouched unless the guard list line needs the new guard named
- [ ] 8.3 Update root `CLAUDE.md`: the `.limited → DENIED` prose is stale after this change; note the
      LIMITED state, the observer seam, and the alert-storm rule (no autonomous reads under limited)
- [ ] 8.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green + `./gradlew build`
      green

## 9. Device validation (the full feature, on the SE2)

- [ ] 9.1 Full-access regression: existing behavior unchanged (join, upload, download on the PhotoKit
      tier)
- [ ] 9.2 Limited upload end-to-end: grant limited, select photos, verify upload via the app-driven
      tier (bunny zone check), N honest, no alert storm across foregrounds and selection changes
- [ ] 9.3 Permission flips both directions on ≥26.1: `GRANTED → LIMITED` (extension deregisters,
      app-driven takes over) and back; no double-writer symptoms in either log
- [ ] 9.4 Implementation-phase check from the design's open questions: observer emission during a
      background wake — measure whether any background read path exists and, if one is ever added,
      that it does not queue alerts (design says none exists; verify)
- [ ] 9.5 Device cleanup: delete probe albums ("SnapSync limited probe" ×N, WhatsApp test album) and
      the ~18 synthetic seed assets; reinstall a clean build
