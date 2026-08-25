## Why

A member on a trip reported "device verification failed! why?" (Bugsink `SNAPSYNC-20`, 2026-08-18). The
diagnostic dump shows the screen told them **sharing is paused while their token was valid for another six
days**, and the one log line that could have said why the cheap renewal path failed named the wrong actor
and threw the evidence away.

Both specs already forbid the first half. `sync-status-screen` says `Unattested` "SHALL be raised **only**
when there is no usable token **and** obtaining one failed — never for a merely stale token";
`device-attestation` carries the scenario "**A merely stale token is not an error**". The implementation
raises it for a merely stale token, because `refreshOutcome()` reuses `isStale()` — whose job is deciding
**when to renew** (7 days before expiry), not **what to show**. What the specs do not yet say, and what the
same dump exposes, is that a verdict may be published by one wake and rendered by a foreground entry an
arbitrary time later: here a background wake with no network wrote `false` on 08-17 13:37, and it rendered
as the first frame the member saw 25 h 47 min later, clearing 4.3 s afterwards.

## What Changes

- **`Unattested` stops firing for a usable token.** `isStale()` keeps its 7-day renewal margin and keeps
  driving *when the app renews* — unchanged. A second, narrower predicate (**absent · unparseable · past
  expiry**) drives *what reaches the screen*. A token six days from expiry authorises every upload, so the
  screen may not claim sharing is paused.
- **A verdict never outlives the refresh that produced it.** The trust feature owns the attestation-health
  flow: it clears to "attested" when a refresh **begins** and writes the outcome when it **ends**. A value
  computed by an earlier wake can no longer be rendered as current at a later foreground entry.
- **`refreshOutcome()` becomes internal.** The public surface is a refresh command plus the health flow, so
  the iOS shell's `refreshAttestation` reduces to a call — the last decision leaves `:app:ios`
  (`module-architecture`, "Shells are wiring only").
- **`AttestedSource` / `AlwaysAttested` / `MutableAttestedSource` are deleted.** `StatusContainerHost`
  takes `attested: StateFlow<Boolean>` directly, matching its documented rule that its inputs are bare
  read-model StateFlows. The forge harness passes its own cell.
- **A failed renewal names its own cause.** `DeviceAttestation` swaps `runCatching { … }.getOrNull()` for
  `getOrElse { log.w(it) { … } }` on the renew branch — the same shape the attest branch four lines below
  already uses — and stops logging "renewal refused" for a failure that never reached the backend.
- **The `Unattested` detail line stops naming a cause it cannot know** and stops prescribing the action
  already in flight; it states that retries continue and nothing is lost.
- **Non-Goal, recorded with its failure mode:** a rejected-but-unexpired token still reads healthy through
  a `401` loop when re-attestation keeps succeeding while the backend keeps rejecting.

No BREAKING changes: no persisted format, wire format, or backend route moves.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-attestation`: the requirement "An expired token stalls uploads; it never loses a photo" gains an
  explicit predicate for *unusable* (distinct from the renewal margin that `isStale` governs) and a
  freshness rule — a surfaced verdict SHALL come from a refresh attempted no earlier than the surface's own
  entry. Its renewal requirement gains a diagnostics rule: a renewal that fails SHALL record the cause it
  actually has, and SHALL NOT attribute a local key failure to the backend.
- `sync-status-screen`: the `Unattested` rung's "no usable token" precondition is sharpened to name the
  predicate (absent, unparseable, or past expiry — never merely near expiry), the freshness rule is
  restated as it binds the screen, and the affordance's detail line gains a constraint on what it may
  claim.
- `desktop-test-harness`: its "Unattested preset" requirement named the injected implementation type
  (`MutableAttestedSource`) that this change deletes, which would have left the requirement describing
  something the tree no longer contains. It is restated to name the *shape* the panel forges — a
  writable attestation-health cell — and no production type. Found by the archive gates, not by the
  diff: this is a spec the code change never opened.

## Impact

- `domain/` `feature/trust/DeviceAttestation.kt` — the new predicate, the owned health flow, the renew
  branch's logging; `refreshOutcome` goes internal.
- `domain/` `compose/SnapSyncApp.kt` — `refreshAttestation` wiring.
- `ui/presentation/` — `AttestedSource.kt` deleted; `StatusContainerHost` takes a bare `StateFlow<Boolean>`.
- `ui/components/AppStatusLine.kt` — the `CannotVerifyDevice` detail line.
- `app/ios/SnapSyncRoot.kt` — the `MutableAttestedSource` cell and `refreshAttestation` body removed.
- `app/desktop/` — `PanelController` / `StatusPane` / `Main.kt` forge the flow directly.
- Tests: `DeviceAttestationTest` (its `refreshOutcome is false only when …` case passes an **empty** store
  today, so the "only" is untested), `StatusContainerHostTest`, and a regression pinning the `SNAPSYNC-20`
  sequence.
- No backend (`api/`) change; no persisted or wire format change.
