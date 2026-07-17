# Proposal: pin-runtime-identity-and-zone-gates

## Why

Migration steps 1–6 move every string the installed base depends on — App-Group id, Keychain
service/account pairs, `NSUserDefaults` keys, DB filenames, OS-registered task/session ids,
framework baseNames — and **none of them is asserted by any test today**. They are all `iosMain`
defaults, invisible to the JVM loop; drift in one (e.g. the device-id Keychain pair) mints a new
device identity and corrupts the event union for every member, remotely unfixably. Step 0 of the
migration plan (`test/architecture/migration/PLAN.md`) converts this worst
silent-device-corruption class into a compile-loop failure before any file moves, and creates the
five zone gates later steps arm — so steps 3a/5/6/9 arm them by creating code, not by writing
gates mid-move.

## What Changes

- **Runtime identity pins**: a new `:test:architecture` text guard (`RuntimeIdentityTest`)
  asserting every runtime-identity literal appears **exactly once** in production Kotlin with its
  exact value, plus pinned occurrences in `build.gradle.kts` files, `iosApp` entitlements, and
  `Info.plist`. Keychain entries are pinned as (service, account) **pairs** — the pair is the
  unit of identity. BGTask ids are additionally asserted to match between Kotlin and
  `Info.plist` (drift between the two silently kills that background tier).
- **Consolidation to make exactly-once true** (the only production edit): `domain/download-store`
  drops its private `group.app.snapsync` copy and imports `LEDGER_APP_GROUP` from
  `:domain:engine` like the six existing importers (new iosMain-only dep, dies at step 4);
  `IosLedgerBackend`'s in-file `"ledger.db"` double becomes one private const. No `:platform`
  module — the `module-architecture` closed module set rejects a const-holder module.
- **Five pending zone gates** in `:test:architecture`, self-arming on the `FakeHonestyTest`
  pattern (scope-absent prints PENDING, non-vacuity assertion once the scope dir exists, arms on
  first file with zero gate edits): model-purity, ports→model, feature-blindness, flow-no-ports,
  presentation-imports (import-level approximation: never `ports/` or `flow/`).
- **PLAN.md step-0 row amendment** in the same PR: scope grew from "guards only" to
  consolidate + guard, and the pin inventory grew beyond the plan's enumeration (sweep found
  `downloads.db`, the album-map defaults key, BGTask/URLSession ids, and the device-manifest
  App-Group layout — all the same corruption class).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `architecture-guards`: two new requirements — (1) the runtime-identity pin guard, enumerating
  the full pinned inventory (the spec is the contract of record for exactly the strings that
  must never change; adding a pin later is a spec delta, deliberately); (2) the five zone gates
  exist now as pending self-arming guards with pinned scan scopes
  (`domain/src/*/kotlin/**/{model,ports,feature,flow,compose}/`, `ui/presentation/src/**`),
  arming without gate edits when their scope gains code.

## Impact

- `test/architecture/src/test/kotlin/…`: one new pin guard + five new zone-gate tests (gating —
  they run in `./gradlew build`).
- `domain/download-store`: `build.gradle.kts` gains an iosMain dep on `:domain:engine`;
  `IosDownloadStore.kt` imports `LEDGER_APP_GROUP` instead of its private const.
- `domain/engine/src/iosMain/…/IosLedgerBackend.kt`: `"ledger.db"` literal consolidated to one
  private const (behavior-identical).
- `test/architecture/migration/PLAN.md`: step-0 section updated to the as-built scope.
- No behavior change on device: byte-identical runtime identity is the point. Green
  `./gradlew build` + `compileIosMainKotlinMetadata` required; no diagrams impact (no module or
  dependency-graph change visible to the generators — verify with `./gradlew architectureDiagrams`
  in case the new download-store→engine edge renders).
