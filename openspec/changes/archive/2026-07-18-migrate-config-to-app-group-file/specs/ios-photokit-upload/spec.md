# ios-photokit-upload — delta for migrate-config-to-app-group-file

## REMOVED Requirements

### Requirement: Extension assembles config from the Keychain payload and compile-time host

**Reason**: the config's storage of record moved from the shared Keychain to the App-Group config
file (migration step 11a, capability `event-link`); the requirement is re-added below re-grounded
on the file-backed store, with the fresh-per-cycle and absent-skips contracts unchanged.

## ADDED Requirements

### Requirement: Extension assembles config from the shared config store and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfig` (`eventId`) read through the shared three-state config store —
`:adapter:ios:ext-safe`'s file-backed store over the App-Group config file, with its
written-through Keychain fallback while that lasts (capability `event-link`) — the stable
per-install `deviceId` read from the **shared Keychain** (per `device-identity`); and the
compile-time edge **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle`
info dictionary). The `deviceId` SHALL be used to build the event-independent byte URLs
(capability `edge-upload-provider`) and as the `device.json` key. The extension SHALL read the
persisted config **freshly at the start of every `process()` cycle** — one three-state
`ConfigReader.read()` per cycle (capability `upload-lifecycle`, the port-pure entry gate); it MUST
NOT cache a value read once at process construction. The extension process outlives a single
invocation, and an event (re)joined by the **app** process writes the shared store but does not
notify the extension's in-memory state; a cached value would make a long-lived extension keep
uploading to a stale, previously-joined event even after the app shows the new one as joined. When
the persisted config is **definitively absent** (the extension woke before the user joined an
event), the extension SHALL log and complete the cycle as a successful no-op — creating no job and
writing nothing — never crashing.

#### Scenario: Config present — provider built from host, eventId, and deviceId

- **WHEN** `process()` runs with an `EventConfig` persisted in the shared config store
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from
  `BackgroundUploadURLBase`, `eventId` from the persisted config, and `deviceId` from the shared
  Keychain, so byte URLs are built by `edge-upload-provider` and `device.json` is keyed by that
  `deviceId`

#### Scenario: Config absent — cycle skipped cleanly

- **WHEN** `process()` runs with no persisted config in the shared store
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job
  and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle

- **WHEN** the extension process has already run a cycle for one event, the app then joins a
  different event (persisting the new `eventId` through the shared store), and the same extension
  process runs its next `process()` cycle
- **THEN** the extension re-reads the persisted config, builds `EdgeUploadRequestProvider` for the
  **newly-joined** `eventId` (the `deviceId` is stable across the switch), and uploads to the new
  event — it does not keep uploading to the event it read at process construction
