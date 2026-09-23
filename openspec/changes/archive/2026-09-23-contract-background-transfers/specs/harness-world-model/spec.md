## ADDED Requirements

### Requirement: The world's transfer doubles are the transfer contracts' Fake bindings

The world's `BackgroundTransfer` and `DownloadTransport` doubles SHALL each be bound, as the `Fake` binding of
that port's contract (capability `port-contracts`), in `:test:world`'s `commonTest`, so every world and
integration test stands on a double held to the same clauses its real adapter passes. The binding SHALL play
the network through the double's existing operator actions — answering each transfer as the clause's fixture
route says — and SHALL NOT add a lever for it. A clause the double fails SHALL be fixed in the double; the
double's levers and inspection stay as this spec requires them. Behaviour the double models that no real host
exhibits — the PhotoKit tier's single free retry — SHALL stay uncontracted rather than asserted by a clause only
the double reaches.

#### Scenario: The world's upload double accepts an unusable payload

- **WHEN** the `BackgroundTransfer` contract hands the world's double a resource whose payload is not the
  world's resource type
- **THEN** the double answers `FAILED`, as a real tier does for a payload it cannot upload, and the world tests
  that stage ordinary resources are unaffected

#### Scenario: The binding completes a transfer

- **WHEN** a clause creates a job to a route that accepts
- **THEN** the binding completes it through the double's own complete action, and the clause reads the outcome
  through the port and its handle exactly as it does against the live adapter
