## Why

The simulator host cannot move a single byte, in either direction. Both app-process transports —
`IosDownloadTransport` and `IosUrlSessionUploadPlatform` — construct a **background** `URLSession`
unconditionally, and on a simulator `nsurlsessiond` refuses every third-party client's connection, so
every transfer ends `NSURLErrorDomain / -1` with nothing transferred (capability
`ios-url-session-upload`, "The app-driven tier uses one transport on every host"). The runbook states
the consequence plainly: downloads are impossible there, and "uploads do not happen on this host yet".

**Two-member scenarios need a transfer at both ends** — one member uploads, the other receives — and
multi-member was the strongest argument for building the simulator host at all
(`changes/archive/2026-08-25-add-simulator-rig-host`). That work is blocked until this exists, so this
change is a dependency of it rather than an optimisation.

The escape was measured in the same session that established the refusal, and is recorded in the
runbook: **the ordinary default session reaches the same server fine in the same process**, and a
foreground download of the identical URL returns `200` with the right byte count.

## What Changes

- **Add a target-bound session-configuration seam** to `:adapter:ios:app-only`:
  `internal expect fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration`
  in `iosMain`, actualized in new `iosArm64Main` and `iosSimulatorArm64Main` source sets. `iosArm64` —
  every shipped binary — yields a background configuration; `iosSimulatorArm64` yields a default one.
  Both call sites use it; neither constructs a configuration of its own.
- **Move `IosDownloadTransport`'s three configuration lines into the device actual.** `discretionary`,
  `sessionSendsLaunchEvents` and `allowsCellularAccess` were each **measured to be set to the value the
  background configuration already defaults to**, so the shipped upload session — which sets none of
  them today — is unchanged by sharing them. After the move, neither actual sets a property its own
  configuration ignores.
- **Report the binding, and predict its consequence.** A public `transferSessionBinding` accompanies the
  seam; the simulator actual logs once, at session construction, that this target's sessions never report
  `didFinishEventsForBackgroundURLSession` — so a `handleEventsForBackgroundURLSession` wake holds its
  receipt to the deadline and expires. The control channel reports the binding on `/device/state`'s
  `build` map beside `uploadTier` and `uploadBase`, and the `Receipted` trigger response carries it with
  a `note` stating what the run did and did not evidence.
- **Two mechanical pins.** A simulator test asserts the simulator actual yields a nil-identifier
  configuration; a `:test:architecture` source-text guard asserts the **device** actual names
  `backgroundSessionConfigurationWithIdentifier` and the simulator actual does not. The second is the
  load-bearing one: the device actual is the one no test can ever run.
- **No drain is synthesised, and no relaunch is claimed.** Suspend/resume and OS relaunch stay
  device-only by vendor guidance (Quinn, *Testing Background Session Code*, r. 16532261). Nothing on this
  host reports events drained, and nothing pretends otherwise.
- **This is not a re-creation of what `2026-08-09-delete-simulator-session-downgrade` deleted.** That was
  a runtime `NSProcessInfo` environment read folded into `CompositionMode` and threaded through six files
  into `:app:ios`. This is a compilation target: no runtime read, no composition change, `:app:ios`
  untouched, and a shipped device binary contains **no route** to the default binding —
  `module-architecture`'s own requirement that "a fact that is fixed by the compilation target SHALL NOT
  be re-derived at runtime". It also takes up an offer left open in terms by
  `2026-08-25-correct-simulator-background-session-claims` D1: *"If it is ever wanted, it is a separate
  change with its own proposal."*

No shipped device binary changes behaviour: `iosArm64` yields the same background configuration, with the
same three properties at the same values, before and after.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-url-session-upload`: "The app-driven tier uses one transport on every host" is rewritten in place
  and renamed — its normative core ("one transport on every host", "no simulator-specific session
  configuration SHALL exist") is what this change reverses. The **runtime** prohibition survives verbatim,
  and so does the whole `nsurlsessiond` measurement, which becomes the requirement's premise rather than
  its consolation. Two further passages that state the transport is not simulator-exercisable are
  corrected.
- `photo-download`: "Background resource download to durable staging" requires transfers over a background
  `URLSession` "so transfers continue while the app is suspended". True of every shipped binary and false
  on `iosSimulatorArm64`; the requirement gains the target qualification and states what the simulator
  binding does not provide.
- `architecture-guards`: a new requirement for the transport-binding gate. This spec enumerates each
  executable guard as its own requirement, so adding one is a spec change by that spec's own convention.
- `upload-lifecycle`: "The upload mechanism is resolved, never selected" states as fact that "the
  app-driven mechanism owns a background `URLSession`". The SHALL it grounds — that re-resolving to that
  kind returns the cached instance — is target-independent and unchanged; the factual clause gains the
  target qualification. Taken deliberately rather than left: an uncorrected claim of exactly this shape,
  in exactly this area, is what two prior changes were spent on.

**Checked and needing no delta**, recorded so the question is not re-asked:

- `module-architecture` — already carries the enabling requirement ("One shared composition": a fact fixed
  by the compilation target SHALL NOT be re-derived at runtime). This change is that requirement's
  prescription, not an exception to it.
- `ios-app-shell`, `device-identity` — both name a background-`URLSession` wake as a context the shell or
  the identity path must serve. Unchanged: they describe what happens when such a wake arrives, and the
  device binding that produces it is untouched.
- `device-manifest`, `ios-photokit-upload` — both mention a background `URLSession` only to say a path
  does **not** use one. Unaffected in either direction.
- `harness-world-model`, `full-stack-harness` — fake transports throughout; no `URLSession` reaches them.
- The control channel has no spec (non-gating dev infrastructure, by
  `changes/archive/2026-08-09-add-rig-control-channel`), so its `/device/state` and trigger-response
  additions carry no delta.

## Impact

- `:adapter:ios:app-only` — new `TransferSessions.kt` in `iosMain` plus `iosArm64Main` and
  `iosSimulatorArm64Main` actuals (both source sets are new to this module; `:adapter:ios:ext-safe`
  already has the equivalent pair for `device-identity`). `IosDownloadTransport` and
  `IosUrlSessionUploadPlatform` each lose their configuration construction. `build.gradle.kts` needs no
  edit — the default hierarchy template supplies both source sets.
- `:test:rig` — `RigHooks.buildFacts()` gains `transferBinding`; the `Receipted` trigger response gains
  the binding and extends its `note`. `Boot.kt` reads the seam's public fact.
- `:test:architecture` — a new source-text guard.
- No `:domain`, `:app:ios`, `:app:ios:extension`, `:ui:*`, backend, or dependency change. No durable
  state, wire format, or stored value changes. No generated diagram changes (no port, module edge, or
  composition seam moves), so `architectureDiagrams` output is unchanged by construction.
- Runbook `.claude/skills/ios-simulator/SKILL.md` — its "What a simulator CANNOT do" section leads with
  "No downloads, and no background `URLSession` at all", which becomes half false. The cause and the six
  ruled-out fixes stay; what changes is the consequence.
- Changelog label: `internal`.
