# Proposal

## Why

Every photo transfer today uses whatever network the phone has, and two specs promise exactly that
(`background-upload`: uploads are never held back for Wi-Fi; `receiving-photos`: downloads use cellular as
well as Wi-Fi). A member on a limited or expensive plan — a guest abroad on a holiday event, a host with
a small data allowance — has only one way to protect it: iOS's per-app mobile-data switch or Android's
per-app restriction. That switch cuts off **everything**, so joining, the status counts, and every server
call stop working too. Members need to keep photos off mobile data while the app itself keeps working.

## What Changes

- A new per-membership choice, **"use mobile data for photos"**, offered on the join screen beside the
  share, receive and album choices, **on by default** (so joining without touching it behaves as today),
  and changeable in the membership's settings until the event closes.
- With the choice off, photo uploads and downloads wait for an unrestricted Wi-Fi network: they do not
  use mobile data, another phone's personal hotspot, or a network on which the phone's Low Data Mode /
  Data Saver is in force. Everything else the app exchanges with the server — joining, publishing what the
  member shares, the counts, renaming, leaving, diagnostics — keeps working on any network.
- A change of the choice applies to transfers that start afterwards; a transfer already handed to the
  phone keeps the network rule it started with (an explicit exception to "saved settings take effect
  immediately").
- While photos are held back for Wi-Fi, the joined screen says so ("waiting for Wi-Fi") instead of the
  generic pending line.
- Nothing changes at event close: a member who stays off Wi-Fi until the event closes has chosen that,
  and the status line has told them.
- Measured platform facts that shape the implementation are recorded in `design.md` (SE2 and XS on iOS,
  the emulator on Android) — not in any spec.

## Capabilities

### New Capabilities
- `mobile-data`: what the member's mobile-data choice means — which transfers it governs, which networks
  count as mobile data, that all other communication is unaffected, that held photos go out as soon as
  an unrestricted Wi-Fi is available and are never lost, and how a change applies to transfers already
  under way.

### Modified Capabilities
- `join-event`: the join screen offers the mobile-data choice, on by default, beside share, receive and
  album.
- `manage-membership`: settings offer and pre-fill the choice; "Saved settings take effect immediately"
  gains the exception for transfers already under way.
- `background-upload`: "the app SHALL NOT hold uploads back for Wi-Fi" becomes "unless the member chose
  not to use mobile data for photos (capability `mobile-data`)".
- `receiving-photos`: "Downloads SHALL use cellular data as well as Wi-Fi" becomes the same exception.
- `sync-status`: a status line telling the member their photos wait for Wi-Fi, in place of the pending
  line, while that is why work waits.

## Impact

- **Model**: the membership's choice (persisted with the membership in both processes' shared config);
  the join/settings form state.
- **Ports**: `NetworkMonitor` (from `network-connection`, which this branch is based on) gains "online
  but restricted"; `UploadTarget` and `Download.start` carry the network rule; `WakeTrigger`'s network
  requirement widens to "unrestricted" (Android resumption).
- **Adapters**: iOS request flags in the shared upload request builder and the download request;
  Android DownloadManager's metered flag, the in-process upload's own gate, WorkManager's network type;
  both `NetworkMonitor` adapters read the restriction; the mocks grow the matching levers.
- **UI**: one checkbox on the join and settings screens, one status line.
- **Contracts and tests**: `NetworkMonitor`, `Upload`, `Download` and `Wake` contracts gain
  restricted-network clauses (emulator live; SE2/XS recorded); integration tests through the rig.
- **No backend change**: the choice never leaves the device.
- **Depends on** branch `network-connection` (the `NetworkMonitor` port) landing first.
