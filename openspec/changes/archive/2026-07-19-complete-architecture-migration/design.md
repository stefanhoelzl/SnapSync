# Design — complete-architecture-migration (step 13b, the finale)

## Context

Steps 0–13a left beacon distance 15: module-set 1 (`:capability:push`) + shells 14 (14 kt, 0
swift). PLAN.md's 13b row named the rest: gates arm as permanent, detekt flips gating, the
flow-transcriber failure arms, diagram generators drop current-state editions, the Keychain
write-through ends, then the beacon + PLAN + RUN + `verify` die. 13b was split from 13a so a
falsely-firing armed gate reverts without un-renaming the Xcode project.

## Goals / Non-Goals

- Goals: every beacon law measurably 0, then permanently gated; the one staged behavior flip
  (reinstall = left) landed honestly; the migration apparatus deleted; CLAUDE.md's module section
  rewritten to the target graph with every runbook intact.
- Non-Goals: renaming `PushHttpClient` to a need-name (see D2); Session-B/C cosmetics beyond
  trivial reach — the LogContext prefix bleed stays a known cosmetic (explicitly out of scope per
  the step brief); any new feature behavior.

## Decisions

### D1 — `:capability:push` re-homes to `:domain` `feature/push`

`PushRegistration` (register-on-token/credential policy) and `EventNotifier` (best-effort notify)
are pure logic over ports (`PushHttpClient`, `PushTokenSource` — both already in `ports/`), which
is the definition of feature material; neither withholds a dependency, so the module fails the
module-set law's bar and dies. The `deviceConfigJson` codec stays `internal` beside its only
consumer (a private DTO pair is not model vocabulary). Tests split by subject: the policy tests
(fake port) → `:domain` commonTest; the three Ktor status-mapping tests → `:adapter:generic`
commonTest beside `KtorPushHttpClient` (they test the adapter, and `:domain`'s zero-project-deps
law forbids it hosting them). Consumers (`SnapSyncRoot`, `UploadExtensionRoot`,
`UrlSessionUploadController`, `PushRegistrationIntegrationTest`) re-import; build files drop the
project dependency.

### D2 — `PushHttpClient` keeps its name (recorded deferral)

Step 3b left `TransferNotify` unassigned because the port carries two needs (registration PUT +
notify POST) and a rename cannot split it. The finale moves the *classes* to their lawful zone;
splitting the port into two need-named ports (and re-plumbing `KtorPushHttpClient`) is real
surface churn with zero behavioral payoff, and no beacon law measures port naming. Deferred,
consciously: the next change that touches the push seam should split it.

### D3 — The 14 shell decisions: resolution table

