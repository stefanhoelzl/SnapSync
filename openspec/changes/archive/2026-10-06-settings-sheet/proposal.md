## Why

The event's settings are a full screen that replaces the joined screen, with Save and Cancel pinned under a
long form: on a small phone the cards are clipped above a pinned note, and a member who flips one switch has
to find and tap Save before anything happens. Settings are a handful of switches; a change should simply
happen, the way a phone's own settings do, and the one consequence worth stopping for — photos withdrawn
from the group — should be asked about at the moment it happens rather than stated in a note nobody reads.

## What Changes

- **Settings open as a sheet over the joined screen.** It rises from the bottom and stops below the status
  bar, leaving a dimmed strip of the joined screen above it. It closes by swiping it down, by going back, or
  by tapping that strip; it has no close button, no title and no app icon — only a drag handle. Every way
  into settings (the footer action, the explanation's links) opens it.
- **Every change applies as it is made.** Save and Cancel are gone. A switch applies when it is flipped; the
  range applies when a preset is chosen or the calendar's OK is tapped. Changes made in quick succession
  apply in order and the last one stands.
- **Withdrawing photos asks first.** Switching sharing off, or making the shared range narrower, asks
  "Stop sharing these photos?" — photos you stop sharing reach no one new, whoever already has them keeps
  them, and photos you received stay — with Stop sharing and Keep sharing. Only Stop sharing applies the
  change; Keep sharing leaves the switch or range as it was. The standing note that said the same thing goes.
- **A change that cannot be saved is undone on screen.** The control returns to the setting in effect and
  the sheet says the change could not be saved; none of its effects happen.
- **A member may switch off both sharing and receiving** and stay in the event. The reason that used to
  block Save goes; the joined screen's status line then reads "Not sharing or receiving" — ahead of missing
  access, a missing network, a future start and an unverifiable device, none of which matters while nothing
  moves — with no counts beneath it.
- **One card holds every setting** — share with its range, receive, album, mobile data — on the settings
  sheet and on the join screen alike, since both draw the same arrangement. Layout only; no spec change.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `manage-membership`: settings change as they are made instead of on Save, Cancel is gone, both directions
  may be off, sharing off and narrowing the range ask first and carry the honest-narrowing statement, a
  change that cannot be saved reverts on screen; the "take effect immediately" and "cannot be saved"
  requirements are restated per change rather than per Save.
- `sync-status`: a membership that neither shares nor receives has its own status line, ranked first after
  "Loading…"; the app menu is not offered while settings are open (restated for the sheet, which leaves the
  joined screen visible but inert above it).

## Impact

- `:domain:model` — `Direction` gains a value for neither direction (persisted in the membership config, read
  by the upload extension; `includesUpload`/`includesDownload` must answer false for it); `SyncHealth` gains
  the both-off line; the settings surface state loses the draft/Save and gains a pending-withdrawal question.
- `:domain:presentation` — the settings intents apply per change through the existing reconfigure command;
  the withdrawal question and the per-change failure live in its local state; the health reduction ranks the
  new line.
- `:domain:feature` `ReconfigureEvent` — unchanged in shape (one call per change); its "Save" wording and the
  both-off path (cancel downloads, upload policy admits nothing) are exercised per change.
- `:ui:components` — a full-height sheet primitive (handle only, dimmed strip, swipe/back/strip dismissal);
  the confirm dialog reuses `AppConfirmDialog`.
- `:ui:screens` — `ReconfigureScreen` becomes the sheet's body with no actions; `ParticipationSections`
  becomes one card for both callers; the status line renders the new health; strings in English and German.
- `:test:integration`, `:test:control`/rig `/user` vocabulary — `reconfigure`/`cancelReconfigure` intents
  change shape; the harness and UI tests follow. No backend, upload-protocol or invite-link change.
