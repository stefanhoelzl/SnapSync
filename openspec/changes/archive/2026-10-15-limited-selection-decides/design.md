# Design — under limited access, the selection decides what a photo is

## Context

See proposal.md for why. What the code does today:

- **One derivation builds every policy.** `selectionRulesFor` (`domain/model` `SelectionPolicy.kt`) builds the rule
  list for all of these:
  - both uploaders
  - the status total `N`
  - the join preview
  - the album gather
  - the device manifest, through the cycle

  It always emits: `CaptureAfter`, `CaptureBefore`, `ExcludeScreenshots`, `ExcludeScreenRecordings`, `MinImageArea`
  and `MinVideoArea`. When their sets are non-empty it adds `NotEcho` and `NotInDenylistedAlbum`. The derivation does
  not know the grant.
- **Today the grant enters the policy in one place only:** `denylistedAlbumMembers` (`AlbumExclusions.kt`) answers the empty set unless
  the grant is `GRANTED`. That is why a selected WhatsApp-folder photo is already shared on Android.
- **Discovery under a partial grant never walks the library.** It is fed the in-memory selection snapshot
  (`LatestSelection.scopeUnder(grant)`, `SelectionScopedDiscovery`). Under a full grant it walks the library.
- **On Android the default gallery is a query scope.** `DefaultGallery.SQL` (`relative_path LIKE 'DCIM/%'`) is
  applied by `AndroidGalleryReader` to every read. Under a partial grant MediaStore itself narrows the result to the
  selection plus the rows this app owns, which are its imports.
- **Echo suppression is not scoped to one event.** It reads `suppressedLocalIds`, every imported row with a created
  id, for any event. Imported rows are never pruned, and recognition after a reinstall is `receiving-photos`' own
  requirement. So received photos that joined the selection are already kept out.

## Goals / Non-Goals

**Goals:**
- One switch, in one place, between the full-library policy and the selection policy. Every consumer follows it by
  construction.
- No pass may ever combine **full-library candidates** with the **selection policy**. That combination uploads
  screenshots, sub-floor media and, on Android, non-DCIM files from the whole library. An upload is a publish and
  cannot be recalled from members who already received it.

**Non-Goals:**
- Making the selection event-scoped, for example "only photos added since joining". Neither OS exposes when a photo
  joined the selection, and a diff the app keeps itself does not survive a reinstall or a grant change.
- Any change under full access, in the extension (it creates only under a full grant), or to the backend, the wire or
  storage.

## Decisions

### D1 — The read says where its photos came from, and that picks the rules

Every consumer already passes the policy *into* the read that produces its candidates, and that read already chooses
between the selection snapshot and a library walk:
- `PermissionAwareCandidateSource` for the status total and the join preview
- `SelectionScopedDiscovery` for the upload cycle

So the read also states **where its candidates came from**: `CandidateScope.Library` or `CandidateScope.Selection`, on
`CandidateRead.Readable` and on `Discovery`. The admission (`EventPhotoSet`) judges them with `policy.over(scope)`.
Under `Selection` that drops every rule that judges **what a photo is**:
- `ExcludeScreenshots` and `ExcludeScreenRecordings`
- `MinImageArea` and `MinVideoArea`
- `NotInDenylistedAlbum`

It keeps `CaptureAfter`, `CaptureBefore`, `NotEcho` and `DenyAll`.

Each `SelectionRule` declares which kind it is (`judgesWhatItIs`, abstract), so a rule added later has to choose.

Only the two snapshot paths label a read `Selection`. Every platform walk (`GalleryDiscovery`, `GalleryCandidateSource`)
labels its read `Library`. A wrong label could only err toward the full rules, which under-admits for one pass and is
healed by the next. The dangerous pairing, a library walk judged without the "what it is" rules, cannot be built,
because no single grant read has to agree with another.

- **Rejected: a grant parameter on `selectionRulesFor`** (the first draft of this design). It needs every consumer to
  read the grant once and hand that value to both its candidate source and the derivation. That is safe only as long as
  each caller is careful.
- **Rejected: a separate `SelectionPolicy` for limited access.** It would re-open the drift this type was created to
  end, where the byte filter and `N` disagree.
- **Rejected: adapters marking selected assets as "never a screenshot".** That lies in the facts, and the facts also
  feed full-grant consumers.

`selectionRulesFor` is unchanged and still emits every rule. The `denylistedAlbumMembers` grant gate stays: it keeps
the album fetch from raising iOS's permission dialog under `NOT_DETERMINED`.

### D2 — A row the manifest would list but walk + policy refuses is withdrawn by forgetting it

