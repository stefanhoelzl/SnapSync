## Context

`:test:rig` (decision record: `changes/archive/2026-08-09-add-rig-control-channel`) runs on one host, the
physical SE2. That design's Non-Goals named simulator support explicitly — *"The fixed default port is
device-only by construction"* — and listed the simulator host among the things it must not foreclose.
This change is that follow-on.

### What a simulator buys, precisely

Three properties the device cannot have: **two members of one event at once** (every multi-member
behaviour is otherwise exercised only in `:test:world` against fakes), a **headlessly wipeable and
seedable photo library** (`SNAPSYNC_WIPE_GALLERY` needs a physical tap on the platform's delete
confirmation), and **headless permission state**. Plus disposability and parallelism.

It is not a device replacement. `PermissionStatus.LIMITED` is not grantable (`simctl privacy` has no
`photos-limited`, though `contacts-limited` exists), there is no APNs token (`no valid "aps-environment"
entitlement string found`), and the OS-driven PhotoKit tier does not run there at all.

### Measured facts

All 2026-08-09 unless stated, on macOS 26.5.2 / Xcode 26.6 / iOS 26.5 simulator, headless on a GitHub
`macos-26` runner.

**Signing — the blocker.**

- An **unsigned** simulator build has no App-Group container: `IllegalStateException: App Group container
  'group.app.snapsync' unavailable — the application-groups entitlement is missing or unprovisioned`,
  thrown from `appGroupContainerPath` and caught by the app-scope error boundary. This is why
  `screenshots.yml` builds `CODE_SIGNING_ALLOWED=NO` and forge boots no live stack.
- An **ad-hoc signature carrying an app-group-only entitlements plist fixes it**
  (`codesign -f -s - --entitlements <plist> …`, then reinstall). The app launches normally.
- The **Keychain group cannot come along**. `keychain-access-groups` makes the app un-launchable
  ("The request was denied by service delegate (SBMainWorkspace)") in the **unprefixed** form; the
  correctly **prefixed** `E9Z8BADH58.app.snapsync.shared` was refused on an ad-hoc signature, and refused
  again when signed with the real "Apple Development" identity plus the full repo entitlements expanded.
- Omitting it yields `OSStatus -34018` (`errSecMissingEntitlement`). Non-fatal for attestation:
  `KeychainAttestStore` throws it, the run survives, App Attest is unavailable on a simulator anyway, and
  the local backend rig fills the absent token so a join still reaches `201`.

**Backend.** Plain HTTP to `127.0.0.1` is **not** blocked by ATS from a real app bundle — ATS exempts
loopback, so no `NSAllowsLocalNetworking` exception is needed. Measured end to end: a Debug simulator
build with `BACKGROUND_UPLOAD_URL_BASE=http://127.0.0.1:8080/api/v1`, launched with
`SIMCTL_CHILD_SNAPSYNC_CREATE_EVENT=<payload>`, produced
`POST http://127.0.0.1:8080/api/v1/events -> 201 (2359ms, req=54, resp=223)` in `debug.log`, the event
landing in `api/.localstore`. The same run proved the null-token path.

**`simctl` traps.**

- `simctl launch <dev> <bundle> KEY=VAL` passes **argv, not environment**; `SIMCTL_CHILD_<VAR>` is
  required (`screenshots.yml:177` already does this correctly).
- `simctl privacy <dev> grant photos app.snapsync` **does not grant anything PhotoKit reads** — see the
  Non-Goals for the 2026-08-25 follow-up, which found the tool at fault rather than the platform
  (`applesimutils` works). What was observed here in August was the consequence: the app, still seeing
  `notDetermined`, raised the system alert, which then sat **modally** and blocked every subsequent
  launch at `MainViewController(mode=deferred)` — five consecutive launches reading exactly like "the app
  hangs on boot". The shutdown/boot dance cleared the pending alert; it never granted access.
- `SNAPSYNC_SEED_POLICY` is unusable there: it logs "seeding N POLICY-PROBE asset(s)" and never
  completes, because the app suspends ~4 s after launch and the async `PHPhotoLibrary.performChanges`
  never finishes. Substitute: `xcrun simctl addmedia` with pre-generated 2400×2000 JPEGs (4.8 MP, clearing
  the 3 MP floor), which land dated ~now.
