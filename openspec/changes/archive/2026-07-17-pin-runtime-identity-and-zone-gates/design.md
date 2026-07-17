# Design — pin-runtime-identity-and-zone-gates

## Context

Step 0 of `test/architecture/migration/PLAN.md`. Steps 1–6 move every string the installed base
depends on; none is asserted by any test (all `iosMain` defaults, invisible to the JVM loop).
Every merge ships to TestFlight, and identity drift is the one failure class that is remotely
unfixable (a drifted device-id pair mints a new device identity and corrupts the event union for
every member of the event). The plan also requires the five zone gates to exist *before* the
zones do, so steps 3a/5/6/9 arm them by creating code rather than by writing gates mid-move.

This design was settled in an interview (2026-07-17); the decisions below record its outcomes,
including two places where the interview **amended the plan as written**: the step grew from
"guards only" to consolidate-then-guard, and the pin inventory grew beyond the plan's
enumeration after a code sweep.

## Goals / Non-Goals

**Goals:**

- Convert runtime-identity drift from silent device corruption into a compile-loop failure, for
  every subsequent migration step.
- Create the five zone gates as pending, self-arming, non-vacuous guards with their scan scopes
  pinned now.
- Record the pinned inventory in the `architecture-guards` spec as the contract of record.

**Non-Goals:**

- No zone code, no `:domain` module, no moves — the gates are born pending.
- No behavior change on device; the two production edits (const consolidation) are
  byte-identical at runtime.
- No new module (`:platform` was considered and rejected — see D2).
- No pinning of dev/test trigger names (`SNAPSYNC_*` env vars): they are injectable only via a
  developer launch, so the installed base holds nothing keyed by them.

## Decisions

### D1 — Consolidate first, then assert exactly-once (amends the plan's "guards only")

The plan said the guard asserts each literal "exactly once", but that was already false:
`group.app.snapsync` appeared in two production Kotlin files and `"ledger.db"` twice in one
file. Alternatives: (a) exact-count pins per literal (guard records ×2 where duplicated), (b)
consolidate the duplicates so exactly-once becomes true, (c) at-least-once. Chosen: **(b)** —
interview decision, against the interviewer's recommendation of (a). Rationale: exactly-once is
the semantics worth having (future drift is single-sited by construction); count-pins would
normalize duplication and make every future move renegotiate counts. Cost: step 0 carries two
small production edits, so PLAN.md's step-0 row is amended in the same PR (the plan's own
divergence rule).

### D2 — The App-Group const's interim home is `:domain:engine`; no `:platform` module

`domain/download-store` held the only private copy of `group.app.snapsync`; six other modules
already import `LEDGER_APP_GROUP` from `:domain:engine`. A `:platform` const module was
considered (the literal is platform identity, not engine semantics) and rejected: the
`module-architecture` module set is closed and its first scenario rejects exactly this — a
module that withholds no dependency. It would also grow the beacon's module-set distance and
need a `targetModules` edit, only to die at step 4 when the adapters become the const's real
home. Chosen: download-store adds an iosMain-only dep on `:domain:engine` and imports the
existing const. The edge is interim by design — step 4 moves all `iosMain` into the adapter
modules together and the edge dies with it.

### D3 — The (service, account) pair is the unit for Keychain pins

