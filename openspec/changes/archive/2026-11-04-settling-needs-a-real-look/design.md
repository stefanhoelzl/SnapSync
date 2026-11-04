# Design

## Context

See proposal.md, "Why". Today:

- **What sets `final`.** `ManifestPublish` stamps the manifest `final` from one fresh read of "the membership's own range
  has ended" (`MembershipProjection.freshReadShareHasEnded`). It never asks whether the walk it follows read anything,
  or when that walk began. `WalkAndPublish` publishes after every walk:
  - when the walk read nothing (`DiscoveryRead.NotReadable`), the old rows go out `final`;
  - when the walk began before the range ended and the publish comes after, the manifest also goes out `final`.
- **The end of a wake.** `ShareSettlement.due()` runs one more walk → publish when the range has ended and the last
  record is not `final`.
- **The skip record.** `DeviceManifestProducer` skips a publish whose (event, version, JSON) equals the record, and
  nothing in production clears the record.
- **The manifest version.**
  - Row inserts and deletes advance it, and so do updates of declared columns (triggers in `15.sqm`).
  - A settings change advances it too (`reconfigure`).
  - A join does not. On the join's kept path (the service could not list what it holds), the rows stay and the
    version stays.
- **The backend.**
  - It stores `final` whenever it arrives, and a later non-final reverts it.
  - A join resets the membership to `sharing` and clears its stored version.
  - "Waiting" counts members not `sharedAll` (settled + every declared role landed). "Done" adds the received
    position.

Phase 4.4c moves all of this into the layers in the same pull request. The decisions below are the behaviour that move
builds in. The class-by-class target state is in the phase's review document, not here.

## Goals / Non-Goals

**Goals:**
- A settled declaration exists only with proof of a successful look begun after the member's range ended, or for a
  policy that shares nothing.
- A rejoin always republishes, so the service learns the rejoined share's state.
- A failed look never takes back a settlement an earlier look made.

**Non-Goals:**
- The backend's rules for done and finished, and the sync-status screen's logic. Only the sync-status spec wording
  changes.
- Any wire, api or schema change.

## Decisions

### D1: Settled is evidence, minted from a look

A domain verdict, `ShareDeclaration.of(phase, look)`, answers `Settled` or `Sharing`. It is the only mint of `Settled`, and
its constructor's opt-in is pinned to that one site.

It answers `Settled` only when:
- the member's own range has ended (the share phase, judged on the membership's range and the clock, D4), and
- the look is a discovery, a read that succeeded, whose `takenAt` is after the range's end.

A `NotReadable` read is no discovery and settles nothing. A discovery begun at or before the end settles nothing
either.

*Alternative rejected:* keep the range check at publish time and require only that "some walk ran this pass". That
cannot tell a walk begun before the end from one begun after, and it would keep the boolean the move replaces.

### D2: A member who shares nothing settles at the end

A policy that does not contribute has nothing to look at. Its publish (the "declined" branches) takes the policy
itself as the look: `ShareDeclaration.of` answers `Settled` once the range has ended, with no discovery. This is today's
behaviour, now stated as the second path to `Settled`.

*Alternative rejected:* walk the library for a receive-only member to get a discovery. That reads photos the member
never shares, and under no grant it never settles, so the event never finishes.

### D3: A failed look never takes back a settlement

When the range has ended and the last declaration this device published for the event was `Settled`, a publish that
cannot prove `Settled` is skipped instead of sent as `Sharing`. Otherwise one unreadable library would revert the
member to `sharing` on the backend and hold the event unfinished. Settling is never taken back on the device for an
ended range, because a later find only adds photos, which the backend already counts.

While the range has not ended (it was never ended, or a settings change moved it later), `Sharing` is published as
usual. That is what reverts a settlement after the range is extended.

The last published declaration is a fact about what was sent, not evidence. The manifest repository reads it from the
record it already keeps.

*Alternative rejected:* a failed look publishes nothing at all. That also delays the withdrawals a settings change
makes, which need no look.

### D4: The share phase is created here

No domain concept for "the member's own range has ended" exists yet. Today it is the old zones'
`EventConfig.shareHasEnded`, read through `MembershipProjection`. 4.4c adds it to the domain, as `SharePhase.of(range,
now)` answering `Sharing` or `Ended`, read from the membership. The settle step and D1/D3 match on it.

### D5: Under limited access, the held selection is the look

The selection snapshot is read at the process's start (its baseline) and kept current by the selection observer. A
discovery from it is therefore taken at the moment it is used, as today. A process that starts after the range ended
reads a fresh baseline.

*Alternative rejected:* stamp the snapshot with its last OS read time. In a long-lived process whose selection never
changes, that would never pass D1, and the member would never settle.

### D6: A join advances the manifest version

Entering a membership advances the manifest version in the join's own transaction (`MembershipWrites.enter`). The next
publish then differs from the record whatever the join-time listing did, and it carries the rejoined share's
declaration. This is the backend's rule mirrored: a join clears its stored version.

*Alternative rejected:* clear the skip record at the join. That works, but it is a second way to invalidate the record
beside the version. The version is already the one rule that a change to what the backend holds forces a republish.

*Alternative rejected:* key the record by membership. There is no membership identity to key it by, and adding one
would mean a migration for a case the version already covers.

### D7: The record keeps what D3 reads, and both processes act on "over"

- **The record's facts as columns.** D3 and the "photos from others" count read two facts from the skip record: whether
  the last publish declared the share settled, and how many photos it declared. A `ledger.db` migration stores them as
  columns of `uploadedManifest`, and drops the one existing row. The body text stays, for the equality check only.
  *Alternative rejected:* decoding the stored JSON. That needs a catch in the data layer, which the containment rule
  forbids.
- **Both processes act on "over".** A publish the service refuses because the event's photos are deleted marks the
  membership deleted in both processes. Until now only the app acted on that answer. The upload extension ignored it,
  because it was handed nothing to act with.

## Risks / Trade-offs

- [A member who stays unreadable after the end never settles] → Correct by the spec: the event waits for its latest
  deletion date, as for a phone that stays off.
- [More republishes after a join] → One small JSON per join.
- [Builds without this fix keep publishing `final` on a failed look] → They reach only their own share, and every
  merge ships. Nothing to migrate.

## Migration Plan

- **Migration.** One `ledger.db` migration (D7) adds two columns and drops the skip record, so each device republishes
  once. It is no backwards-compatibility shim: nothing reads the old shape afterwards.
- **Rollback.** Revert the pull request. An older build reading the migrated database is the normal downgrade case.
  The record is a skip optimisation, so it costs at most one more republish.
