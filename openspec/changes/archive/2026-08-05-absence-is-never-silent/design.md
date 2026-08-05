## Context

`SNAPSYNC-3` is a diagnostic dump that cannot answer its own question. The reported symptom — switching events needs a force-quit — is proven by the log; the cause is not, because the failing seam is silent when it declines to act. Two candidate causes (iOS never invoked `scene(_:continue:)`, versus it did and the Kotlin filter discarded the activity) produce **byte-identical dumps**.

The constraint that makes this hard is structural and deliberate: `:app:ios` and the Swift shells are **wiring-only and untested by project rule**, so no test can observe this seam. What is left is the device log — and the device log records nothing on the path that failed.

The relevant existing machinery:

- `LogContext` (`:adapter:ios:ext-safe`) is a process-global "what triggered this" holder, driven through the `:domain` `ports/LogScope` seam, that prefixes every device-log line with `[<entryPoint>]`. `Logger.invocation` sets it. `AppPorts.logScope` already exists and is already injected (`IosLogScope` in production, `LogScope.NoOp` in world/tests).
- `SentryLogWriter` already maps every Kermit line at Warn-and-below to a Sentry breadcrumb carrying that prefix, so entry logs reach Bugsink with no new code.
- `config/detekt/app-shell.yml` sets `CyclomaticComplexMethod` threshold **2**: one `if` anywhere in `:app:*` fails the build.
- `:ui:presentation` is forbidden by the presentation gate from referencing `ports/`, so it cannot reach `LogScope`.

## Goals / Non-Goals

**Goals**

- Name the rule the codebase already lives by, so the next silent-absence bug is a violation rather than a discovery.
- Make an absent entry log **unambiguous**: today it means either "the OS never called" or "we discarded it"; after this it means exactly one thing.
- Derive the entry-point population, never hand-enumerate it.
- Every line in `debug.log` traces to a named trigger — an OS callback or a user tap.

**Non-Goals**

- Making the OS call us. If iOS invokes neither warm hook, nothing inside the app proves it directly; what changes is that the silence becomes conclusive.
- Determining *which* hook iOS 18 calls, ahead of time. The design is deliberately indifferent to it (D12) and lets the next dump answer it.
- A tree-wide audit of every nullable return. The `ports/` sweep is bounded and deliberate; `feature/` and adapter internals are out.
- Outbound platform calls (PhotoKit reads, `SecItem`, file IO). Scope-3 logging would add ~150 sites at **per-asset** frequency — a 4000-asset walk emits 4000 lines per cycle and rolls the 10 MB `debug.log` cap, deleting the evidence this change exists to preserve.

## Decisions

### D1 — A law, not a logging rule

The rule is stated as a `module-architecture` law (**Absence is never silent**) rather than only as a `diagnostic-logging` requirement.

The four conforming instances — `ConfigFileRead`, `ConfigRead`, `JoinLoad`, `SwitchDecision`, `KeychainRead` — are nowhere near an entry point; they live in `ports/` and `feature/membership`. A rule scoped to entry points would not govern them, so it would be a rule about one seam instead of a named pattern the codebase already follows. Stated as a law it is **retroactively true of five seams and finds exactly three violations** — a law discovered, not invented, which is why it is cheap to accept.

*Alternative rejected:* extend `Commands cross one door`. That law already names OS callbacks as a driver kind, but scoping the rule to the door excludes the five precedents, which are the evidence that the rule is real.

*Precedent for a prose law:* `Necessity claims carry forcing proofs` has no mechanical gate. A law here therefore does not oblige inventing one; it lands with mechanical enforcement at the two seams where enforcement is possible (entry points, `ports/`).

### D2 — The law's discriminator is consequence asymmetry, not nullability

The naive form ("no nullable returns at seams") flags ~24 functions and is noise. The real invariant is the test this codebase already reasons with — the selection policy's *admits on doubt*, the config's *must defer*, the cutoff's *erring toward now is fixable*:

> "nothing" and "couldn't tell" are different answers **wherever their consequences differ**; a deliberate collapse names the consequence that makes it safe **for every cause it absorbs**; and an entry point never collapses into silence.

Both halves are load-bearing. `DiscoveryStore.loadToken` collapses and is correct — *"a cold start with no stored token re-enumerates the whole library, which the ledger makes harmless."* `attestToken` collapses and is **wrong**, not because it collapses but because its stated consequence (`-25308` → retryable 401) does not cover `-34018` (permanent, invisible). "For every cause it absorbs" is the clause that does the work.

