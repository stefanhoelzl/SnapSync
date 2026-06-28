## MODIFIED Requirements

### Requirement: Invite affordances appear only in the joined layer

The invite QR, its caption, and the share action SHALL be presented to the user **only** in the
joined-layer states — `InProgress`, `NothingToSync`, and `Completed` — and SHALL NOT be presented in
the loading, setup-gate, or permission-blocked states. (There are no longer `joining` or `join-failed`
states; reconciliation runs in the extension and the screen shows the listing-derived snapshot during a
(re)join — see `event-rejoin-reconciliation` and `sync-status-screen`.) The gate SHALL be the same
joined-layer predicate that scopes the leave action; the invite URL may be non-`null` before the joined
layer (config present but permission not granted), yet the affordances SHALL still not render outside the
joined layer.

#### Scenario: Joined-layer states present the invite affordances

- **WHEN** the screen is in `InProgress`, `NothingToSync`, or `Completed`
- **THEN** the invite QR, its caption, and the share action are presented

#### Scenario: Non-joined states present no invite affordances

- **WHEN** the screen is in the loading, setup-gate, or permission-blocked state
- **THEN** no invite QR, caption, or share action is presented, even when an event is configured
