## Why

The joined screen leaves four questions a member has at a glance unanswered or ambiguous: *am I in this event?*
(nothing says so — the screen just shows a name and a QR), *what is this QR for?* ("Share this event" reads as
sharing photos as much as inviting people), *how long does this last?* (the range is never shown — only a start
time before it begins and "Event ended" after), and *how much has actually gone through?* (one status line with
no numbers). The last one reverses a deliberate decision (`2026-07-04-redesign-event-ux` removed "n of N images
synced" as a leftover of the personal-backup model); it is right to reverse now because the counts it removed
counted *a library backup*, while the counts proposed here count *the event*: what this membership shares out
of what its range and origin rules admit, and what arrived from the other members — the same two totals the
arrows are already derived from, so the numbers and the arrows can never disagree.

## What Changes

- **The joined screen says the device has joined.** Beneath the event name, a sentence states that this device
  has joined the event. Host and guest see the same words (the app does not know who created an event, and the
  host is a member too).
- **The joined screen shows how long the event lasts.** A dates line under the heading carries the event's range
  in the device's own calendar days (a same-day event shows its start and end times instead) followed by a
  relative phrase: *starts in …* before the start, *ends in …* while it runs, *ended* after the end. The phrase
  counts days, and within the last day hours, then minutes. It updates while the app is open, within a minute.
- **Time is said once.** The not-started status line no longer states the start date and time — the dates line
  does — and instead says that sharing starts with the event. The separate "Event ended" marker above the status
  line is removed; the dates line says *ended*. The "waiting for n of m members" note stays, now beside the
  counts.
- **The joined screen counts photos again.** Under the unchanged status line, a quiet counts line reads, per
  direction, "*n*/*N* shared" / "*m*/*M* received" while work remains in it, and just the total once it is
  complete. A direction the member switched off says so ("Not sharing" / "Not receiving") — unless the device is
  nevertheless doing work in it, in which case its numbers are shown, never masked. The counts line appears only
  under "In sync" or synchronization in progress; it is hidden before the start, without photo access, while the
  device cannot be verified, and while the app is still reading its state.
- **The QR is labelled as an invitation for other people.** Its heading becomes an invitation ("Invite others")
  and its caption tells the member that others join by scanning it with their camera.
- **Long event names stop at two lines** in the heading (visual; no spec change).

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `sync-status`: the joined screen states it has joined and shows the event's dates and time left; the
  "no numbers" rule is replaced by a counts line with its own visibility rule; the not-started line no longer
  states the start; the ended marker moves into the dates line. The Purpose paragraph's "no photo counts" is
  corrected at sync.
- `manage-membership`: the invite QR is presented as an invitation for others to join.

## Impact

- **UI**: `:ui:screens` `JoinedLayer` (heading block, invite labels, counts line, the waiting note's new home),
  `StatusScreen`'s heading; `:ui:components` `AppStatusLine` (the not-started variant's copy, the ended marker
  removed) and the heading component (a two-line limit for the name).
- **State**: `:domain:model` `Layer.Joined` gains the event's range, its timing (phase + time remaining) and the
  counts; `SyncHealth.NotStarted` no longer needs its start. `:domain:presentation` `StatusContainerHost`
  reduces them from sources it already holds (config, the sync and download progress, the minute tick).
- **Tests**: `:ui:screens` UI tests, `StatusContainerHost` tests, `:test:integration` assertions on the joined
  `UiState`; `UiState` is the rig's wire type, so `:test:control` and the desktop mirror follow automatically.
- **Screenshots**: the `in_sync` raws (light/dark) change and are refreshed; `create` and `joining` do not.
- No backend, storage, upload or download behavior changes.
