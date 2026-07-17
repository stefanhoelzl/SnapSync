# Proposal: domain-skeleton-model-ports

## Why

Migration step 3a (`test/architecture/migration/PLAN.md`): the target architecture's
`module-architecture` spec demands one platform-free `:domain` module whose finer structure is
zone packages (`model/` ← `ports/` ← `feature/` ← `flow/` ← `compose/`) guarded by text gates.
This step births the module and its first two zones — the vocabulary/services/codecs and every
port seam — so the later feature/flow/compose steps have a home to move into, and kills five of
the six illegal graph edges the beacon counts.

## What Changes

Behavior-preserving placement only — no signature, visibility, or semantics change anywhere.

- **New `:domain` module** at `domain/` (beside the legacy `domain/*` children, per the
  guards spec's pinned D6 scope): `jvm() + iosArm64 + iosSimulatorArm64`, zero `project()`
  dependencies, no `iosMain` source directory. Two flat zone packages: `app.snapsync.model`
  (22+ files: the whole `app.snapsync.config` commonMain surface incl. the `EventLink` codec and
  the generated `LINK_ORIGIN`; sync vocabulary + `SyncEngine` + `LedgerWriter` + `LedgerBackend`;
  selection policy + album denylist + upload keys + `RawAsset` vocabulary + device manifest +
  `resourcesFrom` mapping; `EdgeUploadRequestProvider` + filename encoding;
  `SyncStatus`/`SyncProgress`; `PermissionStatus`; `UPLOAD_LIVENESS_DARWIN_NAME`) and
  `app.snapsync.ports` (26 files: config, keychain, gallery enumeration/status/manifest,
  permission, download + download-store, the backend-need clients, upload platform/scheduler/
  discovery, push, attest seams, join marker). Current port names kept — renames are step 3b.
- **Three ride-along splits** (files step 2 missed, orchestrator-approved): `Reconciler.kt`
  (marker out), `GalleryResourceEnumerator.kt` (port + pure mapping out; impl + fake stay),
  `AttestSeams.kt` (fake out). Impls and `InMemory*` fakes stay in their old modules.
- **`LINK_ORIGIN` generation moves to `:domain`** (emitting `app.snapsync.model`);
  `:capability:config`'s generator copy is deleted.
- **Tree-wide import/package rename** (~170 files) + build-file rewiring: 24 modules gain
  `project(":domain")`; the five dead edges die (`join→config`, `membership→config`,
  `presentation→config`, `upload→push`, `download→push`); `app/desktop` declares the Ktor client
  dependency it had ridden transitively through the dead `download→push` edge.
- The model-purity and ports→model zone gates in `:test:architecture` arm themselves on the new
  zones (zero gate edits) and pass.

## Capabilities

**No spec deltas.** Placement-only, per the step-2 precedent and PLAN.md ("spec deltas" are
reserved for the behavior steps 11a–12). Per-capability accounting for every touched module:

- `photo-selection-policy`, `gallery-status`, `device-manifest`, `sync-engine`, `sync-ledger`,
  `event-link`, `join-event`, `event-membership`, `event-rejoin-reconciliation`,
  `photo-download`, `echo-suppression`/`download-store`, `push-registration`,
  `upload-completion-notify`, `device-attestation`, `event-album`, `event-creation-ui`,
  `edge-upload-provider`, `upload-lifecycle`, `sync-status`, `permission-seam`,
  `device-identity`: contracts (declarations, signatures, behavior, runtime identity) unchanged;
  only packages/modules moved. Where a spec's prose names the legacy placement (e.g.
  `gallery-status`'s "package `app.snapsync.gallery`", `edge-upload-provider`'s "lives in
  `:capability:upload-url`"), that prose describes the pre-migration layout; placement authority
  during the migration is the `module-architecture` spec (decision D8 of
  `establish-target-architecture`: nothing gates mid-migration), and the placement prose is
  re-derived at migration completion (step 13b re-derives CLAUDE.md and the diagrams; spec
  placement lines reconcile with the step that finishes each capability's move).
- `architecture-guards`: no delta — the zone gates and their pinned scopes were specced in step 0
  precisely so this step arms them by creating code; the status-engine boundary guard and the
  runtime-identity pins still pass unchanged.
- `module-architecture`: no delta — this step implements it.

## Impact

- 55 phase-A moves (all R100 renames) + 3 splits + package/import fixes across ~170 files.
- Beacon: total 91 → 85 — module set 33 → 32 (`:domain` created), illegal edges 6 → 1 (the
  surviving `presentation→event-creation-ui` dies at step 6, per PLAN ⟨R⟩), all other laws
  unchanged (no law increased).
- Gates: `./gradlew build` green (incl. armed model-purity + ports gates),
  `compileIosMainKotlinMetadata` green, `architectureDiagrams` regenerated (4 diagram files).
- One divergence from PLAN's zone list, recorded in design.md D3: `LedgerBackend` sits in
  `model/`, not `ports/`.
