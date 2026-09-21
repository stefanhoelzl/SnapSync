# upload-completion-notify Specification

## Purpose

The mechanism that closes the sharing loop: when an event's union gains a photo the other members can
fetch, they are woken to pull it. Without a wake the push pipe exists but nothing calls it, and a
co-contributor's photos are discovered only when the receiving user next opens the app.

**The device does not fire it.** The fan-out is an effect, on the backend (`api-endpoints`), of the write
that makes an asset newly fetchable: that write commits, then fans out, then responds. Ordering the wake
after the union it advertises is therefore a property of one request rather than something the device
achieves by sequencing two calls correctly — and the versioned device API has no notify route for it to
call.

That is where this capability arrived, and the route it took is the point. It began as a device-issued
`POST /events/<id>/notify` fired once per **drained** cycle that completed an upload — an injected,
best-effort, bounded lambda that could not fail the cycle. The word *drained* was the first thing to go
(`changes/archive/2026-08-27-fix-cap-truncation-loop`): a device with more outstanding work than the
platform's job limit never drains, so it never notified, while the promotion pass consumed the signal
anyway. The trigger then became "promoted a row **and** the projection changed". Moving to `/api/v2`
removed the remaining half: the device has nothing left to decide, because the backend can see the write
for itself.

The trigger then had to move once more. While the manifest listed only completed resources, the publish
**was** the moment the union gained an asset. Now that the manifest declares **intent** (capability
`device-manifest`), its content changes at discovery time, and an asset becomes fetchable when the last
declared role's bytes arrive — so the byte upload carries the trigger, and the publish carries it only in
the one case where an asset becomes fetchable with no byte moving: a membership widening its range to
re-admit resources already stored.

Decision record: `changes/archive/2026-07-05-notify-driven-download`, superseded in part by
`changes/archive/2026-08-27-fix-cap-truncation-loop`, by the move to the versioned device API, and by
`changes/declare-upload-intent`.
## Requirements
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

- **WHEN** a cycle publishes a projection smaller than the last one — rows of a departed asset deleted, a narrowed
  capture cutoff, or a reconfigure
- **THEN** no member is woken, because no asset became fetchable

#### Scenario: The device never sequences the wake itself

- **WHEN** an upload cycle completes any outcome that publishes
- **THEN** it makes no notify call and publishes no unchanged document to provoke one, and the ordering of
  any wake against the union is the backend's guarantee rather than the cycle's

