## Context

Both app-process transports construct a background `URLSession` unconditionally:

- `adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/download/IosDownloadTransport.kt:57-65` — a `run { }`
  block builds the configuration (`discretionary = false`, `allowsCellularAccess = true`,
  `sessionSendsLaunchEvents = true`) and hands it to `sessionWithConfiguration(config, delegate, null)`.
- `adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/IosUrlSessionUploadPlatform.kt:159-160`
  — the same shape, with a bare configuration and no tuning.

On a simulator neither moves a byte. `nsurlsessiond` resolves each client's bundle identifier as it
evaluates the XPC connection, and rejects a client that has none — which is every process an app author can
build there. The client observes `NSURLErrorDomain / -1`. This is recorded as the contract of record
(`ios-url-session-upload`, "The app-driven tier uses one transport on every host"), with six ruled-out
candidate fixes and an expiry trigger, and is not re-litigated here.

### This change's own measurement

Run **2026-08-25**, macOS 26.5.2 / Xcode 26.6, iOS 26.5 simulator (iPhone 17 Pro), from a Kotlin/Native
`iosSimulatorArm64` test binary against a loopback HTTP server serving a 4096-byte file. Seven variants, one
run:

| # | variant | result |
|---|---|---|
| 1 | **default** session, `http://127.0.0.1:8099/probe.bin` | `settled=true status=200 expectedBytes=4096 err=«none»` |
| 2 | `didFinishDownloadingTo` → temp file → `moveItemAtURL` **inside the delegate call** | `tempExists=true tempSize=4096 moveOk=true stagedSize=4096` |
| 3 | `NSHTTPURLResponse` cast + `expectedContentLength` | `200`, `4096` |
| 4 | default session: does `didFinishEventsForBackgroundURLSession` fire? | **`false`** |
| 5 | bare background configuration's defaults | `discretionary=false`, `sessionSendsLaunchEvents=true`, `allowsCellularAccess=true` |
| 6 | `identifier` on each configuration kind | default `null`; background `"probe.cfg"` |
| 7 | **background** session, same URL | `NSURLErrorDomain/-1`, `didFinishDownloading=false`, **`didBecomeInvalidWithError` not called** |

Variant 1 was the kill switch: had it failed, this design would have no basis. Variant 2 is the one that
makes the seam a *configuration* seam rather than a transport rewrite — the staging step production performs
inside the delegate call works identically.

Variant 7 was captured with `nsurlsessiond`'s own log streaming, which names the client process:

```
21:24:54.030  Evaluating new XPC connection … from pid 24316 … client bundle identifier (null)
21:24:54.347  Process with pid 24316 does not have a bundle ID, rejecting connection
21:24:54.378  [0x1039f9680] invalidated … xpc_connection_cancel()
```

The connection was evaluated, explicitly rejected, and cancelled — twice — and the client's session **still
never called `didBecomeInvalidWithError`**.

⏰ **Expiry:** re-measure at the next iOS major, alongside the existing `nsurlsessiond`, PhotoKit and
limited-access platform facts. Evidence is one host, one runtime, n=1 per variant.

### An incidental correction

`2026-08-25-correct-simulator-background-session-claims` records as a standing, unmitigated risk that the
mechanism is *inferred*: "the daemon logs `(null)` and then drops the connection; **it does not print
'rejecting because the bundle identifier is null'**. The causal link is a perfect correlation across five
clients, not an explicit refusal message." That is measured false above — the daemon prints exactly that, at
`E` severity. See D6.

## Goals / Non-Goals

**Goals:**

- Make the simulator host move bytes in both directions, so two-member scenarios become possible.
- Put the choice where nobody can take it wrongly: a compilation target, not a runtime read, and not the
  shell.
- Change no shipped device binary's behaviour, and make that mechanically checkable.
- Say loudly, in the running system, which binding a process got and what it therefore cannot evidence.

**Non-Goals:**

- **Suspend/resume and OS relaunch on a simulator.** Device-only by vendor guidance — Quinn's *Testing
  Background Session Code*: "Test on a real device, not in Simulator" and "Simulator may not accurately
  simulate app suspend and resume" (r. 16532261). Independently, variant 7 shows no transfer can outlive the
  process there, so it is unmeasurable as well as unsupported.
- **Any runtime host determination.** No `SIMULATOR_DEVICE_NAME`, no `NSProcessInfo` environment read, no
  composition branch. This is the thing `2026-08-09-delete-simulator-session-downgrade` deleted and it does
  not come back.
- **Changing the tier resolution.** `resolveUploadMechanism` is untouched: PhotoKit-vs-URLSession is a
  genuine runtime decision over the OS fact and the photo grant, and the transport binding is a different
  axis that never enters it.
