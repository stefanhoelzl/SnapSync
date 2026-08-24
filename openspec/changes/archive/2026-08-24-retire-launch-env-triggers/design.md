## Context

`:test:rig` (decision record: `changes/archive/2026-08-09-add-rig-control-channel`) established that a
build-time-only module can be **contained by compilation**: linked only under `-Psnapsync.rig=true`,
contributing its own call site through an `@EagerInitialization` in a gated source dir, with zero lines in
`SnapSyncRoot` and no source at all in a production build. Its D13 explicitly declined to touch
`ios-app-shell`, reasoning that nothing shipped can observe the rig.

The eleven `SNAPSYNC_*` launch triggers are the mirror image of that: **nothing about them is contained.**
They are parsed by `LaunchDirectives` in `:domain model/`, branched on in `SnapSyncRoot`, and executed by
`DevPhotoSeeder` / `DevGalleryWiper` in `:app:ios`. Every shipped binary carries them. Their inertness rests
on a launch-time fact — a SpringBoard launch supplies no environment — rather than on absence.

That inertness argument is also the reason six of the eight pinned `detektAppShell` suppressions exist:
each is dev equipment justified as "inert in production" inside a module whose law is that shells are
wiring only.

Three facts constrain the design and were verified against `6bc5a2b8`:

- `RigControlChannelTest` asserts `derivedEntryPoints() == wired + excluded` **exactly**, deriving the
  population from `@PlatformEntry` in `SnapSyncRoot`. Anything wired into that map must be a platform entry
  point or the build is red.
- `test/rig/src/hook` is listed in `KotlinShellGuardTest`'s scanned roots, not exempted from them — code
  placed there is shell source and keeps its suppressions.
- `MainViewController` routes through `SnapSyncRoot.sceneMode()` and `SnapSyncRoot.platformEntry(...)`, so
  a second entry point that merely reuses it does not escape the live graph.

## Goals / Non-Goals

**Goals:**

- Production Kotlin declares **no** `SNAPSYNC_*` literal, and a guard holds it there.
- Every dev/test operation returns a status rather than emitting a log line to be found.
- Dev equipment leaves `:app:ios`, taking its pinned suppressions with it (eight → two).
- Forge inertness becomes structural — absent from the binary, not performed by no-op members.
- No capability loses a contract it holds today; contracts move to the capability that owns them.

**Non-Goals:**

- **Restoring the upload tier force.** Deleted here, restored by the producer-resolution work as a runtime
  `forced` input. Not replaced in this change.
- **Runtime tier switching**, in any form.
- **Making `:test:rig` testable.** It gains behavior and stays without a `jvm()` target — see D9, which is
  a deliberate exception with a stated cost.
- **A simulator host for the channel.** Owned by `rig-simulator-host`; this change only ensures the
  identity it needs is expressible.

## Decisions

### D1 — Namespaces name who is on the other side of the call

`/os/*` is what the OS calls, `/user/*` is what a user taps, `/device/*` is the device under test. This is
not taxonomy for its own sake: it decides how each namespace can be held honest. `/os` and `/user` have
populations sitting in source — `@PlatformEntry` members and `StatusContainerHost`'s public `on*` members —
so a hand-picked list is a rot risk and a derived guard closes it. `/device` has no population to derive
from; the set exists only because a test rig exists, so it is hand-listed and its honesty comes from being
small and documented.

*Alternative rejected:* one `/trigger` namespace with the coverage guard growing a second, declared source.
That makes half the guard hand-maintained — the exact hole the derivation exists to close — and it would
have required amending the rig's D5, whose forcing proof (a flow call skips the shell's `host` assembly) is
still correct.

### D2 — Four triggers need no command, because the channel already reaches them

