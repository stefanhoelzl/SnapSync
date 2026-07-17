# Design — create-flow-zone-and-drain-shell (migration step 8, C1+C2+C3)

## Context

Step 8 of the `module-architecture` migration creates the `flow/` zone and drains the iOS app shell
to wiring. The step landed as three separately reviewed checkpoints (each its own commit, per
PLAN.md): C1 repaid the step-5 `LogContext` debt and staged the resolver; C2 created the flows and
deleted `:capability:upload`; C3 finished the command bundle, the micro-rule sinks, the resolver
switch, and this ceremony. The shell was the last home of untested coordination: its entry points
carried ordering, six forge guards, and a tier flag re-derived per call.

## Goals / Non-Goals

- **Goals**: every OS-callback's coordination in tested `flow/` files; user taps crossing the same
  door as OS callbacks (the injected command bundle); composition selection as one pure, tested
  sealed resolver consumed at one switch; behavior byte-preserved (log prefixes, command semantics,
  rule outcomes, construction-timing).
- **Non-Goals**: arming the presentation-imports gate (step 9); the harness collapse onto
  `snapSyncApp` (step 10); re-pointing the `Flows.kt` diagram generator at `flow/` (a transcriber
  rewrite, 13b); draining the extension-side platform adapter's decisions
  (`IosPhotoKitUploadPlatform` moves at 13a; its verb mapping is adapter material).

## Decisions

### D1 — The C1/C2/C3 decomposition

One step, three commits: C1 (a pure debt repayment + staged resolver — riskless, reviewable alone),
C2 (the flow zone + gate arming + module deletion — the structural move), C3 (the behavior-adjacent
finish: commands, sinks, switch). Each checkpoint got its own adversarial review; the OpenSpec
ceremony is deliberately one change for the whole step, because the spec-visible deltas only settle
at C3 (C2's `fetchName`/`ensureAlbumIfOptedIn` shell helpers were interim shapes no spec should
record).

### D2 — LogScope resolution (vs the planned compose/ decorators)

