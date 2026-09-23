## Context

On 2026-09-22 four reviewers read every function-typed seam in the tree:
- the composition layer and the shells;
- the `:domain` zones;
- the adapters and the rig;
- the UI layers.

They reported thirteen defects (B1–B13) plus a set of seams whose shape the law already forbids, or whose
pinned reason is false. Afterwards the defects were grouped by **cause**, not by location. The groups are
G1–G5, listed in the proposal, and the operator chose the preventive measures for each group one at a time.
This design records how those measures are built and why.

The existing laws already name several of these hazards, but gate only part of each:
- The seam gate covers `*Ports` bundles only.
- The lane gate covers `UserCommands` only.
- The flow gate forbids scopes but not unisolated fan-out.
- "Absence is never silent" has no mechanical reach into callback slots.

Most of this change widens gates that already exist. Only a few are new.

Things the design has to respect:
- `:domain` is platform-free.
- The shells are wiring-only and untested.
- Every gate fails closed on novelty.
- `/api/v1` is frozen.
- Only one iOS device is available for measurement.

## Goals / Non-Goals

**Goals:**
- Fix B1–B13.
- Close each class with a rule in the spec of record. Wherever the rule can be checked by scanning source,
  back it with a `:test:architecture` gate.
- Make the test world start as cold as a real process, so wiring that depends on build order fails in
  tests.
- Keep every gate honest about what it cannot see. Heuristic scans say so in their source.

**Non-Goals:**
- Replacing every lambda with an interface. Callbacks into the core that cannot throw stay lambdas, pinned.
- Building failure policies (admit-on-doubt and the like) in `compose/`. The operator did not adopt this
  G2 measure. The album-exclusion read becomes a port per G1, but where its failure policy lives is
  unchanged.
- Changing `/api/v1` in any way.
- The review's findings outside the five classes: stale comments, rig documentation, the Keychain CF leak,
  the simulator UTI content type. They are recorded in the review, not here.

## Decisions

### D1. One umbrella change, shipped as one PR per group

The operator chose a single change. It still ships in five PRs, in the order G1 → G2 → G3 → G4 → G5, each
under `/ship`.

G1 goes first because the others build on it:
- G2's "no core glue in `AppPorts`" is G1's seam rule applied to the bundle.
- G5's "screens take no suspend seam" needs G1's `UserQueries` bundle to exist.

G3 and G4 are independent of each other. Each PR carries its own gates, so no gate lands before the fixes
that make it pass.

*Alternative:* one PR. Rejected: it would be roughly 8 gates plus about 40 files, too large to review, and a
revert would take everything with it.

### D2. The seam gate discovers constructor parameters instead of listing them

The widened gate scans every constructor in `feature/` and `compose/` and pins every function-typed
parameter it finds, keyed by `Class.param`.

Each pin states the parameter's binding in every composition. A reason of the form "returns a value the
composition already holds" is refused for any seam whose binding reads the platform. The judgement stays
human; the gate forces it to be written down.

The scanner's `isFunctionType` gains the nullable form `(… -> …)?`.

*Alternative:* a Kotlin compiler plugin that tracks call targets. Rejected: out of proportion to the
problem, and reach still isn't decidable from types alone (the spec already records this).

### D3. The seams that stop being lambdas

| Seam | Becomes | Bound to |
|---|---|---|
| `deviceId` (both bundles, 6 features) | `DeviceIdentity` port in `ports/`, with KDoc `@throws SecureStoreUnavailable, DeviceIdentityAbsent` | `KeychainDeviceIdentity` (app: MINTING; extension: READ_ONLY); the world's constant fake |
| `reloadConfig` | `suspend fun refresh()` on the config port | `FileBackedConfigStore.reload()` |
| `scheduleBackstop` | a second `BackgroundScheduler` for the backstop task id | `IosBackgroundScheduler(DOWNLOAD_BACKSTOP_TASK_ID, network = false)` |
| extension `admission` | derived in `uploadCore` from a permission port plus a declared tier kind | the ext-safe `currentPhotoPermission` adapter |
| `albumExcludedAssetIds` | the `AlbumManager` port in both bundles | the existing adapters |
| `appVersion`, `host` | plain `String` values | the bundle reads, once, in each process's own root |
| `provision`, `refreshAttestation`, `registerPush` | built in `compose/` from the core | nothing outside the core (removed from `AppPorts`) |
| feature `now`, `permission`, `joined`, `activeEventId`, `activeConfig` | the existing `Clock`, `PhotoAccessStatusSource`, `ConfigSource` / `ConfigReader` ports | unchanged adapters |

