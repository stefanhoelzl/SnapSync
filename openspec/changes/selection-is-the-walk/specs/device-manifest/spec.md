## MODIFIED Requirements

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **deleted** by the first
authoritative walk that does not return it (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk"), so the projection that same cycle publishes stops listing that asset. The deletion
happens before the manifest is projected, so a cycle never publishes an asset its own walk found gone.

Presence is a fact about the library, not about scope. A row is judged gone only when its asset is inside
the membership's capture window and absent from an authoritative walk, never because the selection policy
stopped admitting it. The projection applies the policy on its own, and a row the policy excludes is simply
not listed. Under a partial grant the member's selection is the library from the app's point of view, so a
selection snapshot that has been read is an authoritative walk. **A de-selected photo is retracted**
exactly as a deleted one is (capability `limited-photo-access`). A walk that is not authoritative (an
unreadable library) retracts nothing, and a selection that has not been read yet never reaches a walk.

A row's upload state does not delay its retraction: an in-flight (`REQUESTED`) row is deleted with its
asset, so the manifest stops listing the photo in the cycle that saw it leave, even if its bytes land
afterwards. Such bytes are listed in no manifest.

Deletion-tracking is **exhaustive under both grants**: there is no change token to expire, so a deletion or
a de-selection is observed by the next authoritative walk whenever it runs. Decision record:
`changes/selection-is-the-walk` (D1, D2), which reversed "a partial grant retracts nothing".

This supersedes two earlier requirements: that deletion be recorded by **marking** rows from the change
feed's removal signal, with no reconcile backstop; and, before that, that pruning be driven "incrementally
from the change feed, **and** by the full enumeration's retain-live reconcile". That reconcile was fed the
policy-admitted set, which conflated "gone from the library" with "outside the current capture window" and
discarded upload-suppression state a scope change has no business touching. Presence-driven deletion
retracts only rows inside the window, judged against the walk's whole candidate set.

A restored asset is a new asset to the ledger: its rows are gone, so the walk records it as new work, it
re-uploads under its same keys, and the projection lists it again once it is recorded. The backend
re-stores each role idempotently.

#### Scenario: Deletion retracts the listing

- **WHEN** an asset previously listed in the manifest leaves the library, and an authoritative walk does not
  return it
- **THEN** its ledger rows are deleted and the manifest that cycle publishes no longer lists it

#### Scenario: A restored asset is listed again

- **WHEN** an asset whose rows were deleted is restored to the library and returned by a later walk
- **THEN** it is recorded as new work, and the next manifest projection lists it (when the current policy
  admits it)

#### Scenario: A deletion is not missed for want of a token

- **WHEN** an asset is deleted while no upload cycle runs for an extended period
- **THEN** the first authoritative walk afterwards retracts it; no signal needs to have been received at the
  moment of deletion

#### Scenario: De-selection retracts the listing

- **WHEN** under a partial grant the member de-selects a listed photo, and the next cycle runs over the read
  selection
- **THEN** the photo's rows are deleted and the manifest that cycle publishes no longer lists it

#### Scenario: An in-flight photo is retracted before its upload settles

- **WHEN** a photo whose row is `REQUESTED` leaves the library or the selection, and an authoritative walk
  runs
- **THEN** the manifest that cycle publishes no longer lists it, whether or not its bytes land afterwards

#### Scenario: An unread selection retracts nothing

- **WHEN** under a partial grant the app's cycle runs before the selection has been read
- **THEN** the cycle is withheld and publishes no manifest, so nothing is retracted

