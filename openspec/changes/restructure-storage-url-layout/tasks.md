## 1. Backend routes + storage keys (`backend/src/app.ts`)

- [x] 1.1 Update `byteKey` → `files/devices/<deviceId>/<filename>` and `deviceDir` → `files/devices/<deviceId>/`.
- [x] 1.2 Update `deviceConfigKey` → `devices/<deviceId>.json` (flat sibling; drop the `/config.json` suffix).
- [x] 1.3 Update `deviceManifestKey` → `events/<eventId>/devices/<deviceId>.json` and `deviceManifestDir` → `events/<eventId>/devices/`. Leave `markerKey` (`events/<eventId>/metadata.json`) unchanged.
- [x] 1.4 Pluralize the event routes: `app.post("/events")`, `app.get("/events/:eventId")`, `app.put("/events/:eventId/devices/:deviceId")`, `app.get("/events/:eventId/files")`, `app.post("/events/:eventId/notify")`.
- [x] 1.5 Move the byte routes: remount the child Hono at `app.route("/files/devices/:deviceId/:filename", byteFile)`, and change the listing to `app.get("/files/devices/:deviceId")`.
- [x] 1.6 Change the config route to `app.put("/devices/:deviceId")` (no `/config` suffix).
- [x] 1.7 Rewrite the header doc block (lines ~1–61) and the per-route comments to describe the new paths/keys. Confirm `presignDownloadUrl` needs no change (it composes over `byteKey`).
- [x] 1.8 Sanity-check route collisions: listing (`/files/devices/:id`, 3 segs) vs byte mount (4 segs); `PUT /devices/:id` alone under `/devices/`; `/events/:id/files` (literal `files`) vs `/events/:id/devices/:id` (literal `devices`).

## 2. Backend tests (`backend/test/**/*.test.ts`)

- [x] 2.1 Update every `app.request()` path string and every asserted upstream storage key to the new layout.
- [x] 2.2 Add/keep coverage for the reshaped fall-through: wrong method / unmatched path → `404`, bad UUID / unsafe filename → `400`, across all renamed routes.
- [x] 2.3 Run `deno task test` + `deno fmt --check` + `deno task lint` green.

## 3. Kotlin client call-sites

- [x] 3.1 `:capability:upload-url` `EdgeUploadRequestProvider.kt` → `"$base/files/devices/$deviceId/${encodeFilenameSegment(...)}"`; update `EdgeUploadRequestProviderTest.kt`'s `endsWith(...)` assertion.
- [x] 3.2 `:capability:rejoin` `DeviceFilesSource.kt` → `client.get("$base/files/devices/$deviceId")`.
- [x] 3.3 `:capability:download` `EventUnionSource.kt` → `client.get("$base/events/$eventId/files")`.
- [x] 3.4 `:capability:push` `PushRegistration.kt` → `PUT "$host/devices/$deviceId"` (drop `/config`).
- [x] 3.5 `:capability:event-creation-ui` `EventCreationClient.kt` → `POST "$base/events"`; `EventMetadataSource.kt` → `GET "$base/events/$eventId"`.
- [x] 3.6 `:app:ios:photokit-extension` `IosDeviceManifestUploader.kt` → `PUT "$base/events/$eventId/devices/$deviceId"`.

## 4. Test harness (`:test:world`)

- [x] 4.1 `MiniEdge.kt` — rewrite the segment matcher: byte upload `PUT [files,devices,id,name]`; listing `GET [files,devices,id]`; config `PUT [devices,id]`; create `POST [events]`; metadata `GET [events,id]`; union `GET [events,id,files]`; manifest `PUT [events,id,devices,id]`; notify `POST [events,id,notify]`. Update its header comment.
- [x] 4.2 `HttpDeviceManifestUploader.kt` → `PUT "$base/events/$eventId/devices/$deviceId"`.
- [x] 4.3 `BackendStore.kt` — update the path/key references in comments to the new layout.

## 5. Incidental prose + docs

- [x] 5.1 Refresh stale non-normative path references in comments: `device-id` `DeviceIdentity.kt`, `:capability:config` `EventConfig.kt`, `:capability:push` `PushReceiver.kt`, `:app:ios` `SnapSyncRoot.kt`, and any others surfaced by a repo-wide grep for the old paths.
- [x] 5.2 `backend/README.md` — update the Storage layout, Contract, and Layout sections to the new keys/routes.
- [x] 5.3 `docs/design.md` §3–4 — update the backend storage/endpoint description.
- [x] 5.4 Refresh the illustrative path references in `openspec/specs/device-identity` (Purpose) and `openspec/specs/event-rejoin-reconciliation` (historical mention) for accuracy (non-normative; no requirement change).

## 6. Verification

- [x] 6.1 `./gradlew build` green (compiles all targets + JVM tests, incl. `:test:integration` seam→world→UiState over the renamed `MiniEdge`).
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green (iOS-source proxy).
- [x] 6.3 `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict` green.
- [x] 6.4 Repo-wide grep confirms no stale `devices/<id>/files`, `/devices/:id/config`, singular `/event/` HTTP routes, or `events/<id>/device/` manifest paths remain (excluding intentional historical spec text).

## 7. Deploy + cutover (clean cut, no migration)

- [x] 7.1 Deploy the backend (path-scoped CI on `backend/**`, gated on green fmt/lint/check/test).
- [x] 7.2 Wipe the bunny zone via `scripts/reset-storage.ts` (old-key objects are abandoned).
- [ ] 7.3 Install the matching app build and drive the headless dev loop against a **fresh** event id; confirm an upload lands under `files/devices/<id>/…` in the storage zone (per `backend/README.md` *Verify real uploads*).
