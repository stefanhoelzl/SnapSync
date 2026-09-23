## REMOVED Requirements

### Requirement: The container leave action defaults to a no-op
**Reason**: The container no longer takes loose, defaulted lambdas. Its commands arrive as the one required
`UserCommands` bundle (law "Function-typed constructor params have no defaults in production", capability
`module-architecture`), so a host built without a leave action is not a representable state, and "inert by
default" hid a missing binding rather than stating one.
**Migration**: A host that does not exercise leave — the desktop harnesses, presentation tests — passes a bundle
whose `leave` does nothing, built explicitly (the tests' `testCommands` builder).

## ADDED Requirements

### Requirement: The container receives the leave action through the command bundle

`StatusContainerHost` SHALL receive the leave action as the `leave: suspend () -> Unit` member of the required
`UserCommands` bundle (`model/`), which only `compose/` builds for production — never as a defaulted constructor
parameter, and never as the `LeaveEvent` type itself: the presentation layer is Compose-free and SHALL NOT gain an
engine or gallery dependency. A host that does not exercise leave SHALL say so by passing a bundle whose `leave`
does nothing.

#### Scenario: A host that does not exercise leave says so
- **WHEN** a presentation test constructs `StatusContainerHost` with a bundle whose `leave` does nothing
- **THEN** construction succeeds and invoking `onLeaveEvent()` performs no teardown

#### Scenario: Presentation gains no engine dependency
- **WHEN** the presentation module's dependencies are inspected
- **THEN** it depends on no engine, gallery, or rejoin module — the leave action enters through the `model/`
  command bundle
