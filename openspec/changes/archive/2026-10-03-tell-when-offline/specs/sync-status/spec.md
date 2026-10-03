## ADDED Requirements

### Requirement: The app says when it cannot reach the network

While the app is open, it SHALL tell the user when the device gives it no usable network, and SHALL say which of
two causes applies: the network is **blocked** for SnapSync — a setting of SnapSync's the user can change, such as
its mobile-data switch — or the device is **offline** — it has no network at all. When blocked, the notice SHALL
offer to open SnapSync's page in the phone's Settings; when offline it SHALL offer nothing, since only connecting
helps. The notice SHALL NOT appear for a drop that lasts only a few seconds, and SHALL clear as soon as the
network returns. A server that does not answer while the device has a network SHALL NOT be reported this way.
Mobile data being switched off for SnapSync while the device is on Wi-Fi SHALL NOT be reported, because the app
can use the network then. When the network returns while the app is open, the app SHALL resume its work at once —
what opening the app does — without the user doing anything. Where the notice appears and what it prevents is
each screen's: the joined screen's status line (below), the create screen (capability `create-event`) and the
join screen (capability `join-event`).

#### Scenario: Mobile data switched off for SnapSync, away from Wi-Fi
- **WHEN** the user has turned off mobile data for SnapSync and the device has only mobile data
- **THEN** the app says the network is blocked for SnapSync and offers to open its Settings page

#### Scenario: Airplane mode
- **WHEN** the device has no network at all
- **THEN** the app says the device is offline and offers no Settings action

#### Scenario: A brief drop shows nothing
- **WHEN** the network drops for a second or two and comes back
- **THEN** no notice appears

#### Scenario: Mobile data off but on Wi-Fi
- **WHEN** mobile data is switched off for SnapSync and the device is on Wi-Fi
- **THEN** no notice appears

#### Scenario: The server is down, not the network
- **WHEN** the device has a network but the server does not answer
- **THEN** no network notice appears; whatever failed says so as it does without this notice

#### Scenario: The network returns while the app is open
- **WHEN** the notice is shown and the network returns
- **THEN** the notice clears and the app resumes its work — received photos start arriving and the status
  updates — without the user leaving or reopening the app

## RENAMED Requirements

- FROM: `### Requirement: The missing-access line is the one line the member can act on`
- TO: `### Requirement: Only missing access and a blocked network make the status line tappable`

## MODIFIED Requirements

### Requirement: Only missing access and a blocked network make the status line tappable

When photo access is missing, the status line SHALL be a tappable attention line: tapping it SHALL
raise the system's access dialog if access was never decided, and SHALL open the app's page in the phone's
Settings if it was refused (capability `photo-access`). When the network is blocked for SnapSync, the status line
SHALL be tappable too, and tapping it SHALL open the app's page in the phone's Settings. These two SHALL be the
only tappable status lines; the offline line SHALL NOT be tappable.

#### Scenario: Never asked
- **WHEN** access was never decided and the member taps the line
- **THEN** the system's photo-access dialog appears

#### Scenario: Previously refused
- **WHEN** access was refused and the member taps the line
- **THEN** the app's page in the phone's Settings opens

#### Scenario: Network blocked
- **WHEN** the status line says the network is blocked for SnapSync and the member taps it
- **THEN** the app's page in the phone's Settings opens

#### Scenario: Offline
- **WHEN** the status line says the device is offline and the member taps it
- **THEN** nothing happens

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: photo access missing; no usable network (as "The app says when it cannot
reach the network" requires); the event has not started; the device cannot be verified; the app is still
reading its state; then "In sync" or synchronization in progress. Limited photo access SHALL NOT count as
missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: Missing access outranks a missing network
- **WHEN** a joined member without photo access has no network
- **THEN** the status line asks for photo access

#### Scenario: A missing network outranks everything else
- **WHEN** a joined member with access has no network, in an event that has not started or while everything is
  in sync
- **THEN** the status line says the network is missing, with its cause

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "In sync" or progress, never the missing-access line

### Requirement: A device that cannot be verified is shown, and never blamed on the member

The status line SHALL say, when the device holds no usable verification and the latest attempt to
obtain one failed, that this device cannot be verified and that sharing is paused, and SHALL add only what
is true in every case: the app keeps retrying and no photo is lost (the promise itself: capability
`privacy-security`). It SHALL NOT be tappable, name a cause, or ask the member to do anything. It SHALL
NOT appear while the current verification still works, however close to renewal, and SHALL NOT be shown
on opening the app on the strength of a failure from before that opening — only once the attempt that
opening triggers has also failed. It SHALL clear as soon as verification succeeds. While the device has no
usable network, the network line SHALL be shown instead (as "One status line in a fixed priority" requires).

#### Scenario: Unreachable server with an expired verification
- **WHEN** the device has a network, its verification has expired, and renewing it fails because the server
  cannot be reached
- **THEN** the status line says the device cannot be verified, sharing is paused, the app keeps
  retrying and no photo is lost — with no button and no suggested remedy

#### Scenario: Offline with an expired verification
- **WHEN** the device's verification has expired and renewing it fails because the device is offline
- **THEN** the status line says the device is offline, not that it cannot be verified

#### Scenario: A verification about to be renewed is not an alarm
- **WHEN** the device's verification still works but is due for renewal and a renewal attempt fails
- **THEN** the status line shows the ordinary sync status

#### Scenario: Opening the app retries before alarming
- **WHEN** an earlier background attempt failed and the member opens the app
- **THEN** the ordinary status shows until the attempt triggered by opening also fails; if it succeeds
  the cannot-verify line never appears
