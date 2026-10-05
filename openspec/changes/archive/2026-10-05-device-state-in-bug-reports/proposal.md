# Proposal

## Why

A bug report today carries the build, the membership, the photo permission, store counts and the two log tails —
but not the device conditions that most often explain "nothing uploads" or "stuck below 100%": whether the network
was usable or restricted, whether power saving or a withheld background allowance was throttling the app, nor the
numbers the member was actually looking at. The operator has to ask the member for them after the fact, or guess.
The rule that kept them out — "a report reads nothing the app does not already read" — is dropped in favour of a
bound on *what kind* of thing a report may hold.

## What Changes

- A bug report additionally carries the **device's state** at the moment it is sent:
  - network access (online, online on a restricted network, offline, blocked for this app) — read fresh, not the
    status screen's delayed notice;
  - power saving (Low Power Mode / Battery Saver);
  - the background allowance the OS gives the app (Background App Refresh on iPhone; the standby bucket and the
    battery-optimisation exemption on Android);
  - battery level, whether it is charging, and the thermal state;
  - the device's own id and its time zone;
  - the app's memory footprint (iPhone only — Android reads none).
- A bug report additionally carries the **numbers the status screen showed** — shared done/total and received
  done/total, or "off" — beside the existing store counts, and, under limited photo access, **how many photos the
  member's selection holds**.
- A fact the platform does not have is left out; a reading that fails is reported as `failed (<reason>)` and the
  report is sent anyway.
- The report's content is bounded: it holds this app's own state and the device's settings and conditions, and
  never a photo or what it shows, another app's data, the device's location (beyond its time zone), contacts, or
  the name the user gave the device.
- The sheet says the report carries the device's state as well as the activity log and sync state.
- The rule "a dump reads no data the app does not already read" is **dropped** (it lives in code KDoc, not in a
  spec).
- Free disk space is **not** included: on iPhone it may only be read for a bug report if the sheet displays it, and
  showing it was judged not worth the sheet change.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `privacy-security`: "A detailed bug report leaves the phone only when the user sends one" — the report also
  carries the device's state and the screen's numbers, the sheet says so, and what a report may hold is bounded.

## Impact

- **Domain**: the diagnostic dump assembly (`CollectDiagnosticDump`) and its model; a new port for the device
  conditions not read today (power saving, background allowance, battery, thermal); the composition's dump wiring
  (network read, device id, time zone, memory footprint, selection size); the presentation host passes the
  rendered counts with the send command.
- **Adapters**: an iOS and an Android implementation of the new port, plus a mock and a port contract.
- **UI**: the report sheet's two copy variants.
- **Privacy disclosures**: the Privacy Policy on the site (`site/`) must name device state in the same release
  (capability `privacy-security`, "The Privacy Policy states what leaves the device"); the Play data-safety notes
  (`metadata/play/declarations.md`) are re-checked. The iOS privacy manifest is unchanged — no required-reason API
  is added.
- **Budget**: about 20 short keys (< 1 KB) inside the existing ≤ 4 KB state row; the log budget is unchanged.
