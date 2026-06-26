## Context

v1 ships the device a full AWS credential (via the QR `v=2` payload) and a hand-rolled SigV4
presigner that signs each upload on-device. docs/design.md §4 already retires this model in favour of
a credential-free **edge proxy**: the device PUTs bytes to a deterministic per-resource URL and the
external endpoint (`bunny-upload-endpoint`, Deno + Hono on bunny.net Edge Scripting) streams them into
bunny Storage with the zone `AccessKey` that never leaves the edge.

The backend is **already built and deployed live** at `https://snap-sync-n8xmz.bunny.run`. Probing it
confirms the script validates as specified: `PUT` with a non-UUID `eventId` → `400`, traversal
filename → `400`, `POST`/`HEAD` → `404`. One environmental finding: a **BunnyCDN pull zone fronts the
script** and answers/caches `OPTIONS` itself (generic `200` + CDN CORS), so the script's own
non-resumable `OPTIONS` handler is shadowed in production. `PUT`/`POST`/`HEAD` pass through to origin.

The engine seam is already backend-agnostic: `SyncEngine(provider: UploadRequestProvider, …)`. Only
the iOS extension instantiates the real provider; the desktop harness and `:test:integration` use
fakes. So the swap is localized to the provider, the config payload, the extension composition, and CI.

This change is deliberately the **minimal** pivot. Two design.md features are descoped for v1 of the
migration (and design.md will be edited to record it): the QR's `name`/`startDate` fields and
**date-filtered discovery**. Discovery stays whole-library; everything captured uploads under the
event/device prefix.

## Goals / Non-Goals

**Goals:**
- Device holds **no storage credential** and performs **no signing**; uploads go through the edge proxy.
- Provider becomes a pure, network-free, crypto-free **local URL builder**, fully tested in `commonTest`.
- QR/deeplink carries only `{eventId}` (`v=3`), UUID-validated at scan; `v=1`/`v=2` rejected.
- `deviceId` is a stable, always-available canonical UUID scoping this device's uploads.
- `main`/TestFlight builds target the live edge endpoint (credential-free makes this safe).
- Delete the dead S3/SigV4 surface; keep design.md the source of truth.

**Non-Goals:**
- Date-filtered discovery and the `name`/`startDate` payload fields (deferred; design.md updated).
- Any change to the engine, ledger, status projection, or UI seams (backend swap only).
- Any change to the deployed backend (already live and verified).
- Abuse protection / overwrite rejection / rate-limiting at the edge (design.md §8, deferred).

## Decisions

**1. `deviceId` = lazily-minted UUID persisted in the App Group (not `identifierForVendor`).**
The consumer is a background extension that runs while the device is locked, possibly right after a
reboot. `identifierForVendor` is `nil` in the post-reboot/pre-first-unlock window and requires a
UIKit call in a non-UI Photos extension — exactly when background upload should be working. A UUID
minted with Foundation `NSUUID` and persisted to the App-Group `NSUserDefaults` suite
(`group.app.snapsync`, already used for the ledger + discovery cursor) is **never nil**, needs no
UIKit, and is a canonical lowercase UUID the edge validator accepts. Lazy-minting *inside the
extension* (read-or-mint-and-persist) avoids any app↔extension coordination. *Alternatives:*
`identifierForVendor` (rejected — nil window + UIKit dependency in the background extension); minting
in the app at first launch (rejected — needs the app to have run before the extension; lazy-in-extension
is strictly more robust). Both reset on uninstall, which is accepted for a personal one-way backup.

**2. QR payload `{eventId}` only; `name`/`startDate` and date-filtering deferred.** The minimal pivot
that gets uploads through the proxy. `startDate` is load-bearing *only* for date-filtered discovery;
dropping the filter drops the field. This diverges from design.md §3.2/§4, so design.md is edited in
this change to keep the doc authoritative. *Alternative:* implement the full event model now (rejected
by explicit scope choice — ship the backend swap first, add filtering as a follow-up).

**3. Reject `v=2` rather than migrate old Keychain configs.** An upgraded device with a stale S3
config in its Keychain fails to decode → `config == null` → the setup gate shows "not joined" → the
user rescans the new event QR (which is `v=3`). The version reject **is** the migration — no
migration code, no dual-format decoder. Clean because re-provisioning already clears the ledger/cursor.

