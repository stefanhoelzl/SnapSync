## 1. Backend — the completeness test and its two triggers

- [x] 1.1 Add the `event_assets (device_id, asset_id)` index as a new ordered migration file
      (`api/migrations/0002_*.sql`), `IF NOT EXISTS`, and regenerate the committed `api/schema.sql`
- [x] 1.2 Add a `db.ts` read answering "which events declare this (device, asset), and is every declared
      role now recorded" — the same set comparison `unionRows` applies, never a count
- [x] 1.3 Wake the event's other active members from the v2 byte route after `recordResource`, only when
      the recorded resource was the last declared role missing; best-effort and bounded, never changing the
      response
- [x] 1.4 Make the v2 manifest route's `notifyMembers` conditional: wake only when the publish newly
      declares an asset whose every declared role already has a recorded resource
- [x] 1.5 Add `apns-collapse-id: <eventId>` to the sender's headers in `api/src/apns.ts`; leave
      `apns-priority: 5` and send no `apns-expiration`

## 2. Backend — tests

- [x] 2.1 `api/test/app.test.ts`: a byte upload completing the last declared role wakes the event's other
      members, with the publisher excluded
- [x] 2.2 `api/test/app.test.ts`: a byte upload leaving an asset incomplete wakes nobody
- [x] 2.3 `api/test/app.test.ts`: a manifest publish declaring only unlanded resources wakes nobody; one
      newly declaring an already-recorded asset wakes members
- [x] 2.4 `api/test/app.test.ts`: a retraction-only publish wakes nobody
- [x] 2.5 `api/test/app.test.ts`: a completed-only manifest (today's clients) produces the same wakes it
      does now — the backend half is a no-op against existing devices
- [x] 2.6 `api/test/apns.test.ts`: the request carries `apns-collapse-id` set to the event id, and no
      `apns-expiration`
- [x] 2.7 `deno task schema:check`: the committed snapshot is what replaying the migrations produces
- [x] 2.8 `cd api && deno task test` passes, and `deno lint` passes under the complexity plugin

## 3. Device — the projection stops reading state

- [x] 3.1 Rename `LedgerStore.completedManifestRows()` to `manifestRows()` and rewrite its KDoc: the rows
      the manifest projects from, deliberately not state-scoped
- [x] 3.2 Change `Ledger.sq`'s query to `WHERE absent = 0` — drop both the state predicate and
      `creationDate != ''` — and rewrite the comment above it to say why the bare-row rule now lives in the
      policy
- [x] 3.3 Update `SqlDelightLedgerStore` and `InMemoryLedgerStore` (and the `:domain` test doubles) to the
      new name and predicate; the SQLDelight mapper no longer needs `DONE_STATES` bound
- [x] 3.4 Update `LedgerWriter.completedManifestRows()` and the `UploadCore.kt` call site
- [x] 3.5 Rewrite the KDoc on `LedgerState.DISCOVERED` and `LedgerState.UPLOADED` — both currently state
      they stay out of the manifest projection
- [x] 3.6 Rewrite `projectDeviceManifest`'s KDoc and `DeviceManifestProducer`'s class KDoc: the manifest
      declares intent; the union's completeness check is now the primary mechanism, not defense-in-depth
- [x] 3.7 Verify no production Kotlin still asserts the manifest lists only completed resources
      (`grep -rn "COMPLETED" domain/ adapter/ --include=*.kt`)

## 4. Device — tests

- [x] 4.1 `LedgerStoreContract` (`:test:world`): `manifestRows()` returns rows in every state, excludes
      absent rows, and no longer excludes bare rows — both driver suites inherit it
- [x] 4.2 `SqlDelightLedgerStoreTest`: the predicate change, on the real driver
- [x] 4.3 `DeviceManifestProducerTest`: a `DISCOVERED` row is declared; a `FAILED` row stays declared
      across a failure and its retry, so the produced JSON does not change
- [x] 4.4 `NarrowingRetractionTest` / `CeilingReachesEveryConsumerTest`: still green — the policy remains
      the only admission, now including the undated-row exclusion
- [x] 4.5 `:test:integration`: a declared-but-unlanded resource keeps its asset out of the world's union,
      and the asset appears once the last byte lands
- [x] 4.6 `:test:integration`: the Live Photo case — `primary` lands, the union still hides the asset, and
      a reconciling recipient imports nothing until `live` lands
- [x] 4.7 `./gradlew build` passes, including the detekt tiers and `:test:architecture`
- [x] 4.8 `./gradlew compileIosMainKotlinMetadata` passes

## 5. Specs and comments

- [x] 5.1 Correct `api/src/app.ts:1302` and `:1331` — both still say the manifest lists only uploaded
      resources, which was already false against `api-endpoints` before this change
- [x] 5.2 Correct `api/src/db.ts`'s `event_assets.roles` comment, which names an idiom
      (`json_each` + `NOT EXISTS`) the query does not use — it LEFT JOINs and tests presence per row
- [x] 5.3 Note in `api/README.md` that the byte route fans out on asset completion
- [x] 5.4 `npx --yes @fission-ai/openspec@1.5.0 validate --changes --strict` passes

## 6. Ship

- [x] 6.1 `./gradlew architectureDiagrams` and commit if anything moved
- [ ] 6.2 Open the PR with exactly one changelog label — note that this fixes a user-visible defect
      (Live Photos delivered as stills), so `internal` is not automatic
- [ ] 6.3 `/ship --keep-workspace`, so the hand-back report can leave before the workspace does
- [ ] 6.4 On a confirmed merge, report to the `ledger` workspace per the handoff's completion procedure
