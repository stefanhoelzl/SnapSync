## Context

`registrationOutcome` (`:domain` `model/RegistrationOutcome.kt`) classifies what a change to the OS-driven
upload-job registration did, and carries the Kermit severity as a property of the outcome so the `:app:ios`
call site renders without branching. It landed in `2026-08-24-retire-launch-env-triggers`, and it is what
made this defect visible: before it, `PhotoKitUploadProducer` discarded both the `Boolean` and the
`NSError`.

Under a partial photo grant, iOS refuses `setUploadJobExtensionEnabled` in both directions with
`PHPhotosErrorAccessUserDenied` (3311). The classifier's `else` arm maps that to `Failed` at `Error`, and
`crash-reporting` turns every `Error` line into a Bugsink event.

**Field state.** No 3311 event has arrived yet: the classifier merged 2026-08-21, and every diagnostic dump
inspected reports `photo_permission: GRANTED`. But the tier is genuinely in the field — one reporter runs
iOS 26.5.2 with `upload_tier: photokit` — so the path is reachable by a real member, not hypothetical. It
fires on the grant flip, on each join/switch/create, and on each leave taken while limited, because
`UploadArm` stops the non-selected mechanism on every transition.

**Contract state.** The spec does not merely lag the fix; `ios-photokit-upload` currently *mandates* it,
requiring `Error` for any failure that is not the 3201 fresh-install case. The same measurement also
falsifies a mechanism claim standing in three live places — `ios-photokit-upload`, `limited-photo-access`,
and `CLAUDE.md` — and a scenario asserting a deregistration the platform refuses.

## Goals / Non-Goals

**Goals:**

- Stop an ordinary, supported user action from raising crash-reporting events, without silencing the
  registration failure the reporting exists to surface.
- Make the contract of record state what was measured, everywhere it is asserted — including the
  in-context copy agents read.
- Keep the classification a tested `:domain` decision, so the shell keeps rendering without branching.

**Non-Goals:**

- **Skipping the post-disable ledger repair when the disable was refused.** Considered and dropped: since
  `collapse-upload-tier-seam`, `deregister()` takes the tier-switch path — the one where running the
  repair was the actual hazard, because `clearRequested()` is ledger-wide and would delete rows belonging
  to the mechanism about to start. The only remaining caller of the repairing `stop()` under a partial
  grant is the leave path, and there `ios-photokit-upload` requires the clear on its own terms
  ("Leave clears REQUESTED"), unconditioned on the disable having succeeded. Skipping it would contradict
  a standing requirement to tidy a repair that is no longer spurious.
- Any behaviour change. Nothing about which mechanism runs, what uploads, or what the screen shows moves.
- Pinning `PHPhotosError` in `PlatformVocabularyPinTest`. That guard exists for Apple enumerations decoded
  with a **fallback arm that silently absorbs** an untaught case; here the fallback arm is the loud answer
  (`Failed`/`Error`), so a vocabulary Apple widens cannot hide.

## Decisions

### D1 — The carve-out is disable-only, mirroring 3201

The quiet branch is `!enabling && errorCode == 3311`. The two directions have opposite consequences: a
refused **disable** leaves a record that is inert — the OS does not invoke the extension under a partial
grant, and a return to full re-registers anyway — while a refused **enable** means no registration exists,
no cycle ever runs, and the screen sits at "Synchronization pending…" with nothing to say why. That second
case is exactly what `Error` was introduced for.

*Alternative considered:* one branch on `errorCode == 3311` regardless of direction, on the reasoning that
"the platform refuses to change the registration" is a single fact. Rejected: it would hide the
invisible-and-terminal case behind the routine one, which is the failure this requirement exists to refuse.

The enable case is unreachable in a shipped build — resolution never yields the OS-driven mechanism under a
partial grant — so keeping it loud costs production nothing. It is reachable under the development
mechanism override, which is precisely where a developer needs to be told the pin cannot work.

### D2 — Two sealed members, not one with a conditional severity

`DisableRefusedByGrant` (`Warn`) and `EnableRefusedByGrant` (`Error`) are separate members, each declaring
its severity unconditionally.

*Alternative considered:* `RefusedUnderPartialGrant(enabling)` with `severity = if (enabling) Error else
Warn`. Rejected: no member of this type varies severity by a field — `Applied` conditionals its *message*
on `enabling` but states `Severity.Info` outright — and `upload-lifecycle` requires every answer to be
stated at its definition site rather than computed. Two members also let the tests assert a severity
against a named type rather than against a branch.

### D3 — `Warn`, chosen from measured band frequencies rather than 3201-parity

