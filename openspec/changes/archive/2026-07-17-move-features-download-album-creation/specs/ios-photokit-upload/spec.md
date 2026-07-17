# ios-photokit-upload — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfigPayload` (`eventId`) read from the **shared Keychain** via
`:adapter:ios:ext-safe`'s `KeychainConfigStore` (seated there by migration step 4); the stable per-install `deviceId` read from the **shared
Keychain** (per `device-identity`); and the compile-time edge **host** read from the extension
bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). The `deviceId` SHALL be used to build
the event-independent byte URLs (capability `edge-upload-provider`) and as the `device.json`
key. The extension SHALL re-read the Keychain payload **freshly at the start of every `process()`
cycle** — it MUST NOT cache a value read once at process construction. The extension process outlives
a single invocation, and an event (re)joined by the **app** process writes the Keychain but does not
notify the extension's in-memory config; a cached value would make a long-lived extension keep
uploading to a stale, previously-joined event even after the app shows the new one as joined. The
shared store therefore exposes a refresh (`reload()`) the extension calls before each read. When the
Keychain payload is **absent** (the extension woke before the user joined an event), the extension
SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never
crashing.

#### Scenario: Config present — provider built from host, eventId, and deviceId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase`,
  `eventId` from the payload, and `deviceId` from the shared Keychain, so byte URLs are built by
  `edge-upload-provider` and `device.json` is keyed by that `deviceId`

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle
- **WHEN** the extension process has already run a cycle for one event, the app then joins a different event (writing the new `eventId` to the shared Keychain), and the same extension process runs its next `process()` cycle
- **THEN** the extension re-reads the Keychain, builds `EdgeUploadRequestProvider` for the **newly-joined** `eventId` (the `deviceId` is stable across the switch), and uploads to the new event — it does not keep uploading to the event it read at process construction

