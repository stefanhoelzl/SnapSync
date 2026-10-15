# Design

## Context

See `proposal.md` for the motivation. The state this design starts from, read at `69f35436b`:

- **The OS fact is fixed when the mock is built.** `MockDevice(osDrivenUpload)` builds
  `ExtensionRegistryMock(supported = osDrivenUpload)`, and `JvmMocks` never passes it.
  - "Supported" is encoded as whether the record cell is null, so it cannot change after construction.
  - The app reads `ports.extensionRegistry.supported` once per launch (`SnapSyncApp.kt`, `extensionRegistrableNow`),
    through a port that `JvmApp` builds anew at every launch.
  - The JVM host's `uploadTier` (`JvmRigHost.kt`) is a hard-coded `uploadersCarried(false)`. It is a reported build
    fact, not what composes the mechanism.
- **The grant is a constructor flag on the registry mock.** `ExtensionRegistryMock.partialGrant` is constructor-only
  and is never tied to `PhotoLibraryMock.grant`. `InMemoryExtensionRegistry.isEnabled` already ignores the grant, so a
  registration already reads as surviving a downgrade. Only the refusals depend on the grant.
- **What the device recordings prove.** `ExtensionRegistryContract`'s recordings cover:
  - the full-grant writes and read-backs (`GRANTED.rec`);
  - both refusals with `3311` under `.limited` (`LIMITED.rec`, SE2, iOS 26.6.2);
  - `Unsupported` below iOS 26.1.

  No recording asserts a read-back under `.limited`. The survival claim rests on the 2026-09-21 measurement in
  CLAUDE.md.
- **`host.relaunch(scene = false)` on the JVM host** is process death plus a cold launch with no delivery and no screen.
  Durable mocks are kept: pending wakes and the upload session's transfers.
  - The core is built on first use, so a launch that is delivered nothing runs no work.
  - No lever plays a force-quit.
- **The weak existing claim.** `HeartbeatCadenceIntegrationTest.a_full_grant_sharer_keeps_looking_during_the_event`
  claims "Uploads work below iOS 26.1" but waits only on a `WakeScheduled(BUSY)`. Its two sibling tests claim "No wake
  arrives".
- **Phase ⑩** (`photo-library-refusals`, in flight) edits `MockDevice` next to the library's construction. It renames
  neither `PhotoLibraryMock.grant` nor `library.permission`.

## Goals / Non-Goals

**Goals:**
- **Every state of the iOS 26.1 pairing is reachable through typed levers on the JVM host:**
  - registered or not;
  - full or limited access;
  - force-quit or running;
  - one uploader or both.
- **Every mock behaviour is backed by a recording or a cited measurement**, and the design says which.

**Non-Goals:**
- Android's registry, which stays `Unsupported` (⑧).
- The extension under a locked phone (⑦).
- The library's refusal levers (⑩).
- Device-host event streams (23).
- Backend content events. "Shared" is observed as the photo leaving the device, plus the event's union where a scenario
  says "once".
- Any change to production code. A defect a new test finds is fixed here and recorded below as a decision.

## Decisions

### D1. The OS fact is `extension-registry.carried(on)`: durable, applied at the next launch, off by default

- **What changes in the mock:**
  - `ExtensionRegistryMock` gains a mutable `carried` flag.
  - The record cell always exists, so a registration survives the fact being toggled off and on.
  - `port()` reads `carried` when it is built. A port is built per launch, so a change takes effect at the next
    `host.relaunch`, exactly as a phone reads the fact at launch. The running launch keeps the answer it composed with.
- **Durability:** `MockState` persists the fact beside `REGISTERED`, so a launch-time-adapter host that mocks the
  registry keeps it too.
- **The reported upload tier** reads the same fact at each launch.
- **Why this face:** the fact is the registry's `supported`, so the lever sits beside `registered()`. The face already
  requires `EXTENSION_REGISTRY_MOCKED`, so a host whose registry is real refuses the lever with no new rule.
- **Rejected:**
  - `os.carriesUploader`, which splits one mock's state across two faces and needs the same requirement added.
  - A host-start option, which breaks the convention that an OS fact is a durable fact applied at relaunch.
  - Flipping the default, which would silently change every existing test's OS.

### D2. The registry reads the library's current grant

- `MockDevice` hands the registry the library's grant (`library.grant`), replacing `partialGrant`.
- **The rule:**
  - Under `LIMITED`, `setEnabled` answers `3311` in both directions and leaves the record untouched.
  - Under `LIMITED`, `isEnabled` answers `NOT_REGISTERED` whatever the record holds. A partial grant cannot see the
    record, as measured (D9).
  - The operator's `registered()` is the OS's record itself, whatever the grant.
- **Evidence:**
  - The refusals are `ENABLE_`/`DISABLE_IS_REFUSED_UNDER_A_PARTIAL_GRANT`, recorded on iOS 26.6 and again on 27.0.1.
  - The read is the clause in D3.
  - Survival is the measurement in D9.

### D3. One new clause: a partial grant reads no record, even the one a full grant made

