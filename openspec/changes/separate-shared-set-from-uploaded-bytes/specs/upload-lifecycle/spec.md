## MODIFIED Requirements

### Requirement: The arm's direction gate lives at the choke point, never at the invoker

An upload arm's participation-direction gate SHALL live at the **choke point** — the one function every
trigger, on every tier, funnels through (`UploadCycle.run()`) — and SHALL NOT be placed at the arm's
**invoker**. No upload job SHALL be created for a membership whose direction excludes upload, at **any**
trigger and on **any** tier.

An invoker-gate is only as sound as its enumeration of invokers, and that enumeration is invalidated
silently by a new tier or a new trigger. This is not hypothetical. `changes/archive/2026-07-07-add-join-direction-mode`,
D3, gated the upload arm by not enabling the producer, reasoning: *"Under `DownloadOnly` the producer is never
enabled, so the OS never invokes the upload extension… No extension code changes."* That holds only where the
OS is the invoker. The app-driven tier (`changes/archive/2026-07-04-add-url-session-upload`) had merged
**three days earlier** and invokes its own cycle from the app process, so a download-only membership uploaded
the member's camera roll on every foreground — while the join gate had promised "you won't share yours". D4 of
the same document gated the **download** arm at its choke point, for reasons it stated explicitly, and is
correct. This requirement supersedes D3.

Placing the gate at the choke point is what makes the cheap mistake impossible. Adding a trigger is one line
in the untested composition root and looks obviously correct; bypassing the choke point means building a
parallel upload path, which nobody does by accident.

**The gate withholds job creation and the discovery walk.** It SHALL NOT withhold the device manifest
write. A membership that contributes nothing shares nothing, and the honest expression of that is an
**empty published manifest** (capability `device-manifest`), not a stale manifest left in place from when
the membership did contribute. Withholding the write was previously justified by three consequences; two of
them do not hold — the completion notify is already conditioned on at least one real completion, and an
empty manifest is already what a contributing membership with nothing in range publishes — and the third,
not silently blanking a previous manifest, is now the *intended* behaviour of a narrowing change
(capability `reconfigure-membership`).

**The gate bounds new work, not settlement.** A cycle the gate declines SHALL, before returning, run the
acknowledgement of terminal upload jobs the OS has **already presented** to this invocation. That pass
creates no upload job, enumerates no library, advances no discovery cursor and issues no network request —
so it takes nothing the gate exists to withhold — and it is the only way the platform's acknowledgement
obligation can be discharged on a tier whose extension is still registered.

**The re-join reconciliation SHALL likewise run ahead of the gate.** It establishes which of this device's
uploaded resources are already on the backend — a fact about bytes, which this system defines as
independent of the selection policy (capability `sync-ledger`) — so gating it on direction would make a
policy-independent fact wait on a policy-dependent branch. It is marker-gated and a no-op on a settled
join, so the cost is bounded to the first cycle after a join, switch, or reinstall. Running it early also
means a member who later re-enables their direction re-uploads nothing.

What SHALL remain behind the gate: upload job creation, the retry pass, and the discovery walk.

For a **contributing** membership the acknowledgement pass SHALL keep its existing position, after the
re-join reconciliation has settled. Its ledger writes record the membership this cycle runs under, and a
reconciliation that has not yet settled may still re-baseline (a switch's `resetTo`), so hoisting the pass
above the reconcile for every cycle would risk labelling rows against the wrong event. The declined cycle
has no such hazard: the drained jobs it settles were created under the same event by the same membership,
whose direction — not whose identity — changed.

Placing the acknowledgement behind the gate was justified by the premise that a non-contributing
membership's extension has been deregistered, so the OS presents nothing. That premise SHALL NOT be relied
upon: it holds only where a producer's `stop()` ran, and a membership reconfigured to exclude upload
deliberately does not stop its producer (capability `reconfigure-membership`, *A disabling change drains
in-flight uploads*). Measured on iOS 26.6: with the extension still registered and jobs outstanding, a
cycle that returned before the acknowledgement pass caused the system to report
`com.apple.photos.error Code=50008` ("appex failed to acknowledge jobs for processing state"), **discard**
the outstanding jobs, and record a failed attempt against the upload-job configuration that defers the
extension by ~300 seconds and escalates with the attempt count. A requirement whose safety rests on a
premise that is false on a shipped path is the failure this clause removes. Expiry: re-measure at the next
iOS major.

A cycle declined by the direction gate SHALL be reported at **routine** severity, not as a fault. A
download-only membership skipping its cycle is the designed outcome of a setting the member chose; the
project's reporting seam turns `Error`-severity log lines into crash-report events (capability
`crash-reporting`), so reporting a routine skip as an error manufactures a fault report on every trigger,
for every non-contributing member, indefinitely.

The gate SHALL be reachable by the tier-neutral tests: it lives in `:domain`'s `feature/upload` zone, not
in a composition root, which the project's hard rule declares wiring-only and untested. Root-placed
enforcement is how this capability's own history records the lifecycle shipping with no owner and no test.

#### Scenario: A download-only membership creates no upload job at any trigger
- **WHEN** a cycle is driven for a membership whose direction excludes upload — by foreground entry, a
  background task, a silent push, a producer start, or an upload completion
- **THEN** no upload job is created, for every one of those triggers

#### Scenario: A download-only membership publishes an empty manifest
- **WHEN** a cycle runs for a membership whose direction excludes upload
- **THEN** an empty device manifest is published for that event, replacing any manifest published while the
  membership did contribute

#### Scenario: The gate holds on a tier whose invoker is the app
- **WHEN** the tier in use invokes the upload cycle from the app process rather than being invoked by the OS
- **THEN** the download-only membership still creates no upload job — the gate does not depend on which
  component invokes the cycle

#### Scenario: Stopping the producer is not the gate
- **WHEN** the producer has been stopped for a download-only membership and a trigger subsequently drives a
  cycle
- **THEN** no upload job is created, because the gate is read at the choke point rather than inferred from
  the producer having been stopped

#### Scenario: A declined cycle still acknowledges the jobs the OS presented
- **WHEN** the OS invokes the cycle for a membership whose direction excludes upload, presenting terminal
  upload jobs created before the direction changed
- **THEN** every presented job is acknowledged and its outcome settled in the ledger, and the cycle still
  creates no upload job, performs no library enumeration and leaves the discovery cursor untouched

#### Scenario: A declined cycle still reconciles the re-join
- **WHEN** a cycle runs for a membership whose direction excludes upload on the first cycle after a re-join,
  switch, or reinstall
- **THEN** the re-join reconciliation runs and seeds the ledger, so re-enabling the direction later
  re-uploads nothing

#### Scenario: A declined cycle whose reconcile defers writes no manifest
- **WHEN** a cycle for a non-contributing membership runs while the re-join reconciliation defers
- **THEN** no manifest is written that cycle, so an unseeded ledger is never published as an empty one

#### Scenario: A declined cycle reports no fault
- **WHEN** a cycle is declined because the membership's direction excludes upload
- **THEN** the outcome is recorded at routine severity and produces no crash-report event, so a
  non-contributing member generates no fault report however many times a trigger fires