| # | Site | Resolution | Argument |
|---|------|-----------|----------|
| 1 | `SnapSyncRoot.refreshAttestation` (`\|\|`) | **DRAIN** → `DeviceAttestation.refreshOutcome()` (feature/trust) | The surface-`Unattested`-or-not rule is the trust feature's; semantics preserved verbatim (`ok \|\| !isStale(token())`), now tested (3 new tests) |
| 2 | `SnapSyncRoot.handleBackgroundUrlSession` (session-id routing `if`) | **PIN** (`@Suppress` + proof) | UIKit delivers ONE `handleEventsForBackgroundURLSession` callback for EVERY session id (API contract — no per-session registration surface); this app owns two OS-reattached sessions; mapping the OS-supplied discriminator to its session owner is transcription, inexpressible anywhere but where both session objects live. Expiry: dies with the 18–26.0 tier (iOS 27 GM re-eval) |
| 3 | `SnapSyncRoot.runLaunchEnvPolicyProbe` (`?: return`) | **PIN** (dev equipment) | The probe exists because the selection policy is unobservable without a joined event (creation is attest-gated — no headless route); it drives the real PhotoKit predicate + live graph from a launch-env trigger no tested module can reach. Inert in production |
| 4 | `SnapSyncRoot.presentShareSheet` (presenter `while`-walk) | **DRAIN** → `:adapter:ios:app-only` `presentShareSheet` | The walk is UIKit mechanism (presentation from a covered controller is rejected by UIKit); adapters may branch on technology vocabulary, and presenting UI is app-process-only — placed by linkage |
| 5–7 | `DevPhotoSeeder` ×3 | **PIN** (dev equipment, one proof block) | `PHAssetCreationRequest` writes the attached device's REAL library from a launch-env trigger; branches are operator-input validation + platform-forced chunking (the request retains each `UIImage` until commit — ~12.6 MB per above-floor image, measured; one transaction for thousands stalls/kills on an SE2) |
| 8 | `MainViewController` (transient-error `when`+`if`) | **DRAIN** → `StatusContainerHost.transientError` (`:ui:presentation`) | The law's own sentence: multi-step interactions are presentation-owned choreography and interaction state dies with the UI (step-12 D6 assigned it here). The Orbit side-effect channel dies with it (single-consumer channel, one consumer — the untested shell); the host exposes a self-clearing `StateFlow<String?>`, the shell renders it verbatim. One deliberate micro-divergence: a re-scan within the 4 s window now re-arms the full window (the old keyed `LaunchedEffect` did not restart on an equal value) — the honest reading of "self-clears after it LAST appeared" |
| 9–13 | `IosPhotoKitUploadPlatform` ×5 | **RECLASSIFY-BY-MOVE** → `:adapter:ios:ext-safe` | Not list-editing: the file IS an adapter — it implements the `BackgroundTransfer` port, is named for the technology, and its branches are technology-vocabulary mappings (job state, error class, key recovery), which the ports law explicitly permits adapters ("MAY branch on technology vocabulary"). Placed by linkage: the extension is its only linker, and ext-safe is the extension-linked adapter module (the extension-safety gate now covers it — it touches `platform.Photos` only). Its former `:app:*` seat put lawful adapter branching inside the zero-decision shell scope; the step-9 count-gaming warning is answered by the law argument, not a scope edit |
| 14 | `UploadExtensionRoot.process` (pending→PROCESSING `if`×2) | **DRAIN** → `requeueWhilePending` (`ports/`, beside `processingResultRawValue`) | The requeue is a rule ("this tier alone cannot observe a completion while not running"); the pure function reads pending ONLY on COMPLETED, takes an `onRequeue` hook so the debug.log line survives byte-comparably, and is pinned by 3 new tests. `process()` is now straight-line |

End state: `detektAppShell` = 0 errors with 5 pinned suppressions (SnapSyncRoot ×2,
DevPhotoSeeder ×3), `ignoreFailures=false`, wired into the root `check` (the root gains the
`base` plugin so `./gradlew build` includes it). `KotlinShellGuardTest` pins the suppression
inventory exactly (both directions) and carries the non-vacuity floor (the scanned roots must
exist and be non-empty — a stale `appShellSources` after a rename must fail, never pass
vacuously).

### D4 — The write-through ends; the READ fallback survives until a post-ship Stage-2 change

**Revised after the behavior-review bounce — the ship model escalates the original design.**
The first cut of this change deleted the whole Keychain composition (write-through AND read
fallback) and flipped Missing→None, on the 11a→13b staging's assumption of per-step TestFlight
soaks. That assumption is FALSE on this branch: the migration ships to `main` — and therefore to
every production device — as **one merge**, so at update time the entire joined installed base
consists of **pre-11a devices whose config file has never existed**. A fallback-less
missing-file read would have read every one of them as left on update: a silent, fleet-wide
logout, in the same merge that introduces the file. Shipping the fallback's deletion in the same
merge as its introduction orphans the installed base — so the deletion is severed from this
change entirely.

