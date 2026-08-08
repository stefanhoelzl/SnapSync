## 1. Send the MIME type (already landed in `f7cc4879`)

- [x] 1.1 `EdgeUploadRequestProvider.provide` sets `Content-Type` from `metadata[RESOURCE_META_MIME]`, treating blank as absent, falling back to `resource.contentType`
- [x] 1.2 Test: a resource with `RESOURCE_META_MIME = image/jpeg` and `contentType = public.jpeg` yields `Content-Type: image/jpeg`
- [x] 1.3 Test: a resource with absent or blank MIME metadata falls back to `resource.contentType` (the retry path's shape)

## 2. Recover the type on the PhotoKit tier (already landed in `f7cc4879`)

- [x] 2.1 `IosPhotoKitUploadPlatform` reads `Content-Type` from `job.destination.allHTTPHeaderFields`, case-insensitively, blank treated as absent
- [x] 2.2 `PlatformUploadJob.contentType` falls back to `resource?.uniformTypeIdentifier` then `application/octet-stream`

## 3. Recover the type on the app-driven tier (outstanding)

- [x] 3.1 Record each task's request `Content-Type` in `IosUrlSessionUploadPlatform`'s in-flight task record at `createJob`
- [x] 3.2 Surface that type on the `PlatformUploadJob` returned by `fetchAckJobs()`, replacing the hardcoded `application/octet-stream` at both construction sites (the delegate-completion job and the stranded-task job)
- [x] 3.3 Decide and document what a **stranded** task reports — its request is gone, so there is no recorded type to recover; state the fallback rather than letting it default silently
- [x] 3.4 Verify `./gradlew compileIosMainKotlinMetadata` passes (the Linux-runnable proxy; this adapter is iOS-only and otherwise unbuildable here)

## 4. Verification

- [x] 4.1 `./gradlew build` green (architecture guards, shell gates, tests, diagrams)
- [x] 4.2 On device, confirm a first-attempt upload arrives at the origin with a real MIME `Content-Type` (the local rig's request log is the oracle; previously observed `public.jpeg`)
- [x] 4.3 On device, force a first-attempt failure and confirm the **retry** stores the same MIME rather than `application/octet-stream` (previously all 10 objects of such a run were mistyped)
- [x] 4.4 Exercise 4.2–4.3 on the app-driven tier too, via `SNAPSYNC_FORCE_URLSESSION_UPLOAD=1` — noting the extension must be deregistered first, or it uploads behind that tier's back

## 5. Close-out

- [x] 5.1 Confirm no repair of already-stored objects is being attempted (design decision D4) and that the `web-event-download` open question is recorded rather than silently dropped
- [x] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes
