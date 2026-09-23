## ADDED Requirements

### Requirement: An adapter bound per compilation target is real for the clauses it runs there

A `Live` binding SHALL count as a real implementation for an adapter whose platform configuration is fixed
per **compilation target** — one target compiling a binding the other target cannot run, such as the background
`URLSession` on `iosArm64` and the default one on `iosSimulatorArm64` (`ios-url-session-upload`, "The transport
binding is fixed by the compilation target"). Such an adapter SHALL count as real for the clauses a `Live` binding runs on a host of the target that
compiles it: every call it makes is the call production makes, except the one lookup that resolves the
configuration. That lookup SHALL NOT be counted as covered, and neither SHALL any property only the other
target's binding has. A clause whose outcome depends on such a property — a transfer surviving suspension or
termination, a relaunch to deliver a background session's events, reattachment to a prior process's tasks, a
system invalidation of the session — SHALL NOT be written against the target that cannot exhibit it; it stays
in the adapter's documentation with its evidence (see "Every clause runs against a real implementation on some
host").

A server a transport's clauses exchange bytes with — a loopback fixture that answers each request with a
status, body and length the clause chose — SHALL be a **clause input**, not part of the implementation under
contract: what such a contract states is how the adapter and the operating system's transfer stack behave
given an answer, not how a backend behaves. It therefore SHALL NOT make the binding `Fake`, which is reserved
for a stand-in of the system whose behaviour the contract states ("Hosts are a closed set of what changes
reachable states"). Its routes SHALL be derived from the clause id, so clauses sharing one server cannot read
each other's exchanges.

#### Scenario: The simulator app runs the download transport

- **WHEN** a `Live` binding on `IOS_SIM_APP` constructs `IosDownloadTransport`, whose session the simulator
  target binds to a default configuration, and a clause stages a transfer
- **THEN** the clause counts as run against a real implementation, and none of the outcomes counts as coverage
  of the background configuration or its lifecycle

#### Scenario: A clause needs the background session's lifecycle

- **WHEN** a clause would assert that a transfer completes after the process is suspended
- **THEN** no binding on a simulator host may declare its state reachable, and without a device recording the
  clause is not written; the fact stays documented with its measurement and expiry

#### Scenario: A fixture server answers a transfer

- **WHEN** a transport clause fetches from a loopback server that answers `404` because the clause asked it to
- **THEN** the binding's kind is decided by the transport under contract, not by the server, and stays `Live`