`SecureStore.resolveOrMint(readLegacy)` takes `legacy: SecureStore?`.

**As built (G1):**
- The membership refresh is its own small port, `ConfigRefresh` (implemented by `FileBackedConfigStore`),
  not a method on `ConfigSource`. Adding it to `ConfigSource` would have forced every read-only fake to
  implement a refresh verb.
- The extension's admission became `UploadPorts.process: UploaderProcess`, a sealed tier kind. `App` carries
  the app's in-core admission callback; `Extension` carries a `PhotoGrantRead` port, and `uploadCore`
  applies `extensionAdmission` itself.
- The album read is `AlbumManager` plus a declared `AlbumLookupFailure` (`AdmitOnDoubt` for the app tier,
  `FailCycle` for the extension). That keeps each tier's existing failure answer, and the world's app-tier
  cycle now admits on doubt as the app's does.
- The rig's uploader-pin seam in the iOS root is a named `fun interface UploaderPinSource`. It is still
  assigned once at boot by the rig hook (the one late-bound seam the shell keeps, and not function-typed),
  and the root now reads it at every use instead of capturing it.
- Three seams stay pinned rather than becoming ports, because each is a deliberate zone decision stated at
  its class: `ReadingLedgerCountsSource.read` keeps ledger types out of `feature/status`,
  `MembershipEntry.saveConfig` lets the entry's tests record its ordered steps, and
  `DownloadController.downloadEnabled` is a stated three-valued derivation. The two ledger writes
  (`clearLedger`, `bumpManifestVersion`) stay lambdas because ledger writes are confined to `compose/`.

`onEventMinted` stays a lambda. It is a callback into presentation that cannot throw, and it is pinned.

### D4. `UserQueries` and the query decorator

`model/` declares `UserQueries(loadJoinDetails, shareableCount)`. `compose/` builds it and wraps each field
in `awaitingOnCoreLane(name) { … }`, which is `withContext(coreLane)` plus `Logger.invocation`.

`StatusContainerHost` takes the bundle. Screens get the count through presentation state: the container
runs the query in an intent and reduces `Counting | Count(n) | Unavailable`. So `:ui:screens` holds no
suspend seam, and the `ShareCountRow` effect goes away. Presentation catches failures with
`runCatchingCancellable` and maps them to `Unavailable`.

*Alternative:* keep the query in the screen, wrapped in `withContext`. Rejected: a screen would then pick a
dispatcher, which is exactly the lane choice the gate exists to keep in one file.

### D5. Callbacks bound at construction (B1)

`QueuedPhotoDownloadJobs(onStaged = { r, k, p -> downloadController.onResourceStaged(r, k, p) })`. The
lambda reads the `downloadController` lazy when it is invoked, so building the jobs no longer depends on
building the controller, and the reverse is not needed either. The `var`, the `?: return`, and the world's
workaround of forcing the controller in `init` are all deleted.

The same pattern applies to every other callback slot the new gate finds. The review found three:
`onStaged`, `MetricKitProcessMetricSource.onReport`, and `SnapSyncRoot.uploaderPinSource`. The last one
becomes a rig-provided constructor input of the root's shell.

### D6. No defaulted lambdas; `StatusActions` is built by one factory

The lambda-default ban applies to **constructor parameters**: `UserCommands`, `StatusActions` and its groups,
and every feature, flow and adapter constructor. Parameters of ordinary functions, such as the
`invocation(result = …)` log formatter or a helper's optional hook, are not seams and are not covered.
Applied literally to every function parameter, the rule would have reached about 100 sites that wire nothing.
A production binding that a test replaces lives in a secondary constructor (`KeychainDeviceIdentity(role)`,
`AppGroupFileSecureStore(fileName)`).

The three hand-copied `StatusActions` tables (the iOS shell, the forge, and the desktop pane) are replaced by
`StatusContainerHost.statusActions(…)` in `:ui:screens`. With no defaults, a new action has to be wired
there once. The desktop `StatusPane` stops rebuilding `UserCommands` and takes the world's bundle.

### D7. Cancellation-keeping catch helpers

`model/` gains:
- `runCatchingCancellable { }`, which returns a `Result` and rethrows `CancellationException`;
- `bestEffort(log, name) { }`, which logs at `Warn` and rethrows cancellation.

The step helpers in `LeaveEvent`, `ReconfigureEvent` and `ResetDeviceState` become `required(name) { }` and
`bestEffort(name) { }` over a small `Steps` receiver. `required` stops the sequence by returning a failure
outcome the use case propagates.

