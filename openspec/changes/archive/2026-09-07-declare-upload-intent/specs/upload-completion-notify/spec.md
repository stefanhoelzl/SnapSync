## ADDED Requirements

### Requirement: The fan-out is an effect of the union gaining an asset

The device SHALL issue **no notify request**. Members are woken by the backend, as an effect of the write
that makes an asset newly fetchable, whose ordering against the event union is guaranteed by construction —
the write commits, then fans out, then responds — rather than by the device sequencing two calls correctly.

Two writes can make an asset fetchable, and **both** SHALL fan out:

- **The byte upload** (capability `api-endpoints`). When a recorded resource was the last declared role its
  asset was missing, that asset becomes servable at that instant, and the members of every event declaring
  it SHALL be woken. A byte that completes nothing SHALL wake nobody.
- **The manifest publish** (capability `device-manifest`). A publish SHALL wake members only when it
  declares an asset whose every role is already recorded — the case where an asset becomes fetchable with
  no byte moving, produced by a membership widening its capture-date range to re-admit resources already
  stored (capability `reconfigure-membership`).

The manifest publish SHALL NOT fan out merely because the published document differs from the last one.
Once the manifest declares **intent** rather than completion, its content changes when *discovery* changes:
waking members then would announce a photo they cannot yet fetch, and — because a resource's declaration and
its later completion project identical manifest fields — the publish would fall silent at the one moment
that matters. The device SHALL NOT compensate by publishing an unchanged document to provoke a wake: a write
whose only purpose is its side effect is a device-issued notify in disguise, which this capability exists to
have removed.

Wake volume is bounded by the platform, not by taste. Apple delivers background notifications at its own
discretion and documents a ceiling of two or three per hour, throttling on total volume; a wake spent on a
union the recipient cannot act on therefore consumes the allowance the useful wake needs. Wakes for one
event SHALL carry a collapse identifier (capability `apns-push-sender`) so that undelivered wakes for the
same event coalesce rather than accumulate — they are interchangeable by construction, since a wake carries
only its event and a recipient responds by reconciling the whole union.

#### Scenario: A byte that completes an asset wakes the other members

- **WHEN** a resource's bytes are recorded and every role that asset declares is now present
- **THEN** the backend wakes the event's other active members, and the uploading device issues no request
  of its own

#### Scenario: A byte that completes nothing wakes nobody

- **WHEN** a resource's bytes are recorded while its asset still declares a role with no recorded resource
- **THEN** no member is woken, because the union serves nothing it did not serve before

#### Scenario: A publish that declares an already-stored asset wakes members

- **WHEN** a membership widens its capture-date range and its next manifest declares an asset whose
  resources are all already recorded
- **THEN** the backend wakes the event's other active members, because the union gained a fetchable asset
  with no byte moving

#### Scenario: A publish that only declares intent wakes nobody

- **WHEN** a cycle's discovery finds new resources and publishes a manifest declaring them, none of whose
  bytes have been recorded
- **THEN** no member is woken, because every newly-declared asset is still incomplete and unfetchable

#### Scenario: A retraction wakes nobody

- **WHEN** a cycle publishes a projection smaller than the last one — rows marked absent, a narrowed
  capture cutoff, or a reconfigure
- **THEN** no member is woken, because no asset became fetchable

#### Scenario: The device never sequences the wake itself

- **WHEN** an upload cycle completes any outcome that publishes
- **THEN** it makes no notify call and publishes no unchanged document to provoke one, and the ordering of
  any wake against the union is the backend's guarantee rather than the cycle's

## REMOVED Requirements

### Requirement: The fan-out is an effect of the manifest publish

**Reason**: The manifest publish is no longer the moment the union gains an asset. Once the manifest
declares what a member intends to provide, its content changes at discovery time, while the union gains an
asset when the last declared role's bytes arrive — and because a declaration and its later completion
project identical manifest fields, the publish stops changing at completion and the wake would never fire.
The trigger moves to the write that actually makes an asset fetchable.

**Migration**: Replaced by "The fan-out is an effect of the union gaining an asset" above, which keeps the
device-issues-no-notify rule intact and adds the byte-upload trigger. The retraction wake this requirement
recorded as a known wasted cost stops occurring, so no consumer loses a signal it relied on.