What lands here:
- **The write-through is ended** (the part of 13b's clause that is safe to ship-at-once):
  `save`/`clear` are file-only; `KeychainConfigStore` is deleted; the revert direction is
  sacrificed, consistent with fix-forward. The save's no-equal-early-return posture is kept.
- **The READ fallback is restored** in its 11a shape: `configReadViaFile(file, fallback, migrate,
  repair)` (the migrate-forward + compare-and-repair algorithm, pure and commonTest-pinned), fed
  by a minimal **read-only remnant** — `KeychainConfigReader`, a `ConfigReader` only (no save, no
  clear; it keeps the 11a in-place accessibility repair, an attribute-only write, so a legacy
  item stays readable on locked wakes). One consultation per unmigrated device, then the file
  answers alone.
- **Stage 2 is a named, designated POST-SHIP change**, gated on production soak: after every
  active joined device has executed at least one read on a ≥13b build (its membership migrated
  into the file), a follow-up change deletes `KeychainConfigReader`, collapses the staged
  requirement in `event-rejoin-reconciliation`, and retires the config pair's runtime-identity
  pin. Only then is reinstall = left true.
- **One deliberate deviation from the bounce's letter** ("NO save/clear" on the remnant): the
  leave path (`clear()`) SHALL still best-effort delete the legacy item, FIRST, before the file —
  because a file-only clear plus a live read fallback is self-undoing: the very next read finds
  file-missing + item-present, the exact resurrection state the 11a clear ordering existed to
  prevent (its D2), and every leave on a migrated device silently resurrects. The 11a semantics
  the bounce cites as template are only sound WITH that ordering. What stays ended is the
  write-through proper: no config VALUE is ever written to the Keychain again (`save` is
  file-only). Accepted residue, on record: a migrated device that SWITCHES events leaves a stale
  legacy item (save no longer maintains it), so a Stage-1 reinstall after a switch resurrects the
  *previous* membership — bounded (a genuine former membership; the switch already issued its
  backend leave; re-scanning converges) and dead with Stage 2.
- **RuntimeIdentityTest**: the (`app.snapsync.config`, `eventconfig`) pair pin SURVIVES (the pair
  is read — and leave-deleted — again), with a comment recording that the write-through ended and
  the pair dies with the post-ship Stage-2 change.

### D5 — The flow transcriber: derived scope, realized grammar, hard gate

Re-pointed from `SnapSyncRoot` (the hand-listed TRIGGERS) to the `flow/` directory listing — the
trigger inventory is now derived, per the law. Every function in a flow file is transcribed; a
construct outside the grammar throws `GrammarViolation`, failing `:tools:diagrams:generate` (the
CI `diagrams` job) AND the in-process freshness test under `./gradlew build` — the hard gate,
red-proofed (a planted `if` in `Background.run` failed generation naming file:line:construct).
An empty `flow/` scan also fails (non-vacuity).

The grammar's realized forms (spec delta, architecture-diagrams): straight-line calls; escaping
`scope.launch` (grammar-bound body, may open with one guard); `when` over a feature-returned
sealed result (branches: call / launch / `Unit`); a single LEADING guard (`val x = codec(...)` +
null-return pair, or a sole `<call>?.let{}` region); a best-effort wrap (`runCatching{call}
.onFailure{log-only}` — the absorb is diagnostics); a receiver-list fan-out loop; `log.*` omitted.

The three step-8 D7 debts are refactored INTO the grammar as named feature rules, behavior
preserved:
- Provision's switch guard → `feature/membership`'s sealed `switchDecision(current, next)`
  (`LeavePrevious(previous)` / `Stay`), the flow switching on it.
- Provision's `isGranted()` album step → `AlbumCoordinator.ensureAlbum(..., granted = isGranted())`
  — the access fact joins the coordinator's own leading guard (defaulting `true` for the grant
  subscription, which runs *because* access was granted).
- Provision's `name.isEmpty()` fetch trigger → `EventName.fetchNeed(name): TitleNeed`
  (MISSING/PRESENT), the flow switching on it; and Foreground's nested `?.let` →
  `storeEventNameIfChanged(id, fetched: String?)` tolerates the null no-result (part of the
  "whether a fetched name is persisted" rule).

### D6 — Diagram generators drop their current-state editions

Zones/Ports/Di lose the edition framing and the dead `capability/` scope; Features is rewritten
from module-cards to one card per `feature/` package (derived directory walk, non-vacuous), keeping
the forge name→sources map. The DI matrix's framing updates: compose/ now holds the feature graph;
the matrix keeps the per-root adapter surface visible.

### D7 — The promoted permanent gates (and their red-proofs)

- `ModuleSetTest` — settings include set == the target list (the loud-when-stale list the guards
  spec permits). Red-proof: a planted `include(":capability:zombie")` failed the build.
- `MixedPortImplTest` — no `interface` beside a Ktor/SQLDelight import under adapter/domain/ui;
  non-vacuity floor. Red-proof: a planted mixed file failed, naming it.
