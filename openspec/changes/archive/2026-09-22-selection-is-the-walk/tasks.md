## 1. An unread selection is its own scope (D1)

- [x] 1.1 `model/SelectionScope.kt`: add `SelectionScope.Unread`; `selectionScope(LIMITED, null)` → `Unread`,
      `selectionScope(LIMITED, list)` → `Scoped(list)`; rewrite the KDoc (the `Scoped(emptyList())` "honest
      gap" reading is gone). Update every exhaustive `when` over the scope.
- [x] 1.2 `feature/upload/UploadConfig.kt`: `appAdmission` withholds under `LIMITED` while the scope is
      `Unread`. Give it the input from the same cell (`compose/SnapSyncApp.appUploadAdmission` passes the
      scope, or whether the snapshot has been read); `appMayCreate` follows automatically.
- [x] 1.3 `SelectionScopedDiscovery`: `Scoped` → `fullEnumeration = true`; `Unread` → throw from both
      `discover` and `resourcesFor` (never an empty answer). Rewrite its KDoc.
- [x] 1.4 `compose/PermissionAwareCandidateSource` KDoc: remove the false "a scoped discovery … deletes nothing
      … its empty answer is retryable" paragraph; state that both sides now read the one cell and both
      distinguish unread from empty.
- [x] 1.5 Tests (commonTest): `CycleGateTest` (`LIMITED` + unread → `Withheld`; `LIMITED` + read, even empty →
      `Admit`); `SelectionScopedDiscoveryTest` (read snapshot is authoritative; `Unread` throws on both calls);
      an `UploadCycleTest` case pinning today's bug — `LIMITED`, unread, admitted `DISCOVERED` rows → nothing
      deleted.

## 2. Authoritative walks delete in-flight rows (D2)

- [x] 2.1 `UploadCycle.departedKeys`: drop the `state != REQUESTED` filter; rewrite its KDoc and the
      `decide` comment that says a selection snapshot is not the library.
- [x] 2.2 Tests: an authoritative walk deletes an absent asset's `REQUESTED` row; a read selection snapshot
      deletes a de-selected photo's `COMPLETED` and `REQUESTED` rows; the published manifest omits both; a
      later `markTerminal` for the deleted key answers `false` and creates nothing; re-selecting records the
      photo `DISCOVERED` again.

## 3. A job whose row is gone is answered and nothing more (D3)

- [x] 3.1 `UploadCycle`: skip a row-less job before `adjudicateFailure` in `acknowledgePresented`,
      `recreateRetrySpent`, and the `fetchRetryJobs` loop (no engine event, no `retryJob`, no `createJob`).
      *Deviation:* no done-row skip was added to the `fetchRetryJobs` loop. A `.retry` job the cycle declines
      goes un-acknowledged (50008), and only a missing row is answered by the adapter; a done row's retry keeps
      today's behavior (the guarded writes decline, and one idempotent re-`PUT` results).
- [x] 3.2 `TransferRecord`: add a key read (a read, not a record operation) so the PhotoKit adapter can confirm
      a v1-fallback key's row. Done by moving `LedgerStore.get(key)` up to `TransferRecord` unchanged — every
      implementer already had it, and `LedgerStoreContract` already covers it. The `sync-ledger` delta gained
      "Reader and writer capability split" for it (and for the settle as a second `markTerminal` caller).
- [x] 3.3 `IosPhotoKitUploadPlatform` / `PhotoKitJobMapping`: a recognised destination with no row is
      **pruned** — acknowledge it in place in both `drainTerminals` and `fetch(.retry)`, write nothing, do not
      emit it, and log it at `Info`. Keep `Error` only for `AcknowledgeToDrain` (unmappable). Emit a
      retry-spent failure for re-creation only when its row exists. Update the `reportUnrecoverable` KDoc.
      Put the decision in the pure mapping file so it is testable.
- [x] 3.4 `IosUrlSessionUploadPlatform.recordTerminal`: the "applied to NO row" line → `Info`, reworded as a
      pruned row.
- [x] 3.5 Tests: `UploadCycleTest` — a withheld settle, a retry-spent re-create, and a first-failure retry for
      a deleted key each write no row and create no job; a present `REQUESTED` row still retries.
      Mapping-level test for the pruned classification.

## 4. The foreground settles from the per-device listing (D4)

- [x] 4.1 `feature/upload`: the new settle use-case (working name `StoredUploadSettle`) over `LedgerStore`,
      `DeviceFilesSource` and a device-id thunk: `pendingResources()` → nothing pending means no request →
      `list(deviceId)` with a 15 s timeout → `markTerminal(key, COMPLETED)` for each pending listed key.
      Failures logged as `ShareSetLoad` does (`Warn`; `Error` for `DeviceListingShapeException`). Never throws.
- [x] 4.2 `compose/`: build it (`ForegroundUploadsComposition.kt`, a top-level factory — `AppCore` sits at its
      `LargeClass` ceiling) and hand `flow/Foreground` the effect, in the app composition only.
- [x] 4.3 `flow/Foreground`: one more launch beside the pump, not behind it. The pump and the settle arrive in
      one `ForegroundUploads` bundle — the constructor sat at the flow tier's ceiling of 10, and the user chose
      bundling over raising it. Fix the flow KDoc's list of steps.
- [x] 4.4 Tests (commonTest, fakes): a `REQUESTED` listed key → `COMPLETED`; `DISCOVERED` listed → unchanged;
      `REQUESTED` unlisted → unchanged; no pending rows → no request; failure/timeout → nothing changes; a
      later `markTerminal(COMPLETED)` answers `false`.
- [x] 4.5 `:test:integration` over `:test:world`: the measured downgrade — jobs in flight under `GRANTED`, the
      grant narrows to `LIMITED` with half of them selected, the backend receives all bytes, the extension is
      withheld. After foreground: the selected rows are `COMPLETED`, the de-selected rows are gone, the
      manifest lists only the selected photos, and the UiState reads in sync.

## 5. Rider — batch size 1 (D5)

- [x] 5.1 `UploadCycle`: delete `RESOLVE_CHUNK` and the `chunked` loop. Per admitted row: place, resolve
      (`resourcesFor(setOf(key))`), create, and stop at the first `LIMIT_EXCEEDED`. Rewrite the `enqueue` KDoc
      (the "chunk is a granularity" text).
- [x] 5.2 Tests: a refusal stops the pass with no further resolve; album placement still precedes each row's
      job creation.
- [x] 5.3 grep the tree for `RESOLVE_CHUNK`, `resolveChunk` and "11 ms" (code, specs, `architecture/`) and fix
      what is left.

## 6. Docs, diagrams, gates

- [x] 6.1 Remaining KDocs citing the old rules: `LedgerStore.manifestRows` ("a departed asset's rows are
      deleted"), `SnapSyncApp.selectionScope`, and any "deselection is not withdrawal" / "never authoritative"
      text (`grep -rn "not withdrawal\|never authoritative\|fullEnumeration = false"`).
- [x] 6.2 `./gradlew architectureDiagrams` (the Foreground flow changed) and commit the regenerated
      `architecture/`.
- [x] 6.3 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` are green (detekt tiers included; no
      ceiling raised).
- [x] 6.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate selection-is-the-walk`.

## 7. Verification on real platforms

- [x] 7.1 ~~Simulator~~ — not runnable: `simctl` has no limited photo grant (`ios-simulator` skill). The
      partial-grant scenarios are covered over the real composed core instead
      (`SelectionIsTheWalkIntegrationTest`, including the measured downgrade).
- [x] 7.2 Device — done on the SE2 (iOS 26.6, 2026-09-22, rig Debug build of `6c81e2c6`); see the design's
      "Device verification".
- [x] 7.3 Recorded in the design ("Device verification").

## 8. At sync/archive time (not before; the user drives it)

- [x] 8.1 `upload-state-reconciliation` Purpose: it no longer runs "once, at the join" only — add the
      foreground settle to the Purpose's first paragraphs (a delta cannot edit a Purpose).
- [x] 8.2 `limited-photo-access` and `sync-ledger` Purposes: add a history line citing this change
      (de-selection is deletion; the in-flight exemption retired).
- [x] 8.3 Run the three archive gates in `openspec/config.yaml` (Placeholder Purpose, Delta completeness —
      account for `:domain`, `:adapter:ios:ext-safe`, `:adapter:ios:app-only`, `:adapter:generic:*`,
      `:test:world`; Dead types — none expected, as `RESOLVE_CHUNK` is a `val`).
