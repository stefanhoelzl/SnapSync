# Design

## Context

See proposal.md, "Why". The facts the approach rests on:

- **The receiver's total is local.** `StoreDownloadStatusSource` publishes `DownloadProgress(downloaded, total,
  inFlight)` from downloads.db's `projectionCountsForEvent`. `total` = the event's foreign assets the union has
  listed, minus `UNIMPORTABLE`.
- **The union serves only complete assets.** `eventFiles` (`api/src/routes/shared.ts`) joins `event_assets` to
  landed `resources` across active and departed memberships, and drops any asset with a declared role that has
  not landed. It is public (the event id is the read capability), uncached, and names its position in the
  `SnapSync-Cursor` response header, which the generated reply exposes as `snapSyncCursor`.
- **The backend already holds every declaration.** A device's manifest declares every resource it intends to
  provide (`DeviceManifestProducer`). The backend stores it in `event_assets`. It already reads it for
  `MemberCounts.waiting` (`sharedAll` in `api/src/db.ts`).
- **The device knows what it last published.** `uploadedManifest` (ledger.db) holds the manifest the backend last
  accepted, in the same event-scoped row both processes compare against.
- **When the union is read:** on every silent push (sent when an asset becomes fetchable), on foreground entry,
  and on a background wake at most hourly (`reconcileIfDue`). The foreground status poll is local-only.
- **The status line** is reduced in `syncHealth` (`StatusContainerHost.kt`) into `SyncHealth.Syncing(upload,
  download, waitingForWifi)` and worded in `AppStatusLine`: pulsing → `status_sync_ongoing`, held for Wi-Fi →
  `status_waiting_wifi`, else → `status_sync_pending`. The ended-event `sharingNote` is set only when `health ==
  InSync`.

## Goals / Non-Goals

**Goals:**

- The received total equals the others' declared photos, and the sharer's and the receiver's totals agree as soon
  as the receiver has read the union since the declaration.
- The read-model can tell "this device has download work" (planned, in flight, staged) apart from "others have
  not finished sharing". The line, the arrow and the ended-event note depend on that difference.
- No new request, no new push and no new route.

**Non-Goals:**

- A per-member breakdown ("Anna: 120 still uploading"). The members stay anonymous, and the count stays one
  number.
- Showing the declared total on the web pages. The header makes that possible later; nothing here uses it.
- Making the total current between union reads. A declaration with no arrival yet surfaces at the next read.
  See Risks.

## Decisions

### D1 — One event-wide total, carried on the union read as a response header

The union answer gains an optional header, `SnapSync-Declared`. Its value is the number of assets the full union
read would serve at that position, plus the assets declared by **present** members that are not yet complete. It
is computed in the same transaction as the position and from the same predicates as the union query, so the
first half equals what a full read lists. Departed members' incomplete declarations are left out because they
will never land.

Every full **and** delta read carries it, since it describes the event, not the page.

- *Alternatives:* a field on the event read beside `MemberCounts` was rejected. The event read happens only at
  foreground entry, so the total would go stale while the app is open. A silent push on every manifest growth was
  rejected: it wakes every member on every declaration, and an arrival, which already pushes, follows a
  declaration within one upload. Listing incomplete assets in the union as pending rows was rejected: the
  download store and its planner would have to learn a row they must never fetch, all to get a count.
- *Why a header:* the union body is a bare JSON array that old builds parse strictly. A header is additive, the
  cursor already rides one, and the wire generator turns it into a typed nullable field on the reply.

### D2 — The device subtracts its own **published** count, not its local one

The event-wide total includes the reader's own declarations. The device subtracts the asset count of its
`uploadedManifest` row for this event. That count is its own declaration as the backend last accepted it, so
both terms describe the same backend state. A row for another event, or no row (a receive-only member, or
nothing published yet), subtracts 0.

- *Alternative:* subtracting the local upload total `N` was rejected. `N` runs ahead of the published manifest
  (a photo taken but not yet published), so the received total would read low, and received could exceed it.
- *Alternative:* a reader-specific header ("others' total") was rejected. It ties the answer to the token, and
  the union also answers anonymous readers.

### D3 — `awaited` is reported apart from `total`, and the remainder is clamped

At each refresh the read-model computes

```
awaited = max(0, declared − ownPublished − knownForeign)
```

`knownForeign` is every foreign asset of this event in downloads.db, **including** `IMPORTED` and
`UNIMPORTABLE`, so a photo this device gave up on is never also counted as awaited. `DownloadProgress` gains
`awaited: Int`. The displayed received total is `total + awaited`.

- `awaited` is computed **only when the membership receives**. A member with receiving switched off reports 0,
  so its counts still read "off" and no arrow is ever raised for photos it will not fetch.
- With no stored `declared` (a backend without the header, a reset cursor, or no read yet), `awaited` is 0. The
  screen then reads exactly what it reads today.
- *Why separate rather than folded into `total`:* the line, the arrow's stillness and the ended-event note all
  depend on whether the gap is this device's work or the others'. One summed `total` would lose that.

### D4 — `declared` is stored beside the union cursor

downloads.db's `unionCursor` gains a nullable `declared` column (migration `8.sqm`). It is written in the same
upsert, in the transaction that already advances the cursor. A cursor reset clears it with the row, which is the
fallback D3 wants. Only the download controller's cursor-advancing read writes it. The join-time adoption read
(`ReceivedPhotoAdoption`) moves no cursor and does not need to write it, because the next reconcile follows the
join. The extension never reads the union.

### D5 — The status line: one more health, decided in the reducer

`syncHealth` keeps its arrows. `downloadArrow` is shown when `downloaded < total + awaited`, and it pulses only
on `inFlight` as today. `Syncing` gains `waitingForOthers: Boolean`. It is true when no arrow pulses, nothing is
held for Wi-Fi, the upload arrow is hidden, and every download gap is `awaited` (`downloaded >= total`).
`AppStatusLine` words it, in this order: pulsing → "Photos on their way…", Wi-Fi → "Waiting for Wi-Fi…",
`waitingForOthers` → "Waiting for photos…", else "Photos queued…". The order is the spec's priority: the
device's own waiting work comes first.

`status_sync_ongoing` keeps its key and changes its copy: en "Photos on their way…", de "Fotos sind unterwegs …".
A new key, `status_waiting_photos`, reads en "Waiting for photos…", de "Warte auf Fotos …" (the Wi-Fi line's form). The glossary
(`metadata/messaging.md`) bans sync, upload and download, and both lines comply.

### D6 — The ended-event note widens to `waitingForOthers`

`sharingNote` is set when `health == InSync` **or** `health` is `Syncing` with `waitingForOthers`. The note's own
numbers (`MemberCounts.waiting`) are unchanged.

## Risks / Trade-offs

- [A declaration with no arrival is invisible until the next union read] → An arrival pushes, and a declaration
  is followed by its own upload. The window is one upload, or one foreground entry. Accepted, per the interview.
- [The manifest publish and the union read cross, so the total is briefly off by the photos published in
  between] → It is corrected at the next read. `awaited` is clamped at 0, and `downloaded` is already clamped to
  the total.
- [A sharer who never finishes (app deleted, phone lost) holds every receiver on "Waiting for photos…"
  indefinitely] → It is true: those photos are declared and not here. The event's lifetime bounds it, since the
  sweep deletes the event 3 days after its last activity. The ended-event note says whom the device is waiting
  for. This is the same honesty the "waiting for N members" note already chose.
- [The public union now discloses how many photos are still uploading] → The read is already gated only by the
  event id, and the union exposes every complete asset itself. A count of the pending ones adds nothing that
  identifies a member. This is noted here rather than in `privacy-security`, whose closed list names the read,
  not its fields.
- [A receive-only member's union read is now load-bearing for a header it once ignored] → It already reads the
  union for recognition. Nothing new is fetched.

## Migration Plan

1. Deploy the backend first, because the header is additive and old apps ignore it.
2. Ship the app. Against an older backend the header is absent, `declared` stays null and the screen behaves as
   today, so there is no ordering hazard and no shim. Nothing enters `PENDING_CLEANUPS.md`.
3. Roll back either half independently.
