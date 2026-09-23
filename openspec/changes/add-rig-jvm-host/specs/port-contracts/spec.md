## ADDED Requirements

### Requirement: The contract verb refuses on a host with no in-app registry

The rig's contract verb SHALL run contracts only on a host that has an in-app registry: the app host, on a
device or a simulator. On the control channel's JVM host (capability `testing-architecture`, "One control
protocol, served by two hosts") the verb SHALL answer `409` with the refusal marker, naming the `JVM` host
and stating that JVM contract bindings run in the canonical check. It SHALL NOT run a contract in-process or
answer an empty registry as though there were nothing to run. Its listing (`GET /contract`) SHALL be refused
the same way, so a job that runs "every contract the host lists" can never pass vacuously against the JVM
host.

#### Scenario: A contract is requested from the JVM host

- **WHEN** a caller posts to the contract verb for any contract name on the JVM host
- **THEN** it answers `409` with the refusal marker and names the `JVM` host, and no clause runs

#### Scenario: The contract listing on the JVM host

- **WHEN** a caller reads the contract listing on the JVM host
- **THEN** it answers `409` with the same refusal, not an empty list
