## Context

`ios-url-session-upload` states three times that a background `URLSession` runs on the iOS simulator, and
`IosUrlSessionUploadPlatform` repeats it in shipped code. All four trace to one probe, run 2026-08-09 by
`delete-simulator-session-downgrade`, which printed:

```
PROBE getAllTasks called back = true
PROBE delegate fired = true (unknown error)
PROBE task state after wait = 3          # NSURLSessionTaskStateCompleted
```

The probe aimed an upload task at a **closed** local port, and its recorded reasoning was *"a refusal still
proves the session executed the task"*. That inference requires the error to be a refusal —
`NSURLErrorCannotConnectToHost` (-1004) or `-1005`. It printed "unknown error", which is
`NSURLErrorUnknown` (**-1**). `state == 3` does not separate the cases either: a failed task also ends
`Completed`. Of its three lines, one is compatible with total failure, one is ambiguous, and the third
(`getAllTasks` answering) was never established to require a live daemon connection.

**Re-measured 2026-08-25**, same host versions (macOS 26.5.2 / Xcode 26.6), iOS 26.5 and 26.2, six variants
in one process against a live loopback server:

| variant | result |
|---|---|
| foreground, loopback | `OK http=200 received=4096` |
| background, bare config (the 2026-08-09 shape) | `NSURLErrorDomain/-1` |
| background, `IosDownloadTransport`'s exact config | `NSURLErrorDomain/-1` |
| background, upload task (the 2026-08-09 task type) | `NSURLErrorDomain/-1` |
| background, public HTTPS target | `NSURLErrorDomain/-1` |
| foreground, public HTTPS target | `OK http=200 received=255746` |

The foreground control is exact — same process, same URL, the same 4096 bytes the host's own `curl`
fetched. Config, task type and target are each eliminated as axes.

This is the **second** false claim in this spot. The first said the simulator *cannot* run background
sessions; `delete-simulator-session-downgrade` correctly found that unproven and replaced it with a claim
in the opposite direction that was equally unproven. Both survived because nothing re-measures the fact —
a trade that change's D5 recorded and accepted explicitly.

## Goals / Non-Goals

**Goals:**

- Replace every claim that a background `URLSession` runs on a simulator with what was measured.
- Keep "The app-driven tier uses one transport on every host" **normatively unchanged** while replacing its
  now-false ground with a true one.
- Record the mechanism, its evidence, and its expiry trigger, so the next reader can falsify it cheaply.
- Supersede `2026-08-09-delete-simulator-session-downgrade` D1 without editing that archive.

**Non-Goals:**

- **Any transport change.** No downgrade is reinstated, no `expect`/`actual` seam is added, no host
  determination returns. Scoped out by the owner; see D2.
- **A permanent re-measuring test.** Considered and excluded from this change; see D5.
- **Settling OS relaunch on a simulator.** It needs a transfer that outlives the process, and by the
  measurement below none can exist there — so it is not merely unproven, it is unmeasurable on that host.
- **Editing the archive.**

## Decisions

### D1 — Keep the requirement; replace its ground.

"The app-driven tier uses one transport on every host" currently justifies itself with *"A background
`URLSession` demonstrably runs on the iOS simulator … so the downgrade this requirement's predecessor
provided for defended nothing."* The first clause is false and the second no longer follows: a downgrade
**would** defend something — it would make transfers run on that host.

The requirement is kept anyway, on a different footing: a simulator-only foreground session would make the
host *appear* to work while removing the only host that exercises `__NSURLBackgroundSession`, the class
`fix-download-session-lifecycle` D5's crash lives in. D5's own words — a foreground session "would very
likely run straight through this defect, manufacturing false confidence" — are untouched by this
measurement and now carry the requirement.

*Alternative considered — reinstate the downgrade as a target-bound `expect`/`actual`, the shape
`add-simulator-rig-host` already uses for the device-identity store.* Rejected here for two reasons. It is a
behaviour change, and the owner scoped this change to documentation. And it trades a real capability (a host
that exercises the background class) for an apparent one (a host where downloads seem to work), which is the
trade D5 refused. If it is ever wanted, it is a separate change with its own proposal.

### D2 — State the mechanism, not just the outcome.

"Downloads do not work on a simulator" was already believed, twice, on wrong grounds. What makes this
entry different is that it names the acting component and the check that fails, so it can be refuted by
one command rather than by belief:

`nsurlsessiond` resolves each client's **bundle identifier** as it evaluates the incoming XPC connection.
Apple's own simulator processes resolve to a real one (`com.apple.trustd`, `com.apple.bird`,
`geoanalyticsd`) and their background sessions work. Every process an app author can build resolves to
`(null)` and is dropped **after** the connection is accepted — hence `NSCocoaErrorDomain 4097`
(`NSXPCConnectionInterrupted`), not `4099` (`Invalid`, a lookup failure) and not `4102`
(`CodeSigningRequirementFailure`). `NSURLErrorDomain/-1` is the downstream symptom.

Three client shapes were measured: a bare Kotlin/Native test binary, an installed **signed** app, and an
installed **unsigned** app — all `(null)`, all 4097. A purpose-built minimal app declaring a valid
`CFBundleIdentifier` still resolves to `(null)`, so this is not something a bundle, a plist or a signature
can fix.

### D3 — Name the expiry trigger.

⏰ Re-measure at the next iOS major, alongside the PhotoKit and limited-access platform facts. The claim is
about a daemon's behaviour on one simulator runtime family; it is exactly the kind of fact that changes
silently. The forcing-proof wording names the command that reproduces it.

### D4 — Supersede, do not edit, the archive.

`2026-08-09-delete-simulator-session-downgrade` D1's ground — *"with no behavioural difference between
hosts there is no axis"* — is measured false. That record is **not** edited. This repo has faced the same
situation twice (the limited-access alert-storm claim; and that change's own supersession of
`fix-download-session-lifecycle` D5) and settled it the same way both times: an archived record is an
account of what was believed then, and editing it erases the evidence that the belief existed — which is
precisely what let this one stand.

Note the direction of travel: that change superseded D5's parenthetical as false, and this change restores
D5's premise as **correct for third-party code**. D5's decision was never in doubt; only its ground moved,
twice.

### D5 — No permanent re-measuring test in this change, and say what that costs.

`delete-simulator-session-downgrade` D5 argued for shipping its probe as an assertion so the fact would be
re-measured on every push, and the owner rejected it, recording the trade as *"nothing re-measures this
fact, and prose with no runnable answer is how the original wrong comment survived."* That is exactly what
then happened, over sixteen days.

This change does not ship such a test either — it is scoped to documentation, and a test is code. **The
risk is therefore unchanged and is recorded as such below**, not mitigated. The probe used for the
2026-08-25 measurement is reproducible: a `commonTest`-shaped `iosSimulatorArm64` test creating background
and foreground sessions against a local server, ~35 s to run. Reinstating it is a small, separate change.

### D6 — Correct the code comment, though this is a documentation change.

`IosUrlSessionUploadPlatform`'s session comment is where a reader meets the claim first, and it is the
artefact that actually misled. Correcting prose in the spec while leaving the false version in the file is
how the two drifted apart the first time. The comment is the only file touched outside `openspec/`, and
the `by lazy` session it documents is unchanged.

## Risks / Trade-offs

- **[Nothing re-measures the fact; a third wrong claim can grow in the same spot]** → **Not mitigated.**
  Deliberate, per D5. Mitigation is prose only: the new text names the daemon, the check, the error codes,
  the host versions, and the reproduction, so the next reader can falsify it in one run instead of
  believing it.
- **[The mechanism is inferred, not stated by the daemon]** → `nsurlsessiond` logs `(null)` and then drops
  the connection; it does not print "rejecting because the bundle identifier is null". The causal link is a
  perfect correlation across five clients, not an explicit refusal message. The spec text says so.
- **[A reader takes "no transport downgrade" as evidence the hosts behave alike]** → The requirement's new
  ground states the opposite in its first sentence: the hosts differ, and the requirement is a choice made
  in spite of that.
- **[The simulator becomes less useful and nobody notices]** → `add-simulator-rig-host`'s runbook
  (`.claude/skills/ios-simulator/SKILL.md`) already leads with "no downloads" and now carries the cause and
  six tested non-fixes, so an agent does not re-derive it.

## Migration Plan

None. No durable state, no wire format, no stored value, no compiled behaviour changes. The only
non-`openspec/` edit is a comment. Rollback is reverting the commit.

## Open Questions

None. The one that was open in `add-simulator-rig-host` — why the XPC connection is refused — is closed
above. What remains genuinely unknown is *why the simulator fails to give third-party processes a bundle
identity over XPC*, which is Apple's implementation and not actionable here; the observable consequence is
fully measured.
