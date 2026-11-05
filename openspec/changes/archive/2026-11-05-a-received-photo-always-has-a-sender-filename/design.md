# Design

## Context

See proposal.md for why. The receiving side gets a sender filename from two inputs, and both reach the marking:

- **The event's union**, read by `EventUnion` (`:domain:data`). Each served resource becomes a `UnionResource` whose
  `originalFilename` is a plain `String`. `ReceivingPlans` writes it into `downloads.db` (`downloadResource`).
- **A staged download**, read back from `downloads.db` by `stagedResources` (`RowStatements.kt`). It becomes a
  `StagedResource`, and `LibraryImport` hands its name to `ReceivedPhotoName.mark` (`LibraryImport.kt:230`).

Today `mark("", key, ref)` names the photo `snapsync-<token>.<ext>`, taking the extension from the storage key.
Decision record `changes/archive/2026-10-01-mark-received-photos`, design "Empty sender name", made that choice for a
client whose manifest rows were seeded from a stored-file listing and never enriched.

What was verified on 2026-11-05 (main `cc2195d`):

- **No new empty name can be stored.** Both upload routes declare `filename` with `minLength: 1`
  (`api/openapi.yaml`, upload and the event-less upload) and refuse an empty one with `400 missing filename`. The
  only writes to `resources` are the upload paths (`db.ts` `recordResourceStatement`, `startUpload`, the landing
  update).
- **Earlier, one path could store an empty name.** Before migration 0010, the legacy v1 manifest publish upserted
  `resources.filename` from the manifest, and `routes/manifest.ts` accepted `""`. Migration 0010 copied rows as they
  were, and 0015 marked every existing row landed.
- **No such row is left.** A read-only count against the deployed store (read-only token, 2026-11-05) found 0 rows
  with an empty filename among 749, landed or not. It also found 0 rows still under the pre-0010 `files/devices/`
  layout, so every event from that time is gone. Since no path can write one, the count stays 0.
- **The sender never sends an empty name.** `UploadRequests` replaces a blank name with the upload key before it
  builds the request.

## Goals / Non-Goals

**Goals:**

- No code on the receiving side makes a name for a photo whose sender named nothing.
- An empty sender filename is handled as `docs/patterns.md` §15 says an outside input must be: as a value, refused
  at the boundary where it enters, never with an `InvalidProgramState`.

**Non-Goals:**

- The read side of the marks (`tokenOf`, `MarkedPhotos`): unchanged, including the start-of-name match (D4).
- The sender side's blank-name fallback in `UploadRequests` and the ledger's `originalFilename DEFAULT ''`. They
  keep an empty name off the wire and are not part of receiving. Note: that fallback names the upload with its
  storage key, an internal name. That conflicts with "never an internal name" and is left for its own change.
- The backend: it already refuses an empty name.

## Decisions

### D1. A non-empty `SenderFilename` type at the two reads

`:domain:domain` gains `SenderFilename`, a value class over a non-empty `String`. Its only constructor is a parse:
`SenderFilename.of(text): SenderFilename?`, which answers `null` for `""`. `UnionResource.originalFilename` and
`StagedResource.originalFilename` become `SenderFilename`, and `ReceivedPhotoName.mark` takes one. `mark` then has no
empty case, so its fallback branch, `stemOf`'s `original.isEmpty()` arm and the "never enriched" KDoc go.

Alternatives:

- *`mark` throws `InvalidProgramState` on `""`.* Rejected: `patterns.md` §15 rules it out for "a server's malformed
  reply, a stored row an earlier build wrote". Both inputs here are exactly that.
- *`mark` answers a refusal.* Rejected: the refusal would surface deep in the import, after the bytes were
  downloaded. Refusing at the reads stops it where the input enters, and leaves `mark` total.

### D2. An empty name in the union makes the page unreadable

