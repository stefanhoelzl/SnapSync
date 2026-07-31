## MODIFIED Requirements

### Requirement: An absent field is left unmanaged, never deleted

The apply SHALL treat a field **omitted** from a per-locale file as a no-op that leaves the App Store
Connect value unchanged, and SHALL NOT delete listing fields (it SHALL NOT pass the tool's delete/confirm
flags). Declarative overwrite applies only to fields **present** in the files.

`whatsNew` SHALL be one such permanently omitted field: the release notes are **derived per release**
from the labelled pull requests since the previous release and written by the App Store release
(capabilities `changelog-labels`, `ios-appstore-release`), so the committed per-locale files SHALL NOT
carry a `whatsNew` key. A committed value would be a single static sentence reused by every release —
the committed listing is deliberately version-independent — and it would be overwritten by the next
release anyway. This requirement is what keeps the two writers compatible: because an omitted field is
never deleted, a `main` merge cannot clear the notes the release wrote.

#### Scenario: An omitted field retains its live value
- **WHEN** a per-locale file does not contain the `whatsNew` key and an apply runs
- **THEN** the existing `whatsNew` value in App Store Connect is left unchanged, not cleared

#### Scenario: A merge does not clear the release's notes
- **WHEN** a release has written a version's `whatsNew` and a later commit is pushed to `main`, running
  the apply against that same editable version
- **THEN** the derived notes remain in App Store Connect, because the committed files carry no
  `whatsNew` key and an absent field is never deleted
