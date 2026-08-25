## REMOVED Requirements

The capability is dissolved. It was three specs in one — route mechanics, a storage-layout registry, and
two decisions — and each third goes somewhere different. Two of its requirements are deleted outright as
**duplicates that already exist elsewhere in full**; that duplication predates this change.

### Requirement: Event creation route
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *Event creation*.

### Requirement: Server-minted event id
**Reason**: Route mechanics — the route mints the id and ignores a client-supplied one.
**Migration**: `api-endpoints` → *Event creation*.

### Requirement: Event name validation
**Reason**: The one validation on this route that really is surface: no bound in `event-limits`' sense, no
decision behind the 100-character limit, and no consumer but a display label.
**Migration**: `api-endpoints` → *Event creation*.

### Requirement: Event start-date validation
**Reason**: **Duplicate.** The canonical cutoff form and its rationale are `photo-selection-policy`'s (the
value is consumed directly as a capture-date cutoff, compared lexicographically and parsed without
normalization); the unboundedness of `startsAt` and its interaction with the lifetime are `event-limits`'.
Only the `400` was ever this spec's.
**Migration**: `api-endpoints` → *Event creation*, which cites `event-limits` for the rules and states the
status; the rules themselves stay where they were already stated.

### Requirement: Event end-date validation
**Reason**: **Duplicate.** All five conditions — canonical shape, a real round-tripping instant, strictly
after `startsAt`, within the configured window maximum, and the absent-`endsAt` fallback — are stated in
full in `event-limits` → *Limit values from backend configuration*. Nothing contradicted, and nothing in CI
would have noticed if it had.
**Migration**: `api-endpoints` → *Event creation*, which cites rather than restates.

### Requirement: Event marker registry
**Reason**: Two things wearing one name. The **storage layout** — an event exists iff
`events/<eventId>/metadata.json` is present, the key disjointness argument, the `AccessKey` `PUT` — is
replaced by a row. The **write-once-except-`name`** rule is a security decision, and it matters *more*
under SQL, not less: a table with an `UPDATE` is a far easier place to add a careless `SET` than a
write-once JSON blob, and SQLite offers no column-level immutability without a trigger.
**Migration**: `database` → *Five tables…* and *Event existence is a row*; `event-limits` → *Event fields
are write-once except the name*, which carries the threat argument verbatim.

### Requirement: Faithful create outcome
**Reason**: Stated once for every route rather than per endpoint.
**Migration**: `api-endpoints` → *Faithful outcome — no partial success, no partial list*.

### Requirement: Event metadata and existence route
**Reason**: Route mechanics; the sealed-`404` property that `leave-event` depends on is preserved and
restated on both sides.
**Migration**: `api-endpoints` → *Event metadata and existence*; `database` → *Event existence is a row*.

### Requirement: Event routes require a device token
**Reason**: A duplicate of the rule `device-attestation` owns, along with the closed list of exceptions
that already names the two ungated read routes.
**Migration**: `device-attestation` → *Ungated routes are a closed list*.

### Requirement: Event rename route
**Reason**: Route mechanics. The client half of renaming stays in `event-rename`, which is unaffected.
**Migration**: `api-endpoints` → *Event rename*.
