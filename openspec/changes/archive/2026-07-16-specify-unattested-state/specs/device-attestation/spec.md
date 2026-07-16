## MODIFIED Requirements

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

Interactive failures need no new surface: a gated create or join that `401`s already reduces into
`UiState.CreateEvent(error)` and `JoinPhase.LoadFailed`/`CommitFailed`. It is the **background** stall that
had none.

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
