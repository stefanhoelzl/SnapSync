# Proposal

## Why

The only way to report a problem is a hidden double-tap on the app's name, which only testers know about. A guest
who opens SnapSync while joined usually does so because something looks wrong — their photos are missing, or
someone else's haven't arrived — and right then they have no visible way to tell the operator. Reports from real
users are the signal we lack most now that the app is past its testers.

## What Changes

- A **menu button** (☰) in the top-left corner of the app's title row opens a **side drawer** on every screen,
  except while the event-settings surface is open and while a join or a create is in progress.
- The drawer holds, in this order: **Report a problem** (set apart from the rest; opens the existing report
  sheet), **Website** and **Privacy policy** (both open in the browser), and the app's **version and build
  number** as a footer you can't tap.
- After a report is sent or saved, the app **confirms it briefly**: sent, saved on this device, or, if neither
  happened, that it could not be sent. It never claims the report was delivered.
- The report sheet is otherwise unchanged: same wording, required description of up to 200 characters, Send
  (or Save on a build that cannot report), no rate limit.
- The hidden double-tap on the app's name **stays** as a second way in, on every screen.
- The site's privacy section describes how to reach the report through the menu.
- The marketing screenshots are re-captured, because the title row now shows ☰.

No joined-screen prompt is added: the drawer is the only visible way in (the "Something not working?" nudge on
reopen was considered and dropped).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `privacy-security`: the requirement "A detailed bug report leaves the phone only when the user sends one" changes
  from a *hidden* entry to a visible one in the app menu (the double-tap stays), and adds the confirmation after
  sending or saving.
- `sync-status`: gains a requirement for the app menu itself — where it is available, what it holds, and that its
  links open outside the app.

## Impact

- **UI** (`:ui:components`, `:ui:screens`): the title row gains a navigation button; a drawer component; the
  status screen hosts the drawer and a short confirmation; UI tests for both.
- **Model and presentation** (`:domain:model`, `:domain:presentation`): new overlay flags and user intents
  (open/close the menu, open the website or privacy policy, dismiss the confirmation); the send intent's result
  turns into a confirmation instead of being fire-and-forget; the app version and build number reach `UiState`.
- **Composition** (`:domain:compose`, `:domain:host`): opening the website and policy reuse the existing open-link
  command; the send command returns its result.
- **Test surfaces**: `:test:rig`'s user-intent vocabulary, the desktop world harness, and `:test:integration`
  scenarios.
- **Site** (`site/`): the privacy section's wording about how a report is opened.
- **Screenshots**: `screenshots/` (iOS and Android) re-captured and committed.
- No backend, storage or port change. What leaves the device is unchanged, so the Privacy Policy's list of data
  stays as it is.