The entry-point clause is absolute because the asymmetry there is always the same: a lost trigger is invisible and unfixable; a spurious log line is harmless and visible.

*Honest limit:* the law covers one of the two ways a seam lies. `DeviceManifestStore.loadLastUploaded`'s real historical bug was a **stale non-null** (*"the record survived the re-enroll's empty manifest and suppressed the rewrite forever"*), which this law says nothing about.

### D3 — The entry-point population is derived by three rules

`Commands cross one door` already requires it: *"The trigger inventory SHALL be derived from entry points, never hand-enumerated."* This was tested empirically during design — a hand-enumeration of `SnapSyncRoot`'s entry points was wrong in **both** directions (it included `onOpenUrl`, which the platform never calls, and misclassified `onForeground`/`onBackground`).

```
  R1  every member of a root object invoked from OUTSIDE the root's own file
      ├─ iosApp/iosApp/iOSApp.swift      → SnapSyncRoot.shared.{7}
      ├─ iosApp/BackgroundUploadExtension → UploadExtensionRoot.shared.processRawValue
      └─ app/ios/.../MainViewController.kt → applyLaunchEnvMembership, applyLaunchEnvSeed
         (the SECOND Swift→Kotlin door: Swift calls MainViewControllerKt.MainViewController())

  R2  every `override fun` in a class matching `: NSObject(), *Protocol`
      └─ IosUrlSessionUploadPlatform, IosDownloadTransport.Delegate, PhotoSelectionObserver

  R3  every observer body registered via addObserverForName / registerChangeObserver
      └─ onForeground / onBackground, the PhotoLibraryPermission observer
```

R1 subsumes both Swift doors with one rule. `onOpenUrl` correctly falls out as **not** an entry point (it is reached from `onUserActivity` and the launch-env trigger).

**What counts as an entry point is who is on the other side of the call** — Swift or the OS, versus our own Kotlin. That is why `MainViewController()` is annotated while `shareableCount`/`photoPermission` are not: presentation reads those, iOS does not.

*Residue, named:* a callback shape none of R1–R3 describes (a C function pointer, KVO, a `dispatch_source` handler). Small, and nameable, which is the honest place to leave it.

*Alternative rejected:* a pinned file list plus a keyword inverse sweep. It is the staleness hole this change exists to close, and the door law already disfavours it.

### D4 — The `log.invocation` wrap moves up to the entry points

Today it lives in `LiveShell`/`ForgeShell`, so `SnapSyncRoot`'s entry points are bare delegators. Moving it up puts the annotation and the wrapper on one declaration, collapses the duplicated per-shell wraps, and is the shape the guard can check. `fun onLaunch() = log.invocation("onLaunch") { … }` already exists in this file and already passes the detekt gate, so the form is proven safe.

### D5 — Outcomes are named via `result =`, not via sealed return types

Every entry point supplies `log.invocation(..., result = { … })`, generalizing what `UploadExtensionRoot.process()` already does. Sealed **return types** are used only where a drop is possible and the caller must branch (`eventLinkFromUserActivity`); making every ObjC-visible entry point return a Kotlin sealed type would complicate the Swift boundary for entry points like `onBackground` that have nothing to return.

The sealed outcome for `eventLinkFromUserActivity` keeps its `when` inside `:domain` `model/`, so `:app:ios` stays straight-line under the detekt gate.

### D6 — No runtime "entered without an entry scope" assertion

Designed and rejected. With the population rule-derived (D3), its only unique coverage was the R1–R3 residue. It cannot see the failure class that caused `SNAPSYNC-3` (an entry that decides and returns without touching the core — there is no runtime moment to assert on), and it cannot see cold paths (`runDownloadBackstop`, `onSilentPush`, `process()`, `notifyTermination`) because dev builds do not run them. Meanwhile at the core-command boundary it would fire on **every user tap**, since `UserCommands` enters through `AppCore` with no ambient scope — visible today as the unprefixed `[Info/Http]` and `[Info/DownloadController]` lines throughout the dump. Shell-only placement avoids the noise but watches a door the guard already watches.

*A type-level token was rejected earlier for the same reason plus two more:* it proves "you logged before calling the shell", not "you logged before deciding"; and `@JvmInline value class` is not representable in the generated ObjC header, so the entry points that matter could not take one.

The effort went to the UI door instead (D7), which is the actually-uncovered surface.

