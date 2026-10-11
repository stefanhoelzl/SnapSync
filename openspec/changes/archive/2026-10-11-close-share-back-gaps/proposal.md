# Proposal

## Why

A photo a member received must never come back into the event as theirs. After a reinstall, the app recognises the
received photos still in the library by the SnapSync mark in their names — but two ordinary situations defeat that
today, and the spec accepts both as gaps: a rejoin while the event's photos cannot be read recognises nothing and
shares anyway, and under limited access a received photo the member adds to the selection later is shared before
anything recognises it. Android papers over both for one folder only (the event album's folder is never shared),
which is also the one difference between the platforms' galleries the core architecture work (phase 2c, gallery port)
would otherwise have to carry. Closing the gaps at their cause makes the promise hold on both platforms and lets the
Android-only folder rule go.

## What Changes

- **No sharing before the event's photos have been read.** After a member joins (or rejoins), the app SHALL share
  nothing of theirs — no photo uploaded, none announced to the others, none counted as theirs to share — until it has
  read which photos the event holds and recognised the received ones in the library. A failed read is retried on the
  app's next opportunity (opening the app, a background run, the network returning); it never settles into sharing
  without recognition. The accepted gap "any photo when the event could not be reached at that join" is removed.
- **A received photo that becomes visible later is recognised before it can be shared.** When a member under limited
  access adds photos to their selection, any of them carrying the SnapSync mark SHALL be recognised against the event's
  photos before the app may share them; until that read succeeds, those photos are not shared. The accepted gap
  "a photo the app cannot see because photo access is limited to a selection" is removed for sharing back (it stays
  for receiving again: a photo outside the selection at the rejoin may still arrive a second time).
- **Android's event-album folder is no longer excluded from sharing.** Received photos there are protected the same way
  as everywhere else on both platforms. Consequence: a photo the member *puts* into that folder themselves is now shared
  like any other photo in DCIM, subject to the same rules (capture range, floors, the album rule).
  The sentence "On Android the gap is narrower still…" and its scenario go.
- **A gap stated for both platforms:** after a reinstall, joining a *different* event whose capture range covers photos
  received in an earlier event may share them as the member's own (recognition matches marks against the joined
  event's photos only). iPhone has this today without the spec saying so; Android loses the folder rule that hid it for
  album photos. Rejected alternatives (a rule that a marked photo is never shared; keeping the folder rule) are in
  the design.
- The remaining accepted gap after a reinstall stays as it is: a photo received by a version of the app that did not
  mark it. A photo whose mark the member removed by renaming it is not covered (an edge case, as today).
- "A deleted received photo never comes back" loses its gap "any photo when the event could not be reached at that
  join": nothing is received before the event's photos have been read and the received ones recognised.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `receiving-photos`: "Received photos are never shared back" — the two gaps above are closed, the Android folder
  sentence and scenario are removed, new scenarios for the failed read and the widened selection. "A deleted received
  photo never comes back" — unchanged in substance; its list of forgotten photos keeps the cases that still apply to
  receiving again.
- `photo-sharing`: "On Android, only photos in the DCIM folder are shared" — the exclusion of the event albums' folder
  is removed; received photos are kept out by `receiving-photos`, as on iPhone.

## Impact

- **App behaviour:** a rejoin with the backend briefly unreachable now waits (status stays on the neutral loading line,
  capability `sync-status`) instead of sharing; under limited access, widening the selection with received photos
  costs one read of the event's photos before sharing proceeds.
- **Code (no new module):** the received-photo recognition pass (`ReceivedPhotoAdoption`) — an unreachable event no
  longer settles; a persisted per-membership "recognised" fact that every sharing path requires (the app's upload
  cycle and manifest publish, and the upload extension, which runs in its own process); a re-recognition step on
  selection changes; the Android gallery adapter's candidate scope (`DefaultGallery`).
- **Unblocks** phase 2c (ports: gallery + transfers): its gallery port then needs no app-albums scope option.
- No backend, API, storage-format or invite-link change.