- **Nothing triggers an upload cycle on a headless simulator.** A fully live, joined, permission-granted
  simulator with the app-driven tier armed and three 4.8 MP photos produced no cycle — not one
  `enumeration` line — including across a genuine background→foreground transition with the membership
  already loaded. This is what makes the channel's `/trigger` load-bearing rather than convenient.

### Constraints that bound where code may live

`KeychainContainmentTest` scans the **whole project** minus `build/`, so no `SecItem*` call may appear
outside `:adapter:ios:ext-safe` — including in `:test:rig` and its contributed hook. `detektAppShell`
forbids conditionals in `app/ios/src` at `CyclomaticComplexMethod` threshold 2, and the rig's hook
directory is inside its scanned roots. The rig hook can read `SnapSyncRoot.app` and `.mode` (widened
`private` → `internal` by the control-channel change) but has **no route into** the `by lazy` where ports
are constructed.

## Goals / Non-Goals

**Goals:**

- Run the app, with its live stack, on an iOS simulator, reachable through the existing control channel.
- Resolve a device identity on a host where the addressed Keychain group cannot exist, without weakening
  the protections that stop a locked device from acquiring a new identity.
- Make two instances independently addressable, so #7 is a scripting problem rather than a design one.
- Settle the two measurements `delete-simulator-session-downgrade` named this work as the place for.
- Add no `SNAPSYNC_*` literal to production Kotlin.

**Non-Goals:**

- **Uploads on a simulator.** The OS-driven tier does not run there and the app-driven tier is not forced
  (D10); #5's extension-shaped second process is where simulator uploads arrive.
- **Scenarios, a scenario vocabulary, or Gherkin.** #6.
- **A two-member *scenario*.** #7. This change proves only that two instances are independently
  addressable and identified.
