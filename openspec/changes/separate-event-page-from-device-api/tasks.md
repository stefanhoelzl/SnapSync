# Tasks

One PR. Each numbered group below is one commit, in this order (design.md, Migration Plan).

## 1. The web surface (commit 1)

- [x] 1.1 Give `presignDownloadUrl` (`api/src/routes/support.ts`) an expiry parameter. The device routes keep
  `PRESIGN_EXPIRY_SECONDS`, and a new `WEB_PRESIGN_EXPIRY_SECONDS = 3600` is added. Verify: `cd api && deno task test`
  passes unchanged, and a new unit case asserts `X-Amz-Expires=3600` on a web-signed URL.
- [x] 1.2 Extract the union's row assembly and completeness filter (group by device/asset, drop assets with
  an unrecorded resource) from `GET /events/:eventId/files` into a shared function. Verify:
  `union-read.test.ts` and `union-log.test.ts` pass unchanged.
- [x] 1.3 Add `GET|HEAD /web/events/:eventId/photos` beside the site routes. It answers the page's DTO
  `[{deviceId, assetId, resources:[{role, filename, url}]}]` with 1-hour presigns: `NO_CACHE`, `404` for an
  absent or malformed id, `502` on a read failure. It ignores `Authorization` and logs one anonymous read
  (`deviceId = null`, no trigger). Verify with a new `api/test/web-photos.test.ts`:
  - the answer's shape;
  - an incomplete asset is dropped;
  - a departed member's photos are included;
  - a withdrawn photo is absent;
  - the presign expiry;
  - a bogus bearer token is still `200`;
  - one anonymous `union_fetches` row per read;
  - `404`/`502`;
  - HEAD has no body.
- [x] 1.4 List `^/web/events/[^/]+/photos$` (GET/HEAD) in the token gate's root-route set in `api/src/app.ts`,
  with its comment (a browser surface authorized by event-id possession; `changes/separate-event-page-from-device-api`).
  Verify in `attest.test.ts`:
  - GET/HEAD pass with no token;
  - POST/PUT/DELETE on the path and any deeper path stay `401`.
- [x] 1.5 Fill `%%KEY_ID%%` in `eventFilling` (the event's key id, escaped) and fill it empty in the `pending` and
  `invalid` fillings. Add `data-key-id="%%KEY_ID%%"` to `join.astro`'s root. Verify:
  - `event-page.test.ts` covers an encrypted event, an unencrypted one and the two generic fillings;
  - `site/scripts/check-event-template.ts` accepts the template (`cd site && npm run check`).
- [x] 1.6 Switch `join.astro`:
  - read `data-key-id` instead of fetching `/api/v1/events/<id>`; a value of the wrong shape counts as
    "couldn't load", never as unencrypted;
  - fetch `/web/events/<id>/photos`;
  - delete `withDownloadUrls` and its tests;
  - on a `403` photo GET, re-read the list once, continue by `(deviceId, assetId, role)`, and fail as today
    on a second lapse or a missing item.

  Verify: `cd site && npm run check` passes, the re-read rule is unit-tested in a pure helper beside
  `zip-names.ts`, and `grep -rn "/api/" site/src` prints nothing.
- [x] 1.7 Drive the page against the local backend (`local-backend` skill, `deno task dev:local`) with an
  unencrypted and an encrypted event. Verify the zip contents match the shared photos and the browser's
  network log shows no `/api/` request.
- [x] 1.8 Document the web surface in `docs/architecture.md`: the route table, the gate's closed list, and the
  1-hour presign beside the 7-day one. Verify that the doc names the decision record, and that
  `./gradlew :tools:diagrams:test` (architecture freshness) passes.

## 2. Retire v1 (commit 2)

- [x] 2.1 Replace the `/api/v1` mount with one middleware, placed with the version gate, that answers every
  `/api/v1/*` request `426 {error:"app too old", minAppVersion}` with `NO_CACHE`. Verify with a test that
  GET `/api/v1/events/<existing id>`, PUT on a v1 byte path and POST `/api/v1/attest/token` all answer `426`,
  never `404`.
- [x] 2.2 Delete `api/src/routes/v1.ts` and `api/test/v1.test.ts`. Remove `attestRoutes`' v1 variant (the flat
  body, `401` on a stale challenge), the union's `version === 1` presign branch, and `db.ts`'s `legacy` publish
  mode and its resource upsert. Narrow `ApiVersion` to `2`. Verify: `cd api && deno task check && deno task
  test && deno lint` pass, with every v1 case removed or re-expressed on v2 in `attest.test.ts`, `app.test.ts`,
  `version.test.ts`, `app-version.test.ts`, `union-read.test.ts`, `encrypted-upload.test.ts`, `fcm.test.ts`
  and `dev/fallback.test.ts`.
- [x] 2.3 Move `legacyKeyFor` to `api/src/object-names.ts` (with its tests), delete `identityFromLegacyKey`, then
  delete `legacy-v1.ts` and `legacy-v1.test.ts`. Verify: `grep -rn "legacy-v1" api/` prints nothing, and the
  api tests pass.
- [ ] 2.4 Sweep the remaining v1 references: `api/src/dev/serve.ts`, `api/src/dev/fallback.ts`, `api/src/db.ts`
  comments, `docs/architecture.md`'s "v1 (frozen) differences" section and v1 mentions,
  `scripts/resolve-deployment.py`'s comment, `.claude/skills/{ssh-mac-build,snapsync-device}/SKILL.md`,
  `app/ios/CLAUDE.md`, and the app tests that only use `/api/v1` as an example path
  (`DarwinHttpClientTest`, `GatedPathPinTest`). Verify: `grep -rn "api/v1" --exclude-dir=archive .` prints only
  intentional mentions (`UploadJobDecisionsTest`'s retired-destination case and the `426` test), and
  `./gradlew build` passes.

## 3. The app sends its token on the event read (commit 3)

- [ ] 3.1 Server: `GET /events/:eventId` checks an optional bearer token as the union does (`401` for a bad
  one, anonymous when absent). Verify: new cases in `v2.test.ts` (valid token `200`, bad token `401`, none
  `200`).
- [ ] 3.2 Port and adapter: `Backend.getEvent(token: String?, eventId)`. `HttpBackend` sends it, and
  `verifiesToken` includes the event read (`GatedPaths.kt`). Verify: `HttpBackendTest`'s token pin and
  `GatedPathPinTest` cover the event read.
- [ ] 3.3 Service: `CredentialedBackend.getEvent` becomes `gated { … }`. Update `BackendMock` (a sent token
  must verify) and the Backend contract (a clause: a bad token on the event read is refused; none is
  served). Verify: `./gradlew build` passes, including `:adapter:generic:app:jvmTest` (the contract against the
  real api/) and the mock's contract binding.
- [ ] 3.4 Check the join preview on the JVM rig host (`rig-channel` skill, `-Psnapsync.rigBackend=deno`): a
  fresh install opening an invite attests, then shows the preview. Verify by reading `UiState` and the
  host's `/device/os-record`.

## 4. Integration

- [ ] 4.1 `npx --yes @fission-ai/openspec@1.13.2 validate separate-event-page-from-device-api --strict` passes, and
  `./gradlew build`, `cd api && deno task test`, `cd site && npm run check` all pass on the PR's head.
- [ ] 4.2 After merge and deploy, open a real event's page on the deployed site. Verify the download works and
  `curl -si https://<host>/api/v1/events/<id>` answers `426`.