Count-only pins cannot catch a cross-swap (account `token` moving under another service keeps
every string's count identical while still corrupting identity). Full consolidation of service
strings into consts (`ATTEST_SERVICE`) was rejected: `app.snapsync.attest` legitimately appears
in two pairs, and a const would break the literal pair regex, forcing a two-layer guard
(const→value link plus pair). Chosen: keychain literals stay inline; one regex per pair
(`service\s*=\s*"…",\s*account\s*=\s*"…"`), each matching exactly once tree-wide. The regexes
are formatting-sensitive; each pair is a single constructor-default line today, and a
reformat-triggered red forces a look — which is the guard's job.

### D4 — Scan surfaces: production Kotlin + build files + entitlements + Info.plist

Framework `baseName`s live only in `build.gradle.kts`; the App-Group id also lives in the two
entitlements files (drift there changes the container path just as fatally); the BGTask ids live
in both Kotlin and `Info.plist`, and the OS consults the plist — so those pins assert
cross-surface equality. "Production Kotlin" = `src/*Main` source sets outside `test/` and
`build/`. Test sources are excluded: they may legitimately mention literals, and the guard's
subject is what ships.

### D5 — The inventory extends beyond the plan's list (sweep-complete)

The plan enumerated the app-group id, keychain pairs, two defaults keys, `ledger.db`, and the
baseNames. A sweep found the same corruption class unlisted: `downloads.db`, the album-map
defaults key `app.snapsync.album.map`, the BGTask ids (`app.snapsync.upload.heartbeat`,
`app.snapsync.download.backstop`), the background URLSession ids (`app.snapsync.upload.session`,
`app.snapsync.download.bg`), and the device-manifest App-Group layout (`device-manifest/`,
`accumulator.json`, `last-uploaded.json` — the manifest is the physical fact of membership;
losing the accumulator shrinks the event union). All included. The spec enumerates the full
inventory (interview decision): these strings ARE the never-change contract, so the contract of
record must actually contain them; adding a pin later is a spec delta, which is the right
friction. Excluded consciously: `debug.log` (diagnostics, reinstall-safe), `SNAPSYNC_*` launch
env names (dev-only triggers).

### D6 — Zone-gate scopes are pinned to the conventional layout, as named assumptions

The gates must know their scan paths before the `:domain` module exists; a wrong guess is the
fail-open case (pending forever). A top-level `src/` tree (`src/domain`, …) was raised and
rejected: it needs custom `settings.gradle.kts` wiring and contradicts two committed artifacts —
`FakeHonestyTest` already pins `adapter/fake`, and the plan's diagram rule hand-lists top-level
roots (`app/capability/domain/test`, `adapter` at step 4, `ui` at step 9). Chosen: `:domain`
roots at `domain/` with `src/` beside the legacy submodule dirs until they empty; gates scan
`domain/src/*/kotlin/**/{model,ports,feature,flow,compose}/` and `ui/presentation/src/**`. Each
gate's comment names this assumption so a step-3a deviation is a conscious gate edit, not silent
vacuity.

### D7 — The presentation gate ships as the import-level approximation

The full law ("presentation references only the injected command bundle, feature read-model
types, and `model/`") needs call-site knowledge a text gate does not have. Chosen:
`ui/presentation` sources never reference `ports/` or `flow/` packages, imported or
fully-qualified. The finer no-feature-command-invocation rule stays a review concern until it
has a mechanical form. Consistent with the spec's "source-text gates with derived scopes";
Konsist-style call analysis was rejected as a parser project inside what should be the
migration's highest-leverage hour.

### D8 — One OpenSpec change, `architecture-guards` delta only

The zone-gate *semantics* already live in `module-architecture`; duplicating them into the
guards spec would recreate the intra-tree drift class the audit swept. The delta adds what is
new: the pin inventory and the pending/self-arming existence contract. `module-architecture`
is untouched.

## Risks / Trade-offs

- **[Pair regexes break on reformat]** → each pair is one line today; a red forces a look, and
  the fix is updating the regex in the same PR — acceptable, and preferable to a guard blind to
  cross-swaps.
- **[Gate scopes guessed wrong]** → D6 pins them as named assumptions in gate comments; if step
  3a picks a different root the gates go pending-forever *visibly* (PENDING lines name the
  scope), and step 3a's diff must touch the gates — a conscious edit, reviewed against D6.
- **[New download-store→engine edge reads as architecture drift]** → it is recorded here as
  interim (dies at step 4); `buildHealth` warn-only tolerates it (real use, iosMain, outside its
  jvm/common scope).
- **[String-template occurrences]** — a literal reconstructed by concatenation would evade the
  scan. Accepted: no pinned literal is built that way today, and the exactly-once assertion
  fails if the plain occurrence disappears, which is the same signal.
- **[PENDING lines are stdout-only]** → invisible in a green CI run; accepted for consistency
  with `FakeHonestyTest`. The beacon, not the pending prints, is the migration's visibility
  surface.

## Migration Plan

Single PR: consolidation edits + `RuntimeIdentityTest` + five zone-gate tests + PLAN.md step-0
amendment (row ticked, scope note). Green `./gradlew build` + `compileIosMainKotlinMetadata`.
No device session, no soak, no freeze (nothing moves; runtime identity is byte-identical).
Rollback = revert the PR; nothing on device depends on it.

## Open Questions

None — settled in the 2026-07-17 interview.