- **`PermissionStatus.LIMITED` on a simulator.** Not known to be grantable headlessly. `simctl privacy`
  has no `photos-limited` (though `contacts-limited` exists); whether `applesimutils` can set it is
  **untested** and now worth testing, since that tool turned out to grant FULL access where `simctl`
  could not (below).

  **Photo permission IS grantable headlessly — with the right tool.** `xcrun simctl privacy grant photos`
  does **not** work for PhotoKit: measured 2026-08-25 on iOS 26.2, it writes the TCC row
  (`kTCCServicePhotos|app.snapsync|2|4`) and `PHPhotoLibrary.authorizationStatus(for: .readWrite)` still
  reports `notDetermined`. Nor does a direct `sqlite3` write of `auth_value=2, auth_reason=2,
  auth_version=1` performed while the device is **shut down**; `grant all` plus a restart reports
  **`DENIED`**. TCC is not honoured at *request* time either — calling the real request through a
  temporarily-wired channel command raised the system alert on screen ("SnapSync would like full access
  to your Photo Library"), captured in a screenshot.

  **`applesimutils --byId <udid> --bundle app.snapsync --setPermissions "photos=YES"` works**, and the
  app then reads `GRANTED` on the next launch. With it, the join gate clears and a simulator reaches
  `configResolved: true` — verified end to end. It is a Homebrew formula (`wix/brew/applesimutils`), so
  it is a host-side tool, not a change to the app.

  An earlier revision of this record concluded that full access was ungrantable and that 6.1/6.2 were
  therefore blocked. That was wrong, and wrong in the expensive direction: it inferred a platform
  limitation from three failures of **one** tool. The failures were real and are kept above, because
  `simctl privacy` is the obvious thing to reach for and its silence is the trap.

- **A CI workflow.** D1.
- **Exercising the shipped identity path.** The simulator binds a different `SecureStore`
  implementation (D6), so identity there is a **precondition**, not something the host validates. A
  regression in the Keychain binding is invisible on a simulator, exactly as `LIMITED`, APNs and the
  OS-driven tier are — named here so no later change writes a scenario assuming otherwise.
- **The `SNAPSYNC_FORCE_URLSESSION_UPLOAD` replacement**, owned by the `os-producer-deregistration` change.
- **The `PHPhotosErrorAccessUserDenied` (3311) crash-report noise**, owned by the
  `limited-grant-registration-noise` change. Measured by the `os-producer-deregistration` session: iOS
  refuses the upload-job deregistration under a partial grant, so without the expected-outcome carve-out
  `3201` has, every user switching to Limited Access raises a crash-reporting event. It lives in
  `PhotoKitUploadProducer`'s classifier and is named here only as the cross-reference.

## Decisions

### D1 — The loop is an `ssh-mac` session, not a workflow

Every simulator run happens on a `macos-26` runner reached through the existing ssh-mac loop. No new
workflow is added.

The work here is exploratory — four measurements, each a probe-and-look rather than an assertion — and a
dispatch-only workflow pays a cold ~11–19 minute run per iteration (the `screenshots.yml` cost). The
scripted sequence such a workflow would run is #6's, and it does not exist yet, so a workflow now would
ship a harness with nothing to put in it.

*Rejected:* a `simulator.yml` beside `screenshots.yml`. Reproducible and citable by run id, but pays that
cost on every iteration and freezes a sequence one session old. Named as #6's to add if it wants one.

### D2 — The same `iosApp` scheme plus a post-step; nothing enters `project.pbxproj`

`-scheme iosApp -configuration Debug -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO`, plus the
`gradle.properties` rig append, the `BACKGROUND_UPLOAD_URL_BASE` override, and an ad-hoc `codesign`
afterwards. Every difference is command-line expressible, and `screenshots.yml` already proves this build
works for the simulator SDK. The extension stays in the closure, which #5 wants.

*Rejected:* a dedicated scheme or build configuration. Both are `project.pbxproj`/`.xcscheme` edits — a
file Xcode rewrites and no guard holds — and neither can carry the Gradle property or the signing step, so
the post-step survives either way. A second target is arriving there from the `triggers-into-channel`
change (forge becoming its own target); that change has been asked to confirm it does not alter what
`-scheme iosApp` produces for the simulator SDK, since `screenshots.yml` builds it.

### D3 — A committed `simulator.entitlements`, pinned, and both bundles signed inner-to-outer

The simulator-viable content of the two real entitlements files is identical once the unsupported keys are
stripped: `com.apple.security.application-groups: [group.app.snapsync]`. One plist therefore serves the app
and the appex, and it is **committed** at `iosApp/Configuration/simulator.entitlements` rather than derived
at signing time.

Committing it adds a third occurrence of a pinned runtime-identity literal, so
`RuntimeIdentityTest`'s inventory extends to cover it — a spec delta, because that inventory says outright
that it is the contract of record. The guard also asserts the plist declares **no** `keychain-access-groups`,
so that "add the missing key" cannot be applied as a fix to the un-launchability it would cause.

**Measured 2026-08-25, and it generalises the rule.** An unprovisioned `associated-domains` on an ad-hoc
simulator signature produces the *same* `SBMainWorkspace` launch refusal as `keychain-access-groups`. So
the plist's constraint is not "no keychain group" but **no entitlement a simulator cannot provision**, and
the guard's message should be read that way. It also closes Open Question 6.4 in the unhopeful direction:
a simulator cannot carry the entitlement, so `simctl openurl` cannot route a universal link into the app,
and SNAPSYNC-3 gains no repro path here.

Signing is **inner-to-outer**: the appex with the shared plist, then the `.app`. `--deep` is dropped.
**A first implementation looked only in `PlugIns/` and silently signed nothing but the app** — iOS 26
embeds the appex in `Extensions/`. The script now checks both and *counts* what it signed, because "signed
zero nested bundles" is a bug in this project and would otherwise be indistinguishable from success. It is
documented as "for emergency repairs only", and whether the embedded appex inherited the app-group
entitlement under it was never measured — only the app's own container was. Two lines remove an assumption
#5 would otherwise inherit as a surprise.

*Rejected:* deriving the plist at signing time from the appex's entitlements minus `keychain-access-groups`.
It needs no committed duplicate and follows a rename automatically. Rejected in favour of a surface a
reader can diff and a guard can hold: a derivation is invisible in review, and the failure it prevents is
one nothing else catches.

### D4 — The backend runs in the sandbox, reverse-forwarded

`deno task dev:local` runs locally, with `-R 8080:127.0.0.1:8080` on the ssh invocation, so the Mac's
loopback `8080` tunnels back. The simulator shares the host's loopback, so it reaches
`http://127.0.0.1:8080/api/v1` — a **stable** host, so the build is never rebuilt per session (the reason a
cloudflared quick tunnel, whose hostname is random per session, is not used).

`.localstore` stays where the agent can grep it directly, `api/` edits take effect with no rsync, and no
`deno` install is needed on the runner. Cost: every backend call pays the tunnel round trip, and an SSH
drop reads as `connection refused` — a backend crash — rather than a lost tunnel. The skill states that.

The host reaches the build as an `xcodebuild` command-line override; nothing is committed.
`Config.xcconfig`'s "Must be HTTPS: default ATS (HTTPS-only) applies" comment is corrected, because it is
incomplete in a way that would forbid this: ATS exempts loopback, which is why the measured run needed no
exception.

*Rejected:* `deno` on the runner. Lowest latency and the rsync already carries `api/`. Rejected because the
oracle (`find api/.localstore -type f`) then lives behind ssh, and this change's own debugging is
backend-adjacent. Recorded in the skill as the variant to switch to when latency dominates.

**Reversed in practice, 2026-08-25.** The reverse-forward works — measured, the Mac's loopback reached the
sandbox's rig and answered — but it does not *survive*. The cloudflared quick tunnel dropped repeatedly
during one session (four reconnect attempts to get a shell back), and each drop takes the forward with it.
The app then reports `Could not connect to the server` on `lo0`, which is indistinguishable from a dead
backend: the risk this design already names, met immediately. The session was completed with `deno`
installed on the runner (`deno.land/install.sh`, ~seconds) serving a genuinely local `127.0.0.1:8080`,
which removes the tunnel from the data path entirely. **The fallback is now the recommendation**, and the
reverse-forward is the variant — the skill says so in that order, because sustained work (a download that
must stay up, a relaunch measurement) cannot rest on a quick tunnel.

*Ruled out entirely:* the deployed backend. App Attest does not exist on a simulator, so there is no token,
and only `api/src/dev` fills an absent one — production's gate would refuse every join.

### D5 — The presigned-download scheme becomes configuration, as the host already is

Presigned **download** URLs are minted as `https://…` unconditionally, because the production URL shape is
fixed; the local-backend skill tells the operator to `sed` the scheme by hand. An app cannot `sed`. Left
alone, every simulator download fails on TLS and "downloads are inert on a simulator" reads as confirmed
when nothing about background sessions was tested. This is a gate item, not a convenience: it is a
precondition of both the downloads measurement and of #7, where one member uploads and the other downloads.

**Corrected during implementation.** This decision originally said `serve.ts` mints those URLs and that
`fs-storage`'s guard prefix would move with it. Both were wrong, and the difference matters:

- Minting is `presignDownloadUrl` in `api/src/app.ts` — **shipped** code, not `src/dev/`.
- `fs-storage`'s guard is over `config.host` (bunny's native Storage API, server→zone, always HTTPS), not
  `config.s3Host` (presigned downloads, device→origin). They are different constants and only the latter
  moves, so the guard needs **no** change. Verified rather than assumed.