A ledger row carries a date and an id, not the photo's "what it is" facts. The manifest and the enqueue judge rows
through `admittedAssetIds`, which therefore admits on doubt for the screenshot and size rules. A screenshot recorded
under limited access would so stay listed after widening to full access, where the walk returns it but the policy
refuses it.

The cycle's deletion is generalised from "in-window rows whose asset the walk did not return" to **"in-window rows
whose asset walk + policy did not admit"**:
- *in-window* is the rows' own admission (`admittedAssetIds`), as today
- the comparison set is the walk's **admitted** set instead of its candidate set
- it still happens only on an authoritative walk (`fullEnumeration`)

What each kind of exclusion does with its row:
- **The row's own admission already refuses it** (range, receive-only, album, echo): the row is not in-window, so it
  is kept. It comes back without being uploaded again, exactly as today.
- **The row cannot express the refusal** (screenshot, screen recording, below the floors under a library walk): the
  row is in-window, so it is deleted. The photo is withdrawn as a deleted photo is, and if it comes back it is
  uploaded again. That is the spec's exception (`photo-sharing`, "Restoring or re-including a photo shares it
  again").
- **An absent photo:** not admitted, so it is deleted. Today's presence diff becomes the special case.

The retired retain-live reconcile pruned the ledger to the admitted set *without* the in-window guard, so a raised
cutoff discarded rows. The guard is what makes this different, and the KDoc must say so.

- **Rejected: storing the "what it is" facts on the row**, so that rows are never deleted for this. It would keep the
  upload memory, but it needs a ledger schema change and migration for a case that only arises after a grant switch.
  The user chose forgetting.
- **Rejected: a per-cycle exclusion set taken from the walk.** Manifest writes that skip the walk (a declined cycle)
  would list the screenshot again, so it would flicker in and out for the other members.

### D3 — Android: the partial grant's scope is the selection, and a grant change during the read discards it

`AndroidGalleryReader` applies `DefaultGallery.SQL` only when it reads for a full grant. For the selection scope it
queries the external collections with no path filter, and MediaStore narrows the result to the selection plus the
app's own rows (its imports, which `NotEcho` keeps out).

The residual race: the member widens to full access in Settings between the core's grant read and the query.
MediaStore would then answer the whole library to an unfiltered query. So a selection-scope read **re-checks the
grant after the query**. If it is no longer `LIMITED`, the read answers `GalleryRead.NotReadable`, which means "could
not look": it withdraws nothing and shares nothing.

- **Rejected: moving the DCIM rule into the policy as an asset fact.** It inverts the recorded law that the platform
  defines the default gallery and the policy is the one decision over it. It would also make every full-grant read
  return non-DCIM rows only for the policy to drop them.

On iPhone no equivalent exists. Under `.limited` PhotoKit cannot return anything outside the selection, and
discovery never walks under a partial grant anyway.

### D4 — A grant switch is the same re-derivation

- **Limited to full access:** a selected screenshot is present in the full walk and refused, so D2 deletes its row
  and the manifest drops it. Members who received it keep it.
- **Full to limited access** with it still selected: the snapshot read is judged without the "what it is" rules, so the
  screenshot is new work again. Its object is PUT again, idempotently, the same accepted cost
  `selection-is-the-walk` already pays when de-selected photos return under full access.

The guard "a selection the app has not yet looked at withdraws nothing" is untouched.

### D5 — The join screen's note follows the grant the join state already carries

The join state already distinguishes the grant: it decides the photo-access notice and whether a count is shown.
Presentation maps a limited grant to a second note (`share_selection_note`, English and German). The screen shows
whichever note it is told to and decides nothing.

A user who has not been asked yet sees the full-access note, because the app cannot know which answer they will
give. That is an accepted gap: the note stays true for the default answer.

## Risks / Trade-offs

- **[Existing limited-access members start sharing what was held back]** On update, an in-range screenshot or small
  image already in a member's selection begins to upload. → This is the intended reading: they selected it for
  SnapSync. Call it out in the release notes (`enhancement` label).
- **[A photo selected for another reason]** The selection belongs to SnapSync and nobody else, so every non-import in
  it was picked for SnapSync. → The range keeps a pick from another event out unless the member's range reaches it.
- **[The Android grant re-check is a second binder round-trip per selection read]** → It only affects partial-grant
  reads. The read is driven by the selection observer, not a polling loop.
- **[Unmeasured: does Android keep an item in the partial selection after it is moved between folders?]** → No spec
  scenario depends on it. Both outcomes are covered: if it stays selected it stays shared, and if not it is
  withdrawn like any de-selected photo.

## Migration Plan

App update only, with no data migration. Rollback is an app release restoring the full-library rules under
`LIMITED`. A rollback withdraws the selected-only photos on the next pass, through the same re-derivation, and
re-uploads nothing.
