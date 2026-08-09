## Context

Two claims about one platform fact have coexisted since the app-driven tier shipped, and they contradict
each other:

- `openspec/specs/ios-url-session-upload/spec.md:16` — "**simulator-testable end-to-end** (a background
  `URLSession` runs in the simulator)"; and again at `:350`.
- `IosUrlSessionUploadPlatform.kt` — "The iOS **simulator** does not support background NSURLSession —
  `getAllTasks` never calls back and transfers never run."

The code acts on the second: `OsFacts.isSimulator` is read at runtime from
`NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"]`, folded into
`CompositionMode.Live.useBackgroundSession = !isSimulator`, and threaded through six files to one `if` that
picks `defaultSessionConfiguration()` over `backgroundSessionConfigurationWithIdentifier()`.

Neither claim carried a forcing proof. Both are therefore in violation of `module-architecture` — *"a
platform-capability claim is settled by a compile, not by a symbol table"* and *"necessity claims carry forcing
proofs — an API contract, a measurement, or a vendor doc, never the current code."* The downgrade's stated
purpose was to let the simulator "exercise the real staging → PUT → delegate → ledger flow"; the archived
`fix-download-session-lifecycle` D5 repeats the belief as "(the simulator cannot run background sessions)".

### The measurement

Run 2026-08-09 on macOS 26.5.2 / Xcode 26.6, `iosSimulatorArm64`, via
`./gradlew :adapter:ios:app-only:iosSimulatorArm64Test`. A throwaway probe created a session from
`backgroundSessionConfigurationWithIdentifier`, called `getAllTasksWithCompletionHandler`, and started an
`uploadTaskWithRequest(…, fromFile:)` aimed at a closed local port (the `DarwinHttpClientTest` idiom — a
refusal still proves the session executed the task):

```
PROBE getAllTasks called back = true
PROBE delegate fired = true (unknown error)
PROBE task state after wait = 3          # NSURLSessionTaskStateCompleted
```

Both sentences of the code comment are false. The spec was right.

**What this evidences, precisely.** The **transport**: a background-configured session runs tasks on a
simulator and answers `getAllTasks`. It does **not** evidence **app relaunch** — the OS waking a terminated app
to deliver `handleEventsForBackgroundURLSession`. The probe host was an `xctest` process that stayed alive
throughout and had no app bundle, so it could not have exercised relaunch even accidentally. Those are separate
properties and only the first is in evidence.

**Why a positive is decisive despite the host.** A background session is bound to an app bundle and its
container, so an `xctest` host is the *harder* case, not a privileged one. A success there cannot be a host
artifact; a failure could have been. (Agreed reading rule, set before the probe ran: positive decisive, negative
suggestive only.)

**Expiry.** Re-measure at the next iOS major, alongside the PhotoKit and limited-access platform facts.

## Goals / Non-Goals

**Goals:**

- Delete the simulator transport downgrade and the runtime host determination that drives it.
- Leave exactly one transport on the app-driven tier, for every host, matching what the spec always said.
- Replace two unproven claims with one measured one, stated no more broadly than it was measured.
- Remove `OsFacts` now that it would carry a single field.

**Non-Goals:**

- **Any build-time host seam.** The original premise was that simulator-ness should move from a runtime
  environment read to the compilation target via `expect`/`actual`. The measurement removes the axis entirely,
  so there is nothing to relocate: no `expect`/`actual`, no per-target source sets in `:adapter:ios:app-only`.
- **Tier selection.** PhotoKit vs URLSession stays a genuine runtime decision — one binary serves iOS 18 and
  iOS 26 devices — so the sealed `CompositionMode` resolver keeps that job unchanged. The two axes were never
  the same and are not merged here.
- **Simulator rig capability.** A device-id trigger, an upload-cycle trigger, ad-hoc signing and a simulator
  runbook are owned by the separate simulator-host work, not by this change.
- **Settling app relaunch on the simulator**, or whether downloads are inert there.

## Decisions

### D1 — Delete the downgrade rather than relocate it to the compilation target.

The change was conceived as moving a build-time fact out of a runtime read. The measurement made that moot: with
no behavioural difference between hosts there is no axis, and a seam would exist only to select between two
identical answers. Deleting is strictly smaller and removes the claim rather than re-housing it.