So the scheme joins `s3Host` as a `Config` field: `https` in every deployed configuration, overridden by
`devConfig` alone — the identical shape the rig already uses to point `s3Host` at itself, which needed no
spec delta either. `fs-storage.ts`'s own header argues against rewriting shipped storage code for a
dev-only need; a field the dev entry sets is the smallest form that does not.

A field only helps if it is read, and every existing fixture says `https`, so a regression to a hardcoded
scheme would pass all of them. One test in `app.test.ts` asserts an `http` config yields an `http` URL,
and it was confirmed to fail when the hardcoding is restored.

### D6 — The identity store is bound per compilation target, not chosen at runtime

`KeychainDeviceIdentity` addresses `E9Z8BADH58.app.snapsync.shared` by name, so on a simulator its read
returns `Unavailable(-34018)` — and `resolveOrMint` treats unavailability as disqualifying on both reads
and throws, which is the rule protecting a locked device. The app therefore resolves no id and joins
nothing.

The identity store is already a port (`SecureStore`); what was hardcoded is the **binding**, constructed
directly in `SnapSyncRoot`. This change makes the binding target-specific:

```
  iosMain                    expect fun deviceIdentityStore(): SecureStore
     ├── iosArm64Main            actual → the addressed-Keychain store, unchanged
     └── iosSimulatorArm64Main   actual → an App-Group-file store
```

