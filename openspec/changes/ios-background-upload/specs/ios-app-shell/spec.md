## MODIFIED Requirements

### Requirement: On-disk native ledger on iOS

The `:domain:engine` module SHALL provide an `iosLedgerBackend()` factory (`iosMain`) that constructs the shared `SqlDelightLedgerBackend` over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; read-only access in the app is enforced structurally by handing out the ledger as a `LedgerReader`/`LedgerWatcher` (the app never constructs a `LedgerWriter`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerBackend()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerBackend` is exercised against the ledger backend contract
- **THEN** `get`/`put`/`aggregates` and change signals behave identically to the JVM-driver backend

## ADDED Requirements

### Requirement: Enable the background-upload extension on grant

When photo-library access is (or becomes) full (`.readWrite` → `GRANTED`), the app SHALL call `PHPhotoLibrary.setUploadJobExtensionEnabled(true)` so the system can invoke the background-upload extension. The app itself SHALL perform no discovery or upload; enabling the extension is the app's only producer-side responsibility. The call SHALL be idempotent-safe to repeat on each grant/foreground.

#### Scenario: Granting full access enables the extension
- **WHEN** photo-library permission transitions to `GRANTED`
- **THEN** the app calls `setUploadJobExtensionEnabled(true)`

#### Scenario: The app never uploads or discovers itself
- **WHEN** the app is running with access granted
- **THEN** it only reads the ledger and enables the extension; all discovery and job creation happen in the extension process
