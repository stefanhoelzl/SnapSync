## 1. Prerequisites — not code, but nothing below is testable without them

- [x] 1.1 `restore-upload-url-base` has merged (`1b1597bc`) — the extension registers again, so the byte-upload half of this change is verifiable on device
- [x] 1.2 Run the destination round-trip probe on device (`scratchpad/probe.patch`, `ios-device` + `rig-channel`): create a job with a query-bearing destination and a custom header, and on the ack path record `destination.URL.absoluteString`, `.path`, `.query`, `allHTTPHeaderFields` and `job.localIdentifier`
- [x] 1.3 From the same run, record whether the OS issues a preflight `OPTIONS` and whether it carries the composed headers — if it does not, stop and raise the `api-endpoints` preflight/version-gate contradiction as a backend prerequisite
- [x] 1.4 Write both results into `PROBE-FINDINGS.md` beside this change, in the house form (what was run, against what, when, what would falsify it)
- [x] 1.5 Verify SQLDelight's behaviour when a build opens a ledger at a **newer** schema version, so the rollback story is measured rather than assumed — measured, recorded in `PROBE-FINDINGS.md`: it opens as-is (no downgrade, no refusal) and old-shaped queries still read it, because the migration is additive+nullable and no statement uses `SELECT *`

## 2. The world harness learns v2 — ADDITIVE, lands green on its own, no production Kotlin

The mini-edge serves **both** versions side by side, exactly as the real backend does. Replacing the v1
routes here would break every world and integration test in the same commit, because the client seams do
not move until groups 5-6 — which is precisely what this step exists to avoid.