`:adapter:ios:ext-safe` already declares both targets, and `test/world/src/iosSimulatorArm64Main` is
existing precedent for a target-specific main source set. The device binary contains **no route** to the
simulator binding: `iosSimulatorArm64` is not a runtime guess about the host but a compilation target
whose output only ever runs on a simulator, and the measured un-launchability of
`keychain-access-groups` there makes the implication sound in the only direction it is used.

The simulator store mints, persists and reads back normally, so `resolveOrMint` never reaches its
`Unavailable` branch there and the normative resolution order is satisfied rather than amended. Two
simulators are separate devices with separate containers, so they acquire distinct ids with no operator
input, and an id survives relaunch — which the OS-relaunch measurement (D10) requires.

Because the App-Group container is shared between the app and the appex by construction, #5's
extension-shaped second process obtains the same id by linking the same target's binding. No second
mechanism, and no cross-process agreement to arrange.

This is the containment law applied where it belongs — *"contained by compilation, not by a runtime
check"*. It is **not** the build-time host seam that `delete-simulator-session-downgrade` rejected: that
change rejected relocating a claim whose measurement had shown **no axis existed**. Here the axis is
real and measured — the addressed group cannot exist on a simulator — so there is something to select
between rather than two identical answers.

*Rejected — letting the simulator mint through the existing binding.* To do that, production must
distinguish "unavailable because unentitled" from "unavailable because locked". Asking the host is
`OsFacts`, deleted one change ago; classifying the error code reopens what `reshape-keychain-port` D3
closed deliberately (*"a code invites a `when` that would re-import [the platform's error numbering]"*).
On a locked device the wrong answer mints a second identity and orphans that device's partition and
ledger.