- **`PermissionStatus.LIMITED`, APNs, and the OS-driven PhotoKit tier on a simulator.** Still absent; this
  change touches none of them.
- **Re-measuring the `nsurlsessiond` refusal itself.** Already the contract of record. Variant 7 measures
  something new — the *client's* silence — not that.

## Decisions

### D1 — The binding is chosen by compilation target, not by a runtime read or an injected factory.

`internal expect fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration` in
`:adapter:ios:app-only`'s `iosMain`, actualized in `iosArm64Main` (background) and `iosSimulatorArm64Main`
(default).

This is byte-for-byte the shape `:adapter:ios:ext-safe` already uses one module over, for
`device-identity`'s store split (`DeviceIdStores.kt`, decision record
`changes/archive/2026-08-25-add-simulator-rig-host` D6), whose KDoc argues the case in terms that transfer
without amendment: *"`iosSimulatorArm64` is not a guess about the host: it is a compilation target whose
output only ever runs on a simulator. A device binary therefore contains no route to the store below."*
`module-architecture`'s "One shared composition" states the same as a requirement — *"A fact that is fixed
by the compilation target SHALL NOT be re-derived at runtime and SHALL NOT enter that function."*

*Alternative considered — gate on `-Psnapsync.rig=true`.* Rejected on a fact about the flag rather than a
preference: **the rig's primary host is the physical SE2**, not a simulator (`.claude/skills/rig-channel`,
and `scripts/device-guard` fences it). A rig-gated downgrade would give every device rig build a foreground
session — the exact opposite of the intent — and would stop the rig exercising the transport it exists to
drive. Recovering that means ANDing the flag with the target, which is more machinery reaching the same
place as D1.

*Alternative considered — an injected `(String) -> NSURLSession` factory chosen in composition.* Rejected:
both construction sites are in `:app:ios` (`SnapSyncRoot.kt:396`, `UrlSessionUploadController.kt:129`),
which is wiring-only with zero conditionals, detekt-gated. Every place the factory could be chosen is a
conditional in a module that forbids them — which is how the deleted downgrade was shaped.

**The cost, stated:** every simulator build is degraded, not only rig ones. Measured as free today —
`screenshots.yml` boots no live stack, and `:adapter:ios:app-only`'s four `iosTest` files construct no
session. If a future simulator test wants the background class, it must construct the configuration itself
rather than reach through the seam.

### D2 — One seam serving both call sites, returning a configuration rather than a session.

The seam owns exactly the axis that differs. Both sites keep their own `sessionWithConfiguration(config,
delegate, null)` — identical at both today — and their own delegate.

*Alternative considered — return a whole `NSURLSession`.* Rejected: download needs three tuned properties
and upload needs none, so the seam would grow a parameter object or two overloads, and the sites would stop
sharing it anyway.

*Alternative considered — two seams, one per call site.* Rejected: four actuals, two copies of the
measurement prose, and nothing keeping them in agreement. Drift between two statements of one platform fact
is the failure mode this area has already produced twice.

### D3 — `IosDownloadTransport`'s three configuration properties move into the device actual, and that is behaviour-neutral because it was measured.

Sharing one seam means the download site can no longer tune a configuration the upload site also receives —
unless the tuning is neutral. Variant 5 establishes it is: `discretionary=false`,
`sessionSendsLaunchEvents=true` and `allowsCellularAccess=true` are exactly what a bare background
configuration already carries. The upload session, which sets none of them today, is unchanged; the download
session states explicitly what it was relying on implicitly.

The alternative — leaving the lines at the download call site — would set two background-only properties on
a configuration that ignores them. Harmless, but it leaves an inert line in the one file whose job is to
stop this area making claims that are not true of the target in hand. (Variant 5 also shows
`sessionSendsLaunchEvents` defaults to `false` on a *default* configuration, so leaving the line in place
would set it away from its own default there — a small extra reason.)

*Alternative considered — a second `expect fun applyBackgroundTuning(config)`, no-op on the simulator.*
Rejected: four actuals, still called only from the download site, so it relocates the per-site knowledge
rather than removing it.

### D4 — The wake path degrades honestly, and the degradation is predicted rather than discovered.

Both tiers feed `BackgroundEventsReceipts.drained()` from exactly one source —
`URLSessionDidFinishEventsForBackgroundURLSession` (`UrlSessionUploadController.kt:140`,
`QueuedPhotoDownloadJobs.kt:213`) — and variant 4 shows a default session never sends it. So on a simulator a
`handleEventsForBackgroundURLSession` wake adopts a receipt that **nothing can release**, and it holds to the
20 s `ReceiptDeadlines.BACKGROUND_EVENTS` bound and expires. `IosUrlSessionUploadPlatform.reattach()` is
structurally inert for the same reason: `getAllTasks` can never find a prior process's task.

