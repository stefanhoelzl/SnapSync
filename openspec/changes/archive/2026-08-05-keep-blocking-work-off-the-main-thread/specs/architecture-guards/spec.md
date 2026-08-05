## ADDED Requirements

### Requirement: The main lane is contained to platform UI

A gate SHALL fail the build when a main-thread dispatcher is named outside an allowlist of platform-UI
adapters. The watched forms SHALL cover both languages, because either can put work back on the main
thread: in Kotlin `Dispatchers.Main`, `MainScope()`, `dispatch_get_main_queue`, and
`NSOperationQueue.mainQueue`; in Swift `DispatchQueue.main`.

The gate is lexical containment rather than an attempt to decide whether a call blocks — that is not
decidable — so it makes the main lane unreachable by default and reachable only by an allowlist edit a
reviewer sees. The allowlist SHALL name each entry's reason.

`runBlocking` SHALL NOT appear outside test source sets: it blocks whatever thread it is called on, which
defeats the lane it was called from, and the extension's single pinned use is its composition root's
documented execution model rather than a call inside the core.

#### Scenario: A new adapter reaches for the main thread
- **WHEN** a file outside the allowlist names any watched main-thread dispatcher form
- **THEN** the gate fails the build, naming the file and the form

#### Scenario: A UI adapter is added
- **WHEN** a new platform-UI adapter legitimately needs the main lane
- **THEN** it is added to the allowlist with its reason, and the addition is visible in review

#### Scenario: Blocking is reintroduced through runBlocking
- **WHEN** `runBlocking` appears in non-test source outside its pinned composition-root use
- **THEN** the gate fails

### Requirement: Every user command declares its dispatcher lane

A gate SHALL fail the build when any field of the user-command bundle is built without a lane-declaring
decorator. Two decorators SHALL exist — one for commands that present platform UI and must run on the main
lane, one for everything else — and neither SHALL be a default, so a command that declares no lane does
not compile.

This gate exists because the composition scope cannot cover this door: the presentation container launches
an intent on an unconfined dispatcher, so a command's synchronous prefix runs on the thread that fired it.
It also keeps the manually-verified surface small — the lane choice for every command is visible in one
file, which matters because the UI-lane commands cannot be exercised by any automated test available to
this project.

#### Scenario: A command is added without a lane
- **WHEN** a field is added to the user-command bundle and built without either decorator
- **THEN** the build fails

#### Scenario: A command's lane is reviewed
- **WHEN** a reviewer checks whether platform-UI commands stay on the main lane
- **THEN** every command's lane is readable in the single file where the bundle is built

### Requirement: Adapter constructors perform no blocking work

A gate SHALL fail the build when a blocking platform call appears in a property initialiser or `init`
block of an iOS adapter. Construction happens during graph assembly, which runs on whichever thread
touches the graph first — so constructor I/O is a race between the launch path and the first render, and a
race is why such a defect is never observed in testing.

The gate SHALL carry a named grandfather list rather than blocking on a redesign. The one existing
instance is the file-backed config store, whose constructor read exists because the status container's
first state is built from seams that hold their current truth synchronously; removing it requires either
a placeholder first frame or a launch/render reordering across the shell boundary. The entry SHALL record
that reason, so the exemption is a decision rather than an oversight.

#### Scenario: A new adapter reads in its constructor
- **WHEN** an adapter gains a property initialiser or `init` block that performs a blocking platform call
- **THEN** the gate fails, and the class of defect cannot grow

#### Scenario: The grandfathered instance is inspected
- **WHEN** a reader asks why the config store is exempt
- **THEN** the allowlist entry states the constraint that makes fixing it a separate change
