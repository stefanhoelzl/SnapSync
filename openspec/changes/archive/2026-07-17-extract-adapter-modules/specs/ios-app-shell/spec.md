# ios-app-shell — delta for extract-adapter-modules

## MODIFIED Requirements

### Requirement: On-disk native ledger on iOS

The `:adapter:ios:ext-safe` module SHALL provide an `iosLedgerStore()` factory (iOS-only source) that constructs the shared `SqlDelightLedgerStore` (`:adapter:generic`) over a `NativeSqliteDriver`, persisting the ledger database **on disk in the `group.app.snapsync` App-Group container** so its contents survive process death and are shared between the app and the background-upload extension. (Before migration step 4 the factory and store lived in `:domain:engine`.) This factory SHALL be the single site that names the database location, SHALL open the database in WAL mode (one cross-process writer plus concurrent readers), and SHALL wire the backend's cross-process change notification (post-on-`put` / observe-in-`changes`, per `sync-ledger`). The same factory SHALL serve both processes; on the OS-driven tier the app process constructs no `LedgerWriter` — it holds the ledger only as a `LedgerStore` for its read-only aggregates read and the reset-family operations (per `sync-ledger`).

#### Scenario: The ledger persists across launches
- **WHEN** the app writes ledger state, terminates, and relaunches
- **THEN** `iosLedgerStore()` opens the same on-disk database and the prior state is present

#### Scenario: The ledger lives in the App-Group container
- **WHEN** the extension writes the ledger and the app later reads it
- **THEN** both open the same database file in the `group.app.snapsync` container, and the app's read reflects the extension's write

#### Scenario: Native backend honors the ledger contract
- **WHEN** the native-driver-backed `SqlDelightLedgerStore` is exercised against the ledger backend contract
- **THEN** `get`/`put`/`aggregates` and change signals behave identically to the JVM-driver backend