### D7 — The UI door is decorated in `compose/`

`UserCommands` is wrapped at its single construction site (`SnapSyncApp.kt`), using the already-present `ports.logScope`. This is where the door law says decoration belongs (*"instances SHALL be built, decorated ... only in `compose/`"*), and it is the only place it **can** live: `:ui:presentation` may not reference `ports/`.

Taps carry a `tap.*` context (`tap.leave`, `tap.confirmSwitch`) so a reader distinguishes *the OS did this* from *the user did this* without knowing the codebase. Concretely: establishing that `SNAPSYNC-3`'s 08:49:56 leave was a manual tap and not the switch path required reading `Provision.kt` and `SnapSyncApp.kt` to prove `SwitchDecision.LeavePrevious` fires only `notifyLeave`. A `[tap.leave]` prefix makes that a one-second read.

### D8 — Severity: `Info` for OS-event entries, `Debug` for per-item ones

`photoLibraryDidChange` fires on every library mutation — including each asset the importer creates, so a 200-photo import emits hundreds of callbacks — and the per-task `URLSession` callbacks are one per photo. At `Info` they would flush the ~100-breadcrumb Sentry window before a crash and bloat the rolling 10 MB `debug.log`. Session-level and OS-event entries stay `Info`.

### D9 — The nullable-seam sweep: derive the population, pin the verdict

Every nullable-returning member of the audited zones is audited once and carries a one-line consequence. The guard derives the population and fails when a member has no verdict — so a **new** nullable seam there is a red build until someone states its consequence. Only the reasons are hand-written; the list is not. Same split as D3, and the same idiom as `DeletionLedgerTest` / `RuntimeIdentityTest`.

**The population is `ports/` + `model/` + the composition roots, not `ports/` alone.** The first draft of this decision scoped it to `ports/`, and the audit then showed that *both* violations motivating this change fall outside it: `eventLinkFromUserActivity` is in `model/`, `attestToken()` is in `:app:ios:extension`. A guard that catches neither of the bugs that prompted it is worth stating plainly rather than discovering later. Widening costs little — `model/` holds **6** nullable-returning functions and two of them *are* the entry-point filters (`UniversalLinkActivity`, `PushPayload`) — and it is what gives the guard retroactive as well as prospective power.

`feature/` and adapter internals stay out: the zones above are small and bounded, which is what makes derivation cheap and non-vacuous, and a tree-wide equivalent would be neither.

The audit is affordable and has been done (verdict table below): of 13 `ports/` seams, 3 conform, 7 need a sentence, 1 needs thought, 1 is deferred; `model/` adds 6.

#### Verdict table — `ports/`

| Seam | Verdict |
|---|---|
| `DiscoveryStore.loadToken` | conforms — *"a cold start with no stored token re-enumerates the whole library, which the ledger makes harmless"* |
| `AttestStore.token` / `keyId` | conforms — `readExisting` throws on `Unavailable(status)` rather than returning null |
| `LedgerStore.get` | conforms — dumb row store, null = no row; backend failures throw |
| `DeviceLogSource.tail` | state the consequence — a null omits that log from the diagnostic dump |
| `AlbumMapStore.get` | state the consequence — self-healing cache (said in CLAUDE.md, not at the seam) |
| `DeviceManifestStore.loadLastUploaded` | state the consequence — null re-writes the manifest, which is idempotent |
| `AttestClient.challenge` / `mintToken` / `renewToken` | state the consequence — null → unattested → retryable 401 |
| `DownloadTransport.destinationFor` / `start` | state the consequence — `start` reasons about not throwing but not about what a null costs |
| `JoinedEventMarker.read` | **needs a decision** — absence is load-bearing (*"a reinstall is an absent marker"*), so a read failure of the shared store forges "reinstall". Bounded (one extra reconcile, not a re-upload), but this is exactly the shape the law targets |
| `AlbumManager.ensureCreated` | deferred — silent failure under an explicit `saveToAlbum` opt-in (see Open Questions) |
| `readExisting` | conforms — **found by the guard, not by reading.** It returns `String?` and is the reference implementation of the rule: absent → null, unreadable → throws. It was missing from the hand audit entirely |

#### Verdict table — `model/` and the composition roots

