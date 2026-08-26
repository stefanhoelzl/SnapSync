## ADDED Requirements

### Requirement: A repeated delivery of the same link is acted on once

The join gate SHALL treat a delivery of an event link it has **already acted on and not yet finished
with** as a **no-op**: no second decode outcome is surfaced, no second details fetch is issued, no
second pending join is started, and — on a link carrying `autoJoin=true` — no second provision is
performed.

This is required because **the platform delivers the same link more than once**, and that is measured,
not defensive. Build 687 on an iPhone XS (iOS 18.7.9) received the same URL twice on a cold launch,
~130 ms apart, once from the scene delegate's connection and once from SwiftUI's `.onOpenURL`; on an
SE2 (iOS 26.6) the same duplication occurred both while the app was running (8 ms apart) and cold
(105 ms apart). The app therefore SHALL NOT depend on any arrangement of platform hooks to achieve
"exactly once" (capability `ios-app-shell`) — it SHALL enforce it itself, so that a delivery hook
added, removed, or changed by a future iOS cannot reintroduce double-provisioning.

The boundary SHALL be defined by what the member is currently deciding, not by a timer:

- A repeat of the **same** link while a pending join for that same event is open SHALL be ignored,
  leaving the open surface and any choices already made on it untouched.
- A **different** link SHALL supersede the pending join, exactly as it does today.
- Once the pending join has been **committed or dismissed**, the same link arriving again SHALL be
  treated as a fresh delivery — a member may legitimately re-open an invite, and re-scanning the
  already-joined event remains the separate no-op it already is.

An ignored repeat SHALL still be **recorded** (capability `diagnostic-logging`), naming the entry
point that delivered it: "nothing happened because this is a duplicate" and "nothing happened because
the link never arrived" are different answers, and a device log that cannot tell them apart is what
made this defect take a full day to characterise.

#### Scenario: The same link delivered twice starts one pending join
- **WHEN** the same event link is delivered twice in quick succession through two different platform
  hooks, and the device is not joined to that event
- **THEN** one pending join is started, one details fetch is issued, and the second delivery is
  recorded as an ignored duplicate

#### Scenario: An autoJoin link delivered twice provisions once
- **WHEN** a link carrying `autoJoin=true` is delivered twice through two different platform hooks
- **THEN** the device provisions exactly once, and the second delivery performs no enrollment

#### Scenario: A different link still supersedes
- **WHEN** a pending join is open for one event and a link for a **different** event is delivered
- **THEN** the pending join is replaced by one for the newly delivered event

#### Scenario: The same link after the decision is a fresh delivery
- **WHEN** a pending join is committed or dismissed, and the same link is opened again afterwards
- **THEN** it is acted on as a new delivery rather than ignored

#### Scenario: An ignored duplicate is visible in the log
- **WHEN** a delivery is ignored as a duplicate
- **THEN** the device log records it together with the entry point that delivered it, so it is
  distinguishable from a link that never arrived
