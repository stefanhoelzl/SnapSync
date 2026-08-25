## Context

Bugsink `SNAPSYNC-20` is an operator-triggered diagnostic dump, not a crash: a member wrote "device
verification failed! why?" while joined to a live event. The dump pins the whole chain, and it is worth
stating precisely because two separate defects produced one symptom.

**The visible defect.** The app process started `2026-08-17 13:37:23` as a *background* launch
(`MainViewController(mode=deferred)` — no foreground scene) with no network at all: every request failed
with `NSURLErrorDomain -1009` / `_NSURLErrorNWPathKey=unsatisfied (No network route)`. `refreshLocked()`
could not fetch a challenge, logged "could not obtain a challenge — leaving the existing token in place",
and returned `false`. `refreshOutcome()` then evaluated `ok || !isStale(token())` → `false || !true` →
**`false`**, and the shell wrote that into `MutableAttestedSource`.

The token was **not** expired. `refreshLocked()` only fetches a challenge when `isStale()` is true; the log
shows no challenge fetch at `2026-08-16 12:17` and the first one at `2026-08-17 13:06`, which — with a
7-day margin — bounds expiry to `(2026-08-23 12:17Z, 2026-08-24 13:06Z]`. The token had roughly six days of
life left and authorised every upload: `verifyToken` on the backend is one HMAC comparison plus an expiry
check, nothing else.

That boolean then sat in memory for **25 h 47 min** — the process stayed alive but suspended, and
`refreshAttestation` runs only from a trigger flow. At `2026-08-18 15:24:02.630` the member foregrounded
the app; `MainViewController(mode=live)` built the `StatusContainerHost`, which seeds its first state from
`attestedSource.attested.value`. The first frame therefore rendered a 26-hour-old verdict as current:
*"Can't verify this device — sharing is paused."* It cleared at `15:24:06.900`. The member double-tapped
the diagnostics affordance at `15:24:30.686`.

**The defect that made it visible for 4.3 seconds instead of ~100 ms.** At `15:24:03.022` the challenge
returned `200`; 19 ms later the log says `renewal refused — attesting afresh`. **There is no
`POST /attest/renew` line anywhere in the dump.** `HttpAttestClient.renewToken` never throws — every
failure maps to `null` by contract — so the only expression inside that `runCatching` that *can* throw is
`key.assert(existingKeyId, challenge)`. `DCAppAttestService.generateAssertion` failed locally, before any
network call, forcing the throttled full-attestation path.

`IosAttestKey` constructs precisely the diagnostic needed —
`"App Attest generateAssertion failed: domain=… code=… …"` — and `refreshLocked()` discards it with
`.getOrNull()`, then logs a line that names the **backend** for a Secure-Enclave failure. The sibling
branch four lines below does it correctly: `.getOrElse { log.w(it) { "attestation failed" } }`. So the root
cause of the assertion failure is unrecoverable from this dump by construction. (The likeliest candidate,
`DCErrorInvalidKey`, is what the `device-attestation` spec's own Non-goals describe — "on reinstall, where
the Secure-Enclave key dies but the Keychain device id survives" — but there was no reinstall here:
`Documents/debug.log` runs continuously from 2026-08-11, and deleting the app would have wiped it.)

**Both specs already forbid the first half.** `sync-status-screen`: "`Unattested` SHALL be raised **only**
when there is no usable token **and** obtaining one failed — never for a merely stale token, which the next
wake renews." `device-attestation` carries a scenario titled "A merely stale token is not an error". The
code disagrees because `refreshOutcome()` reuses `isStale()`, whose contract is "missing, expired, or close
enough to expiry to renew now" — a *renewal trigger*, not a *usability verdict*. The test that would have
caught it, `refreshOutcome is false only when the device lacks a usable token AND could not get one`,
passes an **empty** `InMemoryAttestStore`, so the word "only" is asserted by the name and by nothing else.

What the specs do **not** yet say is the second half: nothing forbids publishing a verdict at one wake and
rendering it at a foreground entry an arbitrary time later.

## Goals / Non-Goals

**Goals:**

- `Unattested` reaches the screen only when the token is genuinely unusable — absent, unparseable, or past
  expiry — so the claim "sharing is paused" is true whenever it is made.
- A verdict on screen is never older than the refresh attempt made at the surface's own entry.
- A failed renewal records the cause it actually has, and never attributes a local key failure to the
  backend. The next `SNAPSYNC-20`-shaped dump answers "why" without re-deriving it from an absent HTTP line.
- The rule lives in the tested trust feature, not in the untested iOS shell.
- The detail line stops naming a cause it cannot distinguish and stops prescribing the action already in
  flight.

**Non-Goals:**

