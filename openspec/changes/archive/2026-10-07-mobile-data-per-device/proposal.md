# Proposal

## Why

Whether photos may use mobile data follows the member's data plan, not the event: a member on a small plan
wants it off for every event, and today has to remember to switch it off on every join screen, because each
new event starts from "on". The choice also sits among the event's own settings (share, receive, range,
album), where it reads as a property of the event. It belongs to the phone — set once, in the app menu,
reachable with or without an event. The per-membership choice has not reached a released build yet, so it
can move now without carrying anyone's setting over.

## What Changes

- **One choice for the device.** "Use mobile data for photos" is a setting of the phone, not of a
  membership. It governs every photo transfer of whatever event the phone is in, keeps its value across
  leaving and joining, and starts on for a fresh install. Reinstalling the app starts it on again.
- **It lives in the app menu.** The menu gains the switch, with its note (photos are shared and received on
  any network / only on Wi-Fi), as its first section, ahead of "Report a problem". It is offered wherever
  the menu is — on the joined screen and with no event at all — and applies as it is flipped, with the menu
  staying open. A change that cannot be saved puts the switch back and says so.
- **BREAKING (internal builds only)**: the choice leaves the join screen and the event settings. A
  membership no longer carries one; an internal build's membership that had it off starts on with the
  device setting. No released build ever had the per-event choice, so no user is affected.
- Unchanged: what "off" means (photos wait for an unrestricted Wi-Fi, in the background, never lost),
  that only photo transfers are governed, that a change governs only transfers that start afterwards, and
  the "Waiting for Wi-Fi…" status line.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `mobile-data`: the choice belongs to the device instead of each membership — made in the app menu, kept
  across events, on until the member turns it off; the scenario "a new event starts from the default" is
  inverted.
- `join-event`: the join screen no longer offers the mobile-data choice.
- `manage-membership`: the event settings no longer offer it, and changing it is no longer a settings change.
- `sync-status`: the app menu holds the mobile-data switch first, ahead of "Report a problem", with and
  without an event.

## Impact

- `:domain:model` — `EventConfig` loses `mobileData`; `RangeForm`/`JoinChoice` lose it; the menu's state
  gains the device setting and its could-not-save note; `UiIntent.MobileData` becomes a menu intent.
- `:domain:services` — a small device-settings service over the `Preferences` port (absent or unreadable
  reads as "on"), readable from both iOS processes.
- `:domain:feature` — `JoinEvent`/`ReconfigureEvent` drop the field; the transfer network for uploads,
  downloads and the Android wake, and the waiting line, read the device setting; the diagnostic dump moves
  the value from the event block to the device block.
- `:domain:compose`, `:domain:presentation` — the setting service wired into the app and the extension; a
  user command to change it; the reconfigure command loses its parameter.
- `:ui:screens`, `:ui:components` — the menu's switch row; the participation card loses the mobile-data row
  (English and German strings move).
- `:test:rig` `/user` vocabulary, `:test:control`, `:test:integration`, the desktop harness's mirror — follow
  the intent's move. No backend, upload-protocol, invite-link or adapter change.
