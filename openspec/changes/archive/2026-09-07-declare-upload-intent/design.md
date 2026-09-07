## Context

The device publishes one manifest per (event, device) — a full-state document replacing that
membership's asset set. Today it projects the upload ledger's `COMPLETED` rows, so it answers *what
have I uploaded*. The v2 backend was built for a different question: `event_assets.roles` records the
roles an asset **declares**, `resources` records what **arrived**, and `unionRows` LEFT-joins the
second onto the first so a declared role with no resource reads as `present = false`. An asset is
served only when every declared role is present.

That machinery is inert while the device declares only what it has already uploaded — every declared
role is present by construction. `api-endpoints` describes the intended state in the past tense
already; `device-manifest` still requires the COMPLETED projection. This change resolves that.

Two constraints shape everything below.

**The fan-out is the backend's.** Under v2 the device issues no notify request; members are woken as
an effect of the manifest publish (`notifyMembers`, defined once and called once, from the manifest
route). The device's only lever is whether it publishes at all.

**A `DISCOVERED` row and its later `COMPLETED` row project byte-identical manifest fields.** So under
intent the publish stops coinciding with the union growing, and the existing trigger silently stops
firing at the moment that matters.

## Goals / Non-Goals

**Goals:**

- The manifest states what this device **will provide** to the event, so the backend can distinguish
  "not yet" from "never".
- Members are woken exactly when the union gains something they can fetch — at both write sites.
- No increase in silent-push volume, which APNs bounds at two or three per hour.
- The event union's output is unchanged, except that a partially-uploaded asset stops being served
  shrunk to its landed resources.

**Non-Goals:**

- Changing the manifest wire format. It is already closed, already parsed by both API versions, and
  already described as *"the device's complete statement of what it contributes"*.
- Making the upload cycle's skip decision a difference against the backend.
- Retiring the upload ledger.
- Moving the union's completeness filter from JS into SQL.
- Any change to how the selection policy admits an asset.

## Decisions

### D1 — The projection is state-blind

The manifest lists every non-absent ledger row carrying manifest detail, whatever its state. Upload
state is not an input to the document.

`FAILED` is included, and that is the load-bearing part. The engine retries forever with no attempt
budget, so `FAILED` is not terminal — it is "attempted, still owed". Excluding it would make the
declared role set **oscillate** (declare → retract → declare) as a resource fails and retries, and
each flip is a manifest write and a fan-out.

*Alternative — exclude `FAILED`:* rejected for the oscillation above.
*Alternative — declare only assets whose every row is settled:* rejected because the backend learns
nothing it did not already know; an undeclared asset stays indistinguishable from one this device
does not have.

The consequence is accepted deliberately: a resource that never lands leaves its asset hidden
indefinitely. Serving the asset shrunk to its landed resources is the failure `unionRows` exists to
prevent — *"an inner join would make a missing resource vanish rather than read as incomplete."* A
permanent failure is a retry loop to fix, not a manifest to soften.

### D2 — The ledger's row set is the intent set

The projection needs no new source, because a full re-enumeration follows every event that could
widen scope: the rejoin reconcile clears the discovery cursor, and so does a **lowered** cutoff — the
only reconfigure that widens. The record loop cannot stop early, so a cap-truncated cycle still
records every admitted resource before the cursor advances. Intent is therefore complete from the
first cycle after any change.

### D3 — The bare-row predicate is removed; the policy is the sole admission

`creationDate != ''` duplicates a rule the policy already owns: `CaptureAfter` documents that an empty
capture date *"sorts before any real cutoff and is therefore excluded — the one place a missing fact
excludes rather than admits"*, and the floor is always present (*"`cutoff` is non-null, so no
contributing rule list this derivation produces can lack one"*). A non-contributing policy is
`DenyAll` and projects nothing.

A rule stated twice is the defect class `photo-selection-policy` exists to prevent — the capture-date
ceiling reached two of four consumers. The split is: the store filters **row facts** (`absent`), the
policy decides **admission**.

### D4 — The fan-out gains the byte route; both sites become precise

The byte route notifies when its resource was the last declared role missing. The manifest route
notifies only when its publish makes an asset newly fetchable — the widening case, where a re-admitted
asset's bytes are already stored. Every wake then answers one question: did the union gain something?

*Alternative — the device republishes on completion:* rejected. The document is unchanged, so a
publish whose only purpose is the side effect is device-driven fan-out laundered through a redundant
write, which is exactly what v2 removed.

