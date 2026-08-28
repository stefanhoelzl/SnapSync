## Why

`/api/v2` has been served since `add-v2-device-api`, and **no shipped Kotlin speaks it** — the version is
baked into `BACKGROUND_UPLOAD_URL_BASE=…/api/v1` and no Kotlin composes an `/api/vN` path. Every later step
in that programme (the manifest declaring intent, pending as a set difference, ledger retirement) needs a
device on v2 first; none of them can begin against a surface that is frozen by the shipped install base.

The move also forces a debt to be paid. `min-app-version` shipped its **backend half only**: the gate
refuses a too-old build with an actionable `426`, and the device has nowhere to receive it. Today that is
harmless — `MIN_APP_VERSION` is `0.1` and every install speaks v1, which the gate exempts — but the first
build that speaks v2 is also the first build that can be refused, and with no client half a refusal
degrades to exactly what the capability exists to end: an app that quietly does not work.

## What Changes

**Transport — same semantics, new routes.**

- The baked device-facing base moves to `/api/v2`. v1 stays served, so a rollback is a rebuild with the
  base flipped back.
- The **app-version header** is sent on every request, from **two** places: the shared Ktor client (which
  covers every metadata seam and the attest bootstrap) and `EdgeUploadRequestProvider`, because the OS
  performs the byte `PUT` from a request we compose and it never passes through Ktor.
- The **byte destination** becomes identity-in-path plus a required capture-name query:
  `PUT /files/devices/<d>/<assetId>/<role>?filename=<name>`.
- **Join splits from the manifest.** `PUT /events/<e>/devices/<d>` enrols (and is the only route deciding
  capacity); `PUT /events/<e>/devices/<d>/manifest` publishes contribution only. The register-only empty
  manifest disappears, and with it `ManifestDeviceEnroller`'s dependency on `DeviceManifestStore`.
- The **per-device listing** answers in identity terms (`assetId`, `role`, `filename`); the client
  recomposes the storage key through the existing `uploadKey`.
- **`EventNotifier` is deleted.** v2 has no notify route — the fan-out is an effect of the manifest publish.

**Key recovery — the OS-driven tier's ledger bridge.**

- The ledger gains a **`destinationPath`** column, written by the existing `UploadStarted` write. A returned
  `PHAssetResourceUploadJob` is matched to its row by exact destination-path equality, with the pre-existing
  last-path-component recovery kept as a fallback for jobs created by the outgoing build.
- Under v2 the destination URL no longer ends in the ledger key, so without this every job's key would
  collapse to its role token — silently.

**Absence stops being silent** (three seams, one rule).

- A job whose row cannot be recovered is counted and reported at `Error`, not swallowed.
- The per-device listing distinguishes a **parse** failure from a **transport** failure: one is permanent
  and must be loud, the other is transient and must be retried.
- A `426` is never absorbed into a generic non-2xx.

**`min-app-version` gains its client half.**

- A `426` is detected once, beside the existing `401` handling in the shared client's interceptor, and
  surfaces as a new **top-level `UiState`** — an obsolete build can neither create, join, nor sync, so this
  supersedes the other states rather than sitting inside `SyncHealth`. It clears on the next successful
  response.
- `MIN_APP_VERSION` is raised to **`0.4`**, the first version that speaks v2. This refuses nothing that
  exists: v1 is exempt from the gate, so every current install is untouched.
- **`Config.xcconfig`'s `MARKETING_VERSION` floor is raised to `0.4` with it.** Dev and sideload builds bake
  the floor rather than the CI-computed version, so a minimum above the floor would refuse every rig build
  — including the ones used to test this change on device. CI asserts `MIN_APP_VERSION <= floor`, which makes
  that coupling explicit instead of tribal.

**`appStoreUrl` becomes a device-facing value, and is fixed.**