- [x] 2.1 Split a leading `/api/vN` off the request path in the mini-edge, defaulting to v1 when absent, mirroring the backend's own `splitVersion`; the world's host stays unversioned and a v2 world is built by pointing its host at the v2 prefix
- [x] 2.2 Add the v2 join route: a bodyless `PUT /events/<id>/devices/<id>` that creates or reactivates the membership, writes no manifest, and refuses at capacity
- [x] 2.3 Add the v2 manifest route `PUT /events/<id>/devices/<id>/manifest`: replaces the asset set only, leaves membership state untouched, and refuses a publish from a non-member
- [x] 2.4 Leave the v1 route and `putManifest` exactly as they are — v1 is frozen and its publish really does reactivate, and `putManifest` is also the foreign-device injection helper, not a route
- [x] 2.5 Answer the per-device listing in identity terms under v2 (`assetId`, `role`, `filename`, no `url`), keeping the v1 shape under v1
- [x] 2.6 Add the version gate as an operator lever, default off: when armed, a v2 request with no app-version header or one below the minimum is refused `426` carrying the minimum
- [x] 2.7 Cover the additions in the world's own tests: a bodyless join enrols, a v2 manifest from a non-member is refused, a v2 manifest does not reactivate a departed member, and the v1 route still behaves as before
- [x] 2.8 ~~Delete the v1 routes from the mini-edge once no client seam targets them~~ — **done differently, and the difference is the point.** No client seam targets them, but the mini-edge models the BACKEND, and the real backend still mounts `/api/v1` for the installed base (`api/src/app.ts`'s `v1Only` router). Deleting the world's copy would make it model something that does not exist, and would delete four deliberate guards that now depend on the frozen shape being reachable: the strict-decode rejection, the version gate's v1 scoping, the reactivation contrast, and the v1 listing shape itself. What WAS scaffolding is the unversioned default — an absent prefix was served as v1 so the not-yet-moved seams kept working. That is now removed: an unversioned path 404s, exactly as on the real backend, so a seam built with a prefix-less base can no longer pass here and 404 in production. The v1 host is explicit at the three call sites that want it

## 3. Request composition

- [x] 3.1 Add the app-version header at the shared HTTP client's interceptor, beside the existing `401` handling, so every metadata seam and the attest bootstrap inherit it
- [x] 3.2 Add a marketing-version accessor beside `appBuildVersion()` in `:adapter:ios:ext-safe` (both processes read their own bundle); keep the absent-key decision out of `:app:*`
- [x] 3.3 Compose the v2 byte destination in `EdgeUploadRequestProvider`: identity in the path, capture name as the mandatory `filename` query, falling back to `resource.filename` when metadata is empty
- [x] 3.4 Add the app-version header to the composed byte request — the OS performs it, so the client's header cannot reach it
- [x] 3.5 Extend `EdgeUploadRequestProviderTest` for the new shape, the encoding of each segment and the query, and the metadata-empty fallback resolving to a byte-identical object name

## 4. Key recovery on the OS-driven tier

- [x] 4.1 Add the nullable `destinationPath` column to `ledgerRow` with a SQLDelight migration; keep the key unchanged
- [x] 4.2 Record the destination path in the same write that records a row as requested
- [x] 4.3 Add the `LedgerStore` read that resolves a row by destination path, and implement it in the SQLDelight store and the in-memory fake
- [x] 4.4 Resolve a returned `PHAssetResourceUploadJob` by destination path in `PhotoKitJobMapping`, falling back to the last-path-component recovery when no row matches
- [x] 4.5 Count unrecoverable jobs per cycle and report them at `Error`; keep acknowledging every presented job
- [x] 4.6 Re-pin `PhotoKitJobMappingTest` on v2-shaped destinations, and add cases for the v1-shaped fallback and for the unrecoverable-job report
- [x] 4.7 Extend the ledger store contract tests in `:test:world` for the new column and read

## 5. Join splits from the manifest

- [x] 5.1 Split the `Enrollment` port into a bodyless join and a manifest publish
- [x] 5.2 Reduce `ManifestDeviceEnroller` to a join call — no empty manifest, and drop its `DeviceManifestStore` collaborator and the `clearLastUploaded()` invalidation
- [x] 5.3 Split `HttpEnrollment` to the two routes, and surface the capacity refusal distinguishably from an absent event and from a transport failure
- [x] 5.4a Carry the capacity refusal through the seam and `JoinEvent` — `DeviceEnroller` answers `JoinResult`, `JoinOutcome` gains `EventFull`
- [x] 5.4b Surface it on the join screen — the composition still reduces the outcome to a Boolean; widening that is presentation work and lands with group 8, which reshapes the same surface
- [x] 5.5 Point `DeviceManifestProducer` at the manifest sub-resource
- [x] 5.6 Update the membership tests for the split, including that a rejoin leaves the asset set intact and correctly skips an unchanged republish

## 6. The per-device listing

- [x] 6.1 Decode the listing strictly in `HttpDeviceFilesSource`: require `assetId`, `role` (as the `ResourceRole` enum) and `filename`
- [x] 6.2 Recompose the storage key through `uploadKey`, keeping the seam's key-shaped contract
- [x] 6.3 Distinguish a decode failure from a transport failure in the seam's failure result
- [x] 6.4 Report a decode failure at `Error` in `ExtensionReconciler` and defer without treating it as transient
- [x] 6.5 ~~Seed rows from the listing's reported `assetId` rather than re-parsing the recomposed key~~ — **nothing to do, and the reason is worth keeping**: once 6.2 recomposes the key through `uploadKey`, `assetIdFromUploadKey` on that key returns the reported `assetId` *by construction* (the role token carries no `-`, and a normalised `assetId` carries no `.`). Widening the port to carry the pair would buy a distinction that cannot differ. The task was written before the recomposition was settled
- [x] 6.6 Test: a v1-shaped response fails to decode rather than seeding capture names as keys

## 7. The notify disappears

- [x] 7.1 Delete `EventNotifier` and its `PushHttpClient` usage
- [x] 7.2 Remove the `onBatchUploaded` seam from `UploadPorts` and both composition roots, and from the world
- [x] 7.3 Remove the notify leg from `UploadCycle.publishManifestAndNotify`, keeping the promotion pass and its ordering against the manifest write
- [x] 7.4 Update the tests that asserted the notify trigger; assert instead that a publish occurs and no notify request is made

## 8. The `426` client half

- [x] 8.1 Detect `426` in the shared client's interceptor and parse `minAppVersion` from the body
- [x] 8.2 Add the read-model that owns the refusal, set from the interceptor and cleared on the next successful response
- [x] 8.3 Add the top-level `UiState` case carrying the minimum version and the App Store URL
- [x] 8.4 Render the state in `:ui:screens` from `App*` components only, offering the store link
- [x] 8.5 Observe the read-model in `StatusContainerHost` — a read, so it does not cross `flow/`
- [x] 8.6 Report the refusal at `Error` so it reaches crash reporting
- [x] 8.7 Integration test over the world: a `426` drives the app into the update-required state, and a success clears it

## 9. `appStoreUrl` becomes a shared, correct value

- [x] 9.1 Correct the value in `deployments/components/apple.json` to the form that resolves, with a comment recording that the country-less form 404s while availability is limited
- [x] 9.2 Widen its projection to `[JSON, SITE, PLIST]` in the resolver inventory and render it into the plist
- [x] 9.3 Make `site/src/components/AppStoreButton.astro` read it from `site/src/deployment.json` instead of hardcoding
- [x] 9.4 Read it on the device beside `bakedUploadBase()` and feed it to the update state
- [x] 9.5 Extend the resolver test for the new projection

## 10. Version floor and the gate's honesty

- [x] 10.1 Set `MIN_APP_VERSION = "0.4"` in `api/src/config.ts` and update its pinning test
- [x] 10.2 Raise `Config.xcconfig`'s `MARKETING_VERSION` floor to `0.4`, and rewrite its comment: the floor is what dev and sideload builds carry, and it must stay at or above the minimum
- [x] 10.3 Assert `MIN_APP_VERSION <= floor` in CI — against the floor, not the computed release version. **Not in `api-deploy.yml` as written**: that workflow is path-filtered (an `iosApp/**` change never triggers it) and runs after merge. It is `api/test/min-app-version-floor.test.ts`, which `api.yml` runs on every ref with no path filter as a required check, so it fails in review whichever of the two files moved (verified to fail on a deliberate 0.5)
- [x] 10.4 Note in `diagnostic-logging`'s boot-banner expectations that a dev build's version now trails released builds

## 11. The baked base and the tooling that names it

- [x] 11.1 Render `uploadBase` with the `/api/v2` prefix in `scripts/resolve-deployment.py`, and update `resolve_deployment_test.py`'s six assertions
- [x] 11.1a Move `BackgroundUploadURLBase` to the v2 base in **both** `Info.plist`s, in step with `uploadBase` — `assetsd` validates the registration against this key, and its matching rule (host, origin or prefix) is deliberately unasserted, so a base on v2 with the key still on v1 may register fine and have every upload refused, silently
- [x] 11.1b ~~Extend the post-archive agreement assertion~~ — **already shipped** by `restore-upload-url-base` (`ios.yml:316` asserts `INFOBASE = BASE`). What was missing is that it needs a Mac and a signed archive, so a half-move is reported hours later; added `test_both_info_plists_carry_the_same_version_as_the_generated_base` to `resolve_deployment_test.py` instead, which reads both committed literals against `DEVICE_API_PREFIX` in milliseconds on Linux (verified to fail on a deliberate half-move)
- [x] 11.2 Update `ios.yml`'s post-archive assertion to expect the v2 base, and fold the new readback entries into the one block rather than growing a second
- [x] 11.3 Update the printed hint in `api/src/dev/serve.ts`
- [x] 11.4 Widen `api/src/dev/fallback.ts`'s `CONFIG_ROUTE` to `/api/v\d+/devices/<id>` — a v1-pinned regex here silently returns a simulator to a permanent `401`
- [x] 11.5 Update the `ssh-mac-build`, `ios-simulator` and `local-backend` skill runbooks for the v2 base and the new dev-build version

## 12. Verification

- [x] 12.1 `./gradlew build` green, including the architecture guards and the complexity tiers
- [x] 12.2 `./gradlew compileIosMainKotlinMetadata` green — the Linux-runnable proxy for the iOS source sets
- [x] 12.3 `./gradlew architectureDiagrams` and commit, since flows and composition change
- [x] 12.4 `npx --yes @fission-ai/openspec@1.5.0 validate device-speaks-v2 --strict`
- [x] 12.5 On device: a fresh join uploads, the union lists the photos, and a rejoin re-uploads nothing
- [x] 12.6 On device: confirm the extension registers and the OS-driven tier completes a cycle end to end against a local backend
- [x] 12.7 Confirm no byte re-upload occurs across the v1→v2 crossing, since object names are unchanged