*Alternative — add the byte-route notify and leave the manifest notify unconditional:* rejected on
volume. Each photo burst would produce a declaration wake (useless — the asset is hidden) and an
arrival wake. Apple: *"the system may throttle the delivery of background notifications if the total
number becomes excessive… don't try to send more than two or three per hour."* The budget is spent on
volume, so useless wakes consume the allowance the useful one needs.

*Alternative — accept the latency:* rejected. It is the failure `upload-completion-notify` exists to
prevent, and it spends the same budget at a moment the recipient can do nothing with.

This removes the retraction wake that `upload-completion-notify` currently records as a known wasted
cost.

### D5 — Wakes for one event coalesce

`apns-collapse-id: <eventId>`. A wake's payload is `{content-available, eventId}` and the receiver's
response is "reconcile the union for that event", so any two wakes for one event are interchangeable
by construction and collapsing loses nothing.

`apns-priority` is not a lever: background push type mandates 5, priority 1 is strictly worse, and 10
is not permitted. `apns-push-type: alert` would escape throttling by making sync visible, which
contradicts the mission. `apns-expiration` stays **absent**: omitting it means APNs stores and
retries, a wake stays semantically valid for the event's whole life, and collapse-id already bounds
the queued backlog to one per event.

### D6 — `completedManifestRows()` becomes `manifestRows()`

No state adjective. The name says only what the rows are for and carries no claim that can go stale —
a stale adjective is what produced the contradiction this change resolves.

### D7 — One change, both halves

The device and backend ship in one PR. They cannot land simultaneously either way (the backend
deploys on merge; the client reaches TestFlight after), so the effective order is backend-first, which
is the safe one. The cost accepted is that the halves cannot be reviewed or reverted independently.

### D8 — Test coverage splits by ownership

`api/`'s Deno suite owns the fan-out triggers. `:test:integration` over `:test:world` owns the
declaration and the union outcome — the world already models the presence check faithfully (*"every
manifest resource `key` present in that device's byte partition"*).

The world is **not** taught to model wakes. It would put the trigger rule in two languages that can
silently disagree, and the device under test is the publisher — it never receives its own wake.

## Risks / Trade-offs

**The push budget is unmeasured on this app** → the direction (do not increase wake volume) is what
the design rests on, and D4 reduces volume rather than holding it constant. No decision depends on the
exact number.

**Two-member wake arrival cannot be verified off-hardware** → one device is available, and a simulator
cannot receive a real APNs push from the backend. The off-hardware ceiling is "the notify was sent",
checkable against the local backend. Accepted as a coverage limit, stated rather than papered over.

**The first manifest after a join declares the whole admitted set at once** → a `DELETE` plus one
`INSERT` per asset in a single batch, where today the set grows as uploads complete. The ceiling is
unchanged: at any event's steady state today, `event_assets` already holds the full set. Only the time
to reach it changes.

**During the upload phase the union scans more rows than it serves** → bounded by the 10-device
capacity cap and by upload duration, and it converges on today's steady state. Not mitigated; see
Open Questions.

**A permanently-failing resource hides its asset indefinitely** → accepted in D1. The alternative
serves a Live Photo as a still, which is the defect this change closes.

## Migration Plan

One migration, adding the per-event lookup index the byte route needs (its path names no event).

The backend half is a **no-op against every client that exists today**: a completed-only manifest
declares only roles whose bytes are present, so the precise manifest rule fires exactly where the
unconditional one does. The only behavioural difference for current clients is that a retraction-only
publish stops waking members — a defect the current spec documents.

Rollback: the backend can be redeployed; `database` guarantees every row is reconstructible from the
storage zone plus one full-state manifest publish per device. A shipped client cannot be recalled,
which is why the device half is the smaller of the two.

## Open Questions

- **Should the union's completeness filter move into SQL?** `D8` of `add-v2-device-api` specifies a
  `NOT EXISTS` set-inclusion subquery and the current code implements the same semantics as a LEFT
  JOIN plus one line of JS. The decision there was set-inclusion **over count-equality**, which the
  code honours, so this is not a drift. Moving the filter would stop the query returning rows it
  discards — but only JSON1 *availability* was ever measured, never cost, and this is the most-read
  query in the system. Deferred as a measured optimisation. `db.ts`'s comment naming
  `json_each + NOT EXISTS` should be corrected to match the query.
- **What does the upload cycle do offline once its skip decision reads the backend?** Not this
  change's problem — today's work selection is entirely local, so a cycle creates background jobs
  offline and the OS uploads them when connectivity returns without the app running. Recorded here
  because the answer constrains whichever change makes pending a difference against the backend.
