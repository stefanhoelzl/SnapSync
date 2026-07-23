## ADDED Requirements

### Requirement: The join surface states how long the event's photos are kept

The join surface's **loaded** phase SHALL carry the event's **`deletesAt`**, read from the details body
(capability `event-creation`), and SHALL present, before the confirm action, both:

- the **deadline** — the loaded `deletesAt`, rendered as a date, stating when the event's shared photos
  are removed; and
- a **fixed ceiling statement** — that shared photos are deleted within the maximum retention period
  (30 days) of the event's start.

The deadline SHALL be **server-supplied**, never computed on the device. The client SHALL NOT hold a copy
of the retention constant or of the anchor rule: duplicating either would let a create screen or a join
gate confidently promise a date the backend will not honour, and the drift would be silent. Serving the
derived value is what keeps one authority.

This surface is the **only** place the **retention** is stated in the app. The create screen SHALL be
unchanged — the creator reaches this same gate immediately after minting (`CreateEvent` routes the minted
event into the gate a scanned QR uses), so one line serves both the host and every guest. (The joined
layer's "Event ended" marker is a separate statement about the capture window, not retention; its layout
is capability `sync-status-screen`.)

The stated deadline SHALL be the **ceiling**, never a conditional. An event may in fact be deleted
earlier — the scheduled cleanup reclaims an emptied event (capability `scheduled-cleanup`) — but that
reclamation depends on every member's leave notify reaching the backend and is therefore not assured, so
it SHALL NOT be presented as a promise or a qualification on the date.

#### Scenario: The loaded phase states the deadline and the ceiling

- **WHEN** `GET /events/:eventId` returns 200 carrying a `deletesAt`
- **THEN** the join surface shows that date as when the event's shared photos are removed, alongside the
  fixed statement that shared photos are deleted within 30 days of the event's start, before the confirm
  action

#### Scenario: The creator sees the same statement

- **WHEN** a host completes the create form and the minted event is routed into the join gate
- **THEN** the gate shows the same deadline and ceiling statement it shows any scanning guest

#### Scenario: The deadline is not derived on the device

- **WHEN** the join surface renders the deadline
- **THEN** it renders the value the details response supplied, and no client-side constant or anchor
  formula participates in producing it

#### Scenario: Early reclamation is not stated as a condition

- **WHEN** the join surface presents the deadline
- **THEN** it presents it unconditionally, with no clause making the date contingent on other members
  leaving

## MODIFIED Requirements

### Requirement: One details client

The app SHALL have exactly one `GET /events/:eventId` client: the `EventDirectory` port (`:domain`
`ports/`) and its `HttpEventDirectory` implementation in `:adapter:generic:app`. Every consumer of an
event's details SHALL read through it — the join gate's details fetch AND the membership refresh (the
scan-path fill and the foreground re-fetch, capability `event-link`). There SHALL be no second, looser
event-fetch client: a duplicate client is how producer and consumer semantics drift (the deleted
`EventMetadataSource` accepted responses the gate rejects).

**Folding a fetch result into the persisted membership SHALL be one membership-feature rule** seated in
`:domain` `feature/membership`, named for that need rather than for any single field it touches. The rule
SHALL answer a **sealed outcome** with exactly three arms:

- **refreshed** — the fetch resolved and the event is still the configured one: the rule persists a
  changed name, and backfills any absent window and retention fields, as **one whole-config save** with
  only those fields replaced, never clobbering the persisted cutoff (capability
  `photo-selection-policy`);
- **inconclusive** — the fetch did not resolve definitively (offline, transport failure, non-404 status,
  unparseable body), or it resolved for an event that is no longer the configured one: **nothing is
  persisted and nothing is torn down**, and the last-known values are left unchanged;
- **absent** — the fetch resolved to a definitive `NotFound` **and** the membership's own persisted
  deadline has passed: the membership is torn down (capability `leave-event`).

The teardown on the **absent** arm SHALL be performed by the rule itself, not by a branch in the calling
flow. Attaching the consequence to the verdict is what makes every trigger reach the same outcome by
construction rather than by separate call sites agreeing. It is also what the flow transcriber's closed
grammar requires: the fetch is network I/O and therefore sits inside an escaping launch, where no `when`
is transcribable and an untranscribable flow fails generation (capability `architecture-diagrams`).

The distinction between *inconclusive* and *absent* SHALL be preserved end to end. The port already
separates `NotFound` from `Failed`; the effect the flows are given SHALL carry that sealed outcome rather
than collapsing it, because collapsing them is what makes "could not tell" and "definitively gone"
indistinguishable at the only place the difference matters.

The fetch itself SHALL remain coordination in the `flow/` triggers (`Foreground` unconditionally;
`Provision` for a nameless config) through a `compose/`-built `EventDirectory` effect over this one
client. Each trigger SHALL do no more than hand the fetched result to the rule, so one verdict cannot mean
two different things depending on which trigger observed it.

#### Scenario: The membership refresh reads through the details client

- **WHEN** the foreground refresh (or the scan-path fill) fetches the configured event
- **THEN** it calls the same `EventDirectory` the join gate uses, and updates the stored membership only
  from a resolved outcome

