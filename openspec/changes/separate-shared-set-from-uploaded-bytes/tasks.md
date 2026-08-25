## 1. Confirm the two claims before changing anything

Both defects are code traces, not observed failures. These tasks are expected to go **red**. If either
passes, that claim is wrong — stop, and revise the corresponding part of the proposal and design before
proceeding.

- [x] 1.1 Add a `commonTest` in `:domain` that completes a ledger row for an asset above an old cutoff,
      raises the membership's cutoff past that asset's capture date, projects the device manifest, and
      asserts the row is **still listed**. Expected: RED (it is dropped today).
- [x] 1.2 Add a `commonTest` that uses the same setup, runs a fully-drained full enumeration, and asserts
      the out-of-scope asset's **ledger row survives**. Expected: RED (it is pruned today).
- [x] 1.3 Add a `commonTest` asserting that a membership reconfigured from contributing to download-only
      retains its event's ledger rows across a cycle. **Expected GREEN, not red** — the direction gate
      returns before the walk, so retention never runs (design C states this conditionally; the original
      "Expected: RED" here was wrong). Kept as a regression guard: D9 leaves the walk behind the gate.
- [x] 1.4 Record the three outcomes in the change directory. If any passed, halt and revise before task 2.

## 2. Stop pruning the ledger

Revised after task 1 (see `TASK-1-FINDINGS.md`): retention is **removed**, not re-scoped. Pruning was only
ever changing the listing, which is the manifest's job.

- [x] 2.1 Add the `absent` fact to `LedgerEntry`; add the ledger schema migration for the column
      (same family as the existing 4.sqm/5.sqm), defaulting unset for existing rows.
- [x] 2.2 Replace `LedgerStore.deleteByAssetId` with `markAbsent(assetId)` — an indexed `UPDATE`, not a
      `DELETE`. **Remove `retainAssets` entirely** from the port, `LedgerWriter`, and both backends.
- [x] 2.3 Update `InMemoryLedgerStore` and `SqlDelightLedgerStore` to match, and update the shared
      `LedgerStoreContract` with the mark scenarios (idempotent, keeps `state`, signals once).
- [x] 2.4 In `UploadCycle`: `discovery.removedAssetIds` marks rather than deletes, and the
      `if (discovery.fullEnumeration) ledger.retainAssets(...)` call is deleted outright.
- [x] 2.5 Exclude `absent` rows in `projectDeviceManifest`.
- [x] 2.6 Update the cycle tests that pinned pruning. Nine needed restating, not one — see
      `TASK-1-FINDINGS.md` for the list and what each now pins.
- [x] 2.7 Confirm 1.2 passes and 1.3 still passes.

## 3. Guard the manifest write

- [x] 3.1 Make the cycle suppress the manifest write when the re-join reconcile defers, rather than only
      skipping job creation.
- [x] 3.2 Make the cycle suppress the manifest write when the ledger rows cannot be read. **Already
      structural**: `completedManifestRows()` is read inside the hook, so a read failure means `produce`
      is never called and nothing is published.
- [x] 3.3 Log suppression distinctly from publishing an empty manifest ("could not determine what is shared"
      vs "shares nothing"), at routine severity.
- [x] 3.4 Add `commonTest`s for both suppression paths and for the empty-manifest publish.

## 4. Shrink the direction gate

- [x] 4.1 Move the re-join reconcile ahead of the direction gate, keeping the terminal-job settle pass first.
- [x] 4.2 Stop the gate withholding the manifest write; a non-contributing membership publishes an empty
      manifest.
- [x] 4.3 Keep job creation, the retry pass and the discovery walk behind the gate.
- [x] 4.4 Correct the gate's code comment: two of its three stated justifications no longer hold.
- [x] 4.5 Update `UploadCycleTest` for the new ordering, including the declined-cycle-still-reconciles and
      declined-cycle-publishes-empty cases.
- [x] 4.6 Confirm 1.1 now passes for the direction-off case. (1.1's tests were **inverted** to the new
      intended behaviour — narrowing retracts at the manifest — since they had pinned the pre-change spec.)

## 5. Collapse the policy type

- [x] 5.1 Add `SelectionRule.DenyAll` (`admits = false`) to the sealed rule set.
- [x] 5.2 Replace `SelectionPolicy.None` / `Admitting` with a single policy over `rules`, with
      `admits(facts) = rules.all { it.admits(facts) }` and no special-cased member.
