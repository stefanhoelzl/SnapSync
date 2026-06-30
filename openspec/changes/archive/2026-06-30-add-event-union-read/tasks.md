## 1. Union route + fan-out assembler (`backend/src/app.ts`)

- [x] 1.1 Add a `GET /event/:eventId/files` route to the root Hono app (sibling of `GET
      /event/:eventId` and `PUT /event/:eventId/device/:deviceId`). Reject a non-UUID `eventId` with
      `400` via `validateUUID` (no upstream request); rely on Hono's default `404` for unmatched
      paths / non-`GET` methods.
- [x] 1.2 Gate on the marker: call `readMarker(fetchImpl, config, eventId)`; `null` → `404` "event
      not found"; a thrown error → `502` (mirror the metadata route). Proceed only when the marker is
      present.
- [x] 1.3 Enumerate contributing devices with one `listDir(... events/<eventId>/device/)` (add a
      `deviceManifestDir(eventId)` helper → `events/<id>/device/`). `null` (404/empty) → respond
      `200 []`. Take entries where `IsDirectory === false` and `ObjectName` ends in `.json`; the
      deviceId is `decodeObjectName(ObjectName)` minus the `.json` suffix.
- [x] 1.4 Per device (in parallel — `Promise.all`), perform two reads: (a) GET the manifest object
      `events/<eventId>/device/<deviceId>.json` with the `AccessKey` header and parse it as
      `{ deviceId, assets: [{ assetId, creationDate, resources: [{ role, contentType, key, filename
      }] }] }`; (b) `listDir(... files/<deviceId>/)` → the device's present object names. A manifest
      read that is non-OK, throws, or fails to parse → reject (→ `502`). A file-dir `listDir` returning
      `null` (404) → treat as the empty set (not a failure); any other `listDir` throw → reject.
- [x] 1.5 Completeness + projection: build `present = new Set(entries.filter(!IsDirectory).map(e =>
      decodeObjectName(e.ObjectName)))` and the `size` lookup from the same entries. Keep an asset
      iff every `resource.key ∈ present`. Emit each kept asset as `{ deviceId, assetId, creationDate,
      resources: resources.map(r => ({ role: r.role, contentType: r.contentType, key: r.key,
      filename: r.filename, size: present-entry size for r.key, url: downloadUrl(config, deviceId,
      r.key) })) }`. Flatten across devices into one array.
- [x] 1.6 Faithful outcome: if the marker read, the manifest-dir LIST, **any** per-device manifest
      read/parse, or **any** per-device file LIST fails, respond `502` and return no partial union
      (wrap the fan-out so any rejection maps to `502`; log the failing key like the other handlers).
      A per-device file-dir `404` is NOT a failure.
- [x] 1.7 Set `Cache-Control: no-store` on the `200` union response. Use the configured `AccessKey`
      on every upstream read; never the account API key. Update the file-top route comment block to
      document the new union route (shape, gate, fan-out, faithfulness) alongside the existing routes.

## 2. Union tests (`backend/test/app.test.ts`)

- [x] 2.1 Drive the app via `app.request()` with an injected `fetch` fake that serves canned bunny
      responses keyed by URL: the marker GET, the `events/<id>/device/` LIST (two `<deviceId>.json`
      children), each `device.json` GET, and each `files/<deviceId>/` LIST. Assert a flat union across
      two devices: each asset `{ deviceId, assetId, creationDate, resources }`, each resource
      `{ role, contentType, key, filename, size, url }`, `url` == `downloadUrl(config, deviceId, key)`,
      and `Cache-Control: no-store` present.
- [x] 2.2 Completeness: an asset whose manifest names a resource missing from its device's file LIST
      is **omitted**; an asset with all resources present is **included**. A device whose file LIST is
      `404`/empty contributes nothing (its assets all omitted), and that case is `200`, not `502`.
- [x] 2.3 Gate: absent marker → `404` (no device enumeration); a non-`404` marker read failure →
      `502`. A present marker with an empty/`404` `events/<id>/device/` LIST → `200 []`.
- [x] 2.4 `400` on a non-UUID `eventId` with **no** upstream call made (assert the fake `fetch` was
      not invoked); `404` on a wrong method (e.g. `POST /event/<id>/files`) and on an unmatched path.
- [x] 2.5 Faithful failure: a per-device `device.json` GET that returns `500`, that throws, or that
      yields unparseable JSON → endpoint `502`, no partial union; likewise a per-device file LIST that
      returns `500`.
- [x] 2.6 `AccessKey` header present on every upstream read in the fan-out; the account API key never
      appears on any upstream-facing surface.

## 3. Manifest field rename (producer + schema)

- [x] 3.1 In the iOS upload extension's manifest projection (the `device.json` writer under
      `iosApp/` / the shared module per `ios-background-upload`), rename the resource fields written
      into `device.json`: `filename` → `key` (storage object name), `originalFilename` → `filename`
      (human capture name). Leave `deviceId`, `assetId`, `creationDate`, `role`, `contentType`
      unchanged. The byte-identical skip comparison then re-PUTs the manifest once on the new build.
- [x] 3.2 Update any shared-module manifest model/serialization + its tests (`commonTest`, so they run
      on JVM and `iosSimulatorArm64`) to the new field names; verify
      `./gradlew compileIosMainKotlinMetadata` (the Linux iOS proxy) and `./gradlew build` stay green.

## 4. Checks & docs

- [x] 4.1 `cd backend && deno task test`, `deno lint`, `deno fmt --check`, and `deno check src/*.ts`
      all green (the deploy-gate set).
- [x] 4.2 Update `docs/design.md` §3.5: the event-wide union is now an **edge** read
      (`GET /event/<id>/files`, complete-only, foreign-inclusive, identity-blind), reversing the
      "external/admin-direct" deferral; note the `device.json` `key`/`filename` field names. Keep
      design.md from contradicting code.
- [ ] 4.3 Branch → PR → `/ship` (the single `createApp` app deploys to both targets via the existing
      `backend-deploy.yml`; no CI/deploy/config change).
- [ ] 4.4 Post-merge (manual, optional): `curl` the deployed `GET /event/<id>/files` for an event with
      a known second contributing device and confirm the complete-only union matches the bunny zone —
      the first real-bunny verification of the union response shape.
