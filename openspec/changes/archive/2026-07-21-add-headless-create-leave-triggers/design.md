## Context

The on-device dev/test loop is driven headlessly over USB (`pymobiledevice3 developer dvt launch --env
…`). Launch-env triggers already cover joining (`SNAPSYNC_EVENT_LINK` + `autoJoin`), seeding
(`SNAPSYNC_SEED_PHOTOS` / `SNAPSYNC_SEED_POLICY`), the policy probe (`SNAPSYNC_POLICY_PROBE`), tier
forcing (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`), and marketing forge frames (`SNAPSYNC_FORGE_STATE`). Two
gaps remain: **creating** an event and **leaving** one.

Creation is not blocked by the backend — `POST /events` is attest-gated but a real device has
attestation. It is blocked by the UI: `CreateEvent.create()` → `onMinted` →
`StatusContainerHost.onEventCreated` → `startPending()` opens the pending-join gate **non-auto-confirmed**,
awaiting a tap to pick a cutoff and confirm. There is no launch-env trigger that fires create at all.
Because direction/cutoff/album are fixed at join and re-scanning a joined event short-circuits as
`AlreadyJoined`, testing N membership shapes needs N distinct events — so "create an event headlessly"
is the load-bearing missing primitive.

Leaving has no headless route either: today you can only switch to a different event id (which the join
gate's `autoConfirm` handles as leave-then-join) or reinstall. Nothing reaches the **unjoined** resting
state on a device.

Constraints: `:app:ios` is wiring-only and untested (all logic lives in tested `:domain` zones);
`model/` is pure and its codecs are tested on JVM + `iosSimulatorArm64`; the shell decision-keyword
guards (`KotlinShellGuardTest`) forbid branching in the shell outside pinned exceptions; forge inertness
must stay structural.

## Goals / Non-Goals

**Goals:**
- A headless `SNAPSYNC_CREATE_EVENT` that mints an event and either joins it (`autoJoin`) or reports its
  id (`created eventId=<uuid>`), reusing the existing tested join machinery for the join half.
- A headless `SNAPSYNC_LEAVE` that returns the device to the unjoined resting state.
- Well-defined sequencing when several membership-mutating triggers are set in one launch.
- Parsing and the create-outcome branch tested in `commonTest`; the shell stays thin wiring.
- Same production-inertness posture as every existing trigger (developer-launch-only, once per process,
  no compile guard).

**Non-Goals:**
- Batch creation (multiple events in one launch). One event per launch; relaunch to pre-seed several.
- Driving `choosePhotos` / permission dialogs / share sheet from env (they need real UI).
- Forcing OS-callback cadence (foreground/background/silent-push/`process()` timing stay OS-owned).
- Any change to the backend, the event-link wire format/QR, or the interactive create/join UI.
- Making CREATE idempotent (the backend has no create-if-not-exists; see Risks).

## Decisions

### D1 — CREATE carries `base64url(JSON)` decoded by a new strict `model/` codec

A single opaque variable mirroring `SNAPSYNC_EVENT_LINK`, decoded by a dedicated
`decodeCreateDirective` returning a typed `Success`/`Failure` (the same shape as `decodeEventUrl`), over
a new `@Serializable CreateEventPayload(name, startsAt?, autoJoin, minPhotoDate?, direction?,
saveToAlbum?)` with `ignoreUnknownKeys = false`.

- **Why:** a `name` can contain spaces/quotes/emoji — `base64url` sidesteps shell-quoting entirely, and
  a `model/` codec makes parsing tested on JVM + simulator, matching the "one authoritative codec"
  style. It reuses `EventLinkPayload` for the *encode* side of the synthesized join link, so producer
  and consumer stay anchored.
- **Alternatives:** plain delimited `key=value;…` parsed in `LaunchDirectives` — simpler but fiddly with
  special characters and would push a bespoke parser into the wiring surface. Rejected. There is no
  production wire-format to protect here (unlike the event-link fragment), so opacity is purely
  ergonomic, but the tested-codec benefit stands.

### D2 — `startsAt` defaults to **now**; overridable

Absent `startsAt` resolves to the current cutoff (via the same `CutoffFormatter`/clock the shell already
owns), passed to `POST /events`. `startsAt` is also the cutoff floor, so a create-today event with
`startsAt = now` accepts `SNAPSYNC_SEED_POLICY`'s +1h assets — one launch exercises an actual upload.

- **Why:** the common case (create now, test upload) needs no date; an explicit override remains for a
  specific floor.
- **Alternative:** require `startsAt` — more verbose, no hidden clock dependency. Rejected for
  ergonomics; the clock dependency is injected (see D4) so it stays testable.

### D3 — Create-then-join reuses the existing `autoConfirm` path via a synthesized link

For `autoJoin`, after minting id `X` the app forwards
`onOpenUrl(encodeEventUrl(EventLinkPayload(X, autoJoin = true, minPhotoDate?, direction?, saveToAlbum?)))`.

- **Why:** the entire join gate (`autoConfirm`: details fetch, floor clamp, switch-leave, enroll,
  provision, reconcile) is already tested and on-device-proven. The create half adds only the mint +
  link synthesis; no join logic is duplicated.
- **Alternative:** a `HeadlessCreateAndJoin` use-case that calls `client.create` then enroll/provision
  directly. Rejected — it reimplements `autoConfirm`'s cutoff/direction/album resolution and floor
  clamp, which presentation's gate owns, and doubles the surface that can drift.

### D4 — The `CreateOutcome` branch lives in a tested `HeadlessCreate` use-case, not the shell

`HeadlessCreate` (in `feature/creation`, composed onto `AppCore` by `snapSyncApp`) takes the
`EventCreation` client, a logger, and a `now: () -> String`, and exposes
`suspend fun run(payload, forwardAutoJoinLink: (String) -> Unit)`. It resolves `startsAt`, calls
`client.create`, and branches on the outcome: `Created(id)` → `autoJoin ? forwardAutoJoinLink(synthLink)
: log("created eventId=$id")`; `InvalidName`/`Transient` → log. The shell's application is a single
statement: `app.headlessCreate.run(payload, ::onOpenUrl)`.

- **Why:** keeps the outcome `when` inside a `commonTest`-covered feature and off the untested,
  decision-guarded shell — the effects-as-lambdas pattern the `flow/` zone already uses. The synth-link
  construction is `encodeEventUrl` (pure `model/`), so it too is tested.
- **Alternative:** branch in a pinned shell method (like `runLaunchEnvPolicyProbe`). Rejected — the
  policy probe is pinned because it must drive the *live* PhotoKit fetch, which no tested module can
  reach; the create branch has no such excuse and belongs under test.

### D5 — One ordered, sequential membership application; forge no-ops it structurally

The three membership-mutating triggers apply in `leave → create → event-link` order inside **one
sequential coroutine** (independent `LaunchedEffect`s do not await each other). This folds the existing
`SNAPSYNC_EVENT_LINK` application into the same path. The application is a `Shell` method so the
`ForgeShell` (which holds no `app`) no-ops it, and `LiveShell` runs it.

- **Why:** ordering must be a *guaranteed sequence* for `leave → create` to be meaningful (create must
  read the post-leave config). One coroutine that awaits each step gives that; three effects do not.
  Routing through the shell keeps forge inertness structural, consistent with the existing
  forge-over-event-link precedence.
- **Refinement discovered at implementation:** the `detektAppShell` gate (threshold 2) and its config
  name **ordering** and **error-mapping** as shell-forbidden decisions, so the `if`/`?.let`/decode-`when`
  cannot live in the shell coroutine. The `flow/` zone is the law's home for ordering, but its closed
  transcriber grammar admits only a *single leading guard* / `when`-over-sealed / launches — it has **no**
  shape for "apply N optional triggers in order," so a flow would fail generation. Resolution: a tested
  `feature/creation` coordinator `LaunchEnvMembership` owns the ordering, the `SNAPSYNC_CREATE_EVENT`
  decode, and its error-mapping (over injected `leave`/`ensureAttested`/`openUrl` effects), mirroring
  `HeadlessCreate`'s own "tested feature that sequences over injected effects" posture. The `LiveShell`
  method is then straight-line (`host; scope.launch { app.launchEnvMembership.run(…) }`, complexity 1) —
  **no new shell pin**, and the ordering is `commonTest`-covered.
- **Note (behavior-preserving):** the event-link step is the same `onOpenUrl(link)` call it is today,
  placed last; its spec scenarios (cold provisions once, subsequent re-runs, re-provision does not
  re-upload, invalid rejected, production inert) must all hold verbatim.
- **Alternative:** keep event-link's own effect and add two more independent effects. Rejected — no
  ordering guarantee, and `leave + create` could interleave against a stale config.

### D6 — Attestation made fresh before the create `POST`

The create step awaits `app.attestation.ensureFresh()` before `POST /events` (mirroring the host's
pre-push-registration `ensureFresh`).

- **Why:** `HeadlessCreate` fires the create **once, with no retry**; the shared `http` client's
  `onRejected` refreshes for *next* time but does not re-drive the in-flight request, so a create racing
  a not-yet-ready token would log `Transient` and mint nothing. `LEAVE` needs no such care — its backend
  notify is best-effort and non-blocking.

## Risks / Trade-offs

- **CREATE is non-idempotent — every launch mints a fresh backend event.** Unlike every existing
  trigger (all idempotent/inert on repeat), leaving `SNAPSYNC_CREATE_EVENT` set across relaunches mints
  orphan events. → Mitigation: it is impossible to make idempotent (the backend mints a fresh UUID per
  `POST`, no create-if-not-exists), so it is documented as the honest contract in the spec (a "second
  event is minted" scenario) and flagged with a ⚠️ in the runbook — *unset the variable after the mint*,
  the opposite of the event-link advice. Orphan events are cheap and the trigger is developer-launch-only.
- **Folding the on-device-proven event-link path into the new ordered coroutine is a regression
  surface.** → Mitigation: the event-link step is the identical `onOpenUrl` call, placed last; the
  existing spec scenarios pin its behavior and are unchanged; verify on device that a plain
  `SNAPSYNC_EVENT_LINK` launch still provisions once.
- **`leave → create` depends on `commands.leave()` clearing the config StateFlow before create reads
  it.** → Mitigation: leave is `suspend` and awaited in the sequential coroutine; the create step reads
  `config.value` only after it returns. Covered by the "leave and create apply in order" scenario.
- **`LEAVE`'s unique value is narrow** — the join gate's switch already does leave-then-join, so
  explicit leave only adds the standalone-reset and leave-then-mint-only cases. → Accepted: those two
  cases are otherwise unreachable headlessly, and the trigger is a few lines of wiring over the existing
  leave use-case.

## Migration Plan

Additive dev/test-only change; nothing to migrate and nothing user-facing. Rollback is reverting the
change — the new variables simply cease to exist and every other trigger is untouched. No spec
capability is removed; the `ios-app-shell` delta is purely `ADDED` requirements.

## Open Questions

None — the design tree was resolved in the pre-proposal interview (var shapes, mint/join semantics,
`startsAt` default, mechanism, ordering, batch vs single, process). The non-idempotency guard
(refuse mint-only when already joined) was considered and declined in favor of documenting the honest
contract.
