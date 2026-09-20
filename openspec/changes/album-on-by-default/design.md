## Context

`event-album` gives a membership an album in the system Photos library named after the event, into
which both the photos the device contributes and the photos it receives are mirrored. Its join-surface
requirement makes the affordance a standalone checkbox below both participation switches, and states
it "SHALL **default off** (opt-in)". The decision record
(`changes/archive/2026-07-08-add-event-album`) lists the default under settled questions —
"default (unchecked)" — with no stated rationale beyond the listing.

Everything behind the checkbox is already built and unchanged by this proposal: `EventConfig`
carries `saveToAlbum`, `AlbumCoordinator.ensureAlbum` gates on it, the upload cycle places own
photos before creating their jobs, the importer adds downloaded ones inside its commit, the
`eventId → albumLocalId` map survives leave, and `reconfigure-membership` flips the choice
forward-only after join.

The join surface's seed lives in exactly one value: `RangeForm.saveToAlbum`, beside `shareOn` and
`receiveOn`, which already default `true`. Both the guest path and the creator path reach it — a
mint routes into the same gate after minting.

## Goals / Non-Goals

**Goals:**

- A member who joins an event without touching the album row gets the album.
- The choice remains genuinely declinable, at join and afterwards.
- No member who already joined is disturbed.
- The change is confined to a default: no mechanism, persisted shape, or copy moves with it.

**Non-Goals:**

- Removing the choice, or making the album unconditional.
- Retroactively gathering photos already synced by an existing membership.
- Changing the headless `autoJoin` path's behaviour.
- Reworking the row's placement, rendering, note, or accessibility contract.

## Decisions

**The default flips; the control does not.** The alternative considered was removing the choice
entirely — if grouping is the point, why ask? Rejected: the album writes into the member's own
Photos library, and a member who does not want an extra album should not have to delete one after
the fact. Keeping the checkbox also keeps the change to a seed, leaving the coordinator's guard, the
cycle's placement stage, the importer lookup and the `event-link` override exactly as they are. A
second alternative — default on but demote the row out of the join gate into reconfigure only —
was rejected because it would hide a choice at the one moment it is cheapest to make.

**The headless `autoJoin` path keeps its album default off, deliberately.** Its sibling defaults are
mirrors of the surface seeds (cutoff = the loaded `startsAt`, which is the Event-start preset;
direction = `Both`, which is both switches on), so the obvious move is to flip this one too and keep
the mirror exact. Rejected: `autoJoin` is a dev/test path whose value is that a headless launch does
the minimal, side-effect-free thing, and the event link already carries an explicit
`saveToAlbum` override for exercising placement without a tap. The cost is that the mirror is no
longer exact, which is a real readability loss — so the divergence is stated in the spec rather than
left for a reader to infer from two numbers that no longer match. `join-event`'s autoJoin
requirement is not edited; the statement belongs in `event-album`, beside the default it explains.

**Existing memberships are untouched; no migration.** The alternative was flipping every persisted
`false` on upgrade, on the theory that nobody chose it. Rejected on two grounds. First, a stored
`false` genuinely cannot distinguish "never thought about it" from "chose off", because both
produced the same byte — so the migration would overwrite real decisions to rescue non-decisions.
Second, and worse, the toggle is forward-only by contract: an upgraded membership would get an album
holding only what it syncs *after* the flip, which is a half-album that looks like data loss rather
than a feature. Reconfigure already offers the switch to anyone who wants it, on the same
forward-only terms they would get from a migration, but chosen rather than imposed.

**Copy is unchanged.** The note is already adaptive over both switches. What changes is which
sentence a guest reads first: the on-state feed-naming sentence now introduces the row, and "No
album is created." appears only after a deliberate uncheck — which is precisely when it informs. A
label reword ("Collect in an album", "Event album") was considered on the grounds that "Create an
album" describes an action rather than a state; rejected as churn against a string the tests, the
screenshots and the reconfigure surface all share, for a distinction a guest will not feel.

**The forge's `joining` preset builds the real gate from the real default**, so the committed
marketing raw may move. `joining` is normally byte-identical between capture runs (only `create`
re-diffs, in the region that renders the wall clock), so any diff there IS this change and is not
noise to be dismissed. The screenshots are therefore dispatched, diffed and eyeballed as part of
this change, and committed in the same PR if and only if the raw actually moved — the app store
listing and the marketing site both derive from these raws, and nothing regenerates them.

## Risks / Trade-offs

- **A member gets an album they did not ask for** → It is created in their own library, is empty
  until something syncs, is deletable, and a user-deleted album is never recreated by a passive
  cycle. The row is visible on the gate with a note stating exactly what will be collected, and
  declining is one tap.
- **The headless and interactive defaults now disagree** → A developer reading `autoJoin`'s
  defaults could assume they still mirror the gate. Mitigated by stating the divergence, and its
  reason, in the `event-album` requirement that owns the default.
- **The marketing screenshot silently drifts** → `joining` moving is exactly the signal the
  capture process already watches for, so the drift is detectable; the mitigation is to run the
  capture in this change rather than assuming the row sits below the fold.
- **Empty albums accumulate for members who join and never sync** → Already possible and already
  accepted by the capability ("A membership that never syncs a photo MAY therefore have an empty
  album; this is acceptable"); this change only makes it more common.
