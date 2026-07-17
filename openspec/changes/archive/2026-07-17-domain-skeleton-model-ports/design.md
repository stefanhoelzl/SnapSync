# Design — domain-skeleton-model-ports

## Context

Migration step 3a (PLAN.md). Executed as the plan's two-commit discipline: a move-commit
(55 whole-file `git mv`s, all R100 — byte-identical) followed by this import-fix commit
(packages, imports, splits, build wiring). The step-2 splits made almost every move whole-file;
three files they missed were split here with orchestrator approval.

## Goals / Non-Goals

- Goal: `:domain` exists with `model/` + `ports/` populated, five illegal edges dead, all gates
  green, zero behavior change.
- Non-goals: port need-renames (3b), adapters (4), features/flows/compose (5–8), module
  deletions (4/6), any spec-prose reconciliation of legacy placement lines (13b / owning steps).

## Decisions

- **D1 — module layout**: `:domain` roots at `domain/` with `domain/src/` beside the legacy
  submodule directories, exactly the scope the step-0 zone gates pinned (guards spec, "the
  `:domain` module roots at `domain/`, its `src/` beside the legacy submodule directories until
  they empty"). Gradle precedent for parent-with-children modules already exists in-repo
  (`:app:desktop`+`:app:desktop:ui`).
- **D2 — flat zone packages**: `app.snapsync.model` and `app.snapsync.ports`, no topic
  sub-packages. The gates key on the zone-named segment only; sub-structuring is deferred to the
  steps that own each area (3b names ports; 5/6 shape features). Orchestrator decision.
- **D3 — `LedgerBackend` lives in `model/`, DIVERGING from PLAN's ports list.** The chain
  `SyncEngine` (PLAN: model) → holds `LedgerWriter` (PLAN: model) → holds `LedgerBackend`
  (PLAN: ports) cannot pass the armed model-purity gate ("model references nothing
  project-internal outside model"). Alternatives: park `SyncEngine` + `LedgerWriter` in `ports/`
  (two domain services zone-mislabeled) or leave the build red (halt condition). Chosen: the
  one-file divergence — `LedgerBackend` in `model/` — which every gate accepts (a port interface
  referencing only model vocabulary violates nothing mechanical; "every port interface lives in
  ports/" has no gate). The trio separates at step 5 when `feature/` exists and the writer/service
  move there; `LedgerBackend` takes its `ports/` seat then (or at 3b's rename to `LedgerStore`).
  PLAN.md's row carries this note.
- **D4 — `ConfigPorts.kt` moved whole to `ports/`, including `ConfigSource`.** PLAN's model
  bullet lists `ConfigSource`, but it is a state-port interface sharing a file with `ConfigStore`
  (PLAN: ports) and `configReadFrom` (references the keychain seam). Splitting was out of scope
  for the move-commit and everything in the file is ports-legal. The edge kills PLAN wanted work
  identically — importers import `:domain` regardless of zone.
- **D5 — the three ride-along splits** (orchestrator-directed, reviewer focus):
  `JoinedEventMarker` out of `Reconciler.kt` → `ports/` (KDoc carried verbatim;
  `ExtensionReconciler` + timeout const stay); `GalleryResourceEnumerator.kt` split four ways —
  interface → `ports/`, pure `resourcesFrom` → `model/RawAssetMapping.kt`, `ResourceEnumerator`
  (impl) and `InMemoryGalleryResourceEnumerator` (fake) stay in `:domain:gallery` as own files;
  `InMemoryAttestStore` out of `AttestSeams.kt` (fake stays in `:capability:attest`, the three
  seam interfaces move). All declaration bodies byte-identical; only packages/imports differ.
- **D6 — `LINK_ORIGIN` generation moved to `:domain`**, emitting package `app.snapsync.model`
  (the codec that consumes it lives there; a `model/` file referencing an `app.snapsync.config`
  const would trip the model-purity gate). `:capability:config`'s generator deleted in the same
  commit — two modules generating the same const would collide on every consumer's classpath.
  `EventLinkDomainTest` needed no change (it reads `gradle.properties`, `Config.xcconfig`, and
  the backend source, never the generator's home).
- **D7 — edge kills and the Ktor fallout**: the five PLAN-named edges removed;
  `presentation→event-creation-ui` deliberately survives (impl-side types move at step 6).
  Removing `download→push` un-exposed `ktor-client-core` from `app/desktop`'s transitive
  classpath (push `api`-exported it); `app/desktop` now declares it directly — the honest
  dependency for its `WorldInspectorController`-owned `HttpClient`.
- **D8 — unlisted riders forced by the gates**: `UploadRequestProvider` (interface) and
  `Encoding.kt` (`encodeFilenameSegment`) ride to `model/` — `EdgeUploadRequestProvider`
  (PLAN: model) implements/uses them, and model may not reach outside model.
- **D9 — tests that stayed behind**: `SyncEngineTest` + `RecordingUploadRequestProvider` +
  `InMemoryLedgerBackend` + `LedgerBackendContract` stay in `:domain:engine` commonTest — the
  contract file also hosts `InMemoryLedgerBackendTest`, and the driver tests (jvm/ios) that
  consume the contract stay with the SqlDelight impls until step 4; test source sets cannot be
  shared across modules, so moving any of the four would strand the others.
  `RawAssetMappingTest` returned to `:domain:gallery` commonTest: it drives
  `ResourceEnumerator` + `InMemoryRawAssetSource` (staying classes) and `:domain` has no
  project deps to reach them.
- **D10 — zero spec deltas** (orchestrator-confirmed; step-2 precedent). Specs whose prose names
  legacy placement keep describing the pre-migration layout until the migration's own
  reconciliation point; the accounting is in proposal.md's Capabilities section.