#### Scenario: An inconclusive outcome changes nothing

- **WHEN** the details fetch fails on the network, times out, returns a non-404 error, or returns an
  unparseable body during a refresh
- **THEN** the persisted config keeps every last-known value, nothing is torn down, and syncing is
  unaffected

#### Scenario: A stale fetch after a switch stores nothing

- **WHEN** a fetch resolves for an event that is no longer the configured one
- **THEN** the rule answers inconclusive and stores nothing (the departed membership is not resurrected)

#### Scenario: The sealed outcome reaches the flow intact

- **WHEN** the details fetch resolves to `NotFound`
- **THEN** the effect the trigger receives distinguishes that from a failed fetch, rather than presenting
  both as the same no-result

#### Scenario: Both triggers reach the same consequence

- **WHEN** the same sealed answer is produced under the `Foreground` trigger and under the `Provision`
  trigger
- **THEN** the same action follows in each case, because the rule performs it rather than each trigger

### Requirement: The persisted membership carries the event's start date

The persisted membership state (`EventConfig`) SHALL carry the event's **`startsAt`**, its **`endsAt`**,
and its **`deletesAt`** alongside the capture-date range, in the canonical cutoff shape. On a
**successful details load**, all three are **required and non-invented** — read from the loaded event's
`{ startsAt, endsAt, deletesAt }` body, never defaulted to now or to any client-side guess. `startsAt` is
what the not-started state compares against (capability `sync-status-screen`) and the floor on the
range's lower bound; `endsAt` is what the "Event ended" marker compares against (capability
`sync-status-screen`) and the ceiling on the range's upper bound; `deletesAt` is the second witness the
self-leave requires (capability `leave-event`). All three make the event's bounds auditable on the
device.

A config persisted **before** `startsAt` existed SHALL decode with `startsAt` **defaulted to that
config's `minPhotoDate`**. It SHALL NOT fail to decode.

A config persisted **before** `endsAt` existed SHALL decode with `endsAt` **absent (treated as
unbounded)** rather than failing, so nothing is silently dropped mid-upgrade; reconcile backfills it to
the event's end (capability `event-rejoin-reconciliation`). Every consumer SHALL treat an absent `endsAt`
as **no ceiling** until it is backfilled.

A config persisted **before** `deletesAt` existed SHALL decode with `deletesAt` **absent** rather than
failing; reconcile backfills it (capability `event-rejoin-reconciliation`). An absent `deletesAt` SHALL
be treated as **never reached**, so the self-leave cannot fire on a membership that has not yet learned
its deadline — the safe direction, matching `endsAt`'s unbounded default.

Defaulting `startsAt` rather than failing is deliberate and is **not** symmetric with `minPhotoDate`'s own
no-default rule. `minPhotoDate`'s harshness buys protection against uploading a whole camera roll;
`startsAt`'s would buy a status line. And the blast radius is severe: `EventConfig` is the **only**
place the `eventId` is held, and the invite QR is derived from it — so a decode failure destroys the
member's event id **and** their QR, with nothing in the app to surface either back. A host who is the
only member yet would be permanently locked out of their own event, its uploaded photos stranded.

`minPhotoDate` SHALL be the `startsAt` default because it is the only value **guaranteed** consistent with
the floor invariant (`minPhotoDate >= startsAt`, satisfied here with equality). It also lands the
not-started state correctly by construction: a legacy member joined an event that had already begun, so
their cutoff was at or before "now" when they picked it, so the derived `startsAt` is never in the future
and the not-started state never appears for them.

#### Scenario: A fresh join persists startsAt, endsAt, and deletesAt from the loaded details
- **WHEN** a join confirms against a loaded event carrying `startsAt`, `endsAt`, and `deletesAt`
- **THEN** the persisted `EventConfig` carries all three as non-null canonical strings, none invented nor
  defaulted to now

#### Scenario: A legacy config decodes with startsAt defaulted and no ceiling or deadline
- **WHEN** a config persisted before this change — carrying `eventId`, `name`, `minPhotoDate`,
  `direction`, `saveToAlbum` and **no** `startsAt`, `endsAt`, or `deletesAt` — is decoded
- **THEN** it decodes successfully with `startsAt == minPhotoDate`, `endsAt` absent (an unbounded ceiling
  pending reconcile backfill), and `deletesAt` absent (a deadline treated as never reached), and the
  member keeps their event, their QR, and their cutoff

#### Scenario: An absent deadline never fires the self-leave
- **WHEN** a membership whose `deletesAt` has not been backfilled observes a definitive `NotFound`
- **THEN** the membership is left intact, because the deadline witness cannot be satisfied

#### Scenario: A config with no cutoff still fails to decode
- **WHEN** a config carrying no `minPhotoDate` is decoded
- **THEN** it still fails and reads as *no config*, the cutoff's no-default rule being untouched by this
  change

#### Scenario: Every consumer reads a non-null startsAt
- **WHEN** the persisted membership is read, by the app process or the upload extension process
- **THEN** `startsAt` is a non-null canonical cutoff string, with no nullable branch at any consumer, and
  `endsAt` and `deletesAt` are each either a canonical cutoff string or the absent value pending backfill
