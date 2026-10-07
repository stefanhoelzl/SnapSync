# Proposal

## Why

A phone the service refuses as not genuine cannot create or join an event, but the app tells its user
"Couldn't connect. Check your connection and try again." That is false, and it sends them hunting for a network
problem they don't have. Bugsink SNAPSYNC-42 (2 reports), -43 and -44 (2026-10-06/07) are four bug reports from one
OnePlus 8 Pro on Android 11, on builds 2214 to 2243, each written as "Connection error when creating an event". The
backend refused every one of that phone's attestations, and the edge log names the same cause for all of them: *the
chain does not end at a pinned attestation root*. Refusing such a phone is the promise (capability
`privacy-security`). Misreporting the refusal is the defect: the user can't tell they've hit a wall, and we can't
tell from their report why.

## What Changes

- When the service refuses this phone's verification, the app says so, and names which of three kinds of cause
  applies. It never says the server could not be reached:
  - **the phone's system is modified** (an unlocked bootloader or a system its maker did not ship);
  - **the phone could not be verified**: its hardware's proof is not one the service recognises. The app does not
    blame the user and offers to report the problem;
  - **this copy of the app is not the official one**: the user is pointed to the store.
- The **front screen** shows the refusal as soon as the app learns of it at launch, in place of the scan hint, before
  the user fills anything in. Create stays available, and each tap re-checks first, so a refusal the service stops
  making heals without reopening the app.
- A **failed create** caused by the refusal shows the refusal, not "the server could not be reached".
- A **failed join** caused by the refusal shows the refusal, not a generic failure.
- The **joined screen's** "cannot verify this device" line names the cause when the service has definitely refused
  the phone. This reverses its "SHALL NOT name a cause" rule for that one case. A verification that merely could not
  be obtained (no answer, an expired proof that could not be renewed) keeps today's line.
- **"Report this"** on the could-not-be-verified notice opens the existing bug-report sheet with its description
  already filled in. Nothing is sent until the user confirms, as today.
- A report opened from **"Report this"** also carries what the app knows about the refused verification: the
  service's answer and, on Android, a summary of the certificates the phone presented. The summary gives each
  certificate's subject, issuer, validity and key algorithm, plus the fingerprint of the root's key, so the operator
  can see which root a refused phone's chain ends at and decide whether to pin it. Names are kept as the
  certificates give them, even where one is named after its serial number; the raw certificates are not sent. A report opened from the menu or by the hidden gesture carries none of this.
- Backend: a refused attestation answers with a coarse, closed reason under the current API version. The frozen
  older version is unchanged. Apple and Android verifiers use the same reasons where they apply. When any
  certificate check failed (root, chain, validity, revocation), the answer also carries one `certificate` code; which
  certificate problem it was is read from the report's chain summary, never told to the user.

Not in this change: the backend's rejection log line naming the chain's root, which is internal work that ships
separately. This change gives the operator the same facts through the user's report.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `privacy-security`: "Only a genuine SnapSync app can change an event" gains the promise that a refused phone is
  told it was refused and which kind of cause applies, is never blamed when its proof is merely unrecognised, and is
  offered a report. "A detailed bug report leaves the phone only when the user sends one" allows the sheet to open
  with a description already filled in, still requiring the user's confirmation. A report opened from that offer also
  carries the service's answer and the phone's certificate summary, and no other report does.
- `create-event`: the front screen shows a refusal in place of the scan hint while Create stays available; a create
  that fails because of the refusal says so instead of reporting the server as unreachable.
- `join-event`: a join that fails because of the refusal says so, instead of a generic failure.
- `sync-status`: "A device that cannot be verified is shown, and never blamed on the member" names the cause for a
  definite refusal; the transient case keeps today's line.

## Impact

- **api/**: the mint route's refusal body under the current version; the Android and Apple verifiers classify each
  failure into one of the three reasons; backend tests. The frozen version keeps its body.
- **domain/**: the attestation outcome carries the refusal reason; the attestation service exposes the latest
  verdict (refused with reason / not refused) beside `attested`; create and join failures caused by a gated route's
  `401` after recovery read that verdict instead of collapsing into "transient"; presentation reduces it into the
  front, create, join and joined screens' state.
- **ui/**: new strings in English and German; the notice on the front screen, the create and join failure lines,
  the joined status line; "Report this" opening the report sheet prefilled.
- **adapter/android**: the device-integrity adapter summarises the attestation chain it presents. iOS carries none.
- **Diagnostics**: the attestation service keeps the last refused attempt's facts in memory, and a report opened from
  "Report this" adds them to its state.
- **Mocks / harness / rig**: a lever to make the backend mock refuse attestation with a given reason, so every new
  state is reachable without forging it.
- Older app builds are unaffected: any `4xx` body they don't recognise is still a refusal to them.
