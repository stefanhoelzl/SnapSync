# Tasks

## 1. Schema and migration (api/)

- [x] 1.1 Write migration `0010` (design D2, D3): rebuild `resources` as `(event_id, device_id, asset_id, role, path, content_type, filename)`, PK `(event, device, asset, role)`, FK → `memberships` ON DELETE CASCADE, with no `key` or `UNIQUE(device_id, key)`. Expand each old row to declaring events ∪ the device's present membership with `path = files/devices/<d>/<key>`, and drop rows with neither. Update `schema.sql`. Verify with a `migrations.test.ts` case seeding a device declared in two events, one gone member, one undeclared byte and one byte-before-manifest, asserting the expanded rows. `schema-shape.test.ts` must pass.
- [x] 1.2 Add the path helper `eventBytePath(eventId, deviceId, assetId, role)` = `files/<e>/<sha256 hex>` beside `byteKey` in `storage.ts`. Verify with a unit test pinning one known digest and showing two events yield different paths.

## 2. Reads follow `path` (api/)

- [x] 2.1 Union read, download redirect lookup and v1's presigns join and serve by `(event, device, asset, role)` and `path`. `presignDownloadUrl` takes the path, and the wire `key` is derived with `legacyKeyFor`. Verify that `union-read.test.ts`, `download.test.ts`, `union-log.test.ts` and `v1.test.ts` pass with migrated legacy-path rows and new-path rows in one event.
- [x] 2.2 Listings (design D5): add `GET /api/v2/events/<e>/files/devices/<d>`, returning `resources(e, d)`. The old `GET /files/devices/<d>` and v1's listing return the present membership's rows, or `[]`. Verify with `v2.test.ts` / `v1.test.ts` cases: a row in another event is never listed, and a device with no present membership gets `[]`.

## 3. Writes go to the event (api/)

- [x] 3.1 New byte route `PUT /api/v2/events/<e>/files/devices/<d>/<a>/<r>?filename=` (design D4): token-gated, sharing/settled members only (403, or 409 when closed), writing `eventBytePath`, then the row, completion, union-log gains, landing stamp and wake scoped to `e`. Keep `isGatedRequest` gating the PUT while the GET redirect stays exempt. Verify with `v2.test.ts` cases (member writes and completes, non-member 403, closed event 409, re-upload overwrites one object) and an `app.test.ts` gate case.
- [x] 3.2 Event-less v2 and v1 byte routes resolve the present membership (409 with none) and write the same path. v1's best-effort record and its publish-time row re-creation use `eventBytePath`. Verify with `v2.test.ts` / `legacy-v1.test.ts` cases: an upload lands in the present event, after a switch it lands in the new event, and with no membership it gets 409.
- [x] 3.3 Make `eventsCompletedBy` and the publish's resource writes per event. Verify `completion.test.ts` and `membership-states.test.ts` pass, plus a case where a byte completes only its own event.

## 4. Sweep (api/)

- [x] 4.1 Per-event folder deletion and orphan reconcile (design D7): recursive DELETE of `files/<e>/` for completed/dropped events, and a top-level `files/` listing that deletes `<uuid>/` folders with no live event, skipping `devices`. Suspend the legacy ASSET phase and DIRECTORY step. Update the dev filesystem store (`dev/fs-storage.ts`, `dev/fallback.ts`) for recursive folder deletes. Verify with `scripts/sweep.test.ts` cases (completion deletes the folder, an orphan folder is reconciled, a live event's folder is kept, `files/devices/` is untouched) and `dev/fs-storage.test.ts`.
- [x] 4.2 Update `docs/deployment.md` §3 (sweep) and `docs/architecture.md` §8 (the `resources` bullets, routes table, sweep paragraph, byte-route description) and §9 (byte store). Verify by grepping both docs for `files/devices` and `UNIQUE (device_id, key)`, so only intentional legacy mentions remain.

## 5. Client

- [x] 5.1 `EdgeUploadRequestProvider` takes the event id and builds the event-scoped URL. `uploadCycle` passes `config.eventId`. Update its KDoc ("event-independent" no longer holds). Verify that `EdgeUploadRequestProviderTest`, `DestinationPathTest`, `UploadUrlRequestTest` and `PhotoKitJobMappingTest` pass with the new shape, plus a test that a ledger row recorded with an old-shape destination path still matches its completing job.
- [x] 5.2 `HttpBackend`'s device listing and the `DeviceFilesSource` / `ShareSetLoad` call take the joined event. Update `GatedPaths.kt` so the new PUT is gated. Verify with `HttpBackendTest` and the Backend contract's live binding (`:adapter:generic:app:jvmTest`, against the real `api/`).
- [x] 5.3 `BackendMock` mirrors design D2, D4 and D5: event-scoped records, member-only writes, the per-event listing, and event-less routes resolving the present membership. Verify that the Backend contract's mock binding and `MockStateTest` pass. `./gradlew build` must be green, including `:test:integration`.

## 6. Contract text

- [x] 6.1 Correct `openspec/config.yaml`'s named-futures line ("bytes, ledger keys, and backend GC are already event-independent") to state that bytes are now per event and the event-less routes assume single membership. Verify `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` passes.
- [x] 6.2 At sync, edit `photo-sharing`'s Purpose ("not uploaded again because the member rejoins, switches events or reinstalls") to read "switches events and back". Verify by reading the synced Purpose and running the archive gates in `openspec/config.yaml`.

## 7. Integration

- [ ] 7.1 Run `deno task test` in `api/` and `./gradlew build`, and the `migration-rehearsal` gate on the PR. Verify all are green, and that the rehearsal's row counts before and after the expansion are explained (each declared or present (event, device, asset, role) has exactly one row).