*Rejected — a rig-planted identity file that production reads.* Carried through most of this change's
design, and **subsequently merged to `main` by the `triggers-into-channel` change** while this one was
being built: `SuppliedDeviceIdentity` (the read), `IdentityPlant` + `POST /device/identity` (the write),
and a `device-identity` requirement describing it. This change **removes all three** — the two solve one
problem, and shipping both would give the simulator two identity mechanisms. The removal is a
`REMOVED Requirements` delta rather than a silent deletion, and it records what the two designs agree on
(the whole diagnosis) as carefully as where they differ (the remedy), because the agreement is the part a
later reader is most likely to need. Production would gain a fallback consulted on `Absent` or `Unavailable`, adopted verbatim, never
persisted, with **presence** as the discriminator since no production build writes it. It works, and it
was rejected for three reasons that only became clear once the target axis was noticed. It puts a
read path in shipped code whose only writer is test equipment. It is one implementation with **two
runtime behaviours**, forking on a condition invisible to the type system — and on a device it can take
the wrong branch, because the App-Group container survives an application **update** (measured on device,
iOS 26.6, 2026-08-25: a joined event's `eventconfig.json` survived installing the same IPA over it), so a
plant left by an earlier rig build is readable by a later non-rig one, and the fallback fires on
`Unavailable`, which on a device means *locked*. Guarding that needs a staleness rule keyed to the
planting process — and the ideal token for it, a process start instant, is not reachable from a declared
API (`klib dump-metadata`, Kotlin 2.4.0, `ios_arm64`: `platform.posix` declares no `sysctl`,
`proc_pidinfo` or `kinfo_proc`; `NSProcessInfo` offers `processIdentifier` and a boot-relative
`systemUptime`, and `globallyUniqueString` returns a new value per call). Target selection removes the
fork, and with it the hazard, the rule, and the residual identifier-reuse hole the rule could only bound
rather than close.

*Rejected — a channel verb setting the id.* An OS-initiated cold relaunch calls straight into an entry
point, so nothing can POST first — and the OS-relaunch measurement is precisely that path. Moot under
D6 anyway: the simulator store is self-sufficient. A verb to *pin* a chosen id remains available to #7
as determinism, depended on by nothing.

*Rejected — `SNAPSYNC_DEVICE_ID` as a launch variable* (the shape `add-rig-control-channel` D15 deferred
here). It could never have worked: D15 specified "fills an absence", and `-34018` is a **read error**,
not `errSecItemNotFound`, so there is no absence to fill — and every branch establishing a placement
calls `store.write()`, which fails there too. Independently, `triggers-into-channel` adds a guard
asserting **zero** `SNAPSYNC_*` literals in production Kotlin.

### D7 — No `device-identity` delta, and why

`device-identity`'s requirements describe behaviour over the `SecureStore` port: mint once, persist,
return verbatim, never mint on a read error, and let both processes observe one value. The simulator
binding satisfies every one of them. What differs is *which implementation of the port* a test-only
compilation target links — and substituting a port implementation is the repo's normal test seam, which
no spec records: `:test:world` substitutes every port, and the desktop harnesses run real screens over
forged sources without amending a single product spec.

The argument is stated rather than assumed because there is a reading that cuts the other way: the
spec's Purpose says the id is "persisted in the shared Keychain access group", and for an
`iosSimulatorArm64` binary that sentence is false. The counter is that specs describe the product, and a
binary that cannot run on a device is test equipment — but a reviewer who weighs those differently should
be able to see the choice, not discover it. If that reading wins, the delta is a Purpose note, not a
requirement change: no behaviour moves.

### D8 — The tier override's mechanism is not shared

An earlier draft had this change owning a planted-facts file that the `os-producer-deregistration`
change's upload-mechanism override would also read — "one mechanism, two facts". D6 removes this
change's fact from it. That override still needs a durable, before-first-resolution source, because it
is wanted on **devices**, where a compile-time simulator swap does not apply; owning it moves wholly to
that change.

The collapse is a simplification there rather than a loss. With identity gone, the mechanism has one
consumer whose failure mode is degraded-but-working scheduling on a single developer's phone, so its
staleness rule and honour-logging can be sized to that consequence instead of to an orphaned ledger.
The shared honour predicate that change extracted (`plantedByThisProcess`) has no second consumer here.

### D9 — The device default port stays; the bound port is published

`18099` remains the device default and the device path is untouched. The rig additionally writes its
**actually bound** port into its own container, which on a simulator the host reads with
`xcrun simctl get_app_container`.

All simulators share the host's loopback, so two instances defaulting to `18099` is not merely a collision:
the second's bind fails while `curl` reaches the **first** and answers plausibly, reporting the port that was
asked for. Publishing makes that case loud — the second instance's file never appears — rather than wrong.

*Rejected:* explicit ports only, with no rig change. The host already knows the port it assigned, so nothing
needs discovering; a scripted run never forgets the override. Rejected because an interactive session does,
and the failure is a confident wrong answer.

*Rejected:* OS-assigned ports everywhere (`:test:harness-driver`'s shape). One rule, nothing to collide, but
the device loop loses its fixed port and gains an `apps pull` before every session — the step the channel
exists to remove.

### D10 — The relaunch measurement is taken from a download, not an upload

The open question is whether the OS relaunches a **terminated** app to deliver
`handleEventsForBackgroundURLSession` — the property `22f782bd` and `06561b65` live on, neither of which
could ship with a regression test.

`handleBackgroundUrlSession` routes by **session identifier**, not by tier, and `IosDownloadTransport` runs
its own background `URLSession` on any resolved tier. So an in-flight download across a terminate exercises
the same OS behaviour and the same routing code — and the same download answers the separate
downloads-inert question, making one setup serve both.

Taking it upload-side would first require forcing the app-driven tier, and
`SNAPSYNC_FORCE_URLSESSION_UPLOAD` is deleted by `triggers-into-channel` with its replacement owned by
`os-producer-deregistration` — sequencing this change behind two others for a property that does not need
either. The upload-side confirmation arrives from that change's own device verification once the plant
lands.

**Measured, 2026-08-25 (iOS 26.2 simulator, real signed app bundle).** The relaunch question is **not
answerable on this host**, and the reason is 6.2's answer: no background transfer can be kept in flight,
because every one fails instantly. The attempt was made anyway — downloads kicked, app terminated two
seconds later, 90 s of polling — and the app never relaunched and `handleBackgroundUrlSession` never
fired. That negative is **doubly weak** and must not be read as "the OS does not relaunch a terminated
app": under this repo's own reading rule a negative is suggestive at best, and here the *precondition*
was never established — there were no pending background events to relaunch for.

**And the vendor says the same, which is what actually settles it.** Quinn's pinned *Testing Background
Session Code* states **"Simulator may not accurately simulate app suspend and resume"** (r. 16532261) and
recommends **"Test on a real device, not in Simulator"**. Suspend/resume is precisely the mechanism this
measurement depends on, so the property is **device-only by vendor guidance**, not merely unmeasured by
us. It stays open, and #5–#7 must not build a scenario on it.

**Noted, not owned:** the two background sessions are configured asymmetrically. `IosDownloadTransport`
sets `discretionary = false` and `sessionSendsLaunchEvents = true` explicitly; `IosUrlSessionUploadPlatform`
sets neither and rides the defaults. The shipped 18–26.0 tier already depends on that default for upload
relaunch on every device in the field, so this is probably deliberate and merely unstated — but it is
unexplained in code, and it sits directly under the two commits this measurement protects.

### D11 — Measurements are recorded, not pinned

Every measurement below lands in this record with its date, host and versions. None becomes a CI assertion,
matching `delete-simulator-session-downgrade` D5, where a standing re-measurement was argued for and
rejected by the owner. The recorded trade there — that nothing re-measures the fact and prose is how the
original wrong comment survived — applies here identically and is accepted on the same terms.

`fix-download-session-lifecycle` D5's closing *"downloads remaining inert on the simulator is a known,
accepted limitation"* is **superseded, not edited**, in the manner this repo has twice used: the archive
records what was believed then, and editing it erases the evidence that the belief existed.

### D12 — No spec for the simulator host; a new skill for it

The host is dev infrastructure that is a **lens**, and this repo specs test infrastructure that *holds
behaviour* while leaving lenses unspec'd (`:test:harness-driver`, `ssh-mac.yml`, `api/src/dev`, and
`:test:rig` itself under its own D12). The two spec deltas this change does carry are production contracts
that happen to be reached from here, not descriptions of the host.

A new `ios-simulator` skill owns the runbook, parallel to `ios-device` rather than inside it, because its
headline property is the inverse of that skill's: it needs **no device lease**. `rig-channel` gains one line
for where a simulator's port comes from.

*Rejected:* extending `rig-channel`. It is about a channel, not a host, and it opens by instructing the
reader to take the device lease — the first thing a simulator session must not do.

## Risks / Trade-offs

- **[The simulator does not exercise the shipped identity binding]** → Accepted and named in Non-Goals. A
  regression in the Keychain binding cannot surface on a simulator, because that binary does not contain
  it. The trade is deliberate: the alternative was one implementation forking at runtime, which *does*
  exercise the shipped path — and can take the wrong branch on a real device (D6's second rejection).
  Coverage of the Keychain binding stays where it already is, in `:adapter:ios:ext-safe`'s own tests.
- **[Two bindings of one port drift apart]** → The contract they both implement is `SecureStore`, whose
  three-state read is exercised in `commonTest` against the shared `resolveOrMint` on both JVM and
  `iosSimulatorArm64`. Drift would have to be in a binding's own behaviour rather than in the resolution
  order, and the simulator binding is test equipment whose failure is loud and local.
- **[`iosSimulatorArm64` is also the unit-test target]** → Anything resolving identity *through the
  composition* in a simulator test now gets the file binding. The Keychain-specific tests construct
  `KeychainDeviceIdentity` explicitly and are unaffected, but this is verified rather than assumed
  (task 3.4).
- **[The simulator diverges from `screenshots.yml`, which shares the scheme]** → That workflow builds
  unsigned with `CODE_SIGNING_ALLOWED=NO` and boots forge, which needs no container. This change adds a
  post-step rather than altering the build, so the workflow is unaffected. A second target arriving from
  `triggers-into-channel` is the real risk to that scheme and has been flagged there.
- **[An SSH drop makes the backend look like a crash]** → `connection refused` from inside the simulator is
  ambiguous between "the tunnel died" and "the backend died". Stated in the skill, with `api/.localdev/host`
  and the local process as the disambiguator.
- **[`simctl privacy grant photos` not holding reads exactly like a boot hang]** → The modal alert is
  invisible in every log and blocks every subsequent launch at `mode=deferred`. The skill's earned rule is
  **screenshot first** on any such stall, and the recovery sequence is recorded.
- **[The simulator's coverage is mistaken for the device's]** → Non-Goals name the gaps (`LIMITED`, APNs,
  the OS-driven tier), and the skill repeats them, because the danger is a later change writing a scenario
  for a host that silently cannot run it.

## Migration Plan

None. No durable state changes, no wire format, no stored value. The `iosArm64` target's compiled output
is unchanged by this change, so no device's behaviour moves — the only new binding is compiled for a
target that cannot run on one. Rollback is reverting the commit.

## Open Questions

- **Does the OS relaunch a terminated app on a simulator?** **STILL OPEN, and now known to be
  unanswerable here** — see D10. It requires a background transfer that outlives the process, and 6.2
  shows none can exist on this host. Device-only until that changes. #5–#7 must not build a scenario on
  it.
- ~~**Are downloads inert on a simulator?**~~ **ANSWERED, 2026-08-25 — and the honest answer is neither
  "inert" nor "they work".**

  The background download session **runs**: `DownloadController` planned all three foreign assets, the
  transport created tasks, and `didCompleteWithError` fired for each. So the premise
  `fix-download-session-lifecycle` D5 rested on — *"the simulator cannot run background sessions"* —
  stays **false**, as `delete-simulator-session-downgrade` already found for uploads.

  But **every transfer failed immediately with `NSURLErrorDomain / -1` (`NSURLErrorUnknown`)**, against a
  loopback host **and** against the runner's LAN address, while in the *same process* the app's ordinary
  default-session HTTP reached the same server successfully — event creation, join and the union read all
  went through it — and a plain `curl` fetched the identical presigned URL with `HTTP 200` and the exact
  byte count. So the failure is specific to the **background** session, not to the host, the scheme, or
  the URL.

  Consequently D5's closing sentence — *"downloads remaining inert on the simulator is a known, accepted
  limitation"* — is **correct in outcome and wrong in mechanism**, and is superseded on those terms: the
  session is alive and it is the transfers that cannot complete. That distinction matters, because
  "cannot run background sessions" invites the wrong fix.

  Getting from "unknown error" to `NSURLErrorDomain/-1` needed a rebuild, because the transport logged
  only `localizedDescription` — which iOS renders as the literal string "unknown error". The log line now
  carries the domain and code, so the next person does not pay that cost.

  **Why it fails (researched 2026-08-25; consistent-with, not proven).** Background transfers are not
  performed in the app's process: `URLSession` hands them to **`nsurlsessiond`**, a system daemon, over
  XPC. That relationship is where the Simulator is unreliable, and the reports are numerous and
  simulator-specific — `NSCocoaErrorDomain 4099` *"The connection to service on pid 0 named
  com.apple.nsurlsessiond was invalidated"* (aws-sdk-ios #3083, on Simulator, working on device), and
  `-997` *"Lost connection to background transfer service"* on Simulator. Our `-1` is a third, unhelpful
  face of the same class: the task is created and completes, but nothing transfers.

  This is **consistent with** those reports rather than proven to be them — no source found names
  `NSURLErrorUnknown` for this case, and the daemon's own error was never observed. What would settle it
  is the Simulator's **system log** during an attempt (`xcrun simctl spawn <dev> log stream --predicate
  'process == "nsurlsessiond"'`), which was not captured before the session closed. Named here rather
  than guessed at.

  Apple's own guidance already points the same way and is the part that actually binds: Quinn's pinned
  *Testing Background Session Code* says **"Test on a real device, not in Simulator"** and that
  **"Simulator may not accurately simulate app suspend and resume"** (r. 16532261). Notably it does
  **not** say background sessions are unsupported there — which matches what we measured: the session
  runs, and the transfers do not.
- ~~**Does a signed simulator build route universal links?**~~ **SETTLED, 2026-08-25 — no, and it cannot.**
  The suspicion in the last sentence of this question was the right one: an unprovisioned
  `associated-domains` makes the app un-launchable exactly as `keychain-access-groups` does
  (`SBMainWorkspace` refusal). `openurl` is accepted and no link entry point fires. The 2026-08-09 negative
  is therefore explained rather than overturned, and **SNAPSYNC-3 has no repro path on this host**. The
  warm half stays reachable through the channel's `onSceneContinueActivity` trigger, which exercises
  decode → gate → join without OS delivery.
