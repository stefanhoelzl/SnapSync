## MODIFIED Requirements

### Requirement: Reconcile backfills the event window onto pre-existing memberships

A reconciliation SHALL backfill the event's **window and retention** fields onto a membership stored
**before** they existed — one that carries no `endsAt`, no `maxPhotoDate`, or no `deletesAt`. When the
configured `EventConfig` lacks any of them, the upload tier SHALL fetch the event details
(`GET /events`) and, on a successful response, **backfill and persist** the membership with `endsAt` from
the fetched event, `maxPhotoDate = endsAt` (the guest ceiling defaults to the event end, capability
`photo-selection-policy`), and `deletesAt` from the fetched event's derived delete-by (capability
`event-creation`). Each field SHALL be filled only when **absent**, so a chosen ceiling is never
overwritten, and all of them SHALL ride in a **single whole-config save** so two rewrites cannot lose
each other's field.

Legacy events (whose `endsAt` was the server-fixed `startsAt + 30d` backstop) are thereby capped at their
30-day mark — accepted: for a short-lived-event product a post-30-day capture is almost certainly not an
event photo.

Until a membership is backfilled — for example while the details fetch is unavailable — an **absent**
`maxPhotoDate` SHALL be treated as **unbounded** (no capture-date ceiling applied), so nothing of the
member's is silently dropped mid-upgrade; the ceiling takes effect only once a real value is persisted.
An **absent** `deletesAt` SHALL be treated as **never reached**, so the self-leave (capability
`leave-event`) cannot fire on a membership that has not yet learned its deadline. Both defaults fail
toward keeping data and keeping the membership.

A details fetch that returns **404** (the event is already gone) SHALL **skip** the backfill and leave the
membership's fields absent — there is nothing to backfill from a deleted event, and the membership
otherwise reconciles unchanged. Note that this is the reconcile path only: whether that same `404` tears
the membership down is the separate two-witness rule of capability `leave-event`, and a membership with
no backfilled `deletesAt` can never satisfy it.

The backfill SHALL write only the new window and retention fields onto the config; it SHALL NOT alter the
`eventId`, `name`, cutoff (`minPhotoDate`), `direction`, or `saveToAlbum`, and it is not a switch (no
ledger reset, no cursor clear).

#### Scenario: A legacy membership is backfilled to the event end and its deadline
- **WHEN** a reconciliation runs for a membership stored before this change (no `endsAt`, no
  `maxPhotoDate`, no `deletesAt`) and `GET /events` returns the event with an `endsAt` and a `deletesAt`
- **THEN** the membership is persisted, in one save, with that `endsAt`, `maxPhotoDate = endsAt`, and that
  `deletesAt`

#### Scenario: A membership missing only the deadline is backfilled
- **WHEN** a reconciliation runs for a membership that already carries `endsAt` and a chosen
  `maxPhotoDate` but no `deletesAt`, and `GET /events` succeeds
- **THEN** only `deletesAt` is filled, and the chosen `maxPhotoDate` is left unchanged

#### Scenario: Before backfill an absent ceiling is unbounded
- **WHEN** a membership missing the window fields reconciles while `GET /events` is unavailable, so no
  backfill is written
- **THEN** the absent `maxPhotoDate` is treated as unbounded — no capture-date ceiling is applied and no
  in-scope photo is dropped — and the next reconciliation retries the backfill

#### Scenario: Before backfill an absent deadline is never reached
- **WHEN** a membership missing `deletesAt` reconciles while `GET /events` is unavailable, so no backfill
  is written
- **THEN** the absent `deletesAt` is treated as never reached, so no self-leave can fire, and the next
  reconciliation retries the backfill

#### Scenario: A 404 skips the backfill
- **WHEN** a reconciliation runs for a membership missing the window fields and `GET /events` returns
  `404` (the event is already gone)
- **THEN** no backfill is written, the fields stay absent (the ceiling stays unbounded and the deadline
  stays unreached), and the reconciliation otherwise proceeds unchanged
