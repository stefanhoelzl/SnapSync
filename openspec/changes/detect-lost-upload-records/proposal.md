## Why

The device's ledger is the only thing that decides whether a photo still needs uploading, and it records
a **belief**. When that belief is wrong in one particular direction — the row says the bytes are on the
backend and they are not — the photo is invisible to every other member and nothing ever rechecks. That
is the failure `CLAUDE.md` singles out: "an event photo that silently fails to upload is invisible and
unfixable."

Today the only thing that corrects it is a **rejoin**. The reconcile is gated on a `joinedEventId` marker
mismatch, so on an ordinary cycle it returns immediately without asking the backend anything.

The failure has a named, documented cause. `database` accepts a **10-second maximum data-loss window on
primary failover** and says an acknowledged write lost to it "SHALL be repaired by the next manifest
publish where the publishing device still asserts the fact". That repair worked under v1, whose manifest
publish wrote `resources` rows. **Under v2 it does not** — the v2 manifest writes no resource row at all,
as `api/src/app.ts` states at the byte route: "v2's manifest writes no resource row at all, so nothing
would repair it." A second, tier-specific cause is structural: on iOS ≥26.1 the OS performs the upload and
the job it returns carries no HTTP status (`PHAssetResourceUploadJob` has no `statusCode`), so the device
cannot distinguish a stored `201` from a `502`.

What we do **not** have is a rate. A one-off query against production found **zero** discrepancies across
19 devices and ~1,000 photos — a clean result, on a comparison with no confound for those builds, but one
that cannot see past the 30-day event sweep, past a rejoin that already repaired the divergence, or past a
retraction that removed the declaration.

So this change ships the **detector, not the repair**. Every risk in correcting the ledger — racing the
extension's writes, moving the progress screen backwards, demote/re-upload churn, deciding what a
corrected row should say — comes from *writing*. A read-only check has none of them, changes no upload
behaviour, and answers the question the repair would otherwise be built on faith.

## What Changes

- **The app asks the backend what it holds, on foreground.** A read-only pass fetches the existing
  per-device listing and compares it against the ledger. It is placed in the `Foreground` flow beside the
  download arm's `downloadController.reconcile(…)`, which already does exactly this for the other half of
  the product — the upload arm is the one that never asks.

- **Foreground is the trigger, deliberately.** A member opens the app when someone has told them their
  photos are missing, so the check lands where a complaint is. It also runs in the **app** process, which
  has no ~3-minute extension runtime cap, and it works on **both** tiers — on iOS ≥26.1 the upload arm's
  `onForeground` is `Unit` by design, so today a foreground does nothing at all for uploads there.

- **It compares the admitted, believed-landed set** — rows the membership's current policy admits *and*
  the ledger records as landed — against the listing. That filter is what keeps the nightly sweep from
  reading as a failure: the sweep collects only *unreferenced* bytes, and a policy-admitted asset is
  declared, hence referenced.

- **A disagreement is reported at `Error`**, so it reaches Bugsink through `crash-reporting` as an event
  rather than a breadcrumb. Every UUID-shaped token is scrubbed on the way out, which forces the report to
  be **counts** — which is all the question needs. The two directions are counted separately: rows
  believed landed that the listing lacks (the failure) and listed resources the ledger does not know (a
  ledger-durability signal that costs a re-upload, not a photo).

- **It writes nothing.** No ledger write, no upload behaviour change, no backend change, no new endpoint.
  Reverting is deleting one call.

- **It skips when the answer is already known** — no configured event, or a `joinedEventId` marker
  mismatch, where a rejoin is pending and the divergence is expected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-rejoin-reconciliation`: the per-device listing seam gains a second, **read-only** consumer,
  invoked from the app process on foreground rather than from the upload tier's cycle. The capability
  gains a requirement for that check — its trigger, its comparison set, its preconditions, and that it
  SHALL NOT write. The existing marker-gated seed is untouched.

## Impact

- `domain/flow/Foreground.kt` — one step, alongside the download reconcile; `architecture/flows/Foreground.md`
  regenerates.
- `domain/compose/SnapSyncApp.kt` — `AppPorts` gains the device-listing seam. On iOS 18–26.0 the app
  process already holds it (`uploadCore` composes there); on ≥26.1 it does not, and binds the existing
  `HttpDeviceFilesSource` beside the join/manifest/union clients the app already builds.
- `domain/feature/upload/` — the comparison itself, platform-free and unit-testable.
- No `api/` change. No wire-format change. No migration. No ledger write.
- Depends on `stop-uploading-excluded-photos`: the comparison needs the same row-level admission that
  change introduces, and without it the check would report swept residue as failure.
- Tests: `:test:world` already serves the v2 listing in identity terms **and** models the offline `502`,
  so the whole check is drivable in `:test:integration` — including the sweep-shaped case (bytes gone,
  policy no longer admits) that must **not** report.
- Bugsink is where the answer arrives; read it with `/bugsink`.
- Changelog label: `internal` — no customer-visible behaviour changes.