`ReconfigureEvent` then returns `ReconfigureOutcome` (`Saved | SaveFailed`), which presentation reduces into
the settings surface.

The catch gate allowlists exactly these helpers, the ObjC-boundary helpers, and `runProcessCycle` (which
catches cancellation deliberately at the ObjC edge).

### D8. Isolated fan-out for flows

`model/` gains `suspend fun fanOut(log, vararg children: Pair<String, suspend () -> Unit>)`. It uses
`supervisorScope`, and each child runs in its own `launch` that catches through `runCatchingCancellable`
and logs at `Error` with the child's name. The flow still awaits every child, and cancelling the flow still
cancels them all.

`Foreground` and `Provision` move to it, and the flow gate bans `launch` and `async` in `flow/` outside it.

The architecture diagram transcriber has to recognise `fanOut` as the awaited fan-out form that it
currently reads from `coroutineScope { launch }`. Otherwise diagram generation fails, which is the
transcriber law working as intended.

*Alternative:* `supervisorScope` alone. Rejected: a child that fails would log nothing, and the gate would
have no single call site to check.

### D9. ObjC boundary helpers

In `:adapter:ios:ext-safe` (`app.snapsync.objc`), `objcBoundary(log, name[, fallback]) { }` catches `Throwable`,
logs it at `Error`, and returns the fallback (or `Unit`). `checkedObjC(name) { errPtr -> call(errPtr) }` allocates
the `NSError**`, reads the `Boolean` result, and returns a `Result<Unit>` whose failure is an `ObjCFailure`
carrying the domain, code and description; `checkedObjCValue` is the same for a call that reports success as a
non-nil value. As built, `checkedObjC` takes no logger: whether a failure is a fault, a warning or expected is
the call site's to say (removing an already-absent file is expected, and `Throwable.isNoSuchFile` names that
case), and the log writer rolling its own file has nowhere to log.

The following moved to these helpers:
- `IosPhotoLibraryImporter`'s change and completion blocks (the completion's settle has its own fallback, so a
  throw while settling still resumes the caller);
- the delegates of `IosUrlSessionUploadPlatform`, `IosDownloadTransport`, `PhotoSelectionObserver` and MetricKit;
- every other block handed to ObjC: `dispatch_async` bodies, notification-observer blocks (the root's two
  included), the authorization and App Attest completions, `getAllTasksWithCompletionHandler`,
  `writeDataForAssetResource`;
- every `performChangesAndWait` call, `BGTaskScheduler.submitTaskRequest`, `setUploadJobExtensionEnabled`, and
  every `NSFileManager`/`NSData` call that reports through `NSError**` (the config store's reads and writes
  keep their absence classification exactly).

The `scheduleDownloadBackstop` duplicate is removed with D3.

The gate is a text scan in the style of `KeychainContainmentTest`, and says in its source that it is
heuristic.

### D10. Classifying credential outcomes, `409`, and compare-and-clear (B2, B3)

`withCredentialInterceptor` stops reading raw status codes on every route. It remembers the token it
attached. On a `401` it reports `onRejected(sentToken)` only when a token was attached and the path is
outside the ungated set. It reuses the same ungated-path predicate the backend's closed list defines, and
that predicate is pinned by a test against `api/src/app.ts`'s list.

`HttpAttestClient` returns a sealed outcome (`Minted(token) | ChallengeStale | NotAttested | Refused |
Unreachable`). `DeviceAttestation` handles `ChallengeStale` by fetching a fresh challenge once, and never by
clearing.

`AttestStore.clearTokenIf(expected)` reads the item, compares it, and deletes it only on a match, under the
process's attestation mutex. Two processes can still interleave between the read and the delete. That
window is microseconds wide and needs an extension rejection to land exactly during an app write; the
outcome is one extra refresh, never a lost photo. That is accepted (see Risks).

`DeviceAttestation.onRejected(t)` also coalesces: a refresh is triggered once per rejected token.

The v2 status is **`409 Conflict`**, with the body `stale challenge`. To do this, `/attest/token` and
`/attest/renew` leave the shared `deviceApi` router. As built, one factory (`attestIssuers(staleChallengeStatus)`)
builds them once per version — `401` for v1, `409` for v2 — so the two versions share every line except that one
status and cannot drift anywhere else; v1's answers are unchanged.

