## 1. The trust feature: split the predicates

- [x] 1.1 In `domain/src/commonMain/kotlin/app/snapsync/feature/trust/DeviceAttestation.kt`, add the
  usability predicate beside `isStale` — absent, unparseable (`tokenExpirySeconds` returns `null`), or
  `expiry <= now` — with a KDoc that states outright that it is NOT `isStale`, that `isStale` governs
  *when the app renews* and this one governs *what reaches the screen*, and that a token inside the
  renewal margin authorises every gated request until it expires (D1)
- [x] 1.2 Re-point the surfacing rule at the new predicate: the outcome is `false` only when the token is
  unusable **and** the refresh failed to obtain one. `isStale` and `refreshLocked`'s renewal trigger are
  untouched — verify by reading, not by editing
- [x] 1.3 Correct `AttestedSource`'s claim where it now lives (it moves in §2): the KDoc that says
  `false` "does NOT mean the token is stale" becomes true for the first time; keep the statement, drop
  the seam it was attached to

## 2. The trust feature owns the health flow

- [x] 2.1 Add a `StateFlow<Boolean>` for attestation health to `DeviceAttestation`, alongside
  `tokenChanged`, initialised to `true` (D2). Document it as a derived cache of the last refresh, not
  authority — authority is the token in the `AttestStore` (`module-architecture`, "State and authority")
- [x] 2.2 Publish `true` on **entry** to the refresh and the outcome on **exit** — **outside** the
  `refreshing` mutex, not inside it as this task first said. Inside the lock, a caller queued behind a
  slow refresh would keep showing the stale verdict until it acquired the lock, which is the very frame
  this exists to fix. Design D2 says only "on entry"; the mutex detail was an over-specification here.
  So a verdict cannot outlive the start of the next refresh. Note in the KDoc that
  the optimistic clear is what makes a wake's verdict unrenderable at a later entry, and that it costs a
  claim of "attested" for the duration of the refresh itself
- [x] 2.3 Make `refreshOutcome()` private and expose a `suspend` refresh command that updates the flow
  (D3). The public surface becomes: `token()`, `onRejected()`, `ensureFresh()`, the refresh command, the
  health flow, `tokenChanged`
- [x] 2.4 Extend `adapter/generic/fake/src/commonTest/.../DeviceAttestationTest.kt`:
  - fix `refreshOutcome is false only when the device lacks a usable token AND could not get one` — it
    passes an **empty** `InMemoryAttestStore` today, so the "only" is asserted by the name alone
  - a token inside the margin (e.g. `token(3)`) with `challenge = null` ⇒ health stays `true`
  - an expired token (`token(-1)`) with `challenge = null` ⇒ health becomes `false`
  - an unparseable token ⇒ treated as unusable
  - the `SNAPSYNC-20` regression: a failed refresh sets `false`; a subsequent refresh clears it to `true`
    **at entry**, before its outcome is known
  - renewal is still attempted for a margin token (the trigger did not change)

## 3. Delete the `AttestedSource` seam

- [x] 3.1 Delete `ui/presentation/src/commonMain/kotlin/app/snapsync/presentation/AttestedSource.kt`
  (`AttestedSource`, `AlwaysAttested`, `MutableAttestedSource`) — D4
- [x] 3.2 `StatusContainerHost` takes `attested: StateFlow<Boolean> = MutableStateFlow(true)`; update both
  the initial `reduceFrom` and the `combine`. The reduction body is unchanged
- [x] 3.3 Update every construction site: `ForgeStatusHost.kt`, `app/desktop/StatusPane.kt`,
  `app/ios/SnapSyncRoot.kt`, the four `:test:integration` tests, and `StatusContainerHostTest`
- [x] 3.4 `app/desktop/PanelController.kt` holds a `MutableStateFlow(true)` instead of the deleted type;
  `showUnattested()` and `resetOverlays()` write to it. Verify the forge preset still renders the state —
  it is the only way to review it without a device

## 4. The iOS shell keeps no decision

- [x] 4.1 Remove `SnapSyncRoot`'s `attested` cell and the body of `refreshAttestation`, leaving a call to
  the feature's refresh command; pass the feature's flow to `renderHost` (D3)
- [x] 4.2 Thread the flow through `domain/compose/SnapSyncApp.kt` if `AppCore` must expose it, keeping
  `refreshAttestation` a plain `suspend () -> Unit` for the flows
- [x] 4.3 Confirm `detektAppShell` and `KotlinShellGuardTest` still pass — the shell should have one
  fewer decision, never one more

## 5. Diagnostics: a failed renewal names its own cause

- [x] 5.1 In `refreshLocked`, replace the renew branch's `runCatching { … }.getOrNull()` with the
  `getOrElse { log.w(it) { … } }` shape the attest branch four lines below already uses, so
  `IosAttestKey`'s `domain=… code=… localizedDescription` reaches `debug.log` (D5)