**4. New `:capability:upload-url`; delete `:capability:s3`.** With no SigV4, the provider is pure
string-building, but it still owns the deterministic+injective filename→URL mapping (the idempotency
anchor) and the percent-encoder. A dedicated capability mirrors the existing structure and keeps the
encoder reusable. `:capability:s3` (SigV4 presigner, golden tests, `S3Config`, `S3ConfigPayload`) is
deleted — a personal v1 app keeps no dead backend; git history preserves it. *Alternative:* fold the
builder into `:capability:config` (rejected — keeps provider/seam separation consistent with v1).

**5. Provider takes plain-string `host`/`eventId`/`deviceId`.** Keeps `EdgeUploadRequestProvider` in
`commonMain` and fully unit-testable on JVM + simulator. Sourcing those strings (host from the bundle,
eventId from the Keychain, deviceId from the App Group) is the extension composition root's job —
iosMain wiring, untested by rule.

**6. Bake the real edge URL for all builds; keep the `upload_host` dispatch override.** Credential-free
device + production edge endpoint make baking the live URL into TestFlight safe (unlike S3, where the
inert `dummy.invalid` default protected against shipping a usable creds-less host). The dispatch
override is retained for pointing a dev IPA at a local Deno backend on the LAN.

**7. `Content-Type` only; no metadata headers.** bunny's native Storage API has no custom-metadata
channel, and iOS resources already carry empty metadata. Downstream reconstruction reads identity from
the key path and metadata from the image's own EXIF (design.md §3.5).

## Risks / Trade-offs

- **CDN owns `OPTIONS`; iOS may send a resumable preflight** → If iOS's background upload sends an
  `OPTIONS` resumable-upload preflight, it hits the CDN's generic `200` (no resumable-protocol
  headers), which *should* make iOS fall back to a plain single-shot PUT — but if iOS reads the
  `200`+CORS as "resumable supported" and starts a handshake the CDN/script don't implement, uploads
  fail. **Mitigation:** this is the #1 on-device verification task (watch `idevicesyslog` / the edge
  request log for a plain PUT vs. a resumable attempt). Cannot be settled without a device; the
  backend behaves correctly for plain PUT, which is what raw S3 used in v1.
- **`deviceId` rotates on reinstall** → Post-reinstall uploads land under a new `device/<uuid>/` folder
  and (ledger wiped too) re-upload everything there. **Mitigation:** accepted for a personal one-way
  backup; downstream grouping is by `<deviceId>` so it's additive, not corrupting.
- **Baking the live URL means TestFlight uploads hit real storage** → A TestFlight tester syncing
  writes real objects to the zone. **Mitigation:** intended; abuse protection is deferred (§8); the
  eventId is the only capability and is high-entropy.
- **design.md divergence** → Shipping eventId-only while the doc describes `{eventId,name,startDate}`
  + date-filtering would leave the source of truth contradicting code. **Mitigation:** design.md
  §3.2/§4 is edited in this change to record the deferral.

## Migration Plan

1. Land `:capability:upload-url` (`EdgeUploadRequestProvider` + encoder) with `commonTest`.
2. Reshape `:capability:config` payload to `EventConfigPayload {eventId}` (`v=3`, UUID-validate,
   reject v1/v2); update the Keychain store and the QR-generator Gradle task.
3. Rewire `:app:ios:photokit-extension` composition: read eventId (Keychain) + host (bundle) +
   deviceId (App-Group lazy-mint), build the edge provider; add the App-Group device-id store.
4. Delete `:capability:s3`; fix `:app:desktop` canned config and any `:test:integration` references.
5. Update `.github/workflows/ios.yml` (default `BACKGROUND_UPLOAD_URL_BASE`, input description).
6. Edit `docs/design.md` §3.2/§4 to record the eventId-only / no-date-filter scoping and lazy-mint deviceId.
7. `./gradlew build` + `compileIosMainKotlinMetadata`; branch → PR → `/ship`.
8. **On-device verification** (post-merge, manual): sideload/TestFlight a build, scan a `v=3` test-event
   QR, trigger a sync, confirm objects land under `event/<id>/device/<id>/` in the zone and that iOS
   uses a plain PUT (the resumable-preflight check). **Rollback:** revert the PR; the deployed backend
   is unaffected.

## Open Questions

- Does iOS's background upload-job system send an `OPTIONS` resumable preflight to the edge, and does
  it fall back to plain PUT given the CDN's generic `200`? (On-device only — gates ship confidence.)
- Which exact `2xx` does the system treat as success? (Script returns bunny's confirmed `2xx`.)
- Edge reachability/latency from the background extension while the device is locked — acceptable?
- Backend cleanup (separate change): the script's `OPTIONS` handler is dead behind the CDN — worth
  removing or fronting differently? Out of scope here.
