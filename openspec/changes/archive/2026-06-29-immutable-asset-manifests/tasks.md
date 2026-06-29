## 1. Resource model — original-only, generic roles

- [x] 1.1 In `:domain:gallery` `UploadKeys`, replace the iOS-kind key scheme with role-based keys `"<assetId>-<role>.<ext>"`; add the `PHAssetResourceType` → role map (`photo`/`video`/`audio` → `primary`, `pairedVideo` → `motion`) and treat every other type (full-size renders, adjustment data, adjustment-base media, RAW `alternatePhoto`, proxies) as **dropped**.
- [x] 1.2 Update the iOS `PhotoLibraryResourceEnumerator` to fan each asset out to its **original** resources only (one `primary`, at most one `motion`), excluding all dropped types.
- [x] 1.3 `commonTest` (runs JVM + `iosSimulatorArm64`): role-key derivation for primary/motion, and a table asserting each `PHAssetResourceType` maps to `primary`/`motion`/dropped; assert an edited asset yields only originals.

## 2. Manifest format (`asset-manifest`)

- [x] 2.1 Add a platform-neutral manifest model in `commonMain` (`version`, `assetId`, `creationDate`, `resources[]{role, contentType, filename, originalFilename}`) with JSON serialization to/from the `<assetId>.manifest.json` shape.
- [x] 2.2 Add iOS manifest synthesis: build a manifest from a `PHAsset` and its selected originals — `creationDate` from the asset, and per resource its `role`, `contentType` (UTI→MIME), `filename` (matching 1.1), and `originalFilename`.
- [x] 2.3 `commonTest`: manifest serializes with exactly the v1 fields (no subtypes/location/flags/dims), `resources` non-empty, roles constrained to `primary`/`motion`; JSON round-trips.

## 3. Manifest side-channel upload (extension + app)

- [x] 3.1 **Spike first (gates the rest):** confirmed on the connected device — a Photos background-upload extension can enqueue a background `URLSession` upload whose completion is delivered to the **containing app**, and the object lands in the storage zone. The app-fallback path is not needed.
- [x] 3.2 Extension — first discovery of an asset: synthesize its manifest JSON, write it **PENDING** to the App Group, set `taskDescription = assetId`, and enqueue one background `URLSession` `uploadTask` to `<host>/event/<eventId>/file/<assetId>.manifest.json` with `Content-Type: application/json`; **not** via `SyncEngine`/`createJob`/the ledger.
- [x] 3.3 Extension — re-discovery: skip **DONE** manifests; for a **PENDING** manifest with no in-flight task on its session, re-enqueue exactly one (resurrect a stalled upload), using only local task state.
- [x] 3.4 App — own the shared background session via `handleEventsForBackgroundURLSession`: on success mark the manifest **DONE**, on failure re-enqueue with backoff; map task→asset via `taskDescription`.

## 4. Backend list endpoint — completeness from manifests

- [x] 4.1 Discover the event's objects with a single directory LIST and identify manifest objects (`<assetId>.manifest.json`).
- [x] 4.2 Read each manifest's content and compute completeness: include an asset only when every resource it names is present; omit assets with a missing named resource, orphan resources without a manifest, or an unparseable/absent manifest.
- [x] 4.3 Emit the asset entry shape `{ assetId, creationDate, resources[]{ role, filename, contentType, originalFilename, url } }` (url per `bunny-download-endpoint`), closed field sets.
- [x] 4.4 Faithful outcome: LIST or manifest-read **transport** failures → `5xx` (no partial array); a malformed manifest omits only its asset and still returns `2xx`.
- [x] 4.5 Backend tests: all-present→included, missing-resource→omitted, orphan-without-manifest→omitted, malformed-manifest→omitted+2xx, transport-failure→5xx, created-but-empty→`[]`, filename round-trip.

## 5. Rejoin reconciliation

- [x] 5.1 Change the `EventFilesSource` seam (and its iOS HTTP + fake impls) to return **complete assets** (each with `assetId` and `resources[].filename`).
- [x] 5.2 Seed `COMPLETED` via `resetTo` for every resource of each listed complete asset (key = `filename`, carrying `assetId`); do not seed assets absent from the listing; clear the discovery cursor; producer disabled during the seed.
- [x] 5.3 `commonTest`: complete-asset resources seeded; absent/partial asset not seeded (and re-uploads); cursor cleared; a seeded resource yields `AlreadyUploaded` from the producer.

## 6. Docs

- [x] 6.1 Update `docs/design.md`: reverse the "full resource fidelity" decision; record immutable original-only resources, the per-asset manifest, read-time completeness in the list endpoint, and the Change 2 decoupling follow-on.

## 7. Verification

- [x] 7.1 `./gradlew build` green (compiles all targets + JVM/Compose tests, incl. new `commonTest`s).
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` green (iOS source-set proxy).
- [x] 7.3 Backend test suite green.
