# gallery-status — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` SHALL live in `:domain`'s `ports/` zone (seated by migration step 3a) and its
settable in-memory implementation in `:domain:gallery` — the fakes' interim home until they re-home
to `:adapter:fake` (migration step 10). `:domain:presentation` SHALL NOT depend on `:domain:gallery`,
so no fake gallery type is reachable from presentation code; presentation consumes gallery-derived
counts only through the `feature/status` read-models.

#### Scenario: Presentation compiles without the gallery fakes

- **WHEN** `:domain:presentation` is compiled
- **THEN** `:domain:gallery` is not on its compile classpath, and no in-memory gallery type is
  reachable from presentation code

### Requirement: Library resource enumeration seam

The gallery domain SHALL define, in `:domain`'s `ports/` zone (`PhotoLibrary`, seated by migration step 3a), a resource-enumeration seam that returns the
library's **in-scope** resources as a list, each carrying `(filename, assetId, contentType, metadata)` —
where `filename` is the upload key (the reinstall-stable identity, `<assetId>-<kind>.<ext>`) and
`assetId` groups a photo's resources. There is **no** `version`: existence under the upload key is the
proof of upload, so nothing compares content versions and the ledger keeps no timestamp (capability
`sync-ledger`). The seam SHALL take a **capture-date lower bound** and return only resources of assets at or
after it; it SHALL NOT offer an unbounded enumeration. This is the **single shared derivation** of those
fields: the iOS background-upload producer's full-enumeration path SHALL delegate to it, so the same
`filename` is computed wherever enumeration happens (the join seed and the producer agree
byte-for-byte). The **app-side status consumer** SHALL **also** consume this seam — but only to count the
device's in-scope assets, which is the status total `N` (capability `sync-status`). It SHALL NOT derive an
expected-filename set and SHALL NOT read the per-device listing: own-device completeness is ledger-backed,
and the status path issues no storage LIST. What the shared seam guarantees is that the total counts
exactly the assets the cycle would upload — so the screen can reach 100% — not that two derivations of
"complete" agree. The iOS implementation SHALL be
PhotoKit-backed (`:adapter:ios:ext-safe`'s decision-free walk composed through `feature/upload`'s `ResourceEnumerator`, the shared walk-plus-mapping composition — migration step 6); `:domain:gallery` SHALL keep providing a settable in-memory implementation for the JVM
harness and tests until the fakes re-home to `:adapter:fake` (migration step 10). Presentation SHALL keep consuming counts only through the `feature/status` read-models, never
the enumeration seam directly (per "Module placement keeps the seam off presentation").

The enumeration seam SHALL be realized as the composition of the **decision-free raw-asset walk seam**
(above) with a **pure `commonMain` mapping** `RawAsset` → resources. The mapping SHALL be the single
site of the fan-out orchestration: it SHALL normalize the `assetId` `'/'→'_'`, drop every resource whose
raw type maps to no role (`resourceRole` → originals only), derive each kept resource's `filename` via
`uploadKey`, and assemble the per-asset manifest metadata (`creationDate`, original filename, MIME). The
mapping SHALL be pure and platform-free, so this fan-out orchestration — previously exercised only in the
iOS adapter — SHALL be unit-tested on JVM **and** the iOS simulator with a fake raw-asset walk. The iOS
enumeration adapter SHALL shrink to the decision-free walk plus this shared mapping; it SHALL hold no
role filter, key derivation, or normalization of its own.

#### Scenario: Enumeration yields per-resource identity
- **WHEN** a consumer enumerates a library with photos that each have one or more resources
- **THEN** it receives one entry per resource, each carrying that resource's `filename` and its photo's
  `assetId` — and no version, because none is derived

#### Scenario: The producer and the seed derive identical keys
- **WHEN** the upload producer enumerates a resource and the join seam enumerates the same resource
- **THEN** both yield the same `filename` (one shared derivation)

#### Scenario: The status total counts exactly what the cycle would upload
- **WHEN** the app-side status consumer enumerates to compute the total `N`, and the upload cycle
  enumerates the same library to decide what to upload
- **THEN** both see the same in-scope assets, so `N` never counts a photo the cycle refuses — which is
  what lets the screen reach 100% instead of sitting below it forever

#### Scenario: Enumeration requires a bound and never omits an in-scope asset
- **WHEN** a consumer enumerates with a capture-date lower bound `C`
- **THEN** every resource of every asset at or after `C` is returned, no unbounded enumeration entry point
  exists to call instead, and any asset before `C` that the platform fetch over-returns is removed by the
  consumer's own authoritative cutoff filter

#### Scenario: The in-memory enumeration honours the bound
- **WHEN** a test sets the in-memory enumeration to a resource dated before `C` and one dated at or after `C`,
  and enumerates with bound `C`
- **THEN** only the at-or-after resource is returned, on JVM and on the iOS simulator

#### Scenario: Fake enumeration is settable
- **WHEN** a test sets the in-memory enumeration to a list of resources
- **THEN** enumerating returns exactly those resources, on JVM and on the iOS simulator

#### Scenario: The fan-out mapping is exercised off-device
- **WHEN** a fake raw-asset walk supplies an asset with an original resource, a paired-video resource,
  and an edit-artifact resource, and the enumeration seam maps it
- **THEN** on JVM and the simulator the mapping yields exactly the originals — `<assetId>-primary.<ext>`
  and `<assetId>-live.<ext>` with the `assetId` `'/'→'_'` normalized and the `creationDate`/original-
  filename/MIME metadata attached — and drops the edit artifact, with no PhotoKit involved

### Requirement: Upload-key to assetId round-trip parser

`:domain`'s `model/` zone (seated by migration step 3a) SHALL own a **single** `assetIdFromUploadKey` parser — the exact inverse of its
`uploadKey` derivation — that recovers a resource's `assetId` from a bare upload key
(`<assetId>-<role>.<ext>`). It SHALL be the **only** implementation of that parse: both the
extension-side upload-job reconstruction (`ios-photokit-upload`, "Completion and retry adjudication")
and the re-join reconciler (`event-rejoin-reconciliation`) SHALL call this one function, replacing any
private per-module copy. Because the parse is now load-bearing at the record path (a mis-parse writes a
wrong or empty `assetId`), the round-trip SHALL be pinned by a test: for every key `uploadKey` produces,
`assetIdFromUploadKey` SHALL recover the original `assetId`. The parser SHALL remain in `model/`,
the one shared derivation both consumers import (per "Module placement keeps the
seam off presentation").

#### Scenario: assetId round-trips through the upload key

- **WHEN** `uploadKey` derives a key for a resource with a given `assetId` and role
- **THEN** `assetIdFromUploadKey` applied to that key returns the original `assetId`, for assetIds with
  and without embedded `-`, on JVM and on the iOS simulator

#### Scenario: Both consumers use the one parser

- **WHEN** the upload-job reconstruction and the re-join reconciler each recover an `assetId` from a key
- **THEN** both call `model/`'s `assetIdFromUploadKey`, with no private duplicate remaining in
  the reconciler or the upload cycle

