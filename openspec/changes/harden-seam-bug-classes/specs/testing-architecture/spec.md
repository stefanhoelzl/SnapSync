## ADDED Requirements

### Requirement: Every OS entry point is exercised from a cold core

Each OS entry point SHALL have an integration test that starts from a cold core.
For every OS entry point the iOS root exposes (the `fun` members of its `Shell` delegate, which the spec
`ios-app-shell` names as the surface every OS entry point delegates to), an
integration test in `:test:integration` SHALL build a fresh `AppCore` over the world's ports, touch **only**
the core members that entry point's shell delegate touches, and assert the entry point's outcome. The test
SHALL be tagged with the entry point's name so the parity gate in `architecture-guards` can find it.

This is what reaches the wiring the shells are forbidden to test and the world must not warm up: a path
that only works because some other entry point built a lazy member first is invisible to every test that
enters through the UI host, and is exactly what an OS relaunch exercises.

#### Scenario: A background download relaunch from a cold core

- **WHEN** the parity test for the background-`URLSession` relaunch builds a fresh core, adopts the
  download session's events, and the fake transport stages a resource
- **THEN** the resource reaches the download controller and its import is awaited before the OS handler
  is released

#### Scenario: An entry point without a parity test

- **WHEN** a shell entry point has no tagged integration test
- **THEN** the build fails, naming the entry point
