## Why

SnapSync needs S3 credentials before it can do anything, but there is no way to get
them onto a device: the build-time `BuildKonfig` route bakes one bucket into the
binary, which is useless for a personally-provisioned app and impossible to change
without a rebuild. Instead, a user should be able to **scan a QR code with the stock
Camera app**, have it open SnapSync, and have the deeplink carry the full S3 config —
turning provisioning into a single scan.

## What Changes

- A custom `snapsync://config?v=1&d=<base64url(json)>` URL scheme carries the full
  `S3Config` (bucket, region, endpoint, accessKeyId, secretAccessKey). The stock Camera
  app recognizes the QR and offers to open SnapSync.
- A new pure decoder (commonMain) base64url-decodes and JSON-parses the payload and
  does **structural-only** validation (`v == 1`, all five fields non-empty); the iOS
  layer persists the result to the **Keychain** under a shared access-group so the
  future background upload extension can read it. A new `ConfigSource`
  (`StateFlow<S3Config?>`) seam exposes the current config; a new valid deeplink
  **hot-swaps** it (silent replace, ledger untouched) with no restart.
- The status screen gains a **setup gate**: until config is present **and** photo
  permission is `GRANTED`, the screen shows a two-card stack (each card checkable):
  "Connect your storage" (passive — completed by the external scan) and "Allow photo
  access" (the existing permission CTA). A malformed deeplink shows a transient,
  self-clearing error on the storage card. **BREAKING** (spec-level): this generalizes
  `permission-gate`'s single-switch, permission-first "Gate replaces the status hero"
  requirement into a two-input setup gate.
- The desktop test harness gains a single **config toggle** so the gate's
  config-present / config-absent states are reachable without a real QR.
- A repo Gradle task becomes the **authoritative QR generator** — it encodes the five
  fields into the `snapsync://config?…` URL and renders a QR PNG, reading secrets from
  env / gitignored `local.properties`, never committing them.

## Capabilities

### New Capabilities
- `deeplink-config`: the config-provisioning plumbing — the `snapsync://` URL scheme and
  payload contract, the pure decode/validate logic, the `ConfigSource` seam, the iOS
  `onOpenURL → Kotlin` bridge, the Keychain-backed store (shared access-group, synchronous
  seed, hot-swap), and the QR-generator Gradle task.
- `setup-gate`: the combined two-card setup gate — its two-input precedence
  (config × permission), the `UiState.Setup` reduction, the stacked-card rendering, the
  per-step CTAs (delegating to the permission ports and to a passive scan instruction),
  and the transient invalid-link error.

### Modified Capabilities
- `permission-gate`: the gate-rendering and gate-intent requirements ("Gate replaces the
  status hero", "Gate intents route through the container") move out to `setup-gate`,
  generalized to two steps; the permission domain contracts (`PermissionStatus`, the two
  ports, full-access mapping, the PhotoKit adapter, Settings-liveness) stay.
- `sync-status-screen`: the sync hero now appears only when config **and** permission both
  pass; its precedence note points at `setup-gate` instead of `permission-gate`.
- `ios-app-shell`: `SnapSyncRoot` constructs the Keychain config store as a third source,
  and `iosApp.swift` gains `onOpenURL` forwarding the raw URL into Kotlin.
- `desktop-test-harness`: a config toggle on `PanelController`, plus sync presets now also
  force config-present (mirroring how they force `GRANTED`).
- `design-system`: a new semantic `SetupCard` component (Material card containment; status
  glyph + title + optional detail + optional action slot).

## Impact

- **New module** `:capability:config` (commonMain decoder + `ConfigSource` seam +
  `commonTest`; `iosMain` `KeychainConfigStore`). New Gradle task for QR generation.
- **iOS entitlements / signing**: adds an App Group + a shared keychain-access-group,
  threaded through the existing cloud-managed signing pipeline.
- **Info.plist**: registers the `snapsync` URL scheme (`CFBundleURLTypes`).
- **Presentation**: `StatusContainerHost` takes a third source; `UiState` gains a `Setup`
  variant; the container's side-effect type widens from `Nothing` to carry the transient
  invalid-link error.
- **No server, no domain, no Universal Links.** Secret credentials live in the QR and
  transiently in iOS URL handling — accepted for a single-user, scoped-IAM, rotatable-creds
  provisioning flow.
