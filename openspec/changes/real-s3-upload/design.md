## Context

The `ios-background-upload` slice shipped a working extension that discovers resources, drives the
engine, and creates system upload jobs — but against a **dummy** destination
(`DummyUploadRequestProvider` → `https://dummy.invalid/…`). The slice explicitly parked the real
provider behind a one-line swap. Meanwhile `s3-request-provider` delivered a complete, golden-tested
`S3UploadRequestProvider` (pure SigV4 query-presigned `PUT`, path-style, `UNSIGNED-PAYLOAD`), and
`deeplink-config` delivered the QR → Keychain config path. Nothing connects them, so no byte has
left the device.

Three constraints shape the implementation:

1. **`PHBackgroundResourceUploadExtension` validates the upload host at compile time.** Apple
   requires `BackgroundUploadURLBase` hardcoded in the extension `Info.plist`; the system only
   uploads to destinations under that host. A user-configurable upload host is not supported. (Today
   it is `https://dummy.invalid`.)
2. **Delivery is via a single archive, exported twice.** CI (`ios.yml`) archives once per push
   (the merge gate) and exports both a development IPA (every push, sideload artifact) and an App
   Store IPA (main → TestFlight) from that *same* archive. The baked `Info.plist` is therefore
   shared between the dev IPA and TestFlight within a run.
3. **On-device testing is the only way to exercise real upload.** The extension is
   physical-device-only on iOS 27; the dev sideload IPA (installed over usbmuxd) is the test
   vehicle.

## Goals / Non-Goals

**Goals:**
- Real presigned uploads end-to-end: discovered resource → `S3UploadRequestProvider` → system job →
  bytes in an S3 bucket.
- Verifiable on a physical device against a local MinIO server on the same LAN, with live terminal
  confirmation of each object that lands.
- Correct the config model so the upload host lives where iOS already forces it (compile time) and
  only genuinely-runtime fields (bucket/region/creds) travel in the QR.
- Keep product code paths identical between test and production (config always from Keychain; host
  always from `BackgroundUploadURLBase`).

**Non-Goals:**
- Completion/retry/failure adjudication — the ledger still stops at `REQUESTED` (drain-all kept).
  Success is observed in the bucket, not reduced into app state.
- Production S3 wiring (a real bucket/host for the shipping app) — deferred; `main`/TestFlight stays
  inert (`dummy.invalid`).
- Any in-app QR scanner or config UI change beyond dropping `endpoint` from the payload.

## Decisions

### D1: Host is compile-time (`BackgroundUploadURLBase`), everything else runtime (Keychain)
The host moves out of `S3ConfigPayload` and is read from the extension bundle's
`BackgroundUploadURLBase` (`NSBundle`), then combined with the Keychain payload to form `S3Config`
at the extension composition root. **Why:** iOS mandates a compile-time host regardless; carrying it
in the QR was redundant and could silently mismatch the baked value. **Alternatives:** (a) keep
`endpoint` in the QR and require it to equal the baked host — drift-prone, two sources of truth;
(b) bake the *entire* config into the IPA (no QR) — pulls a test-only branch into the config path
and bakes secrets into the artifact. Rejected in favor of the clean split.

### D2: `S3Config` and the presigner are untouched; only field provenance moves
`S3Config` stays a five-field value (`bucket, region, endpoint, accessKeyId, secretAccessKey`) and
`s3-request-provider` needs no change. A new `S3ConfigPayload` (four fields, no `endpoint`) is the
QR/Keychain/`ConfigSource` type; the composition root maps `payload + host → S3Config`. **Why:**
keeps the golden-tested provider contract stable and localizes the model change to `:capability:config`
+ the iOS composition root. **Payload version bumps `v=1 → v=2`** so an old five-key QR is rejected
rather than silently mis-decoded.

### D3: Bake the test host via an optional `workflow_dispatch` input, not a repo variable or commit
`ios.yml` gains `workflow_dispatch` with optional `upload_host`; the archive sets
`BACKGROUND_UPLOAD_URL_BASE=${{ inputs.upload_host || 'https://dummy.invalid' }}`. **Why:** a plain
push (incl. `main`) supplies no input → stays `dummy.invalid`, so TestFlight is never polluted and
no "don't-merge-this-commit" discipline is needed; the IP lives nowhere in the repo. **Alternatives:**
a GitHub repo variable gated off `main` (extra expression, persistent state) or a committed xcconfig
on the branch (visible but must never merge) — both heavier than a dispatch input. The
`Info.plist` value becomes `$(BACKGROUND_UPLOAD_URL_BASE)`, defaulted in `Config.xcconfig`.