The simulator actual therefore logs, once at session construction, what this binding is and what it cannot
do — so the expiry line reads as a predicted host limit rather than a fault. This matters because
`OsReceipt`'s expiry line otherwise collapses two causes with different consequences: *imports genuinely
overran* (a device concern) and *this host has no daemon to signal drain* (not a concern at all). Law
"Absence is never silent": a deliberate collapse must name the consequence that makes it safe for every
cause it absorbs, and here it cannot.

*Alternative considered — synthesise the drain: when the configuration has no identifier, call
`host.onBackgroundEventsFinished()` after the last in-flight task completes.* **Rejected**, and it is the
one alternative this change must refuse by name. `2026-07-12-fix-download-session-lifecycle` D5 declined a
simulator escape hatch precisely because a foreground session "would very likely run straight through this
defect, **manufacturing false confidence**". Synthesising the drain would make a simulator run
indistinguishable from one where the OS really delivered, which is that objection in its purest form. It
would also put a host conditional back inside the transport.

### D5 — D5's protected coverage does not exist, and that is measured rather than argued.

`fix-download-session-lifecycle` D5 and `correct-simulator-background-session-claims` D1 both rest the
refusal on the same claim: a simulator downgrade "removes the only host that exercises
`__NSURLBackgroundSession`", the class whose defect D5 guards — creating a task on an invalidated session
raises an ObjC `NSException` that Kotlin/Native cannot catch, aborting the process.

Variant 7 measures that the simulator **never reaches the invalidation path**. The daemon explicitly rejects
and cancels the connection, and the client session never calls `didBecomeInvalidWithError`. Whatever
`__NSURLBackgroundSession` does on that host, it is not the thing D5 guards; the transport is never told the
session died, so it never faces the reuse that aborts.

Two supporting facts, so this does not rest on one variant:

- **No automated coverage is lost, because there is none.** `:adapter:ios:app-only`'s `iosTest` is four
  files (`IosStagedBytesTest`, `WebLinkActivityTest`, `IosBackgroundSchedulerTest`, `UrlSessionOutcomeTest`)
  and none constructs a `URLSession`. Nothing in the repo constructs a background session outside the two
  production call sites.
- **The device keeps the background binding**, so the manual device runs that were the only real exercise of
  that class are untouched.

**Stated honestly:** the observation window was ~10 s after the transfer settled, n=1. A late invalidation
outside that window would not have been seen. It is claimed as "not observed within 10 s of a rejected
connection", not as "cannot occur".

D5's *decision* is not overturned — it refused a foreground hatch for downloads on a host where the
consequences were unmeasured, which was right at the time. What is retired is the specific ground that a
simulator background session covers the invalidation defect.

### D6 — Correct the "no explicit refusal message" risk, in the spec, not the archive.

`correct-simulator-background-session-claims` carries an unmitigated risk that the mechanism is inferred
from correlation rather than stated. The daemon states it: `Process with pid <n> does not have a bundle ID,
rejecting connection`. The forcing proof in `ios-url-session-upload` is upgraded from correlation to a quoted
refusal, and the sentence disclaiming an explicit message is removed.

**The archive is not edited.** This repo has settled the same situation three times the same way (the
limited-access alert-storm claim; `delete-simulator-session-downgrade` superseding D5;
`correct-simulator-background-session-claims` superseding D1), on the reasoning that an archived record is an
account of what was believed then, and editing it erases the evidence that the belief existed.

### D7 — Two mechanical pins, and the important one guards what no test can run.

- `:adapter:ios:app-only` `iosSimulatorArm64Test`: the simulator actual yields a nil-identifier
  configuration, and `transferSessionBinding` reads `"default"`.
- `:test:architecture`: a source-text guard asserting `iosArm64Main`'s actual names
  `backgroundSessionConfigurationWithIdentifier` and `iosSimulatorArm64Main`'s does not — exact in both
  directions.

The second carries the weight. The device actual **cannot** be executed by any test in this repo — the iOS
tests run on `iosSimulatorArm64` and nothing else — so a source-text gate is the only mechanism that can stop
a future edit quietly shipping a foreground session to real users. `:test:architecture` already holds guards
of exactly this shape for exactly this reason (`KeychainContainmentTest` catches fully-qualified `SecItem*`
calls "which no linter can see on `iosMain`").

