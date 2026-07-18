# Design: rehome-ui-modules

## Context

Migration step 9 (PLAN.md). The presentation-imports gate (`ZonePresentationImportsTest`) was
created pending at step 0 with its scope pinned to `ui/presentation/src`; steps arm gates by
creating code, never by editing gates. The step therefore had to make presentation *actually
lawful* under the gate's letter before the move landed it in scope. Inputs from the step-8 C3 law
review (advisory A1): presentation's `commands: UserCommands = UserCommands()` inert default, and
`forgeStatusHost`'s presentation seat.

## Goals / Non-Goals

- Goals: re-home the three UI modules to the target names; make `ui/presentation/src` pass the
  armed gate with zero gate edits; repay the CutoffFormatter through-ports violation; retire the
  Arrow/ArrowLevel dupe (ledger −1); keep the tree device-behavior-preserving.
- Non-Goals: package renames (directory scope suffices; pure-move fidelity wins); the harness
  collapse (`:adapter:fake`, step 10); any change to reduction semantics, copy, or timing.

## Decisions

- **D1 — packages keep their pre-move names.** The gate scope is the directory
  `ui/presentation/src` (architecture-guards D6); no spec names target packages for the UI
  modules; and a tree-wide package rename would break pure-move verifiability for zero law gain.
  Mirrors step-4 D2.
- **D2 — `UserCommands` seats in `model/`, not `flow/` and not presentation.** The
  module-architecture law says presentation receives "the injected flow command bundle", but the
  gate's letter (the import-level approximation the spec pins) forbids `ui/presentation` naming
  the `flow/` package at all, and gates arm with zero edits. The bundle is a pure record of
  command callables (vocabulary); `model/` is the only zone nameable by both `compose/` (which
  must build instances) and presentation (which must declare the constructor parameter). A
  presentation-owned bundle type was rejected: `compose/` cannot name presentation (`:domain` has
  zero project deps), so "instances built only in compose/" would break.
- **D3 — the inert `UserCommands()` default stays in presentation** (A1 resolution). With the
  bundle in `model/`, the default constructs a model-typed null object — the identity bundle the
  type itself defines — not command wiring; the "built only in compose/" clause governs flow
  command instances, and the gate's letter (which decides, per the review note) sees a legal
  `model/` reference. Removing the default would force ~20 construction-site edits to re-state
  inertness explicitly, for no enforcement gain.
- **D4 — read-models cross as bare `StateFlow`s.** The law: presentation observes feature
  read-model StateFlows directly. Config and permission are port-exposed StateFlows; handing the
  flow (model + kotlinx types) into the host keeps the observation direct while removing the
  port-type names the gate forbids. Feature-owned read-model *types*
  (`SyncStatusSource`, `CreationStatusSource`, `DownloadStatusSource`) stay as typed params — the
  gate and the law both permit feature read-model types.
- **D5 — permission taps become bundle commands.** `requester.request()`/`openSettings()` were
  user taps calling a port from presentation — the exact bypass "Commands cross one door"
  forbids. They join the bundle (`requestAccess`/`openSettings`), bound to the
  `PhotoAccessRequester` port in `compose/` (`AppPorts` gains the field); `onAcknowledgeAccess`
  fires the same command. `loadJoinDetails` stays an injected query — it returns a value the gate
  reduces on.
- **D6 — `JoinLoad` → `model/`; `toJoinLoad` → `feature/membership`.** The mapping names
  `ports.EventDetails`, so it had to leave presentation; a shell seat is barred (a `when` mapping
  is a decision; shells are zero-conditional), and `compose/` cannot see a presentation-owned
  `JoinLoad`. Seating the outcome vocabulary in `model/` and the port-outcome mapping in the
  membership feature (whose use-case owns the details fetch) satisfies every import law; the
  world inspector keeps its own inline mapping (test equipment, exempt).
- **D7 — `CutoffFormatter` is a pure presentation class over injected now/zone; the ports are
  `Clock` + `TimeZoneSource`.** The formatter cannot take port *types* (the gate forbids
  presentation naming `ports/`), and a `model/` seat cannot either (model imports nothing
  project-internal); so the ports are declared need-named in `ports/`, implemented in
  `:adapter:generic` (`SystemClock`/`SystemTimeZone` — platform-free technology impls), and the
  *shells* bind them into the formatter as plain function/value inputs ("shells construct
  adapters, supply thunks"). `zone` stays a construction-time value, matching the previous
  `SystemCutoffFormatter()` capture semantics exactly (behavior-preserving). The interface/impl
  split collapsed: nothing ever implemented the interface but the one production class, and tests
  always used the real impl on a fixed clock.
- **D8 — the shared formatter is root-owned, not `AppCore`-owned.** `forgeStatusHost` (which
  stays a presentation-seated factory per CLAUDE.md, now taking the formatter as a parameter)
  must render the create screen's wall clock, and `ForgeShell` holds no route to the live graph
  by design — an `AppCore` seat would either boot the graph in forge mode or duplicate the
  binding. `SnapSyncRoot` binds once; host, screen, and forge share the instance.
- **D9 — `Arrow` (model/) is the unification survivor; `ArrowLevel` dies.** Presentation is
  Compose-free (cannot depend on components) and components is presentation-free (design-system
  direction), so `model/` is the only shared seat; the enum is sync vocabulary (remaining-work ×
  live-activity per direction), unlike `SyncDirectionChoice`, whose config-capability decoupling
  rationale stands and is untouched. `:ui:components` gains `api(project(":domain"))`; the
  screens-side `toLevel()` mapping dies — which is the point: two declarations rendered by two
  layers can drift, one cannot. The components-internal composable formerly named `Arrow` was
  renamed `ArrowIcon` to avoid shadowing the imported enum.
- **D10 — the presentation gate's test-inclusive scope is honored, not narrowed.** The gate walks
  all of `ui/presentation/src`; its two tests assembling the real `CreateEvent` over a stubbed
  `EventCreation` port would have armed the gate red. Rather than carving a test exemption into
  an armed gate (forbidden mid-move), the tests were re-seated as bundle-level choreography —
  the mint is `CreateEventTest`'s, the full create→gate→join stack is
  `:test:integration`'s `create_event_lifts_the_setup_gate` — recorded in the
  `architecture-guards` delta as a deliberate sharpening.