### D4: HTTP to the local host via `NSAllowsLocalNetworking`, shipped in all configs
The extension (and app) ATS config gains `NSAllowsLocalNetworking`, which relaxes ATS only for
private/local/`.local` addresses; the public HTTPS endpoint is unaffected, so it is safe to ship and
survives the Release/TestFlight build path the dev IPA is exported from. **Alternative:** MinIO over
TLS with a device-trusted cert — fiddly for a throwaway rig; held as the fallback if the spike (below)
shows `BackgroundUploadURLBase` demands `https`.

### D5: Drain-all kept; success verified in the bucket, live
The extension still records only `REQUESTED` and acknowledges every job. **Why:** the ask is "mint
real URLs," not "implement sync completion semantics." With real uploads, the confirmation signal is
the object arriving — surfaced live by the test rig (`mc watch`), which is a better positive signal
than an app-state transition would be here. Completion/retry is its own future slice.

### D6: Local S3 test rig = MinIO via podman, one script, terminal-first
`scripts/local-s3.sh`: start MinIO (`--network host` so it binds the LAN IP), create the bucket
(ephemeral, fresh each run), auto-detect the LAN IP, print the host to pass as `upload_host`, render
the deeplink QR to the terminal (Unicode half-blocks) + PNG fallback, then `mc watch --recursive`
to stream each uploaded object. **Why MinIO:** it actually *validates* SigV4 presigned `PUT`s (a
403 on a mis-signed URL is real end-to-end proof the presigner is correct on the wire), and its
object browser/`mc` confirm arrivals. The community admin-UI removal does not affect the server or
presigned support. **Why not** a mock (adobe/S3Mock) — it ignores signatures, giving false
confidence; **why not** SeaweedFS/Garage — known flaky presigned-PUT support. This rig is test
equipment: no spec, no `SHALL`.

### D7: App primes Local Network permission at launch
A background extension cannot present the iOS Local Network prompt. The host app, at launch with a
payload present, fires one throwaway request at the `BackgroundUploadURLBase` host so the app-wide
grant exists before the extension runs. Harmless against public HTTPS (no LN permission applies).
Thin `NSURLSession` glue in `:app:ios`.

## Risks / Trade-offs

- **`BackgroundUploadURLBase` may demand `https`, or reject a bare IP / port.** → Spike it first:
  build a dev IPA via dispatch with `upload_host=http://<lan-ip>:9000`, install, watch
  `idevicesyslog` for a host-validation rejection. If it fails on `http`/IP, fall back to MinIO-over-TLS
  (D4 alternative). This is task 1 and gates the rest of the on-device verification.
- **Local Network prompt may not actually apply** (the PUT is likely performed by `nsurlsessiond`, a
  system daemon, not the extension process). → D7 priming is cheap and defensive; if the spike shows
  it is unnecessary it can be dropped without affecting the contract.
- **`v=2` payload breaks any previously-generated `v=1` QR.** → Acceptable: no production users; all
  QRs are regenerated from the updated `generateConfigQr`. The decoder rejects `v=1` cleanly.
- **Baked host vs runtime bucket coupling.** The bucket is a path segment under the host, so host
  validation (host-level) tolerates a runtime bucket — but if `BackgroundUploadURLBase` turns out to
  prefix-match including the path, the bucket may also need to be compile-time. → Surfaced by the
  same spike; falls out of watching whether the job is accepted.
- **Drain-all means the app status screen never reaches "backed up"** for these uploads (stuck at
  REQUESTED). → Expected for this slice; verification is the bucket stream, documented in the rig.

## Migration Plan

1. Spike `BackgroundUploadURLBase` acceptance on device (gates D4).
2. Land the config-model change (`deeplink-config`: `S3ConfigPayload`, `v=2`, codec/tests, generator)
   — pure Kotlin, verifiable on JVM + simulator via `commonTest`.
3. Land the extension wiring (real provider, host-from-bundle, skip-when-absent) + module deps;
   verify `compileIosMainKotlinMetadata` (Linux proxy) and simulator tests.
4. Land the iOS project changes (`Info.plist` `$(BACKGROUND_UPLOAD_URL_BASE)` + ATS, `Config.xcconfig`
   default, app priming) and the `ios.yml` `workflow_dispatch` input.
5. Add the test rig (`scripts/local-s3.sh`, terminal-QR rendering).
6. On-device verify: `local-s3.sh` → dispatch a dev IPA with the printed host → install → scan QR →
   watch objects land. **Rollback:** the change is inert until a dispatch supplies a host and a QR is
   scanned; reverting the branch restores the dummy provider.

## Open Questions

- Does `BackgroundUploadURLBase` accept `http://` + bare IP + port (the spike). If not → TLS.
- Is the app-launch Local Network priming actually required, or does the system daemon sidestep it?
  (Resolve during on-device verify; drop the priming requirement if moot.)
