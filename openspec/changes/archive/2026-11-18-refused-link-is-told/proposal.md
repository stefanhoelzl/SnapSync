# Proposal

## Why

When a member taps the website or the Privacy Policy in the app menu and the phone refuses to open it, nothing
happens. The menu closes, no browser appears and the app says nothing. `app-experience` promises "Following a link
SHALL open the page in the browser", and when the phone will not, the member is left with a dead tap.

This happens in the field. Bugsink SNAPSYNC-51, -53 and -71 hold 13 refusals since the menu shipped (2026-10-07):
five iPhone models, iOS 27.0.1 to 27.2.1, builds 0.5, 0.6 and 0.7. Each is a member tapping one link, then the other,
then giving up. The app cannot reproduce it. On the SE2 (iOS 27.0.1), the dev build and the App Store build both
opened both pages every time, by rig and by finger (measured 2026-11-18). The menu drawer, the app's own associated
domain and iOS 27 itself are therefore ruled out. What remains is the phone's own state, most likely a Screen Time
restriction on Safari or web content. Each refusal is also close in time to that version's App Store submission, so
App Review's devices are a plausible source. iOS answers only "not opened" and gives no reason, so the logs cannot
yet tell these apart.

## What Changes

- **A refused page is told.** When the phone does not open a page the member asked for, the app briefly says so on
  the screen they are on, and says where the page can be found: the website's address, or the store by name. This
  covers the menu's two links and the store button on the update-required notice, because every page the app opens
  outside itself goes through one command and fails the same way.
- **The refusal records its circumstances.** On iPhone, the refusal's log line also records whether the app was in
  front, whether iOS reports any app able to open the address, and the scene's state, so the next field refusal
  tells "restricted" from "not in front". The line stays an error so it still reaches Bugsink. Android's refusal
  already names its cause and is unchanged. No user can observe this part.

Nothing else changes. The share sheet's refusal is out of scope: it is a different surface with no field reports.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-experience`: gains the requirement "A page the phone will not open is told". The existing requirement "The
  app menu is one tap away on every screen" is unchanged: it still promises that following a link opens the page,
  and the new requirement says what the member sees when the phone does not.

## Impact

- **Core:** the open-link command answers what became of the hand-off, as the report command does, and presentation
  shows a short self-clearing notice from it. The port's `Handoff.Refused` gains the circumstances an adapter could
  read.
- **iOS adapter:** on a refusal, the link opener reads iOS's own answers to "can any app open this?" and "is the app
  in front?". These are new calls at the OS boundary, so the `LinkOpener` device recording is re-recorded on the SE2.
  `Info.plist` may need `http`/`https` in `LSApplicationQueriesSchemes` for iOS to answer the first question, which
  the apply phase measures.
- **UI:** one notice on the screen, its words in `strings.xml` (English and German).
- **Bugsink:** SNAPSYNC-71 stays open and collects the new fields. The fix commit carries **no**
  `Bugsink-Resolves` trailer: a refusal can still happen, and only the cause the data shows can close it.
- **Release:** an app change, label `enhancement`. No api, site or store change.
