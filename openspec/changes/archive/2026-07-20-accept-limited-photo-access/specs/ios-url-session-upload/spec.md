# ios-url-session-upload — delta

## MODIFIED Requirements

### Requirement: Per-version tier selection

Tier selection SHALL occur at the existing `backgroundUploadSupported()` guard
(`NSProcessInfo.isOperatingSystemAtLeastVersion(major=26, minor=1, patch=0)`) in the app composition
root (`SnapSyncRoot`). On `false` the app SHALL construct and start the app-driven pump
(the `IosUrlSessionUploadPlatform`, the `BackgroundUploadPump`, and the `IosBackgroundScheduler`) — the
only mechanism that exists there. On `true` the app SHALL construct **both** the `ios-photokit-upload`
path (`setUploadJobExtensionEnabled`) **and** the app-driven pump, and the tier-neutral orchestrator
(capability `upload-lifecycle`) SHALL start exactly one of them, selected by current permission: the
PhotoKit path under `GRANTED`, the app-driven pump under `LIMITED` (the OS never invokes the extension
under a partial grant — capability `ios-photokit-upload`). The two mechanisms SHALL be mutually
exclusive **at start-time** within one running process (`upload-lifecycle`, "Exactly one producer
started per process").

#### Scenario: Version gate selects the app-driven mechanism below 26.1
- **WHEN** `backgroundUploadSupported()` returns false
- **THEN** the composition root starts the app-driven pump and does not call `setUploadJobExtensionEnabled`

#### Scenario: Full access on 26.1+ runs PhotoKit only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `GRANTED`
- **THEN** the PhotoKit extension is registered and the app-driven pump is not started

#### Scenario: Limited access on 26.1+ runs the app-driven pump only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `LIMITED`
- **THEN** the app-driven pump is started and the PhotoKit extension is not registered

## ADDED Requirements

### Requirement: The app-driven tier serves limited memberships with selection-driven triggers

The app-driven mechanism SHALL serve `LIMITED` memberships unchanged in its transport, staging,
ledger-writer, and cycle semantics — the measured fact grounding this tier's limited role is that it
uploads under `.limited` on the first attempt with the full cycle (bytes, manifest, notify). What
differs under `LIMITED` is **when the cycle reads the library**: the pump's autonomous triggers
(foreground entry, silent push) SHALL NOT initiate a library read while permission is `LIMITED`
(capability `limited-photo-access`, "No autonomous library reads"); cycles that read run from the
cold-launch baseline and from selection-change consumption, and continuation triggers
(`onUploadCompleted`, session events, the heartbeat) SHALL drain already-enqueued work without a fresh
library read.

#### Scenario: A selected photo uploads under limited via the ordinary cycle
- **WHEN** a `LIMITED` member with upload-inclusive direction selects an in-scope photo and the
  selection-change consumption enqueues it
- **THEN** the app-driven mechanism uploads it exactly as it would any enqueued work — background
  session, staging, ledger `COMPLETED`, manifest, notify

#### Scenario: Continuation drains without re-reading the library
- **WHEN** several enqueued uploads complete one after another under `LIMITED`
- **THEN** the continuation cycles upload the remaining queue without initiating a new library read
