## ADDED Requirements

### Requirement: A dev/test control channel binds the loopback address only

Every server the control channel (`:test:rig`) starts SHALL bind the loopback address, named once as a
constant in the channel's common source, on the app host and the JVM host alike. The channel forces
operating-system entry points, exposes event state and, on its JVM host, exposes the world's failure levers.
A test-only JVM guard
SHALL assert this over the channel's whole source tree:
- every server construction binds that constant;
- no other network address literal appears in the tree.

Widening the bind is a one-token edit that reads as a connectivity fix and looks nothing like a security
decision, which is why a guard holds it rather than review. The guard SHALL fail loudly rather than
vacuously: finding no server construction in a non-empty tree is a failure, naming the tree it scanned.

#### Scenario: A bind address is widened

- **WHEN** a server in the control channel is constructed with any host other than the loopback constant,
  or the tree names another address literal
- **THEN** the guard fails naming the file and the literal

#### Scenario: The guard finds nothing to check

- **WHEN** the guard scans the channel's tree and finds no server construction
- **THEN** it fails, naming the tree, rather than passing
