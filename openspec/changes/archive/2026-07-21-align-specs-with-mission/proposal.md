## Why

The project began as a personal one-way photo backup and shifted, more than once, into what it is
now: **joined users easily share the photos they take during a short-lived event (days/weeks —
celebrations, holidays, trips), synced directly between device galleries, with no accounts and a
simple setup**. Three futures are named but not committed: Android support, paid events (small
events ≤3 persons / ≤3 days stay free), and concurrent membership in multiple events.

A full review of all 56 specs against that mission (decisions recorded in this change's
`design.md`) found the contracts largely aligned — the receive side, the anti-backup guards, the
account-free enrollment, and the App Store copy all match — but left a handful of drifts and
unnamed futures in place:

- `device-manifest` still specifies a **whole-library (no cutoff) projection**, a backup-era
  leftover that `photo-selection-policy` forbids ("no scope in which a membership admits the whole
  library"); the null path survives in `projectDeviceManifest`'s signature, dead in production.
- No spec states **who owns "the event is over"** — the capture cutoff is a lower bound only, and
  the fact that the event's server-side lifetime is the deliberate end bound is nowhere written.
- The client's **single-active-membership** model (switch = leave-then-join) is stated everywhere
  and futured nowhere, so new work keeps deepening it unwittingly.
- **Creation is free for any attested device** with no named payment attach point, and the
  capacity/lifetime bounds are not named as the future free/paid tier boundary.
- `startsAt` is contractually unbounded; with `endsAt = startsAt + duration` a far-future start
  quietly extends an event's total life — accepted, but silently.
- **App Attest and APNs** are load-bearing and Apple-only, with no note that they are platform
  bindings of platform-neutral needs (Play Integrity / FCM on a future Android client).
- The **mission itself lives nowhere in the repo**, which is what allowed the drift.

## What Changes

- **`device-manifest`**: remove the whole-library (no cutoff) projection clause and its scenario —
  every projection is date-filtered, because a membership's cutoff is required, never absent.
  Tighten `projectDeviceManifest` (and the producer's `startDate`) to non-null, behavior-preserving:
  the production call chain (`UploadCycle.onDiscovery`) is already non-null end-to-end.
- **`photo-selection-policy`** (Purpose): state that the cutoff is deliberately a lower bound only
  and that the event's lifetime (`event-limits`) owns "the event is over" — uploads stop because
  the event stops existing, never because the policy excludes late photos.
- **`join-event` + `upload-lifecycle`** (Purpose): pin single-active-membership as the *current
  contract*, name concurrent multi-event membership as a future direction, and note the pieces
  that already compose with it (event-independent ledger key, device-partitioned bytes).
- **`event-creation`**: the token requirement names creation as the future payment gate's attach
  point; the start-date requirement documents the `event-limits` interplay — a far-past start
  mints an already-expired event (self-defusing), a far-future start extends the marker's life
  (accepted while creation is attestation-gated and free; re-examined under paid events).
- **`event-limits`** (Purpose): name capacity/duration as the future free/paid tier boundary,
  becoming creator-chosen behind a payment gate with no schema or enforcement change.
- **`device-attestation` + `apns-push-sender`** (Purpose): name App Attest / APNs as the iOS
  bindings of platform-neutral needs, with Play Integrity / FCM as the Android bindings-to-be.
- **Mission home**: the mission statement and the three named futures land in
  `openspec/config.yaml`'s `context:` block (injected into every OpenSpec agent, never rewritten
  by `update`) and, compressed, in `CLAUDE.md`'s opening.

Deliberately unchanged (decided, not overlooked — rationale in `design.md`): the 30-day duration
and 10-device capacity defaults; no capture-date end bound; no user-facing disclosure of event
expiry; the device-UUID identity posture; the lazy expiry reap (a nightly cleanup is a separate
in-flight change).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-manifest`: whole-library projection clause + scenario removed (the only behavioral-text
  change, and it removes a dead path).
- `photo-selection-policy`: Purpose gains the end-bound ownership statement.
- `join-event`: Purpose pins single-membership as current and names the multi-event future.
- `upload-lifecycle`: Purpose does the same for the arm's transition table.
- `event-creation`: token + start-date requirements gain future/interplay prose (scenarios
  unchanged).
- `event-limits`: Purpose names the free/paid tier boundary.
- `device-attestation`: Purpose names the platform seam.
- `apns-push-sender`: Purpose names the platform seam.

## Impact

- **`:domain`**: `model/DeviceManifest.kt` (`projectDeviceManifest` `startDate` → non-null),
  the manifest producer's matching parameter, and `DeviceManifestProducerTest`'s `startDate = null`
  call sites. No behavior change — production never passes null.
- **Docs**: the eight spec deltas, `openspec/config.yaml` (context block append — the archiving
  gates in it are preserved verbatim), `CLAUDE.md` (opening paragraph).
- **No backend, API, UI, or dependency changes.**
