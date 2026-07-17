# Tasks — domain-skeleton-model-ports

## 1. Phase A — the move-commit (committed as e9bb56a)

- [x] 1.1 `:domain` module skeleton: `settings.gradle.kts` include, `domain/build.gradle.kts`
      (jvm + iosArm64 + iosSimulatorArm64, zero project deps, no iosMain), zone dirs on the
      gates' pinned scope
- [x] 1.2 22 model + 22 ports production files moved (whole-file `git mv`, all R100)
- [x] 1.3 11 zone-pure commonTest files moved alongside

## 2. Phase B — import-fix commit

- [x] 2.1 Ride-along splits: `Reconciler.kt` (marker → ports), `GalleryResourceEnumerator.kt`
      (port → ports, `resourcesFrom` → model, impl + fake stay), `AttestSeams.kt` (fake stays)
- [x] 2.2 `LedgerBackend` → `model/` (D3 divergence)
- [x] 2.3 Package declarations → flat `app.snapsync.model` / `app.snapsync.ports`
- [x] 2.4 Tree-wide import rename (~170 files, symbol-mapped; `.sq` schema imports included)
- [x] 2.5 Build-file rewiring: 24 modules gain `project(":domain")`; 5 dead edges removed;
      `app/desktop` declares ktor-client-core
- [x] 2.6 `LINK_ORIGIN` generator: `:domain` owns it (package `app.snapsync.model`); config's
      copy deleted
- [x] 2.7 CLAUDE.md module-list rows updated for every reference the step invalidated

## 3. Gates

- [x] 3.1 `./gradlew build` green — incl. the armed model-purity + ports zone gates
      (1 test each, 0 failures, non-vacuous scan)
- [x] 3.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 3.3 `./gradlew architectureDiagrams` regenerated; output in tree (freshness gate green)
- [x] 3.4 Beacon after: 91 → 85 (module 33→32, edges 6→1, others unchanged; no law increased)
