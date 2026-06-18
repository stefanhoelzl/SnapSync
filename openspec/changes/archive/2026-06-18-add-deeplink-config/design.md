## Context

SnapSync is a single-user photo-backup app (Compose Multiplatform, iOS + JVM desktop
harness). It cannot upload anything without an `S3Config` (`bucket`, `region`, `endpoint`,
`accessKeyId`, `secretAccessKey`). Today there is no delivery path: the intended build-time
`BuildKonfig` route bakes one bucket into the binary, which cannot be changed without a
rebuild and is wrong for a personally-provisioned app.

The app's architecture is a set of synchronous-seed `StateFlow` seams combined in an Orbit
container: `PhotoLibraryPermission` exposes `PermissionStatusSource`/`PermissionRequester`,
`LedgerSyncStatusSource` exposes `SyncStatusSource`, and `StatusContainerHost.reduceFrom()`
folds them into `UiState` with **permission-first precedence**. The real upload work will
run later in a background PhotoKit extension, not the app — so credentials must eventually
be reachable from a separate process.

This change delivers config by **deeplink**: a QR code scanned by the stock Camera app opens
`snapsync://config?…`, and the URL carries the whole config.

## Goals / Non-Goals

**Goals:**
- Provision the full `S3Config` by scanning a QR with the stock Camera app — no server, no
  web domain, no in-app scanner.
- Persist credentials securely and in a place the future upload extension can read.
- Gate the app behind a setup screen until both config and photo permission exist.
- Keep all decode/validate logic in shared, unit-testable Kotlin.
- Ship an authoritative QR generator so the wire format has exactly one source of truth.

**Non-Goals:**
- No Universal Links / `apple-app-site-association` / hosted endpoint.
- No live S3 connectivity probe at setup time (validation is structural only).
- No in-app config viewer, editor, or clear/de-provision affordance (re-scan replaces).
- No automatic ledger reset on a bucket switch (accepted edge case).
- No in-app QR scanner and no camera-usage permission.

## Decisions

### D1 — Full config (incl. secret) in the QR, custom `snapsync://` scheme
The deeplink carries all five fields including `secretAccessKey`. **Alternatives:** an
encrypted payload unlocked by a typed passphrase, or a reference token resolved against a
server. **Why full-in-QR:** simplest, offline, no infrastructure; acceptable for a single
user who provisions scoped, rotatable IAM credentials. A **custom scheme** (not a Universal
Link) avoids owning a domain/AASA file and works once the app is installed — which is always
true when provisioning an installed app. The Camera app surfaces custom-scheme QR codes with
an "Open in SnapSync" affordance.

### D2 — Wire format: single base64url JSON blob
`snapsync://config?v=1&d=<base64url(json)>` where the JSON is
`{"bucket","region","endpoint","accessKeyId","secretAccessKey"}`. **Alternative:**
individual percent-encoded query params. **Why one blob:** endpoint URLs and secret keys
contain `/ + =` that are error-prone to escape per-field; one opaque, versioned param
(`v=1`) decodes in a single step and leaves room to evolve the format.

### D3 — Thin Swift, decode in Kotlin
SwiftUI `onOpenURL` (handles cold-launch and warm delivery) hands the **raw URL string** to
a Kotlin entry point (`SnapSyncRoot.onOpenUrl(String)`). All base64/JSON decode, structural
validation, and Keychain write happen in shared Kotlin. **Alternative:** parse in Swift and
pass a structured object. **Why Kotlin:** the logic is unit-tested in `commonTest`, not
duplicated per platform, and stays portable if Android ever lands.

### D4 — Structural-only validation
Decode base64url → parse JSON → require `v == 1` and all five fields non-empty. No network.
**Alternative:** a signed HEAD probe against the bucket before persisting. **Why structural:**
keeps setup offline and the error surface small; bad credentials surface as upload failures
later, which the ledger already models.

### D5 — Keychain under a shared access-group, set up now
Persist to the iOS Keychain under a shared keychain-access-group + App Group, established now
even though the upload extension does not yet exist. **Alternative:** app-default keychain
now, migrate later. **Why now:** one entitlement/provisioning change through the
cloud-managed signing pipeline instead of a later migration; the extension can read the same
item from day one. The store reads the Keychain **synchronously at construction** to seed its
`StateFlow`, mirroring `PhotoLibraryPermission`'s synchronous-real guarantee.

