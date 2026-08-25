## MODIFIED Requirements

### Requirement: The app renews on every wake, well before expiry

The app SHALL check the token's remaining lifetime at **every** point its process is already awake — a
launch, a foreground entry, a silent-push wake, and each `BGTask` handler — and SHALL renew when the
token is absent, expired, or nearing expiry.

Renewal SHALL NOT depend on a dedicated scheduled background task. (iOS budgets background task
identifiers per app; a dedicated task would compete with the app's existing ones and would still run only
when the system chose. Checking at every wake yields strictly more opportunities to renew.)

A renewal that fails SHALL record the cause it actually has, and SHALL NOT attribute the failure to a
party that was not involved. The assertion is produced locally by the Secure Enclave and the refusal comes
from the backend; these are different failures with different remedies, and a device log that names one for
the other makes the difference unrecoverable after the fact. Where the platform supplies an error value —
an error domain, code, and description — that value SHALL reach the device log rather than being discarded
in favour of a generic message.

#### Scenario: A wake with a stale token renews it

- **WHEN** the app process wakes for any reason and the token is absent, expired, or near expiry
- **THEN** the app obtains a fresh token and persists it to the shared Keychain

#### Scenario: A wake with a fresh token does nothing

- **WHEN** the app process wakes and the token is comfortably within its lifetime
- **THEN** no attestation and no renewal is performed

#### Scenario: A renewal that fails names the party that failed

- **WHEN** the local assertion cannot be produced, so no renewal request is ever sent
- **THEN** the device log records the platform's own error value and does not state that the backend
  refused the renewal

### Requirement: An expired token stalls uploads; it never loses a photo

When the token is expired or absent, a gated upload SHALL fail with `401` and the resource SHALL be
retried, never abandoned — the engine's retry policy is error-agnostic and the upload request is re-minted
from the provider on each attempt, so a refreshed token is picked up on the next cycle without any
special-casing.

The failure SHALL be **visible and never silent**, reduced into `UiState` rather than thrown to the UI.
This is the only signal a stalled device gives: an expired token prevents the successful upload whose
completion notification is what would otherwise wake the app to renew, so recovery depends on the next app
wake from another source (the user opening the app, or another member's upload).

**Which** state renders it is not this capability's to say. An unusable token surfaces as the joined layer's
`Unattested` health, specified by `sync-status-screen`, which owns the health precedence and ranks it. What
this capability requires is only that the stall reach the screen at all: an attestation failure that showed
nothing would leave a device reporting "Syncing" while every upload `401`s — invisible, and unfixable by a
member who cannot know it is happening.

**"Unusable" is a narrower test than "due for renewal", and the two SHALL NOT share one predicate.** A token
is unusable when it is **absent, unparseable, or past its expiry** — and only then. A token that is merely
inside the renewal margin still authorises every gated request until the instant it expires, so a failed
renewal against one is not a stall and SHALL NOT be surfaced as one. (Verification on the backend is an
expiry check plus one HMAC comparison; nothing else can make an unexpired, well-formed token stop working
except a rejection, which is a different path.) The renewal margin governs *when the app spends a renewal*
and SHALL remain wide; it is not a statement about whether uploads can proceed.

**A surfaced verdict SHALL NOT outlive the refresh that produced it.** The app checks attestation only at
its wakes, and a process may hold an outcome from one wake across an arbitrary suspension before a surface
renders it. The health a surface shows SHALL therefore derive from a refresh attempted no earlier than that
surface's own entry: on entry the prior outcome SHALL be discarded, and the state SHALL be re-established
by the attempt the entry triggers. Otherwise a member is shown a verdict formed under conditions — network,
backend, key — that no longer hold.

Interactive failures need no new surface: a gated create or join that `401`s already reduces into
`UiState.CreateEvent(error)` and `JoinPhase.LoadFailed`/`CommitFailed`. It is the **background** stall that
had none.

**Non-goal — a rejected but unexpired token.** When the backend rejects a token that has not expired (its
signing key was rotated, or the leave cascade collected this device's attestation record), the token is
dropped and the next refresh obtains a new one. If that refresh keeps **succeeding** while the backend keeps
rejecting what it mints, this requirement surfaces nothing and the screen reads healthy through a permanent
`401` loop. Detecting it needs evidence this capability does not currently collect. It is named here so the
`Unattested` state is not read as covering it.

Decision record: `changes/archive/2026-07-14-add-device-attestation` — see its `tasks.md` 4.5, which records
why the background half needed a state of its own after this change's D11 had promised it would not.

#### Scenario: A stale token stalls rather than strands

- **WHEN** the OS performs an upload carrying an expired token and the endpoint responds `401`
- **THEN** the resource is not marked complete, is retried, and uploads successfully once the app has
  renewed — no photo is lost

#### Scenario: The stall is visible

- **WHEN** no usable token can be obtained and uploads are failing because of it
- **THEN** it is surfaced on the joined layer as the `Unattested` health (capability `sync-status-screen`) rather than failing silently behind a screen that reads "Syncing"

#### Scenario: A merely stale token is not an error

- **WHEN** the token is stale but the next wake renews it successfully
- **THEN** nothing is surfaced — a renewal that works is a non-event, and flashing an error for it would be noise

#### Scenario: A token inside the renewal margin that fails to renew is not a stall

- **WHEN** the token is inside the renewal margin but has not expired, and the wake's renewal fails for any
  reason — no network, a refused assertion, a refused attestation
- **THEN** nothing is surfaced: the token still authorises every gated request, so no upload is stalled and
  the screen SHALL NOT state that sharing is paused

#### Scenario: A verdict from an earlier wake is not shown at a later entry

- **WHEN** a wake concludes that no usable token can be obtained, the process is suspended, and a surface is
  later entered
- **THEN** that earlier conclusion is not rendered; the surface shows the outcome of the refresh its own
  entry triggers