*Alternative considered — keep the downgrade, move it to `expect`/`actual`.* Rejected: it would carry a
measured-false claim into a new structure, where it is harder to question than a comment was.

### D2 — Delete `OsFacts` instead of leaving it with one field.

With `isSimulator` gone the type wraps a single `Boolean`. `resolveComposition(directives,
backgroundUploadSupported, isForgeState)` states the input plainly.

*Alternative considered — keep `OsFacts` for future OS facts.* Rejected as speculative: re-adding a type when a
second fact appears costs one line, and `module-architecture`'s wording is corrected in the same change either
way.

### D3 — Correct the comment to exactly what was measured.

The replacement text states that the transport runs on the simulator, cites the probe and its date/versions, and
says app relaunch is **unproven**. Writing "background `URLSession` works in the simulator, full stop" would
replace one overclaim with another — the precise failure this change exists to end.

### D4 — Supersede D5; do not edit the archive.

`fix-download-session-lifecycle`'s D5 contains "(the simulator cannot run background sessions)", now false. The
archive is **not** edited. This repo already faced the identical situation — a measured platform claim in an
archived design record, falsified later by a probe — with the limited-access alert-storm claim, which remains
verbatim in `2026-07-20-accept-limited-photo-access/design.md` (five occurrences) and was handled by
`2026-08-06-correct-limited-access-read-premise` superseding it. An archived record is an account of what was
believed and decided then; editing it erases the evidence that the belief existed, which is the very thing that
let this one survive.

The supersession records three facts:

1. D5's parenthetical is **measured false**.
2. **D5's decision stands, unweakened.** Its argument for refusing downloads a foreground hatch is that the
   crash comes from `__NSURLBackgroundSession`, the *background* subclass, so a foreground session "would very
   likely run straight through this defect, manufacturing false confidence." The probe does not touch that.
3. D5's closing "downloads remaining inert on the simulator is a known, accepted limitation" is now
   **unproven** — if background sessions run there, downloads may not be inert at all. Settled by the
   simulator-host work, where an ad-hoc-signed app and a local backend make the check nearly free. A coverage
   gain, not a defect.

### D5 — No permanent CI guard for the platform fact.

The probe is discarded rather than promoted to an assertion in `:adapter:ios:app-only`'s `iosTest`.

*Alternative considered — ship it asserting, so `ios-test` re-measures every push and the false comment cannot
regrow.* Argued for and **rejected by the owner**. The recorded trade: nothing re-measures this fact, and prose
with no runnable answer is how the original wrong comment survived. The command and its output are preserved in
Context so the measurement is reproducible by hand.

## Risks / Trade-offs

- **[The fact is never re-measured; the false comment regrows]** → Mitigated only by prose: the corrected
  comment cites the probe, its date and its host versions, and Context above records the exact Gradle command.
  Accepted deliberately per D5.
- **[App relaunch on the simulator is assumed rather than shown]** → Mitigated by scope: nothing in this change
  depends on relaunch, and the corrected comment says so explicitly rather than implying whole-capability
  support. Named as the simulator-host work's to settle.
- **[A simulator now holds a real background session where it previously held a foreground one]** → Low: no
  cycle can currently be triggered on a headless simulator at all (measured 2026-08-09 — a fully live, joined,
  permission-granted simulator with the tier armed produced no `enumeration` line across repeated foreground
  transitions), so this path is not reachable there today. When the simulator-host work makes it reachable, a
  background session is the faithful transport, which is the point.
- **[Deleting `OsFacts` touches a required check]** → `architecture/di.md` lists it, so `./gradlew
  architectureDiagrams` must be re-run and committed or the `diagrams` check blocks the PR. Mechanical, but it
  fails the build if forgotten.
- **[`PlatformIdentifierTest`'s accepted exception names `OsFacts`]** → Its reason ("the resolver is a total
  function over `OsFacts`") must be reworded, not deleted: the `PHOTOKIT`/`URL_SESSION` pin it guards is still
  required and still correct.

## Migration Plan

None. No durable state, no wire format, no stored value changes; every edit is compile-time. No shipped device
binary changes behaviour — a device used a background session before and after — so there is nothing to roll
forward or back beyond reverting the commit.