| Seam | Verdict |
|---|---|
| `eventLinkFromUserActivity` | **fixed** — now returns `EventLinkDelivery`, the three states named |
| `pushEventId` | conforms — absent key and wrong type are one answer (no fan-out, handler released); the payload is an already-materialised dictionary, so no read can fail; `SilentPush.run` logs the outcome |
| `Direction.fromWire` | conforms — a pure lookup over a closed set; unrecognised and absent share the caller's response |
| `EventLink.parseFragment` | conforms — pure string work; malformed/empty/unrecognised all surface as one invalid-link error |
| `LaunchDirectives.positiveInt` | conforms — unset, non-numeric, non-positive are all "not requested" for a dev trigger |
| `UploadKeys.resourceRole` | conforms — a pure mapping over an OS enum; unknown and not-carried both skip the resource |
| `DevPhotoSeeder.solidColorImage` | conforms — dev-only; any cause costs one fewer synthetic asset |
| `UploadExtensionRoot.attestToken` | **fixed (partially)** — the collapse stays, but the status is now logged, and the doc names the `-34018` cause its original justification never covered. Whether the cycle should *stop* rather than 401-loop is deferred |

**What the audit cost and what it found.** Twenty-one declarations, resolved in one pass. Seventeen
were fine and only needed their consequence written down; two were the known violations; one
(`JoinedEventMarker.read`) needed a real decision; one (`readExisting`) the hand audit **missed
entirely** and the guard caught — which is the argument for the guard in one line.

**`JoinedEventMarker.read`, decided.** Absence there is load-bearing (*"a reinstall is an absent
marker"*), so the law demanded an answer. The answer is that the collapse is **platform-forced, not
chosen**: the marker lives in a shared `NSUserDefaults` suite, and that API has **no error channel at
all** — `stringForKey` answers nil for an absent key and offers no way to report a failed read, so
there is no third state available to encode. What makes it safe is the bound on being wrong: a forged
"reinstall" costs exactly one reconcile, and a reconcile seeds already-stored photos as `COMPLETED`
rather than re-uploading them. Recorded at the seam with its expiry: if reconciliation ever stops
being cheap and idempotent, the marker needs a store that can distinguish the two.

### D12 — Two warm hooks, not a swap — **ATTEMPTED, FALSIFIED, REVERTED (2026-08-04)**

> **Outcome first.** This decision was implemented, shipped to a device, measured, and undone. The
> second warm hook **cannot fire while this app installs a scene delegate**, so it could never have
> fixed anything. What follows is the original reasoning, kept because the *reason it was wrong* is
> the reusable part; the correction is at the end.


SwiftUI's continuation modifier is added **alongside** `scene(_:continue:)`, not instead of it.

The evidence is the 2026-07-16 device matrix, measured on an SE2 running **iOS 26.5.2**: `.onOpenURL` no/no, the SwiftUI continuation modifier **no cold / YES warm**, `application(_:continue:restorationHandler:)` no/no, scene delegate **YES/YES**. That table is still the best evidence in the tree and this change does not contradict it — it records what the table never covered: **one OS version, on one device.** The deployment range is iOS 18 through 26, and the failing report is iOS 18.7.9.

*Rejected:* swap the scene delegate's warm half for the modifier. That is the clean split — cold via `willConnectTo`, warm via the modifier, no overlap, no dedup needed — and it repeats the exact mistake being fixed: it would leave **iOS 26** depending on a hook with a single data point in order to fix an **iOS 18** problem no available hardware can reproduce (the project's only test device is the SE2 on 26.5, and forcing the 18–26.0 upload tier there does not touch URL delivery). Keeping both means a link dies only if every mechanism fails on the same OS.

*Rejected:* ship the instrumentation first and the fix after a dump. The objection to fixing now was that the fix is a guess — which D13's per-hook naming dissolves. Splitting would cost the reporter a release cycle to learn what one dump will say anyway.

**What actually happened.** Installed on an SE2 (iOS 26.5.2) and exercised with real link taps:
**8 warm deliveries, 8 hits on `scene(_:continue:)`, ZERO on the SwiftUI modifier.** The cause is
structural, not version-specific: a scene has exactly **one** delegate; `configurationForConnecting`
makes it ours; SwiftUI's own scene delegate — which feeds `.onContinueUserActivity` — is therefore
never instantiated. July's matrix measured that modifier with SwiftUI's delegate in place, because no
custom one existed yet. **The rows are mutually exclusive configurations, not composable features**,
and reading them as composable was the error in this decision. Both branches of the iOS 18 unknown
lead to the same place: if 18 calls the scene delegate the modifier is unnecessary, and if it does not,
the modifier has nothing feeding it either. We cannot drop the scene delegate to make room, because
`willConnectTo` is the only cold path. So the hook was removed, along with D13's suppressor, which
existed only to make two hooks safe.