A real 690 KB diagnostic dump runs ≈3,600 `Info` lines, ≈1,350 `Debug`, and **19** `Warn`. `FileLogWriter`
filters nothing, so all three reach the device log and the dump; only `Error`/`Assert` become events. `Warn`
is therefore the only band a reader can scan — one `grep` — while remaining a breadcrumb.

*Alternative considered:* `Debug`, for exact parity with the 3201 carve-out. Rejected: the parity is of
shape, not of reasoning. 3201 earned `Debug` by firing on **every fresh install's first join** — high
volume, low information. 3311 fires only on membership-lifecycle actions taken under a partial grant, and
it records a genuine divergence: the app asked the OS to change state and was refused, so the app's model
of the registration is knowingly wrong afterwards. Different frequency, different information content.

This also matches what the tree already does at every other site, and what `crash-reporting` now requires:
the ordinary failure is recognised and reported at `Warn` by the layer that knows it is ordinary
(`Reconciler.kt` for the listing timeout and fetch failure; `SnapSyncApp`'s `notifyLeave` for the leave
notify), and `Error` is reserved for what no layer recognised.

### D4 — The mechanism is *contradicted by measurement*, not "the old probe was wrong"

`ios-photokit-upload` sourced "registration succeeds and lies" to the 2026-07-20 probe, whose two
registration log lines are `PhotoKitUploadProducer.start()`'s own unconditional
`log.i { "background-upload extension re-registered…" }` — emitted after `setEnabled(true)` whatever it
returned, with the `Boolean` and `NSError` discarded until 2026-08-21. So "succeeds" described a return
value nobody read, and "no error" meant none was looked for.

Since the disable is refused, "the enable was refused and the extension was never registered" explains the
probe's 22 minutes of zero invocations at least as economically as "registered but never invoked", and that
probe could not distinguish them. The 2026-08-25 measurement then showed the enable *is* refused.

The spec therefore says the **asserted mechanism is contradicted by measurement**, not that the probe
misread what it saw. That probe is not re-runnable, and a claim about an unre-runnable observation is not
one this change has standing to make. The probe's own observation — zero invocations, then invocation
within seconds of the grant returning — is retained verbatim.

### D5 — The requirement leads with the cause

*"The OS does not invoke the extension under a limited grant"* names the consequence; the cause is that the
app cannot register one. Renamed to **"The registration cannot be changed under a partial grant"** —
"changed" covering both directions, since the disable is refused identically and that is the half producing
the telemetry. Nothing outside the spec's own heading cited the old name, so the rename ripples nowhere;
the archived decision records keep the old name, correctly, as immutable history.

### D6 — The correction lands in all three places at once

The falsified sentence stands in `ios-photokit-upload`, `limited-photo-access`, and `CLAUDE.md`.
Correcting one and leaving the others is the exact pattern that produced this defect: the 2026-07-20
conclusion was copied outward and never re-derived.

`CLAUDE.md` is included deliberately despite being outside `openspec/`. It is injected into every agent's
context in this repository, so a falsified measured claim there shapes work before any spec is opened — and
it is the *unguarded* half of the digest: `LawsDigestTest` keeps the `## The laws (digest)` section in sync
with `module-architecture` and scopes to that section only, so the measured-facts paragraphs can drift
silently, and did.

## Risks / Trade-offs

- **A quiet outcome hides a registration problem that is not routine.** → The carve-out is keyed to one
  measured code in one direction, and the enumeration is stated as closed: a code becomes expected only
  once a device measurement shows it on an ordinary path. Everything else still lands on `Failed`/`Error`.
- **`Warn` is a breadcrumb, so a persistent partial-grant problem raises no event.** → Accepted, and it is
  the point: the condition is routine and self-healing, and it stays visible in the device log and in any
  diagnostic dump, which is where someone investigating "why isn't this uploading" already looks.
- **The enable measurement is n = 1, one point release, reached through a development pin.** → Stated in
  the requirement as an evidence limit rather than smoothed over, and carried by the capability's existing
  iOS 27 GM expiry trigger. The classification does not depend on the enable case being common; it depends
  on it being *possible*, which the measurement establishes.
- **The `PHPhotosError` constants are literals with no mechanical pin.** → Deliberate (see Non-Goals). If
  Apple re-valued one, the carve-out stops matching and the outcome reverts to `Failed`/`Error` — noisy but
  **loud**, never silent, so no guard is load-bearing here.
- **`limited-photo-access`'s relinquish requirement was synced days ago and is amended again here.** →
  Unavoidable: `collapse-upload-tier-seam` archived before its own D11b measurement could reach that spec,
  and its workspace is gone. The requirement's purpose is untouched; only the claimed effect is qualified.