As built, the rejection route is `compose/`'s `AppCore.onCredentialRejected(sentToken)`: the trust feature
compare-and-clears under its refresh lock (`onRejected(sentToken): Boolean`), and only a clear that happened
launches a refresh. Both shells and the world bind it. `clearTokenIf` is a default method on the `AttestStore`
port (read, compare, clear). The client also classifies v1's `401 stale challenge` body as `ChallengeStale`,
since the remedy is the same.

*Alternatives to 409:*
- `400`: already means "invalid body" on these routes, so a client could not tell the two apart.
- `422`: plausible, but less idiomatic for "your request raced a state change".
- `410`: implies the resource is gone.

*Why the backend change is needed at all:* the client-side classification (the interceptor skipping
ungated paths) fixes B2 without it. The status change removes the ambiguity at the source, so a future
client that forgets the ungated set still cannot misread a stale challenge.

### D11. Sealed reads for unknown-capable sources

`ConfigSource` gains `membership: MembershipRead`, where `MembershipRead` is `Member(cfg) | NotMember |
Unreadable`. It sits beside the existing `config` flow. As built it is a plain read, not a second `StateFlow`:
every reader reads it fresh at each use and none collects it. The port's default derives it from `config` (so a
source that cannot fail to read never answers `Unreadable`); `FileBackedConfigStore` overrides it, updated
wherever its `config` is, through the pure `membershipAfterReload` (the three-valued twin of
`configAfterReload`: an unreadable reload keeps the last conclusive answer).

`UploadTransitions` and the push receivers read `MembershipRead`. On `Unreadable` they log and defer.
`FileBackedConfigStore` already classifies absent versus unreadable (`isConfigFileAbsence`), so the
information exists and is only dropped at the port today.

`isGranted` becomes `hasUsableAccess` everywhere it is bound to `grantsPhotoAccess`.

### D12. Confinement and ordered emission

- **`PhotoSelectionSnapshotSource`:** every emission goes through one `Channel` consumed on the source's
  serial lane (`limitedParallelism(1)` over `Default`). Each baseline read carries an observation
  generation, so a baseline whose generation has ended is dropped. Changes that arrive during the baseline
  queue behind it.
- **`QueuedPhotoDownloadJobs.outstandingImports`:** registration hops to the scope before the delegate
  callback returns. The delegate schedules it with `scope.launch(start = UNDISPATCHED)` on a
  single-parallelism lane shared with `drained()`.
- **The confinement gate** checks for `@ConfinedTo("lane")` on mutable fields of the classes it knows
  receive OS callbacks.

