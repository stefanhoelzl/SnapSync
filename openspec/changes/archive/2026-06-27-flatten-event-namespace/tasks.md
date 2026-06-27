## 1. Backend: flatten route, key, and listing (`backend/`)

- [x] 1.1 Upload route: change `/event/:eventId/device/:deviceId/file/:filename` →
      `/event/:eventId/file/:filename` in `app.ts` (`app.route(...)` and the header comments). Drop
      the `deviceId` path param and its `validateUUID(deviceId)` check; keep `eventId` + `filename`
      validation. Storage key → `${eventId}/${encodeURIComponent(filename)}`.
- [x] 1.2 List endpoint: replace the two-level walk (list `<eventId>/` for device dirs → list each
      `<eventId>/<deviceId>/` → flatten) with a **single** `LIST <eventId>/` returning files directly.
      Delete the per-device fan-out, the directory-entry filtering for sub-dirs, and the per-device
      partial-failure handling. Absent dir → `[]`; any List failure → `502`.
- [x] 1.3 `FileEntry` / response shape: drop the `deviceId` field → `{ filename, size, lastModified }`.
- [x] 1.4 `validators.ts`: keep `validateUUID` for `eventId`; remove the `deviceId` comment/usage note.
- [x] 1.5 Update `backend/test/app.test.ts` (and any others): upload paths drop `/device/<uuid>/`;
      key assertions → `<eventId>/<filename>`; list tests assert a single LIST and the 3-field entry
      shape; drop the cross-device-aggregation and per-device-failure cases (add a single-LIST-failure
      → 502 case). `deno task test` green.

## 2. On-device provider (`:capability:upload-url`)

- [x] 2.1 `EdgeUploadRequestProvider`: drop the `deviceId` constructor param; URL →
      `$base/event/$eventId/file/${encodeFilenameSegment(resource.filename)}`. Update the KDoc
      (`<eventId>/<deviceId>/` → `<eventId>/`, idempotency tuple `(host, eventId, filename)`).
- [x] 2.2 `EdgeUploadRequestProviderTest`: drop the `deviceId` arg from construction; update expected
      URLs to the flat form; keep injectivity/encoding/stability cases. Runs on JVM + simulator.

## 3. Extension: remove the device-id machinery (`:app:ios:photokit-extension`)

- [x] 3.1 Delete `DeviceIdStore.kt` (commonMain), `IosDeviceIdStore.kt` (iosMain), and
      `DeviceIdProviderTest.kt` (commonTest).
- [x] 3.2 `UploadConfig.kt`: drop the `deviceId` field and the `deviceId` param/guard in
      `buildUploadConfig(eventId, host)`; update KDoc.
- [x] 3.3 `UploadExtensionRoot.kt`: remove `deviceIdProvider`, the `deviceId()` call, the
      "deviceId present" log, and pass `EdgeUploadRequestProvider(config.host, config.eventId)`.
      Remove the device-id-unavailable no-op branch (only the absent-Keychain-payload no-op remains).
- [x] 3.4 `compileIosMainKotlinMetadata` (Linux proxy) green; `:app:ios:photokit-extension` tests pass.

## 4. Re-join consumer (`:capability:rejoin`) — comment only

- [x] 4.1 `HttpEventFilesSource`: drop the stale `deviceId` mention in the KDoc ("ignoring the
      `deviceId`/`size` fields…" → "ignoring the `size` field…"). `FileDto` already omits `deviceId`;
      no behavior change. Tests already filename-only — confirm green.

## 5. Design source of truth (`docs/design.md`)

- [x] 5.1 §3.1: rewrite the key to `<eventId>/<encoded filename>`; remove the `<deviceId>` bullet, the
      "per-device namespacing makes the local id sufficient" line, and the per-device dedup/attribution
      trade-off. Note flat-namespace collision analysis (idempotent / UUID-collision) instead.
- [x] 5.2 §3.5: drop "group by `<deviceId>`" from downstream reconstruction (key path no longer
      carries a device id).
- [x] 5.3 §4: update the endpoint request path (`/event/<eventId>/file/<filename>`) and the list
      response shape (`{ filename, size, lastModified }`); drop the device dir from the LIST prose.
- [x] 5.4 Bottom summary table / scattered `<eventId>/<deviceId>/` references (the persistence-store
      row, the key examples): flatten and remove `deviceId` from the App-Group store list.

## 6. Verify

- [x] 6.1 `./gradlew build` green (all targets + JVM tests, incl. `:capability:upload-url` and the
      extension module).
- [x] 6.2 `compileIosMainKotlinMetadata` green (iOS source sets compile without the device-id code).
- [x] 6.3 `deno task test` (or the backend's check) green.
- [ ] 6.4 Clear the bunny storage zone of old `<eventId>/<deviceId>/...` test objects before the next
      deploy (clean break — flat LIST will not see nested objects).
