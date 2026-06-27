## REMOVED Requirements

### Requirement: Extension supplies an App-Group-persisted device id
**Reason**: The storage key drops the `<deviceId>` level (`<eventId>/<deviceId>/<filename>` →
`<eventId>/<filename>`), so the extension no longer needs a device id to compose the upload
destination. The lazily-minted App-Group UUID and its store have no remaining consumer.
**Migration**: None on-device — `DeviceIdStore`/`DeviceIdProvider` are deleted. The provider is now
configured from host + eventId only (see "Extension assembles config from the Keychain payload and
compile-time host").

## MODIFIED Requirements

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build
the destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built
edge URL `<host>/event/<eventId>/file/<filename>`, no signing), create a system upload job via
`creationRequestForJob(destination:resource:)`, and **then** report `UploadStarted(job)` to the
engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED` is recorded only after the
job exists, never before). On `AlreadyUploaded` it SHALL create no job and write nothing. Completion
and failure outcomes are reduced into the ledger by the drain (see "Completion and retry
adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally, a system upload job is created with it,
  and only after the create succeeds does the extension report `UploadStarted`, which records
  `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED` at the same version)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from two sources: the
runtime `EventConfigPayload` (`eventId`) read synchronously from the **shared Keychain** via the
`:capability:config` `ConfigSource`; and the compile-time edge **host** read from the extension
bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). When the Keychain payload is
**absent** (the extension woke before the user joined an event), the extension SHALL log and complete
the cycle as a successful no-op — creating no job and writing nothing — never crashing.

#### Scenario: Config present — provider built from host + eventId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase`
  and `eventId` from the payload

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and
  writing nothing to the ledger
