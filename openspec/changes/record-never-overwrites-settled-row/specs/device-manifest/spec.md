## MODIFIED Requirements

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **marked absent** — from the
change feed's precise removal signal — so the next projection stops listing that asset. The rows
themselves SHALL be retained: their bytes are still on the backend, so the record that suppresses
re-upload stays true and a restored asset does not re-upload.

When an asset whose rows are marked absent is seen in the library again by the upload cycle's walk, its
rows SHALL be **un-marked** (capability `sync-ledger`, "Prune operations are writer-only"), so the next
projection lists it again — whatever the rows' upload state, and without re-uploading a byte. Presence is a
fact about the library, not about scope: the un-mark SHALL NOT depend on the membership's selection policy,
which the projection still applies on its own.

There SHALL be **no** full-enumeration retain-live reconcile. The change feed's removal signal is the only
deletion input. A deletion the feed missed — because the change token expired — leaves the asset listed for
the event's remaining life; its bytes are still present, so a member downloads it successfully and the photo
simply stays in the event, exactly as it does when a member leaves. Deletion-tracking is therefore not
exhaustive, and does not need to be.

This supersedes the prior requirement that pruning be driven "incrementally from the change feed, **and** by
the full enumeration's retain-live reconcile". That reconcile was fed the policy-admitted set, which
conflated "gone from the library" with "outside the current capture window" and discarded upload-suppression
state a scope change has no business touching.

#### Scenario: Deletion marks the rows

- **WHEN** an asset previously listed in the manifest is reported deleted by the change feed
- **THEN** its ledger rows are marked absent and the next manifest projection no longer lists it

#### Scenario: A deleted asset's rows survive

- **WHEN** an asset's rows have been marked absent
- **THEN** those rows are still readable and still `COMPLETED`, so restoring the asset re-uploads nothing

#### Scenario: A restored asset is listed again

- **WHEN** an asset whose `COMPLETED` rows are marked absent is returned by a later walk of the library
- **THEN** its rows are no longer marked absent, the next manifest projection lists it (when the current
  policy admits it), and no upload job is created for it

#### Scenario: A missed deletion leaves the asset listed

- **WHEN** an asset is deleted while the change token is expired, so no removal signal is ever received
- **THEN** the asset remains listed and remains downloadable from its still-present bytes — no full
  enumeration retracts it
