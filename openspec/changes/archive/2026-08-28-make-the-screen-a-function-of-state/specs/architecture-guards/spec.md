## ADDED Requirements

### Requirement: The screen-state gate

`:ui:screens` SHALL hold no Compose-remembered state outside a named allowlist, and every allowlist
entry SHALL state why its state is HOW the screen draws rather than WHAT it shows (capability
`sync-status-screen`). The gate SHALL also fail on an allowlist entry whose file no longer exists or no
longer holds state, so a spent permission cannot read as a standing one.

This is the mechanical half of "what the screen SHOWS is `UiState`". Without it the rule is a comment:
the drift it exists to catch — a value the screen renders that no state carries — produced a banner one
host could not render at all, and neither the build nor any test said so.

#### Scenario: New screen-held state fails the build
- **WHEN** Compose-remembered mutable state is declared in `:ui:screens` outside the allowlist
- **THEN** the gate fails, naming the declaration

#### Scenario: An allowlist entry that stopped being used fails too
- **WHEN** an allowlisted file no longer holds state, or no longer exists
- **THEN** the gate fails rather than leaving a permission nobody is using

### Requirement: The event-name limit gate

The client's event-name cap SHALL equal the backend's, asserted from source: `model/`'s
`EVENT_NAME_MAX_LENGTH` against `api/src/validators.ts`'s `MAX_EVENT_NAME_LENGTH` (capability
`event-creation`). No screen SHALL state a name cap as a literal.

The backend owns the rule and the client mirrors it so an over-long name is unreachable rather than
rejected on a round trip — which makes the mirror useful only while it agrees. Nothing else can notice a
disagreement: a raised backend limit makes the client refuse names the server would take, a lowered one
makes it offer names the server will reject, and the only symptom of either is a `400` the member cannot
act on while naming their event.

#### Scenario: The two limits diverge
- **WHEN** either constant changes without the other
- **THEN** the gate fails, naming both values

#### Scenario: A screen restates the cap as a literal
- **WHEN** a name field's `maxLength` is written as a number instead of the shared constant
- **THEN** the gate fails, naming the file and line
