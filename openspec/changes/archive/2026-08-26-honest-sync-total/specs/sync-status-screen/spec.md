## ADDED Requirements

### Requirement: A joined cold launch never claims settled

The joined layer SHALL NOT render the settled "In sync" line before the snapshot's inputs have been
read. On a cold launch — and on any entry where the status projection has not yet read the gallery
total, the ledger counts, or the download projection — the health SHALL be `SyncHealth.Loading`, which
the status line renders as its neutral in-progress line, never as the settled indicator.

This follows from the precedence already stated in *Sync status snapshots reduce to UI state*
(`SyncStatus.Loading` → a joined loading first-frame, ranked above every `Ready`-derived value) and from
that requirement's rule that every `Joined` health value is derived from real source values, never
placeholders. It is stated as its own requirement because the rule was being satisfied only vacuously:
the arrow derivation hides an arrow when `synced >= total`, and a placeholder `total` of `0` satisfies
`0 >= 0` on **both** arms, so a snapshot minted over unread inputs reduced to `InSync` — a check mark
reading "In sync" — on a device that had counted nothing. The member-visible consequence is a status
that appears settled and then, seconds or minutes later, appears to regress to "Synchronization
ongoing…" with no photos taken in between (`SNAPSYNC-14`, `SNAPSYNC-16`).

The settled line asserts "everything of yours is shared and everything of theirs is received". The
screen SHALL make that assertion only over counts it has.

`SyncHealth.Loading` SHALL remain a **neutral** line — no check indicator, no direction arrows, no
attention background — so that a member who sees it is told that the app is working it out, and is not
told an answer.

#### Scenario: A cold launch renders the neutral line, not the settled one

- **WHEN** the app launches into a joined membership with photo access granted, and the status
  projection has not yet read the gallery total or the ledger counts
- **THEN** the joined health is `SyncHealth.Loading` and the status line renders neutrally — no check
  indicator and no "In sync" text

#### Scenario: A short visit that never completes a read never shows In sync

- **WHEN** the member opens the app and leaves it before any status read completes
- **THEN** the status line showed the neutral in-progress line for the whole visit, and the settled
  "In sync" line was never rendered

#### Scenario: The settled line appears only once the counts are read

- **WHEN** the gallery total, the ledger counts and the download projection have all been read, and
  both direction arrows are hidden by their own counts
- **THEN** the status line reads "In sync" with the settled indicator

#### Scenario: A read zero total still settles

- **WHEN** the membership contributes nothing (a counted upload total of `0`) and the download
  projection has been read with its imports complete
- **THEN** both arrows are hidden and the status line reads "In sync" — a counted zero settles the
  screen exactly as it does today

#### Scenario: Counts arriving do not read as a regression

- **WHEN** the status projection completes its first read and the counts show work outstanding
- **THEN** the line moves from the neutral in-progress line to "Synchronization ongoing…" or
  "Synchronization pending…" — never from the settled "In sync" line, because that line was never shown
