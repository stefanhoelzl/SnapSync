# upload-completion-notify Specification

## Purpose

The mechanism that closes the sharing loop: when a device publishes what it contributes, the other members
are woken to pull the new photos. Without a wake the push pipe exists but nothing calls it, and a
co-contributor's photos are discovered only when the receiving user next opens the app.

**The device does not fire it.** The fan-out is an effect of the manifest publish, on the backend
(`api-endpoints`): the publish commits, then fans out, then responds. Ordering the wake after the union it
advertises is therefore a property of one request rather than something the device achieves by sequencing
two calls correctly — and the versioned device API has no notify route for it to call.

That is where this capability arrived, and the route it took is the point. It began as a device-issued
`POST /events/<id>/notify` fired once per **drained** cycle that completed an upload — an injected,
best-effort, bounded lambda that could not fail the cycle. The word *drained* was the first thing to go
(`changes/archive/2026-08-27-fix-cap-truncation-loop`): a device with more outstanding work than the
platform's job limit never drains, so it never notified, while the promotion pass consumed the signal
anyway. The trigger then became "promoted a row **and** the projection changed". Moving to `/api/v2`
removed the remaining half: the projection changing IS the publish, so the backend can fan out from it and
the device has nothing left to decide.

What the device still controls is **whether it publishes at all**, which the manifest producer's
skip-if-unchanged rule (capability `device-manifest`) already governs — so a cycle that changed nothing
still wakes nobody.

Decision record: `changes/archive/2026-07-05-notify-driven-download`, superseded in part by
`changes/archive/2026-08-27-fix-cap-truncation-loop` and by the move to the versioned device API.

## Requirements

### Requirement: The fan-out is an effect of the manifest publish

The device SHALL issue **no notify request**. Members are woken by the backend as an effect of the device's
manifest publish, whose ordering against the event union is guaranteed by construction — the publish commits,
then fans out, then responds — rather than by the device sequencing two calls correctly.

The device's only remaining lever is therefore **whether it publishes at all**. The manifest producer's
skip-if-unchanged rule (capability `device-manifest`) is what keeps a cycle that changed nothing from waking
anyone: an unchanged projection is not written, so no fan-out occurs.

This is a **widening**, and it is accepted knowingly rather than discovered later. The device previously
fired only when a cycle both promoted an `UPLOADED` row **and** published a changed projection. The second
condition survives as the publish itself; the first does not. A publish that changes the projection while
promoting nothing — a **retraction**, produced by rows marked absent, a narrowed capture cutoff, or a
reconfigure — now wakes the event's other members where before it woke nobody.

That wake is **wasted**, not useful, and this specification records the reason so it is not mistaken for a
feature: a recipient reconciles the union **additively** (capability `photo-download`), planning assets it
does not already hold and never pruning ones that have vanished, and a resource already planned carries the
presigned URL it was planned with. A recipient therefore cannot act on a union that shrank. The cost is one
background wake per retraction, bounded by the same skip-if-unchanged rule.

Because the device no longer decides, the conditions it used to evaluate SHALL NOT be reintroduced as a
client-side gate on publishing. Suppressing a manifest write in order to suppress a wake would trade a wasted
wake for a stale union, which is the strictly worse failure.

#### Scenario: A publish that changes the projection wakes the other members

- **WHEN** an upload cycle publishes a manifest whose projection differs from the last one written
- **THEN** the backend wakes the event's other active members, and the device issues no request of its own

#### Scenario: An unchanged projection wakes nobody

- **WHEN** an upload cycle's manifest producer skips its write because the projection is unchanged
- **THEN** no publish occurs and no member is woken

#### Scenario: A retraction wakes members, and that is accepted

- **WHEN** a cycle publishes a projection that is smaller than the last one — having promoted no row —
- **THEN** the other members are woken, find no new asset to fetch, and the wake is accounted for as a known
  cost rather than treated as a defect

#### Scenario: The device never sequences the wake itself

- **WHEN** an upload cycle completes any outcome that publishes
- **THEN** it makes no notify call, and the ordering of the wake against the union is the backend's
  guarantee rather than the cycle's
