## MODIFIED Requirements

### Requirement: Token-delta discovery feed driven by the in-memory gallery

The world's fake `BackgroundTransfer.discoverResources(sinceToken, policy)` SHALL derive its change feed
from the in-memory gallery — the honest `InMemoryCandidateSource`, which answers the single
`CandidateSource.candidates(policy)` read over the world-owned asset cell and whose candidates map their
resources through the real shared fan-out when the cycle asks for them. Adding an asset SHALL surface it
as a new `Candidate` in `Discovery.candidates`; removing an asset SHALL surface its id in
`Discovery.removedAssetIds`; and an operator **expire-token** action SHALL return
`Discovery.fullEnumeration = true` carrying the whole current key-set (the routine token-expiry path).

A full enumeration SHALL reconcile **nothing** away. The cycle no longer prunes or marks rows for assets
an enumeration did not return (capability `sync-ledger`), so the expire-token path exercises
re-enumeration and cursor advance, not retention. Removal reaches the ledger by exactly one route — the
`removedAssetIds` signal — which marks those rows absent rather than deleting them, so the world can
drive both the "reported removal" case and the "removal the feed never reported" case, and they have
different observable outcomes.

#### Scenario: Adding an asset yields a new resource

- **WHEN** an asset is added to the in-memory gallery and discovery runs
- **THEN** `Discovery.candidates` carries that asset, and its resources fan out when the cycle asks

#### Scenario: Removing an asset yields a removed id

- **WHEN** an asset is removed and discovery runs
- **THEN** `Discovery.removedAssetIds` carries its id and the cycle marks its ledger rows absent, so
  they stop counting and stop being listed while remaining readable

#### Scenario: Expiring the token forces a full enumeration

- **WHEN** the operator expires the token and discovery runs
- **THEN** `Discovery.fullEnumeration` is `true` with the whole key-set, and the cycle re-enumerates and
  advances its cursor

#### Scenario: A removal the feed never reported leaves the rows alone

- **WHEN** an asset is removed from the in-memory gallery while the token is expired, so no
  `removedAssetIds` signal is ever produced for it, and a full enumeration then runs
- **THEN** its ledger rows survive unmarked — the world can therefore demonstrate that an unreported
  deletion leaves the asset listed rather than silently retracted
