# Design

## Context

See proposal.md — Why. The relevant mechanics today:

- **Echo suppression** keeps received photos out of the member's contribution: the download store's created local ids
  (`suppressedLocalIds`, across every event — a switch keeps them) feed the policy's `NotEcho` rule, read by
  `CycleGateRead` in **both** uploader processes (the extension opens the download store read-only).
- **Recognition after a reinstall** is `ReceivedPhotoAdoption`: it reads the joined event's whole union, matches the
  SnapSync marks of the library photos it can see (`MarkedPhotoLookup`) and records each match as an import — which
  is what puts it into echo suppression. It runs on join (`adopt`) and before imports and on a usable grant
  (`ensureAdopted`), **once per process per membership** (`settledFor`, in memory).
- **Gap 1:** an unreachable union *settles* the pass (`adoptOrFail` returns `true` after "union unavailable"), so
  sharing starts with nothing recognised. Nothing persists whether a pass ever succeeded, and the extension never
  runs the pass at all — it relies on the app having done it.
- **Gap 2:** under a partial grant a selection change runs `TailComposition.onSelectionChanged` → the snapshot-fed
  walk and manifest publish → the tail; no recognition pass runs, and `settledFor` would skip one anyway. The selection
  snapshot already carries each photo's resources, hence its filename and mark.
- **Android folder rule:** `DefaultGallery.CANDIDATE_SQL` excludes `DCIM/SnapSync/%` from the candidate reads
  (`assets`, the selection snapshot, `albums`), `SQL` keeps it for the by-id reads and `libraryAssets`
  (decision D8, `changes/archive/2026-09-30-android-event-album`).

## Goals / Non-Goals

**Goals:**
- Sharing (upload, manifest, status total) in **every** process waits for a successful recognition of the joined
  membership.
- Recognition happens before a newly visible marked photo can be admitted.
- One candidate scope on Android, the same reading as iPhone's.

**Non-Goals:**
- Recognising photos received in a *different* event after a reinstall (stated gap; see Decisions).
- Recognising renamed photos, or photos received by an unmarking version.
- Any backend or API change; any change to receiving other than "nothing before recognition".

## Decisions

### D1. Recognition is a persisted, per-membership fact every sharing path requires

The download store records `recognised(eventId)` after a pass that **read the union** (a library read under no
usable grant still answers "not yet", as today). `CycleGateRead` withholds the cycle (`UploadAdmission.Withheld`:
creates nothing, publishes nothing) while the joined event is not recognised — in the app and in the extension alike,
since both read the download store already. A leave or switch drops the record with the membership's other
event-scoped rows (`event-scoped-local-state`); a rejoin recognises again.

- *Rejected: keep `settledFor` in memory and gate only the app.* The extension and every later process would know
  nothing; the extension is exactly the uploader that runs while the app is not.
- *Rejected: hold the join itself until the union is read.* Joining needs the backend anyway, but the union read is a
  separate call that can fail after a successful join; failing the join for it would turn a transient into a visible
  join error, while holding sharing keeps the member joined and converges silently.

### D2. An unreachable union no longer settles the pass

`adoptOrFail` answers "not settled" when the union cannot be read, so the next opportunity asks again: the next
foreground, background run (`ensureAdopted` on the tail's import step and on a usable grant), and the network
returning. Imports already wait for the pass (`importsReady`), so receiving holds with sharing. The cost noted in the
code ("retrying it would hold every import behind the network") is now the intended behaviour: nothing is received
or shared before recognition.

### D3. Newly visible marked photos are recognised before the selection change's cycle

`onSelectionChanged` first looks for photos in the new snapshot that carry a SnapSync mark and are not suppressed — a
read of the snapshot already in memory, no platform call. Only if there are any, it runs a recognition pass over them
(a fresh union read) before the walk and publish; if the union cannot be read, the change's cycle is withheld for
those photos' sake (D1's gate, with the event marked not recognised until the pass succeeds), and the next
opportunity retries.

- *Rejected: "a marked photo is never shared".* It would also close the different-event case, but under a full grant
  the mark lives on the photo's resources, the expensive read on iPhone (~110 ms per asset), while the status total
  counts from facts only — keeping upload, manifest and total one set would need resource reads for every candidate.
  The user chose to state the different-event case as a gap instead.
- *Rejected: re-run the whole pass on every selection change.* A union read per change, for nothing in the common case.
- Consequence: a selected marked photo the union does not know (received in another event — the stated gap) stays
  unrecorded, so every selection change while it is selected reads the union again before sharing resumes. Selection
  changes are member actions, so the cost is one read per change, not per cycle.

### D4. Android's candidate scope is the whole default gallery

`DefaultGallery` keeps one fragment, `DCIM/%`, for every read; `CANDIDATE_SQL` and the `candidates` flag go. Received
photos in the album folder are kept out by echo suppression, as everywhere. `albums()` lists the event album folders
too; the denylist matches messenger and screen-capture names, so an event album only matches one if the event is named
like one — and then only its received photos (already suppressed) and photos the member put there are affected.

- *Rejected: keep the folder rule as extra protection.* It is what forces an app-albums scope onto the gallery port
  (phase 2c) and it protects only one folder on one platform.

## Risks / Trade-offs

- [A rejoin while the backend is briefly unreachable shares nothing until it is reachable] → the status line stays on
  the neutral loading line ("Up to date is never claimed before the app has looked", capability `sync-status`); the
  network-returns path and every wake retry. Accepted: correctness over latency.
- [An existing membership has no `recognised` record after the update, so sharing pauses] → until the first pass
  under the new rules, which the app's next start or wake runs (every foreground, background wake and import drain
  asks `ensureAdopted`). Deliberately no backfill: the joined event lives in the config file, not the store, so a SQL
  migration cannot name it; and a backfill would inherit passes that settled on an unreachable union — the gap this
  change closes. Accepted cost: a short sharing pause after the update.
- [The extension now withholds until the app recognised] → after a reinstall the app always runs first (a rejoin is
  an app action), so this only delays the extension's first cycle until the pass succeeded.
- [A member's own photo moved into the event album is shared] → intended (photo-sharing delta), stated in the spec.
- [Different-event photos after a reinstall may be shared] → stated gap on both platforms (receiving-photos delta).

## Migration Plan

- Download-store schema migration (`7.sqm`): add the `recognised` table, empty. Covered by `DatabasesContract`'s
  migration clauses (from the oldest supported version). No backfill and so no compatibility shim (see Risks).
- Rollback: an older build cannot open a newer store's schema the way it does today (the extension pauses on an
  older schema; the app migrates forward only), as with every store migration.