`SNAPSYNC_EXPORT_LOGS` → `/device/logs?process=extension`. `SNAPSYNC_EVENT_LINK` →
`POST /os/onSceneContinueActivity?arg=<url>`: the env var routed `LaunchEnvMembership.run(openUrl = ::onOpenUrl)`
→ `shell.onOpenUrl(url)`, and the wired trigger routes `deliverUserActivity` → `forwardEventLink(…, ::onOpenUrl)`
→ the same `shell.onOpenUrl(url)`, additionally exercising the real `NSUserActivity` decode and
activity-type filter. `SNAPSYNC_LEAVE` → `host().onLeaveEvent()`, the command the UI button fires.

A consequence to correct rather than carry: `onLaunchActivity`'s exclusion note currently says "relaunch
with `SNAPSYNC_EVENT_LINK` instead". That overclaims — the env var never reproduced cold universal-link
*delivery*, it forwarded a URL to `onOpenUrl`. The note must stop promising it.

### D3 — An event is created the way a user creates one

`onCreateEvent(name, startsAt, endsAt)` mints and then opens the real join gate; `onConfirmJoin(cutoff,
until, direction, saveToAlbum)` carries exactly the four fields `CreateEventPayload` carried, and `/device/state`
already exposes `UiState.JoiningEvent(eventId, phase)` so the minted id and gate phase are readable.
Mint-only is `create` → read the id → `cancelJoin`.