- **Changing when the app renews.** `isStale()`'s 7-day margin and the renew-at-every-wake discipline are
  untouched. This change alters only what is *surfaced*.
- **Carrying the cause to the member.** Offline and backend-refused stay one screen state; only the wording
  changes. Threading a sealed cause through presentation was considered and rejected (D4).
- **Closing the rejected-but-unexpired hole.** After `onRejected()` clears a token, the flag stays
  optimistically attested until a refresh fails. If re-attestation keeps *succeeding* while the backend
  keeps rejecting the minted token — a rotated signing key, or a leave cascade that collected this device's
  attestation record — the member sees a healthy screen through a permanent `401` loop. That is the case
  `AttestStore.clearToken`'s own documentation warns about, it survives this change, and closing it needs a
  rejection counter or a post-refresh gated probe. Recorded here so it is not mistaken for covered ground.
- **Diagnosing the assertion failure itself.** This change makes the cause *recordable*; it does not claim
  to know it. Whether `DCErrorInvalidKey` recurs on this device is answered by the next dump, not here.
- **The extension's dormancy.** The same dump shows the upload extension has not run since
  `2026-08-14 18:09:47` while the member's own-photo total grew 101 → 144 → 189 with `photos_completed`
  stuck at 101 and `photos_pending` at 0. That is a different capability and is being handled separately.

## Decisions

### D1 — Two predicates, not one: `isStale` triggers renewal, a new check decides what is surfaced

`isStale(token)` stays exactly as it is — absent, unparseable, expired, **or within 7 days of expiry** —
because it answers the question it was written for: *should this wake spend a renewal?* Renewing eagerly is
the whole reason the capability can survive iOS starving the app's background wakes, and narrowing it would
trade a visible false alarm for an invisible real stall.

A second predicate answers *is this token unusable right now?* — **absent, unparseable, or past expiry**.
Only that one feeds the health flow.

Unparseable counts as unusable. `tokenExpirySeconds` returns `null` for anything that is not
`<deviceId>.<expiry>.<hmac>`; a token whose expiry cannot be read is one we cannot show to be valid, and the
backend's `verifyToken` will reject it. Treating it as usable-until-a-`401` was considered and rejected: it
would put the member back behind a screen reading "Syncing" while every upload fails, which is the exact lie
the `Unattested` rung exists to prevent.

*Alternative rejected — narrow the renewal margin instead.* Shortening the 7-day margin would shrink the
window in which a failed renewal is visible, but it does not make the claim true inside that window, and it
weakens the renewal discipline to fix a display bug. Wrong lever.

### D2 — The trust feature owns the health flow, and clears it when a refresh *begins*

`DeviceAttestation` exposes the health as a `StateFlow<Boolean>` it owns, alongside the existing
`tokenChanged` flow. On entry to a refresh it publishes "attested"; on exit it publishes the outcome.

This is what makes a verdict non-stale by construction rather than by a freshness check somewhere
downstream: the flow cannot hold a `false` written by an earlier wake past the *start* of the next one, and
every trigger flow (`Foreground`, `SilentPush`, `DownloadBackstop`, launch) calls the refresh. Applied to
the `SNAPSYNC-20` sequence: the 08-17 background wake still writes `false` at its end; the 08-18 foreground
entry clears it at `15:24:02`, before the first frame; and the 4.3-second window renders the ordinary
snapshot-derived health instead of a lie.