- **The clause:** `ExtensionRegistryContract` gains `A_PARTIAL_GRANT_READS_NO_RECORD`, under a new state
  `RECORD_PRESENT_UNDER_PARTIAL_GRANT`. It covers `isEnabled → NOT_REGISTERED`.
  - As first proposed, the clause asserted `REGISTERED` (`A_REGISTRATION_SURVIVES_A_PARTIAL_GRANT`). The SE2 failed it
    (D9), so it states what iOS answers.
- **Entering the state on the SE2:**
  1. The app joins an event of our own under a full grant, which registers the extension.
  2. A person narrows access to limited in Settings.
  3. The binding runs under limited access.

  A full-grant contract run cannot set the state up: its last clause leaves no record.
- **The `LIMITED` recording is re-recorded** with the clause in it. This takes the SE2 lock (`snapsync-device` /
  `device` skills), which the user authorised for this phase.
- **The mock and the simulator stand-in bind the same clause**, so the coverage gates hold every substitute to the
  recorded answer.
- **Rejected:** landing the clause unrecorded. The coverage gates would count it only on the mock, which proves nothing
  about iOS.

### D4. Evidence that no contract can hold is cited, not recorded

- **iOS invoking its uploader under limited access** (`process()` 4 s after a photo joined the selection, SE2/26.6,
  2026-09-21):
  - The subject is iOS delivering a call into the app. A contract records the app's calls into the OS and their
    answers, not the OS's decision to call in.
  - The JVM host already lets the operator play that invocation (`extension-host.process()`) under any grant. The
    extension's own gate, `extensionAdmission`, which withholds without `GRANTED`, is what the narrowing test exercises.
  - Cited in the KDoc of the tests that rely on it, with CLAUDE.md's ⏰ re-measure note.
- **What a force-quit does on iOS:**
  - **No background launch, silent push or session event until the next opening.** This is Apple's documented
    behaviour, cited in `changes/archive/2026-07-04-add-url-session-upload/design.md` and
    `changes/archive/2026-07-05-push-notification-infra/design.md`. It is platform documentation, not a measurement of
    ours, and the lever's KDoc says so.
  - **The app's background-session transfers are cancelled, and their ends arrive late.** iOS reports each one as
    `NSURLErrorCancelled` (`-999`) when the app next brings its session up, which the app records as failed and
    uploads again. This was measured on the SE2 (`changes/archive/2026-09-16-scope-stranded-repair/design.md`,
    "Force-quit mid-transfer"), so it outranks the documentation reading this design first took (see D8).

### D5. `played-os.forceQuit()` is its own act: process death, cancelled transfers, and a hold until the opening

- **What it does, in order:**
  1. Takes the upload session's live transfers out of flight. Their ends are kept back until the opening, when the
     session is brought up again and each is reported cancelled (D4). A force-quit's cancellation is not the app
     cancelling: the dead process is never told.
  2. Relaunches the app process with no scene and no delivery, reusing `relaunch(scene = false)`, whose launch runs no
     work until something is delivered.
  3. Sets a hold.
- **While held:**
  - Every delivery that would launch the app is refused with a reason (`LeverRefused` / `409`), naming the force-quit:
    `os.backgroundTask`, `os.silentPush`, `os.backgroundTransfers`, and the app's wakes as the played OS fires them.
  - `extension-host` keeps answering.
  - Pending wake requests are kept, not cleared. iOS keeps the request and does not honour it.
- **The opening:** `os.foreground()` ends the hold, as a cold foreground launch, and so does a link the member opens.
  On the JVM host the opening builds the screen, as `relaunch(scene = true)` does, and the session then reports the
  cancelled transfers' ends.
- **Observability:** the world stream gets `WorldEvent.ForceQuit`. The opening already shows through the foreground
  delivery's own events.
- **Hosts:** `played-os` requires `BACKGROUND_TIME_MOCKED`, and the act needs the host to own the app's process, as
  `host.relaunch` does. A device host refuses it, as it refuses `relaunch`.
- **Rejected:** `host.relaunch(forceQuit = true)`. The hold gates the OS's deliveries, so it belongs to the played OS,
  and a host verb would carry OS behaviour.

### D6. What each test waits on

| Scenario (capability) | Test shape |
|---|---|
| Uploads work below iOS 26.1 (`delivery`) | Fact off, full-grant sharer, app in the background, a photo joins the library. Waits for `SessionTransferCreated` for it; `never` `UploadJobCreated` or `ExtensionRegistered(true)`. |
| iOS's uploader follows a switch (`delivery`) | Fact on, relaunch, join A: `ExtensionRegistered(true)`. Switch to B, take a photo, `extension-host.process()`. `UploadJobCreated` for B; `never` for A. |
| Reopening after a force-quit resumes uploading (`delivery`) | Fact off, joined sharer with P0's transfer in flight. `forceQuit`, then P1 taken: `never` `SessionTransferCreated`. Then `os.foreground()`: `SessionTransferCreated` for P0 (its cancelled transfer) and for P1. Then background, P2, a heartbeat: `SessionTransferCreated` for P2. |
| iOS's uploader keeps sharing after a force-quit (`delivery`, new) | Fact on, registered, `forceQuit`, photo taken, `extension-host.process()`: `UploadJobCreated` for it, with the app still held. |
| A photo both uploaders pick up arrives once (`delivery`, new) | Fact on, registered, a photo. The app's pass and `extension-host.process()` both run: at least one of `SessionTransferCreated` / `UploadJobCreated` for it; once settled, `public-backend.union()` lists it once. |
| Narrowing access after joining keeps unselected photos private (`photo-access`, new) | Fact on, full grant, join: `ExtensionRegistered(true)`. `library.permission(LIMITED)`; the registration is unchanged (`never` `ExtensionRegistered(false)`). A photo outside the selection, `extension-host.process()`, a heartbeat: `never` a transfer or job for it. |

