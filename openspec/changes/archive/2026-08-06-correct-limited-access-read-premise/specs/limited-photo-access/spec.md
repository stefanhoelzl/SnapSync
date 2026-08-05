## MODIFIED Requirements

### Requirement: No autonomous library reads under a limited grant

While permission is `LIMITED`, no autonomous trigger SHALL read the photo library: the foreground
upload pump kick, the upload half of the silent-push fan-out, and the status refresh's gallery walk
SHALL all skip their `PHAsset`-fetching work. Everything that does not touch `PHAsset` SHALL keep
running on those same triggers — config reload, HTTP reconcile, download planning and imports,
ledger-count polling, attestation refresh.

**The justification is corrected, the behaviour is not.** This requirement was recorded as *the*
load-bearing alert rule — the claim being that autonomous fetches queue the alert while in-flow reads
are clean. On-device measurement (SE2, iOS 26.5.2 — `PROBE-FINDINGS.md` in this change's decision
record) contradicts both halves:

- ~15 autonomous library walks, four launches one second apart, against an **unchanged** library
  produced **zero** alerts; and
- one camera capture followed by the **sanctioned** change-observer read produced an alert, queued,
  surfacing on the bare home screen after the app was killed.

The rule that fits that evidence **and** the original probe's is: under `.limited` a `PHAsset` fetch
surfaces the alert **iff the library gained content outside the app's selection since the app last
looked**, armed **once per change** rather than once per fetch, and merely surfaced by the first fetch
after it. App-created assets join the selection at creation, so they never arm it. The original probe's
two storms — the initial grant picker, and taking a photo while the app re-fetched — were both periods
of out-of-scope change; its five clean creations were fetch-free.

It follows that **read volume does not change the alert count**, so this discipline SHALL NOT be
justified as alert suppression. It is retained on its own merits, which are real: under a partial grant
the selection *is* the scope, so reading it rather than walking the library is the correct source, and it
removes per-foreground `PHAsset` round-trips that buy nothing. Reads under `LIMITED` therefore continue
to happen at exactly two moments and no others:

- **one baseline read on a cold foreground launch** (opening the app is a user action), establishing
  the status total and catching any backlog (selection changes made while the app was dead); and
- **on a selection-change emission** (next requirement).

**The residual SHALL be stated rather than implied**: because the change observer fires for changes the
app cannot see, and the app's response to an emission is itself a fetch, **every photo a member takes
under a partial grant arms one alert that the app's next read surfaces**. During an event that is one
system prompt per photo taken, and no read strategy avoids it — the alert is armed by the OS on a change
the app never observes. The only mitigation available is the offered upgrade to full access.

Expiry trigger: re-measure on the next iOS major, or if Apple documents the automatic alert's trigger.
Caveats on the evidence: one device, one OS point release, **n = 1** out-of-scope change.

#### Scenario: Foreground entry under limited does not walk the library
- **WHEN** the app enters the foreground with permission `LIMITED` (not a cold launch)
- **THEN** no `PHAsset` fetch occurs; the reconcile, ledger-count poll, and attestation refresh still run

#### Scenario: A silent push under limited wakes only the download arm
- **WHEN** a silent push arrives while permission is `LIMITED`
- **THEN** the download receiver runs; the upload receiver performs no library read

#### Scenario: The cold-launch baseline catches offline selection changes
- **WHEN** the selection was widened while the app was not running, and the app is then cold-launched
  to the foreground
- **THEN** the single baseline read discovers the new photos and they are enqueued

#### Scenario: Reads against an unchanged library are alert-free
- **WHEN** the app fetches repeatedly under a partial grant and the library has gained nothing outside
  the selection since its last read
- **THEN** no limited-access alert is queued, however many times it reads

#### Scenario: A photo taken elsewhere costs one prompt, not one per read
- **WHEN** the member captures a photo outside the app's selection and the app subsequently reads the
  library any number of times
- **THEN** exactly one limited-access alert is queued, surfaced by the first of those reads
