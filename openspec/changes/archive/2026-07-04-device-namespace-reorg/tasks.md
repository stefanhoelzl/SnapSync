## 1. Backend storage keys + routes (`backend/src/app.ts`)

- [x] 1.1 Repoint the byte-object key helper `byteKey(deviceId, filename)` to
  `devices/<deviceId>/files/<filename>` (percent-encoding each of `deviceId` and `filename`); the
  presigned-URL builder and upload handler that call it inherit the new key with no other change.
- [x] 1.2 Repoint the device byte-store list directory helper `deviceDir(deviceId)` to
  `devices/<deviceId>/files/`; the per-device list route and the union fan-out that call it inherit it.
- [x] 1.3 Change the byte-upload route mount from `/files/device/:deviceId/:filename` to
  `/devices/:deviceId/files/:filename` (the `byteFile` sub-app that shares PUT + OPTIONS); update the
  handler's param reads if the mount shape changes.
- [x] 1.4 Change the per-device list route from `GET /files/device/:deviceId` to
  `GET /devices/:deviceId/files`.
- [x] 1.5 Confirm the unchanged surfaces stay unchanged: `GET /event/:eventId/files` path, the
  `events/<eventId>/…` marker + manifest keys, the manifest write route
  `PUT /event/:eventId/device/:deviceId`, the `AccessKey` usage, faithful `2xx`/`5xx`/`404`/`502`
  handling, and last-write-wins.

## 2. Backend docs + tests

- [x] 2.1 Update the file-header route/layout comments in `backend/src/app.ts` (the routes list and the
  storage-key/registry notes) to the `devices/<deviceId>/files/<filename>` layout and the new URLs.
- [x] 2.2 Update `backend/README.md` to describe the device namespace and the new upload/list URLs.
- [x] 2.3 Update the backend `Deno.test` suite (`backend/**/*.test.ts`): route paths for byte-upload and
  per-device list, and the expected upstream storage keys/prefixes (`devices/<id>/files/…`) for upload,
  list, presigned-URL composition, and the union fan-out; add/adjust an unmatched-path case for the new
  route shape.

## 3. Device upload URL builder (`:capability:upload-url`)

- [x] 3.1 Repoint `EdgeUploadRequestProvider` to compose
  `<host>/devices/<deviceId>/files/<encoded-filename>` (was `<host>/files/device/<deviceId>/…`); keep
  the injective percent-encoding, Content-Type-only header, and no-query-string contract unchanged.
- [x] 3.2 Update the `EdgeUploadRequestProvider` KDoc (the `<host>/…` example and the
  `resource.filename → …` mapping note) to the new path.
- [x] 3.3 Update `EdgeUploadRequestProviderTest` (`commonTest`) expected URLs to the new path, keeping
  the percent-encoding and injectivity assertions.

## 4. Device re-join listing client (`:capability:rejoin`)

- [x] 4.1 Repoint `DeviceFilesSource` to `GET <host>/devices/<deviceId>/files` (was
  `/files/device/<deviceId>`); update its KDoc references to the route and the `/devices/<id>/files/`
  partition.
- [x] 4.2 Update `ReconcilerTest`/`Reconciler` KDoc and any test `UploadRequest`/URL strings that spell
  the old `/files/device/…` path (cosmetic consistency; the reconciler does not parse the URL).

## 5. Test world + remaining fixtures

- [x] 5.1 Update `:test:world` `MiniEdge` mock routes and path parsing: serve `GET /devices/<id>/files`
  and `PUT /devices/<id>/files/<filename>` (and OPTIONS), and store/list under the
  `devices/<id>/files/` prefix so the real stack, `:test:integration`, and the full-stack harness run
  against the new layout.
- [x] 5.2 Update `:test:world` `BackendStore` KDoc/comments (and any key-building logic) that reference
  `GET /files/device/<id>` or the `files/<deviceId>/` prefix.
- [x] 5.3 Update the download union test fixtures (`HttpEventUnionSourceTest`) and any other doc
  comments still spelling `/files/device/…` for consistency (union `url`s are opaque to the client).
- [x] 5.4 Grep the tree for residual `files/device` and `files/<` / `"files/"` references and fix or
  confirm each is intentional (e.g. the retired-route note in the upload spec).

## 6. Verify

- [x] 6.1 Backend green: `cd backend && deno fmt --check && deno lint && deno check && deno test`.
- [x] 6.2 App green: `./gradlew build` (all targets + JVM/Compose tests, incl. `:capability:upload-url`,
  `:capability:rejoin`, `:test:integration`) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy).
- [x] 6.3 `npx --yes @fission-ai/openspec@1.4.1 validate device-namespace-reorg --strict` passes.