The cost is honest and small: during a refresh the app claims attested while it does not yet know. That is
already the standing default (`MutableAttestedSource(initial = true)`, documented as "attestation is
somebody else's problem" for every non-iOS host), and the alternative is D4's third state.

*Why not "only the Foreground flow may publish"?* It gets the same result for this bug, but it makes the
flag depend on which trigger ran rather than on when the verdict was formed, and it leaves a device that has
never foregrounded with no verdict at all. The begin/end rule is the general statement; trigger-selection is
a proxy for it.

*Why not a generation counter read by presentation?* Freshness would then be presentation's problem, and
presentation would need a notion of "session" it does not have. The feature knows exactly when a refresh
starts; nothing else does.

### D3 — `refreshOutcome()` goes internal; the shell keeps no decision

Today `:app:ios` holds the cell and calls `refreshOutcome()`, which is the surfacing rule sitting in an
untested, wiring-only module — it was drained there at the migration finale precisely so the rule would live
in the feature, and the *cell* was left behind. With D2 the public surface becomes a refresh command plus the
flow, so `SnapSyncRoot.refreshAttestation` reduces to a call and the last decision leaves the shell
(`module-architecture`, "Shells are wiring only"; `:app:*` Kotlin is untested by contract).

### D4 — One boolean, one screen state; the detail line changes, the headline does not

`AttestedSource`, `AlwaysAttested`, and `MutableAttestedSource` are deleted and `StatusContainerHost` takes
`attested: StateFlow<Boolean>` directly, matching its documented rule that its inputs are bare read-model
StateFlows plus feature sources. `:domain` cannot implement an interface that lives in `:ui:presentation`
(the dependency runs the other way), and a wrapper would add indirection for no behavioural gain. The forge
harness passes its own `MutableStateFlow`; every other construction site passes `MutableStateFlow(true)`.

The headline stays: once D1 lands, "Can't verify this device — sharing is paused" is true whenever it is
shown. The detail line does not survive. "Reopen the app or check your connection" prescribes the thing
already in flight — reopening the app *is* what triggered the refresh — and names one of the causes it
collapses, so a member being refused by the backend is told to check a connection that is fine. It becomes a
statement of consequence that holds for **every** cause the collapse absorbs: retries continue, nothing is
lost. (`module-architecture`, "Absence is never silent": a deliberate collapse names the consequence that
makes it safe for every cause it absorbs.)

*Alternative rejected — carry the cause.* `refreshLocked()` distinguishes offline, renewal-failed, and
backend-refused in its logs, and threading that to the screen would let the copy differentiate. It needs a
sealed state through the feature, the flow, presentation, the design system, and both harnesses, and the two
causes lead to the same member action (none). Not worth that surface for this change; the log now carries
the distinction for the operator, which is who can act on it.

*Alternative rejected — an explicit third "checking" state.* The most literal reading of "'nothing' and
'couldn't tell' are different answers". It replaces the boolean with `Unknown`/`Attested`/`Unattested`
through presentation, the skin, and the forge harness — and the member-visible outcome during a refresh is
identical to D2's (no attention line). The distinction has no consequence at the surface, so it does not
earn the blast radius.

### D5 — The renew branch logs its throwable, in the shape the attest branch already uses

`runCatching { client.renewToken(deviceId(), key.assert(existingKeyId, challenge), challenge) }.getOrNull()`
becomes a `getOrElse` that logs the throwable, and the message stops asserting a refusal. `IosAttestKey`
already builds the `NSError` domain, code, and localised description into the exception; nothing new needs
constructing — the diagnostic exists and is being thrown away.

The wording matters as much as the logging. "Renewal refused" is a claim about the backend, and on
2026-08-18 the backend was never asked. The replacement must be true for both shapes the branch absorbs —
a local assertion failure (throwable, no request) and a genuine backend refusal (`null`, request made) —
which is the same "name the consequence safe for every cause" rule D4 applies to the copy.

*Alternative considered — split the two failures into separate log lines* (assert first, catch its
throwable; then call `renewToken` and log a `null` result as a real refusal). Strictly better diagnostics,
and a reasonable follow-up, but it restructures the branch; logging the throwable already makes the two
distinguishable, because a local failure carries one and a refusal does not.

*Alternative rejected — raise it to `Error` severity so it becomes a Bugsink event.* A dead Secure-Enclave
key silently costs a device the throttled attest path on every future renewal, which is worth knowing about
across the installed base. But it is a `Warn`-level recoverable condition — the app recovered here in 3.8 s
— and promoting it would put a routine transient into crash triage. The `debug.log` line is enough to
answer the next dump.

## Risks / Trade-offs

- **A device whose token expires while it cannot renew now shows nothing until the expiry instant, where it
  previously warned seven days early.** → That is the intended correction, and the warning it replaces was
  false. The real protection is unchanged: `isStale`'s margin means the app has had seven days of wakes to
  renew before the state can arise at all, and an expired token still stalls rather than strands (retries
  are error-agnostic and re-mint the request each attempt, so no photo is lost).
- **During a refresh the screen claims attested while the answer is unknown.** → Bounded by the refresh
  itself, which is ~100 ms on the assertion path and seconds on the throttled attest path. It is the
  standing default today, and the state it suppresses is non-actionable, so a member loses nothing they
  could have acted on.
- **Deleting `AttestedSource` touches every construction site of `StatusContainerHost`.** → Mechanical and
  compiler-enforced; the presentation-imports gate and `CompositionSeamTest` both fire on a mistake. The
  forge harness's `showUnattested()` preset must keep working — it is the only way to review the state
  without a device.
- **The rejected-but-unexpired hole stays open** (Non-Goals). → Named in the spec delta's non-goals rather
  than left implicit, so the next reader of `Unattested` sees which stall it does *not* cover.
- **The assertion failure's cause is still unknown after this change.** → By design: this makes it
  recordable, not diagnosed. If it recurs on this device the next dump names the `DCError` code; if it does
  not, the fresh attest already replaced the keyId and the condition healed.