**Cost of the error:** one device build/install cycle, and a spec requirement asserting "more than one
independently-measured warm mechanism" that was false in the contract of record until corrected. The
change keeps every diagnostic; it no longer claims to fix `SNAPSYNC-3`.

### D13 — The dedup is the experiment's instrument — **REMOVED with D12**

> Removed 2026-08-04: with one warm hook there is no duplicate to suppress, and keeping a suppressor
> whose stated justification had been falsified is exactly the unexamined machinery the law added by
> this change exists to prevent. The **distinct entry-point names survive** — with `onLaunchActivity`
> (cold) and `onSceneContinueActivity` (warm), the next dump is still decisive.


Each hook forwards under a **distinct entry-point name**, and the duplicate suppressor **logs what it suppressed**. Together those make the dump read as an experiment result: either one hook's entry appears alone — naming the winner on that OS — or both appear with the second marked suppressed. Two hooks that logged identically would leave the OS-version question exactly as open as it is today, so the naming is load-bearing, not cosmetic.

Exactly-once is enforced by a pure, clock-injected suppressor in `model/`, keyed on the URL within a short window.

*Rejected:* keying on `NSUserActivity` identity. It would be exact — both hooks receive the same object — but it is an ObjC-runtime fact only `:app:ios` can observe, and `:app:ios` is wiring-only and untested by rule, so the decision would land in the one place nothing can test. A `(url, window)` rule is pure data, lives in `model/`, and runs on JVM and simulator.

*Window:* the hooks fire in the same run-loop turn, milliseconds apart, so anything above a few tens of milliseconds suffices. The upper bound is the shortest interval at which a *deliberate* re-open of the same link must still count — a human cannot re-scan faster than a couple of seconds, and re-opening the currently-joined event is already a no-op, so over-suppression is invisible while under-suppression opens the join gate twice.

### D10 — Swift OS callbacks must reach Kotlin

`SwiftShellGuardTest` gains a body rule: every `func` in `AppDelegate` / `SnapSyncSceneDelegate` / the extension principal must call the Kotlin root. This catches two live holes — `notifyTermination()` (the OS announcing it is killing the upload cycle, forwarded nowhere) and `didFailToRegisterForRemoteNotificationsWithError` (an interpolated `NSLog`, which os_log redacts wholesale, so the line never appears anywhere).

### D11 — `attestToken` gets the cheap half only

`runCatching { … }.getOrNull()` gains a log of the status before returning `null`. Whether the extension should *distinguish* `-34018` and stop rather than 401-loop is a separate design question and is deferred (see Open Questions). The collapse itself stays: its reasoning is sound for the cause it was written for.

## Risks / Trade-offs

- **`LogContext` mislabeling gets more likely** → Its "outermost wins" trade-off was justified by *"iOS delivers app entry points serially per process."* UI taps are **not** serial with background work, so a tap landing inside an in-flight cycle inherits the cycle's label. It degrades onto the tap, not the cycle. Accepted and written into the spec rather than inherited silently — which is the law this change adds.
- **Breadcrumb eviction** → D8's severity split; per-item sites never reach `Info`.
- **The derivation residue** → R1–R3 do not describe every conceivable callback shape. Named in the guard's failure message so the next reader extends the rules rather than adding a pinned exception.
- **Inventory verdicts rot** → Only the reasons are hand-written; a missing reason fails the build, and a wrong-but-present reason is a review problem, not a mechanical one. Accepted.
- **The law covers absence, not staleness** → D2's honest limit. A follow-up law is not proposed on one instance.
- **Scope creep from a "law" framing** → Mitigated by landing it with three mechanical enforcement points and no tree-wide audit beyond the three bounded zones.
- **The fix may not work** → If iOS 18 invokes neither warm hook, nothing changes for the reporter. D3 and D13 make that outcome legible in a single dump instead of another guessing round, and the change still delivers its diagnostic value in full.
- **Double delivery on iOS 26**, where both hooks firing is the *expected* case → D13 is the whole mitigation, and it is the one part of this change a device check on the available SE2 can actually falsify: a warm scan must produce exactly one join gate and one suppression line.

## Migration Plan

No data, schema, or wire-format change; nothing to migrate and nothing to roll back beyond reverting the commit. One change, one build: the instrumentation and the warm-link fix ship together, because per-hook entry naming (D13) is what turns the fix from a guess into an experiment whose result the next dump reports.