- **Waiting:** all of these are `behaviour { }` tests on mocked time; they wait on events, never the wall clock.
- **The re-pointed claim:** `HeartbeatCadenceIntegrationTest`'s first test is re-pointed to "No wake arrives", which is
  what it shows.

### D7. Coverage

- **Modules this moves:**
  - `:domain:feature`: `UploadTransitions`' registered branches, `OsDrivenRegistration`, and `ExtensionRegistration`'s
    switch-time re-registration;
  - `:domain:compose`: the extension composition's admitted path;
  - `:domain:services`: `OsQueueTransfer`.
- **The ratchet:** after the tests land, each module's integration exception in the root build file's `coverage { }`
  is raised to what it now measures, or deleted if the module reaches the target. This uses ②'s rule, and floors only
  rise.

### D8. Correction found in apply: a force-quit's cancelled transfers are reported at the next opening

- **What happened:** as first built (D5 step 1, "no end reported to any process"), the force-quit test found that
  opening the app uploaded the photo taken meanwhile but never the one whose transfer the force-quit cancelled. Its
  ledger row stayed in flight with no transfer behind it.
- **Why the mock was wrong:** `2026-09-16-scope-stranded-repair` measured a force-quit mid-transfer on the SE2. iOS
  delivered both cancellations as `-999` at relaunch, when the session was re-adopted, and the app recorded them failed
  and uploaded them again. `2026-09-22-both-uploaders-active` relies on the same measurement.
- **The fix:** `UploadSessionMock` keeps a force-quit's cancelled transfers and reports them cancelled at the opening.
  Production code was right, and nothing in it changes.
- **Checks:** with the corrected mock, the test passes. Replacing the force-quit with a plain relaunch fails it (the
  transfer is still live, so it is never sent again), and so does letting the extension admit limited access in the
  narrowing test. Both mutations were run and reverted.

### D9. Measured on the SE2 (iOS 27.0.1, 2026-10-15): a registration survives the narrowing, unseen

- **The procedure:**
  1. With full access, the app joined an event of our own: "extension enable succeeded".
  2. Access was narrowed to limited in Settings and the app relaunched.
  3. iOS read the registration as absent (`isUploadJobExtensionEnabled() -> false`) and refused both writes with
     `3311`.
  4. Access was widened again and the app relaunched.
  5. iOS read the registration as present (`enabled: true`). The app's log shows no write between the narrowing and
     that read.
- **What it shows:** the record survived the narrowing, and only the read is grant-dependent. On 26.6 the read was
  already known to collapse under `NOT_DETERMINED` (`OsExtensionView`).
- **Consequences:**
  - The clause states the read (D3), and the mock and the simulator stand-in answer it.
  - The production rule that trusts the read only under a full grant (`UploadTransitions.compare`) is what this
    requires, and it stays as it is.
  - The scenario this phase claims, that narrowing keeps unselected photos private, holds either way. iOS's uploader
    is still invoked from the surviving record (measured on 26.6, not re-measured on 27), and its own entry gate
    withholds.
- **Re-measured at the same time, on the new major:**
  - The full-grant recording is identical to 26.6's.
  - Both refusals are unchanged.

## Risks / Trade-offs

- **The SE2 recording needs a hand-made downgrade in Settings.** The recording can't script it. → The binding refuses
  (`NotRunHere`) unless it finds a record and a `.limited` grant, so a mistaken run records nothing false. The
  procedure goes in the clause's KDoc.
- **Re-recording `LIMITED.rec` on a newer iOS** could change the two refusal clauses' recorded answers. → The answers
  are diffed before commit. A changed answer is a finding to raise with the user, not something to absorb.
- **Mock wiring conflicts.** D2 and ⑩ both edit `MockDevice`. → The conflict is textual (adjacent lines); whichever
  lands second rebases.
- **The overlap test's "once" leans on the event's union.** It checks that the union lists the photo once. It does not
  check that a member's library holds it once. That is `receiving-photos`' concern and out of this phase's scope.
- **The hold must cover every app delivery.** One the hold misses would let a test pass while iOS would not have
  launched the app. → The hold is implemented where `MockEntryDriver` routes app deliveries, not per verb. A test
  asserts each app-launching verb is refused while held.
