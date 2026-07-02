## MODIFIED Requirements

### Requirement: Schema in a shared module read by the extension

The store's schema and a read-only **suppression projection** (the set of `createdLocalId`s) SHALL
live in a lean shared module linked by **both** the app (writer + full reader) and the upload
extension (read-only reader of the suppression projection). The extension SHALL open the store
read-only and read only the `createdLocalId` set, over WAL (the mirror of how the app already reads
the extension's ledger). The app-side download logic (planner, transfer controller, importer) SHALL
NOT be linked by the extension.

The extension SHALL depend on the suppression projection through a **narrowed `SuppressionSource`
type** exposing only `suppressedLocalIds()` — not the full `DownloadStore` interface. The composition
root SHALL wire a read-only `SuppressionSource` factory into the upload cycle, so the extension's
inability to write or read beyond the suppression set is **compile-enforced** rather than a linkage
convention. `DownloadStore` MAY extend `SuppressionSource`, but no `DownloadStore`-typed value SHALL
reach the extension's upload cycle.

#### Scenario: Extension reads the suppression set read-only

- **WHEN** the upload extension needs the suppression set
- **THEN** it opens the store read-only and reads the `createdLocalId` projection, without linking the
  app-side download logic

#### Scenario: The extension is typed to the narrowed suppression source

- **WHEN** the extension's upload cycle is assembled
- **THEN** it receives a `SuppressionSource` (only `suppressedLocalIds()`), never a `DownloadStore`, so
  it cannot express a write or a non-suppression read