Verification is the existing loop: `./gradlew build` for the guards and the `commonTest` outcome and suppression tests, `./gradlew compileIosMainKotlinMetadata` as the Linux proxy for the iOS source sets, and the Swift halves on macOS CI. On the SE2 (iOS 26.5) the checkable claims are that a scanned QR still delivers once cold and once warm with both hooks installed, and that every device-log line carries a prefix. **The iOS 18 outcome is not verifiable on available hardware** and is read from the reporter's next dump — which is why the entry naming, not the fix, is the part of this change that cannot be allowed to slip.

## What this change could not verify

Recorded here rather than left implied, because the fix's whole justification rests on it.

- **The iOS 18 outcome is unverifiable on available hardware.** The project's only device is an SE2
  on iOS 26.5, where warm delivery already works; forcing the 18–26.0 upload tier there changes the
  upload mechanism and touches URL delivery not at all. So no local or CI run can show that the
  second warm hook fixes the reported bug. What ships is an instrumented experiment: whichever hook
  appears in the reporter's next dump is the one the platform invoked, and if neither appears the
  question moves off the app entirely (AASA approval state via `swcutil` in a sysdiagnose).
- **The Swift halves compile only on macOS CI.** There is no Swift toolchain on the development
  machine; `compileIosMainKotlinMetadata` is the Linux proxy and covers the Kotlin side only.
- **The device pass ran only half.** The headless half is done (SE2, iOS 26.5.2): the build installs,
  launches, and the new entry points fire and name their results. The **tap half is not done and
  needs a human** — a warm link tap is the only thing that exercises the two hooks and therefore the
  only check that can falsify a double join gate on iOS 26, which is the one risk
  `EventLinkDeliveryGate` exists to remove. It cannot be automated here: UI gestures need a signed
  WebDriverAgent, `SNAPSYNC_EVENT_LINK` bypasses the delivery hooks by design, and the remaining
  headless triggers mutate a live membership.
- **Prefix coverage is partial, by design and now measured.** 21/21 synchronous entry-driven lines
  carry a prefix; 17 of 38 lines in a launch carry none, all of them escaping `scope.launch` work.
  That matches the stated non-goal, and the device run is what turned it from an assumption into a
  number. The `runDownloadBackstop.run` exit line arriving unprefixed is the process-global
  outermost-wins imprecision this change wrote into the spec — observed, not theorised.

## Open Questions

- **Does iOS 18 call `scene(_:continue:)` at all?** The question `SNAPSYNC-3` turns on, still
  unmeasured. Not measurable here: no iOS 18 device, and **a simulator cannot stand in** — on an iOS
  26.5 simulator, where a real device shows 8/8 warm deliveries, the app received **zero**, because
  simulators do not route universal links (`simctl openurl` opens the URL but never associates the
  domain). Answerable two ways: an actual iOS 18 device, or the reporter's next dump, where
  `onSceneContinueActivity` present/absent settles it outright.
- **Next thing to try when an iOS 18 device exists: `scene(_:willContinueUserActivityWithType:)`.**
  UIKit offers it *before* `scene(_:continue:)`, and a delegate that does not implement it may not be
  offered the continuation at all. It lives on the delegate this app already owns, costs nothing on
  iOS 26, and — unlike the SwiftUI modifier — is not structurally excluded by our own scene delegate.
  Untried, and stated as a lead rather than a diagnosis.

- **`JoinedEventMarker.read` treats a read failure as a reinstall.** Absence is load-bearing there (*"a reinstall is an absent marker"*), so an unreadable shared store forges the reinstall verdict. The cost is bounded — one extra reconcile, which seeds already-stored photos as `COMPLETED` rather than re-uploading — but it is the law's exact shape and the audit could not settle whether the marker's backing store can fail distinguishably. Needs a decision during 7.1.
- **Should the extension distinguish `errSecMissingEntitlement` and stop?** A permanent, un-retryable attestation failure currently 401-loops. Deferred; this change only makes it visible.
- **`AlbumManager.ensureCreated` returns `null` on failure under an explicit `saveToAlbum` opt-in**, silently. "Best-effort" is stated, but the user asked for the album. Surfaced by the audit; deferred as a product question.
- **Does the UI door want its own guard?** The `UserCommands` decoration is one site today, so a guard may be premature. Revisit if a second construction site ever appears.