- [x] 5.2 Replace the message: "renewal refused" is a claim about the backend, and on 2026-08-18 no
  request was sent. The new wording must hold for both shapes the branch absorbs — a local assertion
  failure (throwable, no request) and a genuine refusal (`null`, request made)
- [x] 5.3 Keep it at `Warn`. It is recoverable and rides as a breadcrumb; promoting it to `Error` would
  put a routine transient into crash triage (D5, rejected alternative)

## 6. The status-line copy

- [x] 6.1 In `ui/components/.../AppStatusLine.kt`, replace the `CannotVerifyDevice` detail line. Headline
  unchanged — it is true once §1 lands. Shipped: "Still retrying — your photos aren't lost." The line
  first proposed here ("SnapSync keeps retrying. Your photos aren't lost.") wrapped at 390pt and left
  "lost." orphaned on its own row — found by 6.3, which is the whole reason that check exists.
- [x] 6.2 Update the surrounding comment, which currently justifies the old line as naming "what actually
  clears it"; the new line deliberately names no cause (D4)
- [x] 6.3 Check the string still fits the pill at phone width in both themes via the forge harness's
  Unattested preset (load the `ui-harness` skill; no device needed). It did NOT on the first wording —
  see 6.1. The shipped line renders on one row in both themes; the headline's own two-line wrap is
  pre-existing and untouched

## 7. Verify

- [x] 7.1 `./gradlew build` — JVM + simulator unit tests, the architecture guards, `detektAppShell`
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable iOS proxy
- [x] 7.3 `./gradlew architectureDiagrams` and commit any regeneration; stale `architecture/` blocks the PR
  — `architecture/di.md` regenerated: the deleted `MutableAttestedSource` row drops out
- [x] 7.4 `npx --yes @fission-ai/openspec@1.5.0 validate --changes --strict`
- [x] 7.5 Drive the forge harness's Unattested preset and confirm: the state still renders, and the new
  detail line reads correctly (`ui-harness` skill) — done in both themes; see 6.3
- [x] 7.6 On-device confirmation is **not** required to merge, but record what would confirm it: a
  foreground entry after an offline background wake shows no attention line, and a `debug.log` renewal
  failure now carries a `DCError` code

## 8. Archive gates (`openspec/config.yaml`)

- [x] 8.1 Gate 1 — no spec's `## Purpose` carries the CLI's minted "TBD - created by archiving",
  checked across the whole tree. Clean
- [x] 8.2 Gate 3 — the diff removes `AttestedSource`, `AlwaysAttested`, `MutableAttestedSource`, and all
  three now exist in **zero** `.kt` files. `openspec/specs/desktop-test-harness/spec.md:319` still named
  `MutableAttestedSource`. **The gate fired and blocked the archive**, on a spec this change never
  otherwise touched — exactly what it exists for. Fixed by a `desktop-test-harness` delta (§8.4).
  Archived changes that name the type are decision records and are correctly left frozen
- [x] 8.3 Gate 2 — every module the diff touches, resolved to its owning capability:
  - `domain` (feature/trust, flow/Foreground) → `device-attestation` — delta present
  - `ui/presentation` (StatusContainerHost, deleted seam, PendingJoinSource KDoc) → `sync-status-screen`
    — delta present
  - `ui/components` (AppStatusLine detail line) → `sync-status-screen` — delta present. **`design-system`
    needs none**: its `CannotVerifyDevice` requirement (attention indicator, a label stating the device
    cannot be verified, no `onClick`, background, no chevron) is unchanged, and it never described a
    detail line — the headline still says exactly what it requires
  - `app/desktop` (PanelController, StatusPane, Main) → `desktop-test-harness` — **delta added**, §8.4.
    `full-stack-harness` needs none: it never mentions attestation and takes the shared pane's default
  - `app/ios` (SnapSyncRoot) → `ios-app-shell` — **none needed**: the shell is wiring-only, the spec
    names "the attestation" generically, and its standing requirement that the host's read-model inputs
    are bare StateFlows is made *more* true by this change, not less
  - `adapter/generic/fake` (DeviceAttestationTest) → **none needed**: test-only; `InMemoryAttestStore`'s
    port contract and the fake-honesty rules are untouched
  - `architecture/di.md` → `architecture-diagrams` — **none needed**: a generated artifact, regenerated
    and committed (§7.3)
- [x] 8.4 Add the `desktop-test-harness` delta: restate "Unattested preset" so it names the shape the
  panel forges (a writable attestation-health cell) rather than a production type, and record why the
  old wording outlived the type it named
- [x] 8.5 Re-run `validate --changes --strict` and re-run all three gates clean before archiving