- `DeletionLedgerTest` — the retired items stay dead (zxing, kotlincrypto, `capability/`,
  LedgerReader, LoggingPushReceiver, EventMetadataSource, the LeaveNotifier interface,
  Arrow/ArrowLevel, Enrollment ×>1); patterns assembled by concatenation so the guard's own source
  never matches (the beacon's D3 self-match lesson — during this change the beacon itself caught
  the guard's first, quoting version at 5). Red-proof: a planted zxing toml line failed.
- `KotlinShellGuardTest` — D3's pin inventory. Red-proof: a planted suppression failed.
- Zone edges: no separate gate — the illegal capability↔capability / domain→capability edges are
  structurally unexpressible now (`:domain` has zero project deps by the module-set gate; the
  `capability/` tree's absence is a DeletionLedger row).
- The beacon's detached-from-check self-check dies with the beacon (nothing to detach).

### D8 — The final beacon measurement (the historic artifact)

Run after all drains with a fresh `detektAppShell`; every law 0; the beacon test passed green for
the first and only time, then the module was deleted with PLAN.md and RUN.md, and the `verify`
job was removed from `architecture.yml` (whose header now records the completion posture). The
report is quoted in the change's PR/report; the per-law table: module set 0 · zone edges 0 ·
shells 0 (0 kt via detekt, 0 swift) · mixed files 0 · deletion ledger 0 · posture self-check 0 —
Total: 0.

### D9 — Step-9 A1 pins confirmed as permanent posture (item c)

The `UserCommands()` inert default in `StatusContainerHost` (the model-typed identity bundle — a
null object, not command wiring, so constructing it in presentation sits outside the
built-only-in-compose rule's letter) and the `forgeStatusHost` seat in `:ui:presentation` (the
name→sources map must be constructible without any route to the live graph) both HOLD as
permanent posture. The armed presentation gate covers the import law; no further mechanism is
warranted. No code change.

### D10 — CLAUDE.md rewrite

The module list is rewritten to the target graph (step annotations and migration-era deltas
stripped; the `:test:architecture` row now enumerates the full permanent-gate roster); the
"CURRENT state, being replaced" paragraph is replaced by the completed posture; the laws-digest
preamble drops the beacon-era framing (bullet names untouched — LawsDigestTest green); the
testing-strategy rules 2–3 describe the composed-core integration shape; the logging section
drops step references; `app/ios/CLAUDE.md` updates the entitlements bullets (file-only config;
the Keychain group now serves device-id + attest only) and the extension root's description.
Historical forcing-proof citations (device sessions, archived decision records) are kept — they
cite the archive, which survives. Every operator runbook is intact.

## Final device pass (checklist for the operator session)

1. **THE HEADLINE UPDATE-PATH ITEM — the skip-the-soak test**: install a pre-11a-era IPA (Keychain-only
   config), join an event, then install THIS build directly over it (no intermediate 11a/12 build —
   exactly what the one-merge ship does to every production device). Expect: the first read (either
   process — try letting the OS-scheduled extension run first) logs
   `config migrated: legacy Keychain → App-Group file`, the membership is INTACT (no setup gate, no
   leave-side reconciliation, marker untouched), and subsequent reads answer from the file alone.
   Membership loss here is a ship blocker.
2. Xcode archive builds (13a's three pbxproj edits + this change's framework contents are
   Linux-blind; the archive is their proof).
3. Update-in-place over a ≥11a joined install → one OS-scheduled cycle → both debug.logs: same
   device id, no cursor reset, no re-upload, marker intact, config answered from the FILE.
4. **Reinstall (Stage-1 semantics)**: delete + reinstall → the membership RESURRECTS from the
   legacy item (this is the staged behavior in force; reinstall = left arrives only with the
   post-ship Stage-2 change) and the clear-and-seed reconciliation re-uploads nothing.
5. Leave on a MIGRATED device stays left: leave → both the legacy Keychain item (first) and the
   config file are deleted → relaunch + foreground reload stay on the setup gate (no fallback
   resurrection). This exercises the leave-path legacy-item deletion (D4's deviation).
6. Share sheet presents from the joined layer (the drained adapter path).
7. An invalid QR flashes the transient error and self-clears (~4 s; the presentation-owned path).
   **Also try a double scan within the window**: the second scan re-arms the full 4 s (the
   deliberate re-arm divergence, D3 row 8) — confirm it reads as intended, not as a stuck error.
8. Attestation: airplane-mode launch shows Unattested only when no usable token exists
   (refreshOutcome path).
9. PhotoKit tier: a drained cycle with in-flight rows still returns PROCESSING
   (requeueWhilePending path) — observe `process: N pending — requesting re-invocation` in the
   extension log.
10. Push registration: after launch, confirm the `[onPushToken]` entry and the registration `PUT`
    line (`push token registered`) in debug.log — the collector rides host assembly, and a
    refused PUT must re-send on the next credential (watch for the 401-then-retry pair on a fresh
    install).