- [x] 5.3 Replace `from()` + `excluding()` with the single suspend rule-builder that gates on
      `config.direction.includesUpload` internally and invokes neither exclusion source when it excludes
      upload. Delete `excluding()`.
- [x] 5.4 **Chose the guard.** `SelectionPolicyConstructionTest` forbids `SelectionPolicy(` in any
      production source set outside `SelectionPolicy.kt`. A private constructor was rejected: the platform
      translator's tests and both harnesses must present arbitrary rule lists — that is what a translator
      test is for — and the doors needed for them would be indistinguishable from the one being closed.
      Production builds policies through `selectionPolicyFor` / `noContribution`; the guard found four
      real sites when first run, all now converted.
- [x] 5.5 Delete `enumerates` and update its two readers (`ShareableCount`, `EventPhotoSet`).
- [x] 5.6 Update the consumers that exhausted the sealed type. Went further than planned: none of them
      needs a capture bound any more. `UploadCycle` reads the membership's policy supplier and gates on
      `contributes`; `OwnDeviceGalleryStatusSource` receives a finished policy and lost both exclusion
      readers; `InMemoryCandidateSource` translates rules like the real adapter does.
- [x] 5.7 Update `projectDeviceManifest` for the new policy type.
- [x] 5.8 Update `SelectionPolicy.kt`'s KDoc: it currently documents the two-variant rationale and the
      derived-`CaptureAfter` decision at length, and both are superseded.

## 6. Translate the deny-everything rule

- [x] 6.1 Add the `DenyAll` arm to `predicateFor`, emitting `creationDate < <distant past>`. Do **not** use
      the `(mediaSubtypes & N) == 0` zero-row form — see design D4.
- [x] 6.2 Extend `PhotoKitCandidateSourceTest` (iosTest, macOS CI) to pin the new arm.
- [x] 6.3 Verify `:adapter:ios:ext-safe:compileIosMainKotlinMetadata` passes on Linux.

## 7. Reconfigure surface

- [x] 7.1 Helper text replaced. **DRAFT WORDING — needs review**: "Sharing less stops listing those photos
      to the event — anyone who already received them keeps them. Photos you've received stay." It must
      not imply deletion or recall; the retraction cannot reach a member who already downloaded.
- [x] 7.2 Correct `ReconfigureEvent`'s comment claiming a raised cutoff "un-shares nothing".
- [x] 7.3 Add a `commonTest` for the narrow-then-widen round trip: listings return, no byte re-uploads.
- [x] 7.4 Update any `:ui:screens` / `:ui:presentation` test that pins the old helper text. None did.

## 8. Test-only consumers

- [x] 8.1 Fix `:test:rig`'s `GalleryReader` to take `admitted` from `policy.admits(facts)` rather than
      re-running the rule list, and to name the refusing rule from the list only as a label. Non-gating, so
      it will not fail the build if missed.
- [x] 8.2 Update `describe()` for the `DenyAll` rule.
- [x] 8.3 Verify `:test:rig:compileIosMainKotlinMetadata` passes on Linux.
- [x] 8.4 Update the world harness and `:test:integration` for the new policy construction.

## 9. Verify

- [x] 9.1 `./gradlew build` green (domain `commonTest` on JVM, architecture guards, detekt).
- [x] 9.2 `./gradlew compileIosMainKotlinMetadata` green for `:adapter:ios:ext-safe` and `:test:rig`.
- [x] 9.3 `./gradlew architectureDiagrams` and commit if anything moved — stale diagrams block the PR.
- [x] 9.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 9.5 Ledger schema migration applies cleanly over an existing database (migration test).
- [ ] 9.6 CI macOS job green for `iosSimulatorArm64Test` / `PhotoKitCandidateSourceTest`.

## 10. Measure on device before shipping

- [ ] 10.1 Confirm on the connected iPhone that the `DenyAll` predicate returns **zero** rows — load the
      `rig-channel` skill, set a download-only membership, and read the gallery route. Every one of the
      three constraints already documented above `predicateFor` is a case where a plausible predicate did
      something else; this one is reasoned, not measured.
- [ ] 10.2 Confirm a download-only membership publishes an empty manifest and that its ledger rows survive.
- [ ] 10.3 Record the measurement in the change directory before archiving.
