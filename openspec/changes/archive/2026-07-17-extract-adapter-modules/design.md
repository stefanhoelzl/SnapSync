# Design — extract-adapter-modules

## Context

Migration step 4, the riskiest structural step: every iosMain move, with the installed base's
runtime identity riding on these files. The step-0 pins (`RuntimeIdentityTest`) are the safety
net; the reviewers verify byte-level move fidelity. The coverage question this design settles is
**which impl lands in which adapter module**, since the target spec pins the module set and the
placement *rule* (by linkage) but not the per-file inventory.

## Goals / Non-Goals

**Goals:** move every iosMain and Ktor/SQLDelight impl out of `domain/*`/`capability/*` into the
three adapter modules; delete the two emptied `:app:ios:*` modules; arm the extension-safety gate;
keep the diff verifiable as pure motion.

**Non-Goals:** no package normalization (see D2); no feature moves; no module deletions beyond
the two named; no behavior change of any kind.

## Decisions

### D1 — Placement by linkage, resolved as "which process links it today"
`ext-safe` = exactly the impls the extension framework (`SnapSyncUploadKit`) links today, direct
or transitive. `app-only` = impls only `SnapSyncKit` links. This keeps the extension binary's
contents identical to before the move (the lean-extension property is preserved structurally, not
by luck) and puts the extension-safety gate over exactly the surface the appex can contain.
Three files needed judgment because they use no UIKit/BackgroundTasks API yet are app-linked only:

- `IosDownloadTransport` — owns the background `URLSession` id `app.snapsync.download.bg`, which
  the OS reattaches to the **app** process across relaunch; an extension-side link of a
  session-owning adapter would be a second claimant of an OS-held identity. App-only.
- `IosPhotoLibraryImporter` — the download feature's import writer; the extension never links
  `:capability:download`. App-only.
- `PhotoLibraryPermission` — permission *requesting* is meaningful only where the system sheet
  can present (the app scene); the extension never links `:domain:permission`. App-only.

`IosUrlSessionUploadPlatform` (Foundation-only) and `IosBackgroundScheduler`
(`platform.BackgroundTasks`) are pre-decided app-only by PLAN step 4 ("its entire source is the
two app-only adapters"). *Rejected:* placing the three Foundation-only files in ext-safe because
they *could* link — it would grow the appex image with code it never runs and put never-linked
code under the extension gate.

### D2 — No package renames: pure `git mv`, packages keep their legacy names
The spec pins adapter *modules*, not adapter packages; every gate and diagram in the repo scopes
by directory, never by package. Keeping packages (`app.snapsync.engine`, `app.snapsync.ios.discovery`,
…) makes every moved file byte-identical including its package line and leaves every consumer
import untouched — the entire diff is `git mv` plus build files, which is the strongest reviewable
claim of zero semantic drift available on the step the plan calls the riskiest. Kotlin permits one
package across modules. Package normalization is deferred to the feature-move steps (5/6), which
already do tree-wide renames under the same review protocol. *Rejected:* per-module flat packages
(`app.snapsync.adapter.*`) — ~50 import-line edits across the two composition roots for zero
enforcement gain, on the step where minimal-diff matters most.

### D3 — SQLDelight: both databases in `:adapter:generic`, disjoint `srcDirs`, generated packages kept
The generated packages (`app.snapsync.engine.db`, `app.snapsync.downloadstore.db`) are not runtime
identity (the pinned db *filenames* are) and renaming them would force import edits in six files
for no gain — kept. Two `create()` databases in one module need disjoint source dirs:
`src/commonMain/sqldelight/ledger/**` and `src/commonMain/sqldelight/download/**`, each set via
`srcDirs`. The `.sq`/`.sqm` files move verbatim.

### D4 — Contract-coupled tests stay; self-contained tests move
`SqlDelightLedgerStoreTest` (jvmTest), `NativeLedgerStoreTest` (iosTest) and the download-store
jvmTests extend contracts living in their modules' `commonTest` (`LedgerStoreContract`,
`DownloadStoreContract`), which cannot be referenced across modules (test source sets are not
published). They stay in `:domain:engine`/`:domain:download-store` with test-scope dependencies on
`:adapter:generic` — same-package resolution keeps their bodies untouched. The four self-contained
MockEngine tests of moved Ktor clients (`HttpEventDirectoryTest`, `HttpLeaveNotifierTest`,
`HttpEventUnionSourceTest`, `HttpEventCreationTest`) move to `:adapter:generic` commonTest;
`IosKeychainTest` (no cross-source-set need, and its subject is the containment module) moves to
`:adapter:ios:ext-safe` iosTest. `PushRegistrationTest` stays (it primarily tests staying code)
and `:capability:push` gains a commonTest dep on `:adapter:generic`.

### D5 — The interim `iosMain` edges die here, as promised
Step 0's `download-store → engine` iosMain edge (`LEDGER_APP_GROUP` import) and the album/
membership/discovery imports of the engine consts become intra-module references inside
`ext-safe` (the consts move with `IosLedgerStore.kt`, their exactly-once pin intact).

### D6 — Emptied source sets: minimal build cleanup, skeletons kept
`:domain:permission` and `:capability:config` lose their last production source; their build files
shrink to bare target declarations (deps pruned — nothing left consumes them). `:domain:engine`
loses all production sources but keeps its commonTest (contracts + `SyncEngineTest`); its
SQLDelight plugin/config leave with the store. Dead dependencies on production-emptied modules are
pruned from consumers (`:domain:gallery`'s `api(:domain:engine)`, `:app:ios`'s
`:domain:permission`/`:capability:config`, …). Deleting these skeletons is steps 5/6.

## Risks / Trade-offs

- [Legacy package names inside adapter modules] → accepted (D2); dies with the feature-move
  renames.
- [`:domain:engine` tests now test another module's impl] → interim by design (D4); the tests
  follow their subjects when the features move.
- [Extension framework relink] → the framework baseNames and every runtime literal are pinned;
  Session A (the step-4 pause) is the on-device proof: same device id, no cursor reset, no
  re-upload, marker intact.