This deletes `HeadlessCreate`, `CreateEventPayload`, `decodeCreateDirective` and `CreateDirective.kt`. Three
things the env var did are replaced rather than lost: atomicity becomes two requests with an observable gate
phase between them; the `ensureAttested()` pre-refresh was justified by *cold launch* ("so the attest-gated
create is not lost to a cold-launch 401") and over a channel the app is already running, with a rejected
token dropped and re-minted on a `401` anyway; and the greppable `created eventId=<uuid>` oracle becomes a
`/device/state` read.

*Why this is not a second way-to-drive:* it is the removal of one. Two creation paths existed; the
interactive one survives.

### D4 — No inventory routes

`GET /triggers` is deleted rather than renamed. The information that carries value is the *reason* an
excluded member is excluded, and the 404 body already returns it. Names live in the `rig-channel` skill;
truth lives in the coverage guard. An inventory would be a third copy of a list, and the only one no guard
can hold.

### D5 — `GET /device/gallery` is one read at three depths

No `?cutoff=`: the raw census — `total`, `screenshots`, `screenRecordings` — from a direct `PHAsset` fetch,
**not** through `CandidateSource`. That is deliberate: the production predicate drops screenshots before
enumeration, so the SELECT-form subtype counts are the only evidence that the subtype bits match real,
OS-generated assets, which a synthesized library cannot demonstrate.

With `?cutoff=`: the policy's admitted/excluded counts and **every** asset — id, capture date, dimensions,
subtypes, and the verdict with the rule that decided it. Unbounded, because a silent cap reads as "that is
the whole library" and the summary's `total` sits beside the rows to confirm nothing was dropped.

With `?resources=true`: each asset's resources. Opt-in because `assetResourcesForAsset` is the expensive
call — ~110 ms per asset on an SE2, so ~17 minutes on a 9525-asset library — while facts are plain in-memory
properties and cost nothing. Worth having because `RawResource.originalFilename` **is** the upload/ledger
key, so it answers "what filename will this upload under" and "why did one asset produce two ledger rows".

The response reports `permission`, because under a `LIMITED` grant `total` is the hand-picked selection and
not the library, and nothing else in the body would say so.

### D6 — Writes block and return the outcome; there is no mutex

The whole point of the channel over env vars is that a command answers. A `202` would not make the wipe more
headless, only hide the wait. Every write blocks until done, so a caller that awaits a response is serial by
construction; deliberately firing parallel destructive commands at one's own device is not a failure mode
worth machinery. Reads stay unblocked, which matters most precisely while a wipe waits on a tap.

The gallery commands run on `Dispatchers.Default`, never `hooks.mainLane` — `performChangesAndWait` is
blocking, and today's chain is on `Dispatchers.Default` for that reason.

### D7 — The wipe cannot be made headless, and says so

`PHPhotoLibrary` raises its own confirmation and someone must tap the device. Measured (SE2, iOS 26.6,
2026-08-08): one change block deleting 9525 assets **and** 5 albums raised **two** confirmations, one per
kind — batching does not collapse them. A tapped Cancel arrives as a failure carrying `PHPhotosError.userCancelled`
(3072), which is an answer the caller needs, and therefore a reason to block rather than poll.

### D8 — Ordering transfers to request/response, and `LaunchEnvMembership` dies

Today the shell runs `wipe → seed → seed → probe` in one coroutine and completes `photoLibraryTriggersDone`,
which the membership chain awaits, because a join must not enumerate a library being deleted underneath it.
With blocking commands there is no chain to gate: each request observes the state the previous one returned.
`LaunchEnvMembership` — whose sole purpose was applying four optional launch variables in a fixed order —
has no caller left once leave and event-link become `/user` commands and create becomes two of them.

*Cost, accepted:* the `reset → leave` safety rule ("so a `SNAPSYNC_LEAVE` set in the same launch is a no-op
rather than a backend notification aimed at the wrong backend") stops being enforced by an ordering. It
becomes a runbook note.

### D9 — `:test:rig` takes the gallery code as-is: no `jvm()` target, no tests

`DevPhotoSeeder` and `DevGalleryWiper` move into `:test:rig`'s iOS source essentially unchanged. They must
**not** land in `test/rig/src/hook`, which is scanned shell source — placing them there would keep all six
suppressions while appearing to remove them.

This is a deliberate exception to the module's own stated condition ("NO TESTS, deliberately… the condition
that makes that honest is that this module holds no projection it could get wrong; if that ever stops being
true it needs a `jvm()` target and tests with it"). The condition becomes false and the build file must be
rewritten to state the new posture rather than keep a claim that no longer holds. The suppressions vanish
because the gate stops scanning, not because the decisions moved — that is the honest description and it
belongs in the file.

*Alternative rejected:* pushing the decisions into `:domain model/` to keep the rig test-free. That puts
more dev vocabulary into the module that ships, which is the opposite of this change's purpose.

### D10 — `WipeRequest` is deleted; the refusal becomes a `400`

The scope grammar was a `model/` type because a launch variable has no other place to be validated. Over
HTTP an unrecognized `?scope=` is a bad request, and a `400` is a louder answer than the log line the launch
form could manage. The safety property — a mistyped scope must refuse rather than delete — is preserved by
the status code.

*Cost, accepted:* three `commonTest` assertions on the parse are lost, on the one irreversible operation.

### D11 — Forge becomes its own Xcode target, not a mode of the app

Containment cannot be achieved by gating source alone. `SnapSyncRoot.kt:217` names `ForgeShell` directly, so
any production build keeping that `when` keeps `ForgeShell`, `forgeStatusHost` and the whole `ForgePreset`
table. And swapping only `MainViewController` would not help: `ForgeShell` implements ~15 `Shell` members
whose job is to make **every** entry point inert, and the Swift shell still calls `onLaunch`, `onForeground`
and the rest.

So `SnapSyncForge` is a third target beside `SnapSync` and `SnapSyncUploadKit`, over a new `:app:ios:forge`
module with its own entry point and framework, linking `:ui:screens` / `:ui:presentation` / `:domain` and
**not** `:app:ios`. `SnapSyncRoot` is not in that binary. `CompositionMode.Forge`, `ForgeShell` and the outer
mode switch are deleted, and `resolveComposition` reduces to a function of `backgroundUploadSupported()`.

*Alternatives rejected:* a contributed shell delegate installed into an `internal` field (adds the permanent
shell seam the rig design was built to avoid, and a null check needing a new pin); a source-set swap inside
`:app:ios` (a mechanical refactor of the 1400-line file every OS entry point routes through).

### D12 — `SNAPSYNC_FORGE_STATE` survives, in forge-only source

The goal is no env var read by **production Kotlin**, not no env var anywhere. `SNAPSYNC_RIG_PORT` already
survives on exactly that basis: "observable by no shipped code — the file reading it does not exist in a
production build — so it is inert by construction rather than by a runtime check." The forge target's Kotlin
is the same category.

This keeps `screenshots.yml` at three launches and six captures, changing one word. The alternatives cost
more for less: three builds of an 11–19 minute job, or making a pipeline whose only check is a human eye
depend on the rig.

### D13 — Forge needs no spec; its one product-facing claim moves

The mechanism is a lens — it renders the real `StatusScreen` over forged sources — and joins `:test:rig` and
`:test:harness-driver` in going unspec'd. But `ForgePreset` is used by nothing except the iOS forge and its
own tests (the desktop forge harness forges through its own `ControlPanel`), so deleting the requirement
outright would drop one claim that nothing else states: **the committed raws depict the real screen in a
state the real reduction can reach.** `ios-appstore-metadata` specs the pipeline ("the committed raw captures
under `screenshots/` SHALL be the source of truth") but not the provenance. The claim moves there, beside the
requirement it qualifies.

### D14 — The tier force is deleted with no replacement here

`SNAPSYNC_FORCE_URLSESSION_UPLOAD` currently makes `LiveShell` pass `osUploadProducer = { null }`, and
`selectedProducer()` reads `producers.osDriven ?: producers.appDriven`, so un-nulling alone would stop it
forcing anything. Restoring it belongs to producer resolution, which replaces both with one resolved producer
from a pure `resolve(osFacts, permission, forced)` — and which requires this change to land first, since it
would otherwise build on `LaunchDirectives` and the flag.

*Window, accepted:* until that lands, the app-driven tier is unexercisable **under a full grant** — the
discovery-walk path. The tier itself remains reachable under a `LIMITED` grant, where the OS never invokes
the extension (measured: zero `process()` invocations over 22 minutes) and the arm selects the app-driven
producer. A requirement travels with the handover: `forced` must survive an **OS-initiated cold relaunch**,
because a process the OS relaunches for `handleEventsForBackgroundURLSession` resolves its tier before any
request can arrive.

### D15 — Identity fills an absence at the supplier, not inside `KeychainDeviceIdentity`

D15 of the rig design proposed `SNAPSYNC_DEVICE_ID` "filling an absence" in the Keychain. That could never
have worked: `errSecMissingEntitlement` (-34018) is a **read error**, not `errSecItemNotFound`, and
`KeychainDeviceIdentity` deliberately never mints on a read error — that distinction is the locked-device fix.
There is no absence to fill. `resolveOrMint`'s adopt and mint branches also both `write()`, so every branch
fails on such a host, not only the read.

So the fallback sits **above** that class, at the supplier: a read-only App-Group file, presence as the
discriminator, which production never creates. A locked device finds no file and defers exactly as today; a
mis-signed build finds none and fails loud. `POST /device/identity` writes it — durable, because an
in-memory value cannot survive the OS-initiated relaunch the simulator host is measuring. `PushRegistration`
becomes a supplier like the other three call sites.

### D16 — The trigger-index guard inverts rather than deletes

`RunbookSkillsTest` currently asserts the `ios-device` skill's index equals the literals in production Kotlin,
with a `size >= 5` non-vacuity floor — the exact negation of the invariant this change establishes. Deleting
it would leave nothing watching, which is how the index drifted in the first place. It becomes an exact
inventory that is empty, so a re-added launch trigger has to be argued rather than land unnoticed. Zero is
the assertion, not a count to floor, so the vacuity problem disappears with it.

### D17 — The reset gets its own capability

Its coherence is one sentence — return a device to holding nothing that describes a backend it no longer
talks to. Split across `sync-ledger`, `download-store` and `leave-event`, the four clauses each read as
arbitrary and the reason they belong together disappears.

### D18 — A failed registration change is reported, with the fresh-install case named

`setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`; the single call site discards
both. `Error` severity routes to Bugsink, making this field telemetry on a mechanism whose failure mode is
"the user silently never syncs".

Measured (SE2, iOS 26.6, `probe-uploadjob-readback`): `start()` is a disable→enable ritual, so its **leading
disable runs against no record on any clean device** and returns `false` with `PHPhotosError 3201` ("Unable
to find the configuration") — reproduced twice. That is expected, not a fault, and raising on it would put a
Bugsink event on every first join of every fresh install. So `3201` on a *disable* logs at debug; `Error` is
reserved for other codes and for a failing *enable*. The flip side is the fix's value: a disable that
**finds** a record returns `true` with no error, so the write distinguishes "there was a registration" from
"there wasn't" as a side effect of doing its job.

`/device/state` reports `isUploadJobExtensionEnabled()` as **what the OS reports**, three-valued. It is a
26.1 selector and the app deploys to min iOS 18, so an unconditional read traps as an unrecognized selector —
it is read through something that exists only on the ≥26.1 tier and reports `notApplicable` elsewhere, never
a bare `false` on an OS that could never register. The read is also grant-dependent: measured `false` under
`NOT_DETERMINED` for a live record that had survived delete-and-reinstall, so `false` carries its own
qualifier.

### D19 — The runbooks are re-cut at the boundary of the running app

`ios-device` keeps getting a build onto the phone and evidence off it; `rig-channel` takes everything done to
an app already running, which is now the whole trigger index. Each stays useful alone — installing a release
build and checking it launches needs no channel runbook.

## Risks / Trade-offs

- **`:test:rig` holds behavior nothing tests** → Accepted (D9). The build file's honesty condition is
  rewritten to state the posture rather than keep a false claim.
- **The irreversible operation's scope parse loses test coverage** → Accepted (D10). The refusal becomes a
  `400`, which is louder than what it replaces.
- **A screenshots regression rides in this PR, and the only check on the six raws is a human looking at
  them** → The capture run is its own gate in the task list: dispatch, download, eyeball, and only then ship.
  A system notification has landed in a capture before (1 of 2 runs), so a re-dispatch is expected, not a
  surprise.
- **The forge target lands in `project.pbxproj`, which `screenshots.yml` builds via `-scheme iosApp`** →
  Verify the new target does not change what that scheme produces for the simulator SDK. `rig-simulator-host`
  deliberately adds nothing to that file and would be affected.
- **A new Compose-rendering target hard-aborts at launch without
  `CADisableMinimumFrameDurationOnPhone = true`** → Mandatory in the new target's `Info.plist`; the failure
  presents in CI as a capture failure with no obvious cause.
- **The identity fallback depends on `SynchronizedLazyImpl` not memoizing a thrown initializer**, so a failed
  resolve retries on next access → Pin it with a test rather than inherit an unpinned language-level property.
- **A reset can be overtaken by an upload cycle already in flight**, which was impossible at launch → Stated
  in the requirement; the rows are visible in `/device/state`'s ledger counts and a second reset clears them.
- **`/device/logs` cannot reach the rolled `.1` sibling** that `SNAPSYNC_EXPORT_LOGS` copied, since
  `DeviceLogSource.tail` reads only the current file → Stated in `diagnostic-logging`; `bytes` is
  caller-specified, so the live tail is not otherwise reduced.

## Migration Plan

This change lands **before** `os-producer-deregistration`, which would otherwise build on `LaunchDirectives`
and the tier-force flag and be reworked. `rig-simulator-host` consumes the identity mechanism and is
otherwise independent.

No runtime migration: nothing here persists state that an older build reads, and the deleted triggers were
never reachable in a shipped launch. The App-Group identity file is created only by the channel, so its
absence is the production case.

## Open Questions

- **The `LIMITED` cell of `isUploadJobExtensionEnabled()` is unmeasured** — only `NOT_DETERMINED` and full
  `GRANTED` were exercised. That is the cell where producer resolution deregisters.
- **The differently-signed-build record is unmeasured** (needs two signing identities), so whether the read
  can report `false` for a record that still blocks with `3202` remains open. The "what the OS reports"
  label is chosen to survive either answer.
- **Whether `rig-simulator-host` drops its own rig-side identity plant** once `POST /device/identity` exists,
  or keeps it for hosts the channel has not reached yet.
