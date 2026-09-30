# Proposal — timely-background-receiving

## Why

A member's phone learns about the other members' new photos only from a silent wake, from opening the app, or
from joining. When the wake is lost — dropped by the phone's system, delayed, or impossible because the phone
has no push service — the photos wait until the member next opens the app, however long that is. Members who
only receive, and every caught-up Android member, get no background time of their own at all, so for them the
gap is structural, not occasional. The receiving spec even forbids the fix ("The app SHALL NOT poll in the
background"), which is a mechanism rule, not an outcome, and fails the swap test.

Phase 3 recorded "no periodic wake; revisit if phase 6 shows missed photos"
(`changes/archive/2026-09-29-android-sharing`, D5). This change reverses that knowingly: the receive-only gap is
structural, and no measurement can close it.

## What Changes

- **Receiving no longer depends on a wake arriving.** Without one, other members' new photos still arrive in
  the background in a timely manner, as the phone's system allows — and at the latest the next time the member
  opens the app. The "SHALL NOT poll" sentence is removed. No figure and no cadence enters the spec.
- **A force-quit stops receiving until the next opening** — stated in the receiving spec, as the uploading spec
  already states it for uploads. A force-quit is the member saying "stop".
- **The app's background wake never stops while the device is joined**, with a busy cadence while work remains
  and an idle cadence (hourly) otherwise. Members who receive but do not share, members whose uploads are held
  back, members under limited photo access, and caught-up members whose phone's system already wakes them for
  new photos all fall back to the idle cadence instead of no wake at all.
- **Caught-up iPhones on iOS 26.1 and later with full access, whose system uploader is confirmed registered,
  wake less often** than today (idle instead of busy): the system uploader already reacts to new photos, so the
  app's own frequent re-check buys nothing there. Below iOS 26.1 and without a confirmed registration, full-access
  sharers keep today's frequent wake until the event's end. **Changes shipped iOS behaviour** (less background
  activity).
- **After the event's end, every caught-up member drops to the idle cadence**: nothing new can enter the event.
- **A background wake checks the event for new photos, and after the end for its close, each at most once an hour
  per event.** A push, an opening and a join still check every time.
- **Under limited photo access, a background start reads the member's selection.** Today a background start that
  finds the app closed never reads the selection, so it withholds every upload — including photos already waiting —
  although the uploading spec promises limited-access uploads on the app's own wake-ups. Measured on an iPhone SE
  (2nd gen., iOS 26.6.2): the read works in a background-launched process and raised no system prompt, including
  after a camera photo outside the selection.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `receiving-photos`: "New photos are announced by a silent wake, and never only by it" — the no-poll sentence is
  replaced by the outcome (photos arrive in the background without a wake, in a timely manner as the system
  allows); a scenario for "no wake and the app stays closed"; a scenario for the force-quit.

`background-upload` (limited-access uploads on the app's own wake-ups), `photo-access` (no prompt of the app's
making; nothing outside the selection) and `manage-membership` (leaving on its own once the event is finished)
already state the outcomes this change delivers or keeps; none gains a line.

## Impact

- **Domain:** the heartbeat's re-arm rule (tail runner + heartbeat service) — keyed on "joined", returning
  none / busy / idle; a throttled union reconcile run by the heartbeat wake; the end-of-wake close check behind the
  same throttle; a small per-event "last checked" service over the existing preferences port; the selection
  observer opened at composition instead of host assembly.
- **Wake port:** the trigger carries busy or idle. iOS adapter: a second task identifier
  (`BGAppRefreshTaskRequest`) beside the processing heartbeat, mutual replacement, cancel covering both. Android adapter: the same work with a longer
  initial delay.
- **iOS project:** the `fetch` background mode and the new permitted task identifier in the app's `Info.plist`;
  the runtime-identity pin; the rig's entry routing.
- **Tests:** four new wake-contract clauses; the iOS device recording re-taken, plus a refresh-off recording;
  the rig's OS record gains the pending wake; integration scenarios for the cadence rule and both throttles; an
  Android force-stop check on the emulator (non-gating).
- **Docs:** `CLAUDE.md`'s limited-access read rule (fact ①) and `docs/architecture.md`/`docs/testing.md` where they
  describe the heartbeat or the read discipline.
- **Backend:** none. Cost per receiving device: at most one union read and (after the end) one event read per
  hour on background wakes.
- **Parallel work:** phase 4 ("receiving", Android downloads + push) also edits `receiving-photos`; whichever lands
  second rebases its delta. Until phase 4 lands, the Android reconcile runs against the ports with no real download
  adapter.
