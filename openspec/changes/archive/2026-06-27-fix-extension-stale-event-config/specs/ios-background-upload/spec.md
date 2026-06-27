## MODIFIED Requirements

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from two sources: the runtime `EventConfigPayload` (`eventId`) read from the **shared Keychain** via the `:capability:config` Keychain store; and the compile-time edge **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). The extension SHALL re-read the Keychain payload **freshly at the start of every `process()` cycle** — it MUST NOT cache a value read once at process construction. The extension process outlives a single invocation, and an event (re)joined by the **app** process writes the Keychain but does not notify the extension's in-memory config; a cached value would make a long-lived extension keep uploading to a stale, previously-joined event even after the app shows the new one as joined. The shared store therefore exposes a refresh (`reload()`) the extension calls before each read. When the Keychain payload is **absent** (the extension woke before the user joined an event), the extension SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never crashing.

#### Scenario: Config present — provider built from host + eventId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase` and `eventId` from the payload

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle
- **WHEN** the extension process has already run a cycle for one event, the app then joins a different event (writing the new `eventId` to the shared Keychain), and the same extension process runs its next `process()` cycle
- **THEN** the extension re-reads the Keychain, builds `EdgeUploadRequestProvider` for the **newly-joined** `eventId`, and uploads to the new event — it does not keep uploading to the event it read at process construction