*Alternative considered — a gating end-to-end transfer test.* Rejected on a structural obstacle, not
preference: it needs a loopback HTTP server in the test binary, and `:test:rig` is *"the ONLY module
permitted to depend on `ktor-server-*`"* — a withholding argument the module set enforces by compile error.
Satisfying it means hand-rolling a socket server in `iosTest`, amending that law inside an `internal` change,
or depending on the public internet. All three are larger than the seam they would guard.

*Alternative considered — no permanent pin at all,* which is what both prior D5s chose. Rejected here
because this is the first change to put **code** on the fact: previously a wrong claim cost a stale comment,
and now it would cost a shipped binary.

### D8 — The binding is reported as data, from the seam that chose it.

A public `transferSessionBinding` accompanies the `internal` seam; `:test:rig`'s `Boot.kt` reads it into
`RigHooks.buildFacts()`, which already carries `uploadTier` and `uploadBase` for the stated reason that "a
caller reading state should not need a second request to learn which backend the build it is reading is
pointed at". `:test:rig`'s `iosMain` already depends on `:adapter:ios:app-only`, so no new module edge.

One public symbol is added to the adapter. It reports a fact and decides nothing.

*Alternative considered — the log line alone, read via `/logs`.* Rejected: it makes the only
machine-readable form of a load-bearing fact a substring match on prose, and it is absent until the first
transfer or wake constructs the session.

*Alternative considered — `:test:rig` derives the binding with its own `expect`/`actual`.* Rejected: the
fact would then exist in two places that can disagree, and the one reported would not be the one the
transport used.

### D9 — A scenario is stopped from over-claiming by the answer it gets, not by a document.

The `Receipted` trigger response gains the binding, and extends the `note` field — which exists precisely to
say what its numbers do not answer — to state that under `default` the run exercised adopt and
session-identifier routing only, that the OS delivered and relaunched nothing, and that the expiry is the
host's inability to signal drain rather than a fault.

Labelling in the spec and the runbook is necessary and not sufficient: prose contradicted by nothing is how
both prior false claims survived. A caller who never opens a spec still cannot read this result without
reading what it evidenced.

*Alternative considered — refuse the trigger entirely under the default binding.* Rejected: it removes real
coverage. Adopt, the receipt bound, and session-identifier routing all genuinely run there, and that routing
is "one of only two pinned complexity suppressions in `SnapSyncRoot`, and untestable by any other means"
(`test/rig/.../Boot.kt:172-173`).

## Risks / Trade-offs

- **[A simulator run is mistaken for background coverage]** → Three layers, none of them only prose: the
  trigger response carries the binding and says what the run evidenced (D9); `/device/state` reports it
  (D8); the session logs it at construction (D4). The spec and the runbook state it as well.
- **[Every simulator build is degraded, not just rig builds]** → Accepted (D1). Free today —
  `screenshots.yml` boots no live stack and no test constructs a session — and the cost falls on a future
  simulator test wanting the background class, which can construct a background configuration directly.
- **[The 20 s receipt expiry on every simulator `handleBackgroundUrlSession` wake]** → Accepted and
  predicted (D4). Cost is wall-clock in a dev scenario. Not mitigated by synthesising a drain, deliberately.
- **[Variant 7's window was ~10 s, n=1]** → Stated as measured (D5), not widened into "cannot occur". If a
  late invalidation is ever observed on a simulator, D5's ground is restored in reduced form and the spec
  text says which sentence to change.
- **[The device actual is unexecutable by any test]** → This is why D7's second pin is a source-text guard
  rather than a test. The residual gap is real: the guard checks that the device actual *names* the
  background factory, not that the resulting session behaves. Only a device run shows that, and it is
  unchanged by this change.
- **[A third wrong claim grows in this spot]** → Not eliminated. What is different this time: the change
  ships two gates rather than none, its claims are each tied to a numbered variant with a command that
  reproduces it, and every claim states its observation window. The expiry trigger is named.
- **[`upload-lifecycle`'s delta restates a long requirement]** → The MODIFIED delta is built from the
  current main spec and diffed, so the removed lines are only the intended ones. Named because this failure
  mode is silent.

## Migration Plan

None. No durable state, no wire format, no stored value, no backend surface. Every edit is compile-time, and
`iosArm64` yields the same configuration with the same three property values before and after — which D7's
guard makes checkable rather than merely asserted. Rollback is reverting the commit.

## Open Questions

None. The one this change was left to settle by
`2026-08-25-correct-simulator-background-session-claims` D1 — whether the trade is worth making — is
answered by variant 7 (D5). What remains genuinely unknown is Apple's reason for not giving third-party
simulator processes a bundle identity over XPC, which is not actionable here and whose observable
consequence is fully measured.
