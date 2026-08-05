## Why

The `limited-photo-access` spec's Purpose rests on three measured platform facts. **The first one is
wrong**, and a device probe run on 2026-08-05 (SE2, iOS 26.5.2 — full record in `PROBE-FINDINGS.md`)
contradicts both of its halves:

> *"autonomous `PHAsset` reads under a partial grant queue iOS's automatic limited-access alert into an
> app-killing storm that survives process death — while **in-flow** reads (a cold-launch baseline, a
> change-observer callback) **are clean**; hence the read discipline."*

| the claim | what was measured |
|---|---|
| autonomous reads storm | ~15 autonomous library walks, four launches one second apart, **no library change** → **zero alerts** |
| in-flow reads are clean | one camera capture → `PHPhotoLibraryChangeObserver` → the **sanctioned** snapshot re-read → **alert**, queued, surfaced on the bare home screen after SIGKILL |

The rule that fits **both** datasets — the original probe's and this one:

> Under `.limited`, a `PHAsset` fetch surfaces the limited-access alert **iff the library gained content
> outside the app's selection since the app last looked**. It is armed **once per change**, not once per
> fetch, and the fetch merely surfaces it. App-created assets join the selection at creation, so they
> never arm it.

This explains the original findings rather than contradicting them: both of its storms — the initial
grant picker and taking a photo while the app re-fetched — were periods of out-of-scope change, and its
five clean creations were fetch-free.

Two consequences follow, and both matter.

**The read discipline's forcing proof does not force.** If the alert is armed once per change and merely
surfaced by the first fetch after it, then reading once or fifty times yields the same count — so
`PermissionAwareCandidateSource`, `SelectionScopedTransfer` and the snapshot cell do not reduce the alert
count at all. They have real merits (correct scope under a partial grant, far fewer XPC round-trips), but
those are not the merits recorded. The *"Necessity claims carry forcing proofs"* law exists for exactly
this.

**Partial access is still alert-prone in ordinary use.** The observer fires for changes the app cannot
see, and the app's response to an emission *is* a fetch — so **every photo a limited member takes arms
one alert their next read surfaces**. During an event, that is one prompt per photo taken. The spec calls
a partial grant "a first-class working state"; that claim is overstated as things stand.

No field user is affected today — all seven diagnostic dumps received so far are `GRANTED`, and nobody
has entered `LIMITED` in production.

## What Changes

- **Correct the spec Purpose's fact 1** to the measured rule, with this change as its decision record.
- **Correct CLAUDE.md fact ①**, which states the superseded rule and actively misleads: it drove a
  larger-than-necessary design and a six-site scope sweep in `fix-duplicate-import-on-restart` before
  the probe retired it.
- **Keep the read discipline**, re-justified on its real merits rather than on alert suppression. This
  change does **not** propose removing or reshaping it.
- **State the residual honestly** in the spec: one alert per out-of-scope library change, mitigated only
  by the existing "Allow full access" affordance.
- Supersede — never rewrite — the archived `2026-07-20-accept-limited-photo-access` findings; the
  archive stays as the history of what was believed and why.

## Open questions, deliberately not answered here

- **Is one prompt per photo acceptable during an event?** If not, the fix is not a read-discipline tweak
  — the alert is armed by the OS on a change the app never sees, so no read strategy avoids it. That is a
  product question about whether `LIMITED` is a supported grant for *contributing* members.
- Whether **collection** fetches (`PHAssetCollection.fetchAssetCollections…`) obey the same rule; the
  probe measured `PHAsset` only.
- Whether iOS 18–26.0 behaves the same; only 26.5.2 was measured, against the original probe's 26.5.

## Caveats on the evidence

One device, one OS point release, **n = 1** out-of-scope change. The negative results are strong (a clean
detector was proven by reproducing the storm immediately afterwards under the changed condition), but
"one alert per change" is not established for larger n.