### D6 — Hot-swap, silent replace, ledger kept
A new valid deeplink writes the Keychain and pushes the new value into a
`MutableStateFlow<S3Config?>`; the UI reacts immediately (no restart). Re-scanning the same
config is an idempotent no-op; a different config silently replaces it. **Trade-off:** the
ledger keys uploads by filename, not bucket, so a bucket switch leaves stale "uploaded"
marks. **Why accept:** bucket-switching is a rare edge case for a single-user app; a confirm
+ ledger-reset flow is deferred.

### D7 — Combined setup gate as a two-card stack (`setup-gate` capability)
Until config is present **and** permission is `GRANTED`, the screen shows a stack of two
checkable cards: "Connect your storage" (passive — completed by the external scan, no button)
and "Allow photo access" (the existing permission CTA). Each card collapses to a glyph+title
when satisfied. **Alternatives:** two sequential single-hero gates (preserves the old spec but
two screens), or a plain checklist row list. **Why stacked cards:** one screen, independent
steps in any order, and a clearer "what's left" affordance than sequential heroes.

This generalizes `permission-gate`'s single-switch model: the gate-rendering and gate-intent
requirements move into the new `setup-gate` capability (the analogue of `sync-status-screen`),
which consumes **two** seams (`ConfigSource` + the permission ports). `permission-gate` keeps
its genuine domain contracts. A new semantic `SetupCard` component is added to `design-system`.

### D8 — Module split: `deeplink-config` (seam) vs `setup-gate` (reduce/render)
Mirrors the repo's existing `sync-status` (contract/seam) vs `sync-status-screen`
(reduce/render) split. `deeplink-config` owns the scheme, decoder, `ConfigSource`, Keychain
store, iOS bridge, and QR generator; `setup-gate` owns the precedence, `UiState.Setup`, and
the card rendering.

### D9 — Desktop harness: a config toggle only
`PanelController` gains a `MutableStateFlow<S3Config?>` implementing `ConfigSource` and a
`setConfigPresent(Boolean)` that sets a canned config or `null`; `ControlPanel` renders one
Switch. Sync presets must now **also** force config-present (just as they force `GRANTED`),
since the hero is gated on both. **Why a toggle, not URL entry:** the gate-state UX is what
the harness needs to exercise; the decode/validate/error path is covered by `commonTest`
against the pure decoder, not the harness.

### D10 — Authoritative QR generator as a Gradle task
A JVM Gradle task encodes the five fields into the `snapsync://config?v=1&d=…` URL and renders
a QR PNG (ZXing), reading secrets from env / gitignored `local.properties`, never committing
them. **Why in-repo:** keeps the encoder in lockstep with the Kotlin decoder so the format
cannot drift.

## Risks / Trade-offs

- **Secret in the QR / URL** → the QR image is a bearer secret and the URL transits iOS URL
  handling. Mitigation: scoped, rotatable IAM creds; treat printed QR codes as secrets;
  documented as accepted for single-user provisioning.
- **Bucket-switch ledger staleness** (D6) → after pointing at a new bucket, prior "uploaded"
  files won't re-upload. Mitigation: documented edge case; reset flow deferred.
- **Entitlement/signing churn** (D5) → adding App Group + keychain-access-group touches the
  cloud-managed signing setup before the extension exists. Mitigation: one change now, no
  later migration.
- **Spec breakage** (D7) → `permission-gate`'s "Gate replaces the status hero" is rewritten.
  Mitigation: requirements move (not delete) into `setup-gate`; permission contracts untouched.
- **`UiState` side-effect widening** → the container's effect type goes from `Nothing` to a
  small type carrying the transient invalid-link error; touches every container consumer.

## Open Questions

- Should the satisfied "Storage connected" card echo the bucket/region as a sanity check, or
  stay opaque to keep config detail off-screen? (Leaning opaque.)
- App Group identifier convention (`group.app.snapsync`) — confirm against the existing bundle
  id `app.snapsync` and Team `E9Z8BADH58` provisioning.
