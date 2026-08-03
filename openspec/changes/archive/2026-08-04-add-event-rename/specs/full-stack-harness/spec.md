## ADDED Requirements

### Requirement: The rename affordance drives the real rename command

The left pane SHALL wire the **real** rename command from the composed app core (capability
`event-rename`), not a stub: the pen beside the event heading opens the rename dialog, and confirming
runs the actual `RenameEvent` use-case against the world's mini-edge — rewriting the registered event's
name and folding the echoed value back into the world's membership config.

This mirrors the bug-report affordance's rule and exists for the same reason: an affordance the operator
can reach must exercise real logic, or the harness reports a success the product would not. The rename
status the dialog reads SHALL likewise be the world's own — never a forged value — so the in-flight,
success, and failure states the operator sees are the ones the real use-case produced.

Both outcomes SHALL be inspectable **as world outcomes**, so an operator or an agent driving the harness
headlessly can confirm a rename landed without a device: the mini-edge's registered event name, and the
membership config the heading renders from.

The controller SHALL expose the rename as a named edge like every other control, and SHALL record it on
the engine console. Because the command is fire-and-forget, the console entry and the inspector refresh
SHALL NOT claim completion synchronously — the refresh rides its own launch, since a rename that has not
yet landed must not be snapshotted as though it had.

In the forge harness the pen SHALL remain **reviewable but inert** — rendered wherever a joined state is
forged, but wired to the defaulted no-op command and an always-`Idle` status — exactly as the leave
confirmation is there. That keeps the two harnesses' existing division: the forge reviews UI states, this
one drives the stack.

#### Scenario: Renaming rewrites the world's registered event
- **WHEN** the operator opens the rename dialog from the left pane, enters a new name, and confirms
- **THEN** the world's mini-edge reports the event under the new name, and the world's membership config
  carries it

#### Scenario: The heading follows the real config
- **WHEN** a rename has landed
- **THEN** the left pane's heading renders the name from the world's real config, not a forged value

#### Scenario: A rejected rename leaves the world untouched
- **WHEN** the backend refuses the rename
- **THEN** the world's registered event name and membership config are unchanged, and the dialog reports
  the failure rather than closing

#### Scenario: The rename is a named controller edge
- **WHEN** the inspector's wiring is inspected
- **THEN** the rename is a named controller method calling the world's public command surface, and it
  appends an engine-console entry
