## 1. Prove the defect first

- [x] 1.1 Add a `:test:integration` case to `UnreadStatusIntegrationTest`: provision under `LIMITED` with
      own assets, call `refreshStatus()` before any selection snapshot is emitted, and assert the status
      host settles to `InSync`. It must FAIL (i.e. currently settle) — this is the regression proof and the
      `bug` label's evidence. Commit it alone, marked as the expected-failure baseline.
- [x] 1.2 Add the companion case: after a snapshot IS emitted with the same assets, the total is counted
      and the screen reads `Syncing…`. Confirms the test is measuring the window and not the fixture.

## 2. The read type and the single unwrap

- [x] 2.1 Add `CandidateRead` to `:domain`'s `model/` — sealed, `Readable(candidates)` plus the
      not-determinable case named for its consequence (design D2). KDoc states the three causes it absorbs
      and the shared consequence.
- [x] 2.2 Give `EventPhotoSet` a **private** secondary constructor over in-hand candidates, delegating to
      the existing lambda constructor. KDoc: private so no consumer can bypass the policy-taking seam.
- [x] 2.3 Add the companion unwrap returning `EventPhotoSet?` — the one `when` over `CandidateRead`.
      Name it for the answer, not the mechanism.
- [x] 2.4 `commonTest` for the unwrap: readable → a set that counts; not-determinable → null. Runs on JVM
      and `iosSimulatorArm64`.
- [x] 2.5 Run `:test:architecture` and confirm `EventPhotoSetSourceTest` passes **unchanged** — no regex
      edit, no new allowlist entry. If it fails, the construction shape is wrong, not the guard.

## 3. The seam and its implementations

- [x] 3.1 Change `ports/CandidateSource.candidates` to return `CandidateRead`. Rewrite the KDoc paragraph
      that currently pushes the distinction onto callers.
- [x] 3.2 `PermissionAwareCandidateSource`: `GRANTED` → readable walk; `LIMITED` with a captured snapshot →
      readable held candidates; `LIMITED` with **no** snapshot, `DENIED`, `NOT_DETERMINED` → not
      determinable. The `orEmpty()` goes.
- [x] 3.3 `InMemoryCandidateSource` (`:adapter:generic:fake`) reports readable — it reads a cell and always
      has an answer.
- [x] 3.4 `PhotoKitCandidateSource` (`:adapter:ios:ext-safe`) reports readable; `candidatesFrom` is
      untouched (it is not the port method).
- [x] 3.5 Update `PermissionAwareCandidateSourceTest`: the "unusable grant yields nothing" case becomes
      "reports not determinable", plus a new case for `LIMITED` with a null snapshot.

## 4. The two consumers, and the deleted checks

- [x] 4.1 `OwnDeviceGalleryStatusSource.refresh`: on not-determinable, log at `Warn` and **write nothing**
      to `_size` (design D4). Update the class KDoc's "not counted is not zero" paragraph to cover the
      unreadable case.
- [x] 4.2 `ShareableCountSource.count`: delete `if (!permission.grantsPhotoAccess) return null` and the
      `permission` parameter; return `null` on not-determinable.
- [x] 4.3 `AppCore.loadShareableCount` drops the `permission` argument. `photoPermission` stays — it is the
      recompute trigger, not a gate.
- [x] 4.4 `AppCore.refreshStatusSources`: delete `if (grantsPhotoAccess)` and its comment block; keep the
      `runCatching`/`onFailure` exactly as found (see task 7.1 on ordering with the other workspace).
- [x] 4.5 Update `OwnDeviceGalleryStatusSourceTest` and `ShareableCountTest` for the new signatures and the
      not-determinable branch.
- [x] 4.6 Confirm `ShareableCountIntegrationTest` passes **with its assertions unchanged** — `DENIED` and
      `NOT_DETERMINED` still yield `null`. That is the proof the outcome is preserved.

## 5. Mechanical unwraps at the remaining call sites

- [x] 5.1 `IosDiscovery.discover` (`:adapter:ios:ext-safe`) unwraps the full-enumeration branch.
- [x] 5.2 `FakeBackgroundTransfer` (`:test:world/UploadFakes.kt`) unwraps.
- [x] 5.3 `WorldGallery.source` (`:test:world/Fakes.kt`) — the operator failure lever wraps its honest
      delegate's result.