The step-5 plan said the `LogContext` global "dissolves into the step-8 compose/ log decorators".
C1 resolved it differently: a `ports/LogScope` seam (`withContext(name, block)`), driven by
`Logger.invocation(scope, …)`, with the process-global ambient (`LogContext` + `IosLogScope`)
seated in `:adapter:ios:ext-safe` beside the device-log writers. Rationale: the decorators would
have wrapped *flow commands*, but the prefix must also span feature-internal invocations the flows
never see (`arm.onProvision`, `pump.onForeground`, the cycle's HTTP lines), so a decorator-only
design either loses those lines' prefixes or duplicates the ambient anyway. A port seam keeps
`:domain` free of global mutable state (the law) while the one true global lives with the writers
that read it — placed by linkage, extension-safe. The compose/ decorators were therefore never
built; the flows call effects/features that self-label, exactly as the shell's escaping launches
did.

### D3 — The command bundle shape

`flow/UserCommands` is a value of four command lambdas (leave · create · commitJoin · share) with
inert defaults, built only in `compose/` (`AppCore.userCommands`) and injected into
`StatusContainerHost` by constructor. Not included: `loadJoinDetails` — a **query** the gate
reduces on (returns `JoinLoad`), kept as its own injected read per "reads do not cross flow";
`onOpenUrl` — already an intent whose decode lives in presentation. The step-6 `EventCreator`
interim collapses: presentation no longer imports the creation feature's command seam
(`EventCreator`/`NoOpEventCreator`); the read-model types (`CreationStatusSource` etc.) stay
directly observed, per the law. Command bodies are the former shell lambdas verbatim (leave's
downloads-then-LeaveEvent ordering, commitJoin's `!= EnrollFailed` mapping, create's pass-through,
share as the shell's platform lambda undecorated).

### D4 — The micro-rule sinks

`EventName.storeEventNameIfChanged(eventId, fetched)` (`feature/membership` — the membership config
is that feature's durable state) holds the persist rule byte-for-byte: `current?.eventId == eventId
&& current.name != fetched`, saving `current.copy(name = fetched)` whole (cutoff preserved). The
flows coordinate fetch-then-store through a `compose/`-built `EventDirectory` effect returning the
`Found` name or null; trigger conditions preserved exactly (Foreground unconditional, Provision
only when `cfg.name.isEmpty()`). `AlbumCoordinator.ensureAlbum` gains `saveToAlbum` and the leading
guard `if (!saveToAlbum || name.isEmpty()) return null` — the shell's `ensureAlbumIfOptedIn`
condition inverted, callers now unconditional. The `:175` map-lookup gate **folded too**:
`albumIdFor(eventId, saveToAlbum)` on the coordinator (sync — the importer reads it inside a
PhotoKit change block); the shell keeps only the config-presence read feeding the importer's thunk.

### D5 — The resolver switch and the shell-delegate shape

`SnapSyncRoot` parses `LaunchDirectives` once, builds `OsFacts` once, resolves `CompositionMode`
once, and switches once: `when (mode)` selects a private `Shell` delegate — `ForgeShell(state)` or
`LiveShell(...)` with the tier's four mechanism thunks (`uploadProducer`, `pumpForeground`,
`uploadSilentPush`, `heartbeat`) bound in the nested `when (tier)`. All entry points are one-line
delegators. Forge inertness is structural: `ForgeShell` has no reference to `app`/`host`; the
`app` lazy casts `shell as LiveShell` (documenting — and failing loudly on — the impossible
forge-mode composition). The C2 tier-selection lambdas dissolved into the switch's per-tier
branches. `urlSessionUpload` stays a root lazy (the background-session drain can adopt an old
upload session on either live tier, as before); its `useBackgroundSession` reads the resolved
`Live.useBackgroundSession` fact.

Two deliberate forge-mode edge deviations (previously-unguarded entry points, unreachable in a real
screenshot run): `runUploadHeartbeat`/`runDownloadBackstop`/`handleBackgroundUrlSession` now
complete their OS handlers immediately instead of booting the live stack (which would have crashed
the unsigned simulator); and `onForeground`'s invocation-params line derives
`useAppDrivenUpload` from the resolved mode (prints `false` while forging, where the old code
computed the would-be tier). Both strengthen the stated rule "while forging, the app assembles no
live stack".

### D6 — Subscription-timing restoration (C2 behavior-review item)

C2's move of the grant collectors into `AppCore.init` widened producer-start: a cold
backstop/URLSession wake constructs `AppCore` (first `app` touch), and the permission StateFlow
replays `GRANTED` into a fresh collector — a start the pre-C2 shell never fired outside host
assembly. C3 restores the old timing while keeping the compose seat: the collectors live in an
explicit `AppCore.installPermissionSubscriptions()` and the **only** caller is the shell's `host`
lazy, at the exact position of the pre-C2 `startUploadsOnGrant()`/`ensureAlbumOnGrant()` calls.
Equivalence: same scope, same two collectors, same transition-only semantics, same install point.

### D7 — Transcriber-grammar advisory (named 13b debt)

Three flow-resident conditions sit outside the flow-transcriber grammar ("straight-line + par +
sealed-result + single leading guard"): Provision's switch guard (`previous != cfg.eventId` inside
an `activeEventId()?.let`), Provision's `if (isGranted())` album step and `if (cfg.name.isEmpty())`
name-fetch trigger. Reshaping them now (e.g. sinking the switch decision into membership, the
grant/name conditions into their features) is possible but not behavior-free-by-inspection — each
would move a *trigger condition* across a seam mid-step. Recorded explicitly as the **13b grammar
debt**: when the `Flows.kt` generator is re-pointed at `flow/` and the hard gate arms, these three
either sink or the grammar names them. (The fetch-result null check and the receivers' sealed
outcomes are within the grammar's sealed-result form.)

### D8 — What did not reach the ~5-decision shell target

Fresh detekt after C3: 14 Kotlin functions (+4 pinned Swift). `SnapSyncRoot` is at 4
(`refreshAttestation`'s `||`, `handleBackgroundUrlSession`'s session-id routing,
`runLaunchEnvPolicyProbe`, `presentShareSheet`'s presenter walk). The remaining 10 sit in
`DevPhotoSeeder` (3 — dev/test equipment, loops inherent), `MainViewController` (1 — transient
error choreography, step-9 presentation material), `IosPhotoKitUploadPlatform` (5 — the PhotoKit
verb/state mapping of an adapter that happens to live in the extension module until 13a), and
`UploadExtensionRoot.process` (1). Draining those is out of this step's operator-locked scope and
not behavior-free; the honest minimum is recorded in PLAN.md's C3 note with per-survivor
justifications.
