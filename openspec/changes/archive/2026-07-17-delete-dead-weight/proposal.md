# Proposal: delete-dead-weight

## Why

Migration step 1 (`test/architecture/migration/PLAN.md`): the deletion ledger names dead weight the
target architecture (`module-architecture`) has no home for — a QR CLI tool nothing invokes in the
product loop, two unused crypto catalog entries, a one-interface module whose seam ceremony outlived
its bug, a duplicate `GET /events/:id` client, three interface/narrowing ceremonies with exactly one
implementation each, and a declared-never-imported module edge. Deleting them first makes every later
move smaller and the beacon's remaining distance honest.

## What Changes

All behavior-preserving; deletion-ledger items minus the two deferred ones (Arrow → step 9,
`DeviceManifestUploader` ×4 → steps 7/10):

- **QR tool dies**: `capability/config/src/jvmMain` (`QrGeneratorMain`), the `generateConfigQr`
  Gradle task, and the `zxing` catalog entries. The event-link codec (`encodeEventUrl` /
  `decodeEventUrl`) remains the single authority for the link, and the in-app invite QR
  (capability `event-invite-qr`, rendered through the codec) is the only QR surface.
- **kotlincrypto catalog entries die** (4 toml lines; no source usage existed).
- **`:capability:device-id` dies**: the `DeviceIdentity` interface and `FixedDeviceIdentity` are
  deleted — use sites take the id as a plain `() -> String` (tests inject a lambda).
  `KeychainDeviceIdentity` moves to `:domain:keychain` (package `app.snapsync.keychain`), keeping
  the step-0-pinned Keychain pair (`app.snapsync.deviceid`, `deviceid`) byte-identical and
  single-sited.
- **`EventMetadataSource` merged into join's `EventDetailsSource`**: the duplicate best-effort
  `GET /events/:id` name client dies; `SnapSyncRoot`'s name refresh reads through the one
  `HttpEventDetailsSource` (a non-`Found` outcome leaves the name unchanged).
- **`LeaveNotifier` interface ceremony dies**: `HttpLeaveNotifier` (the only implementation)
  stands alone; `LeaveEvent` already took the notify as a lambda.
- **`LoggingPushReceiver` dies** (diagnostics-only class, never wired).
- **`LedgerReader` dies**: `LedgerWriter` carries the per-key `entry()` read directly; the
  narrowing ceremony had one construction site and no reader-typed consumer in production.
- **The dead `:domain:status → :capability:membership` edge dies** (declared, never imported).
- **Beacon fix (ride-along)**: the deletion-ledger scan excludes the beacon module's own source —
  its pattern strings self-matched, leaving four items permanently unburnable.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-link`: the "Authoritative QR generator" requirement is removed (the codec is the single
  encoder; the in-app invite QR is the only QR surface); the switch requirement names the concrete
  `HttpLeaveNotifier`.
- `device-identity`: the seam requirement is replaced by placement — a plain `() -> String`
  supplier at use sites, `KeychainDeviceIdentity` in `:domain:keychain`.
- `join-event`: gains the "one details client" requirement — `EventDetailsSource` is the app's
  only `GET /events/:id` client, also serving the best-effort name refresh.
- `event-creation-ui`: gains the counterpart requirement — the capability performs no event fetch
  of its own (its only HTTP surface is `POST /events`).
- `sync-ledger`: the reader/writer split requirement is replaced by the writer-carries-the-read
  shape; prune ops stay writer-only without the reader-typed narrowing language.
- `leave-event`: the notify requirement names `HttpLeaveNotifier` instead of the deleted
  interface.
- `ios-photokit-upload`, `ios-app-shell`: the two requirements naming `LedgerReader` (and the
  never-built `LedgerWatcher`) are restated as "the app constructs no `LedgerWriter`" — the
  invariant they actually pin.

## Impact

- Deleted: `capability/device-id/**`, `capability/config/src/jvmMain/**`,
  `EventMetadataSource.kt` + its test, `LeaveNotifier.kt` (class re-homed to
  `HttpLeaveNotifier.kt`), `LoggingPushReceiver`, `LedgerReader`.
- Touched builds: `settings.gradle.kts`, `gradle/libs.versions.toml`, and the `build.gradle.kts`
  of config, attest, join, app/ios ×2, app/desktop, test/integration, domain/status.
- Touched code: `SnapSyncRoot`, `UploadExtensionRoot`, `DeviceAttestation`(+test),
  `JoinEvent`(+test), `WorldInspectorController`, `JoinGateIntegrationTest`, `Ledger.kt`,
  `LedgerBackendContract`, `EventDetailsSource` (doc), `PushReceiver.kt`, `UploadConfig` (doc),
  `BurnDownTest` (self-scan exclusion).
- CLAUDE.md: `:capability:device-id` row removed; keychain and membership rows updated.
- Expected beacon Δ: deletion ledger −8, illegal edges −3, module-set distance −1, mixed files −2
  (two of step 2's counted files died here).
