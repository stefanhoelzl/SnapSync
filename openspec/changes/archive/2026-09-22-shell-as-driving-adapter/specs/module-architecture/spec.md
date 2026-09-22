## ADDED Requirements

### Requirement: OS entry points cross an inbound port

Each process that the operating system calls into SHALL declare its OS entry surface as one **inbound port** in
`ports/`: an interface the **core implements** and the shell drives, as opposed to the outbound ports the core
calls. The app process's is `PlatformEntries` (foreground, background, an opened URL, a push token, a silent push,
a background task by identifier, handed-back background transfers by channel); the upload extension's is
`ExtensionEntries` (`process(): CycleResult`, terminate). They are separate because the two processes' entry sets
are disjoint.

An inbound port SHALL obey "Ports are the I/O boundary named for the need": its members are named for what the
operating system is telling the app, never for the platform API that delivers it, and its parameters carry
platform-independent values (a string identifier, a raw payload map, a completion to release), never a platform
type. A platform entry whose input is a platform type (a user activity) SHALL NOT be a member; its filter runs in a
tested zone and calls the port.

The implementation SHALL live in `compose/`, beside the shared composition it drives (see "One shared
composition"), and SHALL hold what the shell once held: the entry → flow command transcription, the construction
and holding of each wake's `OsReceipt`, the entry-point logging, and the routing of a background task or transfer
channel to its handler. That routing SHALL be a comparison against identifiers the shell supplies **as data**, so
no platform constant enters `model/`, `ports/` or `feature/`. What the implementation cannot name because it lives
outside `:domain` (the presentation container, the root's token-source adapter) SHALL reach it as in-process
hooks the root supplies when it obtains the implementation; a hook calls back into the process and never reaches
out of it.

A composition root SHALL implement its process's inbound port by **Kotlin delegation** to that implementation.

#### Scenario: A new OS callback is added

- **WHEN** the app starts handling a new operating-system callback
- **THEN** it becomes a member of the process's inbound port, named for the need, implemented in `compose/` and
  covered by that port's contract, and the root gains no forwarding body for it

#### Scenario: A member is named for Apple's API

- **WHEN** an inbound-port member is proposed whose name or parameter type describes an Apple mechanism (a
  `URLSession` identifier, a processing-result raw value)
- **THEN** it is renamed for the need, and the platform's encoding is applied in the shell or an adapter

#### Scenario: The routing needs a platform constant

- **WHEN** the implementation must decide which handler a background task or transfer channel belongs to
- **THEN** it compares against identifiers the root passed in, and the constant stays in the adapter that owns it

## MODIFIED Requirements

### Requirement: Shells are wiring only
`:app:*` Kotlin SHALL contain zero conditionals (enforced by a complexity gate); shells construct
adapters, supply thunks, and **delegate** entry points: an OS entry point that is a member
of a process's inbound port (see "OS entry points cross an inbound port") SHALL reach the core by Kotlin
delegation, never by a hand-written forwarding body. A shell SHALL NOT bind the status screen's taps to
container intents itself; it passes the callback bundle the shared factory builds (`sync-status-screen`).
Swift SHALL be a transcriber, not a decider:
it forwards raw ObjC-visible inputs whole, constructs no domain values, and branches on nothing;
where the OS offers an ObjC-visible surface, Kotlin owns the trigger directly. Swift decision
keywords SHALL be pinned to an explicit list of irreducible occurrences, each justified by a
Swift-only API and carrying its forcing proof.

#### Scenario: A decision appears in a shell
- **WHEN** an `if`/`when` beyond the pinned forms appears in `:app:*` Kotlin or a decision
  keyword beyond the pinned list appears in the Swift shells
- **THEN** the respective gate fails, and the fix moves the decision into a tested zone

#### Scenario: A shell hand-writes a tap binding

- **WHEN** an `:app:*` call site of the status screen builds its callback bundle field by field
- **THEN** the binding is replaced by a call to the shared factory, because a second copy of the table is
  where an omitted or crossed callback hides
