# Design — port-need-renames

## Context

Migration step 3b. PLAN's row names the targets (`LedgerStore`, `PhotoLibrary`, `PhotoAccess`,
`BackgroundTransfer`, `EventDirectory`/`Enrollment`/`TransferNotify`/`EventCreation`); the
`module-architecture` spec carries the law but no port inventory, so PLAN's explicit list is the
whole rename set — every port it does not name keeps its name.

## Goals / Non-Goals

- Goal: the PLAN-named ports carry their need-names; every reference, filename, doc row, and spec
  contract line follows; zero behavior or signature drift; beacon Δ = 0 on every law.
- Non-goals: seat changes beyond the mandated `LedgerStore` trial; port splits/merges (the
  permission pair stays two ports; `PushHttpClient` stays whole); method renames (the spec names
  none); partial-embed impl renames; spec-prose reconciliation beyond the renamed identifiers.

## Decisions

- **D1 — `LedgerStore` stays in `model/`.** The mandated ports/-seat trial was executed: file moved
  to `ports/`, `LedgerWriter` given the `import app.snapsync.ports.LedgerStore` it needs to
  compile, `ZoneModelPurityTest` run — red, as 3a's D3 predicted (`model/` may reference nothing
  project-internal outside `model/`, and the gate scans import lines). Trial reverted; rename done
  in place; seat moves at step 5 with the writer.
- **D2 — mapping derivations beyond PLAN's literal arrows** (PLAN gives targets, not pairings):
  "gallery enumeration" = `GalleryResourceEnumerator` (its KDoc: "the library resource-enumeration
  seam"; `RawAssetSource`, the decision-free walk, is a different port and keeps its name);
  "permission pair" = `PermissionRequester`+`PermissionStatusSource`, renamed by stem substitution
  `Permission`→`PhotoAccess`; backend seams pair as details→`EventDirectory`,
  create→`EventCreation`, manifest-PUT (the physical fact of membership)→`Enrollment`.
- **D3 — `TransferNotify` deferred.** `PushHttpClient` is the only notify-shaped port and it also
  carries registration `PUT`; a rename would mislabel half its surface and a split is not a rename.
  Recorded in PLAN's 3b row; lands with the backend-port split.
- **D4 — full-substring rule for followers.** An impl/fake/contract-test is renamed iff its name
  embeds a renamed port name whole (`InMemoryLedgerBackend`→`InMemoryLedgerStore`); fragments
  (`IosUrlSessionUploadPlatform`, `PhotoLibraryResourceEnumerator`) are left — any new name there
  would be invented, not derived, and "mechanical" is the review claim of this PR. Lowercase-camel
  parameter/variable names are untouched: renaming a parameter breaks named-argument call sites (a
  signature change).
- **D5 — the beacon's ledger regex updated in-PR** (`class \w*DeviceManifestUploader` →
  `class \w*Enrollment`): PLAN calls the deletion-ledger regexes loud-stale lists that the touching
  step updates; without it the ×4-keep-1 uploader debt would silently vanish from the ledger and
  the beacon would report an unearned −1.
