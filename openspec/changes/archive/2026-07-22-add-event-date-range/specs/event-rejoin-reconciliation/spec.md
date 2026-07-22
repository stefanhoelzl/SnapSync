# event-rejoin-reconciliation Specification

## ADDED Requirements

### Requirement: Reconcile backfills the event window onto pre-existing memberships

A reconciliation SHALL backfill the event window onto a membership stored **before** this change — one
that carries no `endsAt` and no `maxPhotoDate` field (the event window and its capture-date ceiling did not
exist). When the configured `EventConfig` lacks the window fields, the upload tier SHALL fetch the event
details (`GET /events`) and, on a successful response, **backfill and persist** the membership with
`endsAt` from the fetched event and `maxPhotoDate = endsAt` (the guest ceiling defaults to the event end,
capability `photo-selection-policy`).
Legacy events (whose `endsAt` was the server-fixed `startsAt + 30d` backstop) are thereby capped at their
30-day mark — accepted: for a short-lived-event product a post-30-day capture is almost certainly not an
event photo.

Until a membership is backfilled — for example while the details fetch is unavailable — an **absent**
`maxPhotoDate` SHALL be treated as **unbounded** (no capture-date ceiling applied), so nothing of the
member's is silently dropped mid-upgrade; the ceiling takes effect only once a real value is persisted. A
details fetch that returns **404** (the event is already gone) SHALL **skip** the backfill and leave the
membership's window fields absent — there is nothing to backfill from a deleted event, and the membership
otherwise reconciles unchanged.

The backfill SHALL write only the new window fields onto the config; it SHALL NOT alter the `eventId`,
`name`, cutoff (`minPhotoDate`), `direction`, or `saveToAlbum`, and it is not a switch (no ledger reset,
no cursor clear).

#### Scenario: A legacy membership is backfilled to the event end
- **WHEN** a reconciliation runs for a membership stored before this change (no `endsAt`, no
  `maxPhotoDate`) and `GET /events` returns the event with an `endsAt`
- **THEN** the membership is persisted with that `endsAt` and `maxPhotoDate = endsAt`, so a legacy
  server-fixed `+30d` event is capped at its 30-day mark

#### Scenario: Before backfill an absent ceiling is unbounded
- **WHEN** a membership missing the window fields reconciles while `GET /events` is unavailable, so no
  backfill is written
- **THEN** the absent `maxPhotoDate` is treated as unbounded — no capture-date ceiling is applied and no
  in-scope photo is dropped — and the next reconciliation retries the backfill

#### Scenario: A 404 skips the backfill
- **WHEN** a reconciliation runs for a membership missing the window fields and `GET /events` returns
  `404` (the event is already gone)
- **THEN** no backfill is written, the window fields stay absent (the ceiling stays unbounded), and the
  reconciliation otherwise proceeds unchanged
