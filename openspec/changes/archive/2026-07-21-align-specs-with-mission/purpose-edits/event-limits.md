# event-limits Specification

## Purpose

> Full replacement of the Purpose section (one paragraph added naming the tier boundary; no
> requirement changes). Apply by hand at archive time and diff.

The bounds every event carries: a device **capacity** and a wall-clock **lifetime**, stamped onto
the event marker at mint from backend global configuration and enforced entirely server-side.
An event moves through three states — **live** (joins allowed under the cap, full sync),
**grace** (no new devices, existing members keep full sync so late uploads of in-event photos
still land), and **expired** (deleted on first touch, members notified). Expiry is deletion: no
tombstone, no scheduler — the first request that touches an expired event reaps it, and
afterwards the event is indistinguishable from one that never existed.

These bounds are also the future **free/paid tier boundary**. When paid events arrive, capacity and
duration become creator-chosen values behind a payment gate at creation (capability
`event-creation` names the attach point), with small events staying free — and because enforcement
reads only the marker's own stamped `endsAt` and `capacity`, that change needs no schema or
enforcement work. Today's initial values are the current free-for-everyone bounds, not that future
free tier.

Decision record: `changes/archive/2026-07-21-add-event-limits`.