- [x] 5.4 `GalleryReader` (`:test:rig`) unwraps; an un-answerable read is reported as such in the response
      rather than as an empty gallery.
- [x] 5.5 `WorldInspectorController` (`:app:desktop`) — both call sites (`:390`, `:467`).
- [x] 5.6 `RawAssetMappingTest` (`:adapter:generic:fake`) — two call sites.

## 6. The album lookup answers without a fetch

- [x] 6.1 The denylisted-album reader is asked only when the library can be read — `albumExclusionsWhenReadable`
      in `compose/`, used by BOTH the status total and the join preview. First attempted in the iOS shell,
      where `detektAppShell` correctly refused it (threshold 2 proves `:app:*` holds no decisions); the
      composition is the lawful home, matching the two existing `PermissionAware*` seams. Design D6.
- [x] 6.2 Correct `ShareableCountSource`'s KDoc claim that the lookup is "cached per surface upstream" — it
      is not memoized anywhere in the production wiring.
- [x] 6.3 **MEASURED** (simulator, iOS 26.4, 2026-08-28): a `PHAssetCollection` fetch under
      `NOT_DETERMINED` DOES raise the system photo-permission dialog — one `AUTHREQ_PROMPTING`,
      `preflight=no`. Pinned by an A/B differing only in direction (`UploadOnly` 1 prompt vs
      `DownloadOnly` 0), and the fix verified on the same host (0 prompts, app's own "Allow photo access"
      affordance shown instead). The earlier call-graph argument that this was unreachable was WRONG — it
      enumerated only the join preview and missed `refreshStatusSources`. Both recorded in `design.md`.

## 7. Sequencing with the adjacent workspace

- [x] 7.1 Before starting section 4, check whether the `testing-concept` / `test-coverage-bounds` workspace
      has landed its two items (the enumeration catch moving into `OwnDeviceGalleryStatusSource`; the
      `refreshStatusSources` ordering rule moving into the `Foreground` flow). Both edit
      `SnapSyncApp.kt:716–756` and `OwnDeviceGalleryStatusSource.refresh`. If they have landed, task 4.4
      reduces to deleting the `if` around a bare `gallery.refresh(...)` and 4.1's log line sits beside their
      failure log. If they have not, rebase before touching those lines and hand them the resulting shape.

## 8. Specs, gates and verification

- [x] 8.1 Verify the two delta specs against the current main specs — build each MODIFIED requirement from
      the live file and diff it, so the removed lines are only the intended ones.
- [x] 8.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 8.3 `./gradlew build` — the canonical check, no display needed.
- [x] 8.4 `./gradlew compileIosMainKotlinMetadata` — the Linux proxy for the `iosMain` source sets; roughly
      a third of the touched files never compile on JVM.
- [x] 8.5 `./gradlew architectureDiagrams` and commit if anything moved — stale `architecture/` blocks the
      PR.
- [x] 8.6 Confirm task 1.1's test now passes, and that it fails again if the `NotReadable` branch in
      `PermissionAwareCandidateSource` is reverted to `orEmpty()`.
- [ ] 8.7 Open the PR with the `bug` label. **Not done** — `/ship` is the user's call.

## 9. Loose ends recorded, not built

- [x] 9.1 Note in `design.md` (Open Questions) whether the `NotReadable` log severity should differ by
      cause — `Warn` for a withheld grant, `Error` for a snapshot that never arrived.
- [x] 9.2 Leave the `permission-gate` vs `limited-photo-access` disagreement about process survival across
      a Settings grant change recorded as a noted premise. Do not resolve it in this change.
- [x] 9.3 Report the two truncated scenarios found in `openspec/specs/limited-photo-access/spec.md` — "The
      snapshot is read at the sanctioned points only" and "No consumer branches on the grant to pick a
      source" both end mid-sentence in the main spec. They are completed in this change's delta because a
      MODIFIED requirement restates the whole block; confirm the completions read as intended before
      archiving.
