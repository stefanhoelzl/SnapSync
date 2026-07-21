## Why

A member who granted limited photo access has two ways to get more photos into an event: widen the
hand-picked selection ("Choose more photos"), or switch the grant itself to Full Access — but the app
offers no route to the second. iOS provides no API to re-raise the full-access dialog under
`.limited`, so the only path is the Settings app, and today a member has to find it unguided. For an
event guest whose photos are silently out of scope, that gap reads as "the app doesn't work", not as
a permission choice.

## What Changes

- The joined layer of the status screen gains an **"Allow full access"** affordance, rendered
  directly below "Choose more photos", under the same condition (permission is `LIMITED`), in every
  sync health.
- Tapping it deep-links to the app's system Settings page via the existing `openSettings` command —
  no new command, port, or dialog. The user completes the switch there (Photos → Full Access); iOS
  then terminates and the member relaunches into the existing cold-launch `GRANTED` path.
- No interstitial consent surface is added: the label plus the OS-mediated Settings toggle are the
  consent, and the widened scope stays bounded by the same cutoff + origin-exclusion policy that
  bounds every full-access member (rationale recorded in design.md).
- Same quiet visual weight as its neighbor (`SecondaryButton` — borderless text); the limited grant
  remains first-class and un-nagged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sync-status-screen`: the partial-grant resting affordance requirement grows a second affordance —
  "Allow full access" renders beneath "Choose more photos" under a `LIMITED` grant and invokes the
  settings route; absent otherwise.
- `limited-photo-access`: the picker-ownership requirement's route set changes — Settings stops
  being the stranding fallback and becomes an offered, in-app-discoverable route to widen the grant
  itself (selection widening stays the picker's job).

## Impact

- `:ui:screens` `StatusScreen.kt` joined layer — the only production code touched; the composable
  already receives `onOpenSettings` (the `DENIED` status-line affordance), so this is render + wiring
  only. No `UiState`, presentation, domain, or adapter changes; `UserCommands.openSettings` and the
  `PhotoAccessRequester` port are reused as-is.
- `:ui:screens` tests — render/tap coverage for the new affordance.
- Forge harness shows the affordance automatically under the `LIMITED` permission preset; marketing
  screenshots unaffected (no forge state renders a limited-grant joined layer).
- Pre-merge, operator-assisted on-device verification of the full LIMITED→GRANTED upgrade path
  (Settings flip, OS kill/relaunch, producer switch, reconcile-no-re-upload, and an end-to-end
  upload landing in the storage zone) — protocol in tasks.md.
