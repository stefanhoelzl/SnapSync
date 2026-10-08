# Proposal

## Why

Every build from 0.5 on can join, upload to and receive from an encrypted event, and the event page opens one in the
browser; only a rig build could create one. With 0.5 in App Store review, 0.6 is the first release whose users can all
read an encrypted event, so 0.6 creates encrypted events — and the parts of encryption a member can now meet (the
invite carrying a key, the incomplete-invite wall, a device that lost its key) become contract instead of rig-only
behaviour.

## What Changes

- A production build creates every new event encrypted. There is no choice on the create screen; a rig build keeps a
  switch to create a plain event, and its default becomes encrypted.
- An encrypted event's invite — QR and shared link — is the path form carrying the event's key after `#k=`. Plain
  events keep the fragment form. **BREAKING** for app versions before 0.5: they cannot open such an invite (they report
  a damaged invite). Keeping them out is the api's minimum app version, raised to 0.5 **outside this change**, before
  the 0.6 promote (tracked in `PENDING_CLEANUPS.md`).
- An invite missing its key, or carrying another, never joins: the join screen says the invite is incomplete. The event
  page without the key names the event but offers no photos.
- A member whose device lost the joined event's key (a restore to a new phone, an invalidated Android key) is told on
  the joined screen to open the event's invite again; nothing is uploaded or downloaded meanwhile, and no invite is
  offered — an invite is never shared without its key. Opening that event's
  whole invite restores the key in place — no leave, no new join screen.
- The event page's Google Play button carries the key in the install referrer, so an Android guest who installs from it
  still lands on the join screen.
- The Privacy Policy says what encryption covers: photos of events created by 0.6 and later are stored encrypted with a
  key only the invite carries; on an iPhone uploading in the background, the service receives one photo's key to
  encrypt that photo itself and keeps neither.
- Not in this change (recorded in `PENDING_CLEANUPS.md` for the 0.6 promote): the api's minimum app version 0.5, and the
  site + store-listing privacy claim. Retiring plain events altogether is a later change.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `privacy-security`: the invite link carries the key that opens an event's photos; what the service can and cannot
  read of an encrypted event's photos.
- `join-event`: the encrypted invite form, the incomplete-invite wall, restoring a lost key by reopening the invite, and
  the Play install referrer carrying the key.
- `event-site`: an encrypted event's page shows its photos only with the whole invite.
- `sync-status`: the status line telling a member their device needs the event's invite again, and its place in the
  priority.
- `manage-membership`: the invite (share and QR) is offered only whole — never while the event's key cannot be read.

## Impact

- `:domain` — `EventKeyMinting` / `DevControls` (the production read replaced), the join gate's same-event rung
  (key restore), a key-presence read-model and the transfer pause while the key is missing, `UiState` + the status
  line, a new `UserCommands` entry.
- `:ui:screens` — the new status line (EN + DE strings).
- `:test:rig` / `:adapter:generic:mock` — the encrypt switch's default flips; integration tests that create via the app
  and rely on mocked transfer bytes opt into plain.
- `site/` — the Play referrer carries `k`; the Privacy Policy section.
- `domain/model` `EventLink` — the referrer decoder accepts the key.
- No api change, no backend deploy. Merges after the `cleanups` branch (which introduces `PENDING_CLEANUPS.md`).
