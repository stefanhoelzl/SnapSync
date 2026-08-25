## MODIFIED Requirements

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL record that an asset has left the library by **marking** its ledger rows absent, and
SHALL NOT delete them (capability `sync-ledger`). A row states that a resource's bytes are on the
backend, and nothing on the device can make that false — no local action deletes an uploaded object, and
reclamation belongs entirely to the nightly sweep (capability `scheduled-cleanup`). Keeping the row is
also what stops a restored asset re-uploading. The ledger writes preserve the single-writer invariant.

Marking keeps the ledger honest about what still exists on device and, critically, clears a row left
non-`COMPLETED` by an asset deleted mid-upload — which would otherwise keep `pending > 0` forever and
hold the extension in the perpetual `processing` re-invocation loop (see "Cap-aware creation and
tri-state processing result"): a marked row counts toward neither `pending` nor `completed`. Because the
device manifest is projected from those same rows and the projection excludes marked ones, one mark also
makes the next projected `device.json` stop listing the departed asset — there is no second structure to
keep in step. No remote object is deleted; the one-way model is unchanged.

- **Incremental (every cycle):** when deriving the changed set from `fetchPersistentChanges(since:)`, the
  extension SHALL also collect each change record's `deletedLocalIdentifiers()` and, for each removed
  `localIdentifier` `L` (normalized `/`→`_` to match the stored `assetId`), call `markAbsent(L)` so all
  of that asset's resource rows are flagged.
- **There SHALL be no reconcile backstop.** A full enumeration SHALL NOT prune or mark rows for assets it
  did not return. The enumeration is narrowed by the membership's own selection policy, so "not returned"
  conflates *gone from the library* with *outside the current capture window* — and the backstop was
  supplied the policy-**admitted** set, so raising a capture cutoff discarded the `COMPLETED` rows of
  photos that were still present and still uploaded. Those rows are exactly what suppresses re-upload, so
  the narrowing became irreversible, and a membership turned download-only admits nothing at all and would
  have lost the event's rows entirely.

The change feed's removal signal is therefore the **only** deletion input. A deletion it never reported —
because the persistent-change token had expired — leaves the asset listed for the event's remaining life;
its bytes are still on the backend, so a member still downloads it and the photo simply stays in the
event, exactly as it does when a member leaves. Deletion-tracking is not exhaustive, and does not need to
be.

A re-added asset (e.g. recovered from "Recently Deleted") SHALL NOT re-upload: its rows were marked, not
removed, so discovery still finds the `COMPLETED` entry, the engine returns no work, and the idempotent
key is never re-sent. iOS keeps a deleted photo recoverable for 30 days — the same order as an event's
whole life — so this is an ordinary sequence rather than an exotic one. No `DELETED` state is introduced
and the upload decision is unchanged.

#### Scenario: Removed asset's rows are marked incrementally
- **WHEN** `fetchPersistentChanges(since:)` reports `deletedLocalIdentifiers` containing asset `L`,
  and the ledger holds rows for `L`'s resources
- **THEN** the extension calls `markAbsent(L)`, so `L` contributes to neither `pending` nor `completed`
  and the next projected `device.json` omits it — while its rows remain readable

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a non-`COMPLETED` ledger row, and a
  later cycle's change feed reports that asset as removed
- **THEN** the extension marks the row, the ledger reaches no pending rows, and `process()` can
  return `completed` instead of looping on `processing`

#### Scenario: A full enumeration reconciles nothing away
- **WHEN** a full enumeration completes with no `limitExceeded` and the ledger holds rows for an
  asset the enumeration did not return
- **THEN** those rows are neither removed nor marked — the enumeration is policy-narrowed, so an asset's
  absence from it is not evidence that the asset left the library

#### Scenario: A narrowed scope costs no ledger rows
- **WHEN** the membership's capture cutoff is raised past an already-uploaded asset and a full
  enumeration runs
- **THEN** that asset's `COMPLETED` rows survive, so lowering the cutoff again re-lists it with no byte
  re-uploaded

#### Scenario: Re-added asset does not re-upload
- **WHEN** an asset whose rows were marked absent reappears in the library (e.g. recovered from
  "Recently Deleted")
- **THEN** discovery finds its `COMPLETED` ledger entry, the engine returns no work, no job is created,
  and the next projection lists it again

### Requirement: Discovery suppresses downloaded assets

The upload cycle's discovery SHALL consult the download store's suppression projection (the set of
`createdLocalId`s of foreign assets this device downloaded and imported) and SHALL drop every
discovered resource whose `assetId` — **normalized `'/'→'_'` to match the stored `createdLocalId`
form** — is in that set **before** engine fan-out (no upload job created). This prevents the
download→import→re-upload echo: an imported foreign asset gets a fresh local `localIdentifier` that
discovery would otherwise treat as a new local asset and upload back. The normalization SHALL be the
**same** transform the shared gallery enumeration applies when deriving the upload key, so the two sides
meet byte-for-byte. The suppression read SHALL be read-only and cross-process (the extension reads the
app-written store over WAL). The filter SHALL live in the platform-free upload-cycle core (an injected
suppression port), not in untested platform wiring, so it is exercised in `commonTest`.

Echo suppression is an id set supplied per cycle, so it is one of the rules the **manifest projection**
re-applies (capability `device-manifest`). A stale row for an asset that has since become an echo is
therefore kept out of the manifest without being removed from the ledger — the row is still a true
statement that those bytes are on the backend.

#### Scenario: A downloaded-then-imported asset is never re-uploaded

- **WHEN** discovery encounters a resource whose `assetId` (normalized `'/'→'_'`) is in the
  suppression set
- **THEN** no upload job is created for it

#### Scenario: A suppressed asset's stale row is not listed

- **WHEN** the ledger holds a `COMPLETED` row for an asset that is now in the suppression set
- **THEN** the row is retained and the manifest projection excludes it, so the asset is neither
  re-uploaded nor offered to other members

#### Scenario: Suppression is consulted before fan-out

- **WHEN** a discovery cycle runs
- **THEN** suppressed assets are removed from the discovered set before the engine is asked to create
  any upload job
