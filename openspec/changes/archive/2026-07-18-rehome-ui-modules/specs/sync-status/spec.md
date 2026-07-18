# sync-status — delta for rehome-ui-modules

## MODIFIED Requirements

### Requirement: Module placement plugs the engine leak

The status projections SHALL live in `:domain`'s `feature/status` zone (package
`app.snapsync.feature.status`) — `SyncStatusSource`, the ledger-backed source,
`LedgerCountsSource`, and the own-device gallery source; the `SyncStatus`/`SyncProgress` vocabulary lives in `model/`
(package `app.snapsync.model`, seated there by migration step 3a). No status source SHALL reach
back for the ledger it was freed from (`ledger-free-status`): completeness and in-flight counts
enter **only** through the injected `suspend () -> LedgerCounts` read, and no status source
SHALL take, construct, or reference the ledger port (`LedgerStore`), the ledger writer, or the
sync engine.

The boundary is mechanically held by the feature-blindness zone gate (`architecture-guards`): a
`feature/status` file may reference only `model/`, `ports/`, and itself — so the ledger writer
and engine (seated in `feature/upload`, migration step 5) and every legacy module are violations
by source-text match, fully-qualified references included. One clause the gate cannot see —
`LedgerStore` is a legal `ports/` reference for other features — is carried by this requirement:
for status it remains forbidden, so the counts seam stays the only ledger surface status can
read (the presentation-imports gate, **armed at migration step 9** over `ui/presentation/src`, adds
the presentation-side containment mechanically).

`:ui:presentation` (re-homed from `:domain:presentation` at migration step 9) SHALL consume
status only through the `SyncStatusSource` seam and the feature's read-model types — never a
ledger type, a port, or the engine.

#### Scenario: Status names no ledger type
- **WHEN** the `feature/status` sources are inspected
- **THEN** no file references the sync engine, the ledger writer, or `LedgerStore` — counts
  arrive only through the injected `LedgerCounts` read

#### Scenario: A status source reaching for a sibling feature fails the build
- **WHEN** a file under `feature/status` references a declaration under `feature/upload` (the
  ledger writer's and engine's seat) or any legacy module
- **THEN** the feature-blindness gate fails, naming both packages

#### Scenario: Presentation consumes the seam only
- **WHEN** presentation's status consumption is inspected
- **THEN** it observes `SyncStatusSource` and the feature's read-model types, and no ledger
  type, port, or engine type is named in presentation code
