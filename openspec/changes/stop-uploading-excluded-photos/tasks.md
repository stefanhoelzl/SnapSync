## 1. Pin the defect before fixing it

- [ ] 1.1 Add a `:test:integration` case over `:test:world`: join with a wide cutoff, run a walk that
      records rows for assets across the range, raise the cutoff via the reconfigure path, run further
      cycles — assert the excluded assets' bytes never reach the backend store. It fails today.
- [ ] 1.2 Add the starvation case: more rows needing a job than one batch, with the excluded ones sorting
      **ahead** of the admitted ones in key order — assert admitted work is still enqueued. It fails
      against a naive fix, which is the point of writing it now.
- [ ] 1.3 Add a `:test:integration` case asserting the manifest and the uploaded set are the identical
      set after a narrowing, not merely that each is individually correct.

## 2. Admit the work source

- [ ] 2.1 Decide `candidatesFromFacts`' home now that it has two callers — leave it in
      `domain/model/DeviceManifest.kt` or move it beside the admission. Behaviour-neutral; make the call
      and note it in the commit.
- [ ] 2.2 In `UploadCycle.enqueue`, admit the needs-job rows through `EventPhotoSet(policy)` over
      `candidatesFromFacts` before any `resourcesFor` call, so no excluded row costs a platform
      round-trip.
- [ ] 2.3 Apply `enqueueBatchSize` to the **admitted** rows, not to the read.
- [ ] 2.4 Update `enqueue`'s KDoc so it describes what the code now does — it already claims "the batch
      bounds only what is RESOLVED"; make that true rather than aspirational.

## 3. Move the bound off the read

- [ ] 3.1 Change `LedgerStore.rowsNeedingJob` so the cycle can read the needs-job rows without a batch
      bound, updating its KDoc to say the caller bounds the resolve.
- [ ] 3.2 Update both backends — `SqlDelightLedgerStore` and `InMemoryLedgerStore` — and the `.sq` query
      if the bound was expressed there.
- [ ] 3.3 Update `LedgerWriter.rowsNeedingJob` and any harness/world wrapper that mirrors the signature.
- [ ] 3.4 Extend `LedgerStoreContract` in `:test:world` for the new contract, so both backends are held
      to it.

## 4. Specs

- [ ] 4.1 Apply the `photo-selection-policy` delta — the ledger's needs-job read named as an
      upstream-filtered structure, plus the narrowing and non-starvation scenarios.
- [ ] 4.2 Apply the `sync-ledger` delta — the work-source read admits before resolving, and the bound is
      on the resolve.
- [ ] 4.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 5. Verify

- [ ] 5.1 `./gradlew build` green, including `:test:architecture` and the eight complexity tiers.
- [ ] 5.2 `./gradlew compileIosMainKotlinMetadata` green.
- [ ] 5.3 Confirm no detekt tier ceiling was raised. If `enqueue`'s complexity now exceeds its tier, extract
      rather than raise — a raise needs a stated forcing proof in the PR.
- [ ] 5.4 Drive the narrowing end to end in the world harness (`:app:desktop:run`) or headlessly via
      `ui-harness`: narrow the cutoff, invoke cycles, watch the upload count stop where the preview said
      it would.
- [ ] 5.5 `./gradlew architectureDiagrams` and commit if anything moved.

## 6. Ship

- [ ] 6.1 Branch → PR with the `bug` label → `/ship`.