`EventUnion.photo` parses each resource's filename the way it parses the photo's ids. A photo with an empty
filename yields no photo, and `photos` then answers `null`, so the read is `UnionRead.Unavailable` ("names what this
build cannot read"). That is what a photo id that is no id does today, and the KDoc of `paged` already states that
rule.

Alternative: *drop just that photo and serve the rest of the page.* Rejected: it would be a second policy for
malformed union rows next to the existing one. The position would also move past the dropped photo, so it would
never be retried. The input is unreachable (Context), so the stricter rule costs no member anything.

### D3. An empty name in a stored download settles the photo unimportable

`stagedResources` parses each row's `originalFilename`. When the next claimable photo has a resource with an empty
one, `ImportQueue` settles it the way a failed import whose bytes the library consumed is settled: through the same
guarded write as `unimportable(claim)` (`settledUnimportable`), releasing its staged bytes. It logs the photo's ref
and the reason through `Log`. It is never handed to the library, and it is not retried.

Such a row could only be one an earlier build planned from a union that served an empty name. That was possible only
before 0010, and every event from that time is gone.

Not a compatibility shim (decided with the user, 2026-11-05). It keeps nothing working for older data: it refuses at
a parse boundary, as `patterns.md` §15 requires for any stored row. So it gets no `PENDING_CLEANUPS.md` entry and no
older-install layer.

Alternatives:

- *Keep the stored read a plain `String` with a non-throwing name path for it.* Rejected: `mark` would keep an empty
  case, which is what this change removes.
- *Defer the photo.* Rejected: a deferred photo is retried on every pass, but nothing could ever make its name
  non-empty.

### D4. `tokenOf` keeps its start-of-name match

The `^` alternative of `markPattern` matches the bare `snapsync-<token>.<ext>` form. Earlier builds wrote that form
into members' libraries, and a reinstalled app recognises its received photos by those marks. Library photos never
age out, so this read is permanent. It is not a shim with an unlock condition. The KDoc says so, so that removing the
write does not read as an invitation to remove the read. `ReceivedPhotoNameTest` keeps a golden-form test of that
read, fed a literal name instead of `mark("")`.

### D5. The requirement is renamed to drop its scenario

OpenSpec 1.13.2 does not apply a MODIFIED block that omits a scenario the current requirement has: `validate` reports
it, and `archive` throws. It also refuses one name in two operations, so REMOVED plus ADDED under the same name is
out. The delta therefore REMOVES "Received photos keep full fidelity" and ADDS "Received photos arrive in full
fidelity", with the same text and the nine remaining scenarios (decided with the user, 2026-11-05).

Every `@Verifies(requirement = "Received photos keep full fidelity")` moves to the new name (6 sites in 3 test files),
and so does the cite in `docs/testing.md`. The archived decision records keep the old name; they are history.

Rejected: *edit the main spec by hand under `skip_specs`.* It is smaller, but the contract would change outside the
CLI's delta and archive guard.

## Risks / Trade-offs

- [Archive appends an ADDED requirement at the end of the spec, so the renamed requirement would leave its place
  among the receiving requirements] → After archiving, move the block back to where the old one stood. That is
  placement only; check the move with a diff that shows no text change.

- [A union page that serves an empty name blocks that event's receiving for this phone] → No path can produce one
  (Context): the wire validator generated from `api/openapi.yaml` enforces `minLength: 1`. The existing api test covered only
  the event-less route's status; task 1.1 extends it to both routes, the body and the absence of a row. Should one ever appear, the read fails loudly with
  "names what this build cannot read" in the log instead of naming a photo from nothing.
- [A stored download with an empty name never arrives] → Only a pre-0010 row could hold one, and its event is gone.
  The same row today is named `snapsync-<token>.<ext>`, so the only difference is one unreachable photo.
- [The mock cannot serve an empty name, so neither refusal has an integration test] → That is correct under the
  lever rule: a mock state must be one the real system can reach. Both refusals are covered by unit tests in
  `:test:data` (`EventUnionTest`, `ImportQueueTest`).

## Migration Plan

None: no stored format, wire or schema changes. Ships as one PR, label `internal`.