As built: the ordering core is platform-free — `SelectionSnapshotLane` in `:adapter:generic:app`, over a small
`SelectionPlatform` interface the iOS adapter implements with PhotoKit — so its ordering is tested on the JVM
rather than only on a simulator. Begin, end, baseline and every change go through one unlimited `Channel`
consumed on the lane; the permission collector advances a `@Volatile` generation. `outstandingImports` became
a `MutableStateFlow<List<Job>>` updated atomically (the law's "thread-safe primitive" branch) rather than a
lane hop: it is the smallest change that makes registration and the drain's take race-free, and a test that
races 500 registrations against drains fails on the old list and passes on the cell. `@ConfinedTo` lives in
`model/` (source retention); the gate covers every `NSObject()` subclass plus a named list of classes that take
OS callbacks through a Kotlin interface.

### D13. Guarded commands in presentation

`StatusContainerHost` gains `guardedIntent(start: (S) -> S?, run: suspend (S) -> R, apply: (current: S,
started: S, result: R) -> S)`:
- `start` reduces into the in-flight state synchronously, before any suspension. When it returns `null`,
  the intent is a no-op, which is what makes a double tap harmless.
- `apply` runs only if the current state still derives from `started`. Each surface defines that as an
  equality on a token such as its `eventId` or a per-surface nonce.

Create, rename and the switch's leave move to it.

`reconfiguringState` and the rename status are keyed by the joined `eventId` inside a single `JoinedSurface`
state, which resets whenever `config.eventId` changes.

As built, simpler than the signature above: `guardedIntent(command, run)` claims the command in a
`MutableStateFlow<Set<Guarded>>` synchronously in the tapping thread (an atomic `getAndUpdate`), launches the
intent, and releases the claim when its body returns. Create and rename are detached on the core lane, so their
use-cases also refuse a second call while their status is `InFlight`, checked before the first suspension. The
switch applies its result only if the pending join is still the one it started from (identity), so a cancel
during the leave stands. Opening the rename sheet clears a terminal latch left by a rename that finished after
the sheet was dismissed. The settings surface and the rename status are `Owned(eventId, value)`, read back only
while that event is the joined one, and both are reset where a membership begins — just before the join
commit, on the interactive and the auto-confirm path. A collector on the config's event id was tried first and
failed in CI: the config is a StateFlow, so a leave and a rejoin of the same event can conflate into A → A, and
the collector never sees the change.

### D14. Entry-point parity tests and a cold world

- **Cold world:** `World` drops its `init`-time touches of core lazies, and a text gate (`WorldBootsColdTest`)
  keeps it that way: `World.kt` may reach `core.` only when deferred. *As built:* this replaced the planned
  test-only `initializedMembers()` accessor, because common Kotlin cannot enumerate `Lazy` delegates without
  reflection, and a production hook for a test would be the wrong trade.
- **Parity tests:** superseded on rebase. Main's `shell-as-driving-adapter` turned the OS entry points into an
  inbound port (`PlatformEntries`, implemented in `compose/`) and contracts every entry through it over a fresh
  World (`PlatformEntriesContract`, coverage enforced by `ContractCoverageTest`). With the world booting cold, that
  contract is the cold-core parity test, so the separate `@ParityFor` tests and their gate were dropped. Each
  entry point gets a `@ParityFor("<entry>")`-tagged test in `:test:integration`, and the parity gate matches
  the two sets exactly.

## Risks / Trade-offs

- **[Pin churn]** The widened seam gate starts with about 60 pins. → Each pin is one line with its reason.
  The count is expected to fall as D3 lands, which is the point of the inventory.
- **[Heuristic gates miss cases]** The ObjC-boundary and confinement scans are text-based. → Each says so
  in its source, and each is sampled by a positive self-test (the pattern `KeychainContainmentTest`
  already uses). They narrow the hazard; they do not prove it absent.
- **[Cross-process compare-and-clear race]** Keychain has no atomic compare-and-delete. → The window is
  tiny and the cost is one redundant refresh. This is recorded, not engineered away.
- **[Shipped v2 clients seeing `409`]** They treat any non-2xx on attest as a failed renewal
  (`HttpAttestClient.post` returns `null`) and clear nothing. That is strictly better than today. →
  No client coordination is needed. The backend can deploy before or after the client.
- **[Lambda-default ban on test ergonomics]** Tests have to wire every seam. → Test sources are exempt, and
  the world supplies the standard bindings. Only production call sites lose their defaults.
- **[`fanOut` in the diagram transcriber]** → This is handled in the same PR as D8. The transcriber's
  closed grammar fails the build until it knows the form, so the failure cannot be silent.
- **[Parity tests for UI-less entry points]** Silent push, BGTask and URLSession relaunch have no UI
  outcome to assert. → Each asserts world outcomes: rows settled, imports landed, the receipt released.

## Migration Plan

1. **G1 PR:**
   - ports and values per D3;
   - `UserQueries`;
   - constructor-bound callbacks (fixes B1);
   - the lambda-default ban and the `StatusActions` factory;
   - the widened seam gate, the callback-slot gate and the lambda-default gate;
   - the lane gate extended to queries.
2. **G2 PR:** core glue moves into `compose/`, the world boots cold, and the parity tests and parity gate
   land.
3. **G3 PR:** catch helpers plus the catch gate, `fanOut` plus the flow gate plus the transcriber, the
   `required`/`bestEffort` steps and the reconfigure outcome, and the ObjC helpers plus their gate.
4. **G4 PR:**
   - backend: the v2 attest router with `409`, and `api/` tests showing that v1 still answers `401`;
   - client: the interceptor classification, the sealed attest outcome, compare-and-clear, and
     `MembershipRead` plus the `hasUsableAccess` rename.
5. **G5 PR:** the ordered snapshot source, the confined `outstandingImports` and the confinement gate,
   `guardedIntent`, membership-keyed surface state, and the screens-no-suspend gate.

Each PR is independently revertable. A revert of the G4 backend half is safe for every client version.

After G1 lands, re-run the device reproduction of B1 (handed off to workspace `repro-onstaged-drop`) to
confirm the fix on the SE2.

## Open Questions

- **Is `409` acceptable for a v2 stale challenge?** D10 records the reasoning. The operator may prefer
  another code before the G4 PR.
- **Should the parity gate also cover the extension's entry point (`process()`)?** It is not in the iOS
  root. The default is no, because the extension's cycle is already covered by `uploadCore` tests. Revisit
  if an extension-only wiring defect appears.
