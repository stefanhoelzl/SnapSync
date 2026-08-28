## ADDED Requirements

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

## REMOVED Requirements

### Requirement: Notify fires once per drained cycle that completed an upload

**Reason**: The device no longer decides when members are woken. The v2 device API has no notify route; the
fan-out is an effect of the manifest publish, ordered against the union by the backend. The two conditions
this requirement combined — a promoted `UPLOADED` row and a changed projection — are no longer both
expressible on the device, and the surviving one (a changed projection) is enforced by the manifest
producer's skip-if-unchanged rule rather than by a notify trigger.

**Migration**: Replaced by "The fan-out is an effect of the manifest publish". The widening this introduces —
a retraction now wakes members — is stated there with the reason it is a cost rather than a benefit.

### Requirement: Notify is an injected best-effort, bounded seam

**Reason**: There is no notify seam to inject. The upload cycle no longer takes a notifier, so there is
nothing to make required, bound, or absorb failures from; the best-effort-and-bounded discipline moves to
the backend's fan-out, which is bounded server-side and never changes the publish's response.

**Migration**: The cycle's notifier parameter is removed along with its no-op implementations in the harness
and tests. Compositions that passed a no-op simply stop passing anything.

### Requirement: Event notify request shape

**Reason**: `POST /events/<eventId>/notify` does not exist on the v2 device API. A device speaking v2 that
issued it would receive a `404`.

**Migration**: `EventNotifier` and its `PushHttpClient` usage are deleted. No replacement request exists —
the manifest publish carries the effect.
