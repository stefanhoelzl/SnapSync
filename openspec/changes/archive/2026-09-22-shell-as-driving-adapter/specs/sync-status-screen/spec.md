## ADDED Requirements

### Requirement: The screen's callback bundle is built in one place

The status screen's callback bundle (`StatusActions` and its sub-bundles) SHALL be built from a
`StatusContainerHost` by **one** factory in `:ui:screens`, and every host that composes the screen (the iOS
app, the forge binary, the desktop pane) SHALL obtain the bundle from it. A host SHALL pass the factory only the
values it genuinely supplies (the shareable-count query and the photo grant that is its recompute key) and SHALL
NOT bind a tap to a container intent itself. The factory SHALL bind every callback the screen can fire: an
omission is a change to the factory's signature, visible at every call site, and never a default left in place by
a host.

The factory SHALL be covered in `:ui:screens`' `commonTest` by clicking the real screen built from its output over
a real container and asserting the container's resulting state or the command it fired. The table clicked there is
the table every host ships, so the test covers what runs rather than a harness's copy.

The bundle's own defaults MAY remain for the screen module's previews and component tests; they SHALL NOT be relied
on by a host.

#### Scenario: A host composes the screen

- **WHEN** the iOS app, the forge binary or the desktop pane renders the status screen
- **THEN** its callback bundle comes from the shared factory, and the host's own code names no container intent

#### Scenario: A callback is crossed in the factory

- **WHEN** the factory binds a control to the wrong container intent
- **THEN** the click test for that control fails, on JVM and the simulator

#### Scenario: The update-required store button

- **WHEN** a host renders the update-required layer
- **THEN** its store button opens the store link through the container on every host, instead of being wired in
  one host and left inert in the others