- The resolved value currently **404s**: `https://apps.apple.com/app/id…` resolves to a storefront the app
  is not in, so `GET /join` — the entire no-app fallback for someone scanning an event QR — redirects to a
  dead page. `site/` already worked around this with a hardcoded country-scoped URL; the backend's copy was
  never corrected.
- The value is fixed once and its projection widened from `[JSON]` to `[JSON, SITE, PLIST]`: the site reads
  it instead of hardcoding, and the device reads it from `Deployment.plist` so the update screen can offer a
  link rather than only a version number. It is a URL, so it must never reach the xcconfig.

**The world harness learns v2** — join route, manifest sub-path, identity-shaped listing, the version gate —
landed first as its own mechanical step, so the behaviour-changing diff is readable.

## Capabilities

### New Capabilities

None. The `426` client half extends `min-app-version` rather than introducing a capability of its own.

### Modified Capabilities

- `edge-upload-provider`: the byte destination is identity-in-path with a required `filename` query, and the
  request carries the app-version header.
- `ios-photokit-upload`: a returned job's ledger row is recovered by its **destination path**, not by the
  destination URL's last path segment; an unrecoverable job is reported rather than drained silently.
- `sync-ledger`: the ledger records the destination a job was sent to, so a row is recoverable from what the
  OS persisted.
- `event-rejoin-reconciliation`: the per-device listing answers in identity terms; the seed key is
  recomposed, and a parse failure is distinguished from a transport failure.
- `upload-completion-notify`: the device issues no notify request; the fan-out is the manifest publish's
  effect, and the trigger is therefore the publish rather than a per-cycle completion count.
- `join-event`: enrolment is an explicit join request carrying no body; it no longer writes a manifest, and
  a capacity refusal is distinguishable.
- `device-manifest`: the manifest publishes to the contribution-only sub-resource and enrols nothing.
- `min-app-version`: gains the client half — every request declares the version, a refusal is surfaced to
  the user as an actionable state, and the minimum is coupled to the marketing-version floor.
- `deployment-configuration`: `appStoreUrl` joins the values both bundles carry, and is verified in the
  post-archive readback.
- `harness-world-model`: the mini-edge serves v2's route table and version gate.

## Impact

**Code.** `:domain` (`model/` request building and upload keys, `feature/upload` recovery and reporting,
`feature/membership` join/enrol), `:adapter:generic:app` (every Ktor seam), `:adapter:ios:ext-safe`
(PhotoKit job mapping, the shared client), `:ui:presentation` and `:ui:screens` (the new state),
`:test:world` (the mini-edge), `:test:integration`.

**Build and tooling.** `scripts/resolve-deployment.py` and its test, `iosApp/Configuration/Config.xcconfig`,
`.github/workflows/ios.yml` (the baked-base assertion, the readback, the new floor assertion),
`api/src/config.ts` (`MIN_APP_VERSION`) and its pinning test, `api/src/dev/serve.ts`,
`api/src/dev/fallback.ts` (a v1-pinned enrolment regex that would silently stop matching), and three skill
runbooks.

**No backend change** — v2 is already served and unchanged by this.

**Blocked on a live regression.** The OS-driven upload tier cannot register its extension on `main`
(`restore-upload-url-base`), so the byte-upload half of this change is untestable on device until that
lands. Two platform facts this change's design rests on are consequently **unmeasured**: whether a
`PHAssetResourceUploadJob`'s destination URL round-trips through the OS job store, and whether the OS's
preflight `OPTIONS` carries the composed headers — the latter matters because a v2 `OPTIONS` without the
version header is refused `426`, which would contradict `api-endpoints`' standing requirement that a
preflight never break the plain-`PUT` upload. Both are recorded in design.md with the measurement that
settles them.

**Known limitation, named not fixed.** A retraction has no recipient-side effect: `DownloadController`
reconciles additively, so an already-planned asset still downloads. This predates v2 and belongs to the
change that makes the manifest declare intent.
