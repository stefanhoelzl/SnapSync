## MODIFIED Requirements

### Requirement: Mutable full-state projection

The device manifest SHALL be projected from the upload **ledger** (capability `sync-ledger`). For a given
event the manifest SHALL list exactly the ledger's **COMPLETED** rows whose asset falls within the current
membership's admitted capture-date range (capability `photo-selection-policy`) — a full-state document
listing only genuinely-uploaded resources. The ledger SHALL be the **only** durable record the projection
reads: no second structure holding the same asset set exists, so none can disagree with it, and
deletion-awareness comes from the ledger's **absence mark** (a departed asset's rows are marked, not
dropped).

The projection SHALL additionally exclude rows marked **absent** — assets that have left the device's
library (capability `sync-ledger`). Absence is a fact the row carries, so the projection can apply it; the
row itself is retained, because its bytes are still on the backend.

Applying the **current** policy at projection time is the **intended** mechanism by which a membership's
scope change reaches the other members, not an incidental filter. The manifest answers *what does this
member share now?*; the ledger answers *which bytes has this member uploaded?*. These are different
questions with different lifetimes, and only the first depends on the policy. Consequently a narrowing of
scope SHALL shrink the projection and a widening SHALL restore it, in both cases **without** any change to
the ledger and therefore without re-uploading a byte (capability `reconfigure-membership`).

An **empty** projection SHALL be a valid manifest and SHALL be published. A membership that currently shares
nothing — because its direction excludes upload, or because its range admits none of its uploaded assets —
publishes an empty document rather than leaving a stale one in place.

Because the manifest lists only `COMPLETED` resources, the event union's byte-presence check (capability
`bunny-list-endpoint`) is not the mechanism that hides not-yet-uploaded assets; it is defense-in-depth
against a `COMPLETED`-but-absent byte.

#### Scenario: The manifest lists completed rows in the event window

- **WHEN** the manifest is produced for an event
- **THEN** it lists exactly the device's COMPLETED ledger resources whose asset is within the membership's
  admitted range — no discovered-but-unuploaded asset, and nothing outside the range

#### Scenario: A deleted asset drops from the manifest

- **WHEN** an asset is deleted locally and its ledger rows are marked absent
- **THEN** it no longer appears in the projected manifest, and its rows are still readable so re-upload
  stays suppressed if the asset is restored

#### Scenario: Narrowing the scope shrinks the projection without touching the ledger

- **WHEN** the membership's admitted range narrows so that a previously-listed COMPLETED asset falls outside
  it, and the manifest is produced again
- **THEN** the manifest no longer lists that asset, and its ledger row is unchanged

#### Scenario: Widening the scope restores the projection without re-uploading

- **WHEN** the membership's admitted range widens again to include that asset, and the manifest is produced
- **THEN** the manifest lists it once more and no byte is re-uploaded, because the ledger row was retained

#### Scenario: An empty projection is published

- **WHEN** the membership currently admits none of its uploaded assets
- **THEN** an empty manifest is published for that event, rather than the previous manifest being left in
  place

### Requirement: Deletion-aware manifest

When an asset is deleted from the library, its **ledger rows** SHALL be **marked absent** — from the
change feed's precise removal signal — so the next projection stops listing that asset. The rows
themselves SHALL be retained: their bytes are still on the backend, so the record that suppresses
re-upload stays true and a restored asset does not re-upload.

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

#### Scenario: A missed deletion leaves the asset listed

- **WHEN** an asset is deleted while the change token is expired, so no removal signal is ever received
- **THEN** the asset remains listed and remains downloadable from its still-present bytes — no full
  enumeration retracts it

## ADDED Requirements

### Requirement: The manifest is published only from a ledger believed complete

The cycle SHALL publish a manifest only on a path where it believes the ledger settled for that event, and
SHALL **suppress the write** — leaving the previously published manifest in place — whenever it does not.
Because the projection is a **full-state** document, publishing one built from an incomplete ledger silently
un-lists resources that really are uploaded.

In particular, when the re-join reconciliation (capability `event-rejoin-reconciliation`) defers because the
device's stored-file listing failed or timed out, the ledger has not been seeded and the cycle SHALL NOT
write a manifest that cycle. The same SHALL hold for any failure to read the ledger rows the projection is
built from.

Suppressing the write SHALL be distinguishable in the diagnostic log from publishing an empty manifest: the
first means "this device could not determine what it shares"; the second means "this device shares
nothing". They differ in consequence, and collapsing them would make an outage indistinguishable from a
deliberate withdrawal.

#### Scenario: A deferred reconcile suppresses the manifest write

- **WHEN** the re-join reconciliation defers because the device file listing failed or timed out
- **THEN** no manifest is written that cycle and the previously published manifest is left in place

#### Scenario: A ledger read failure suppresses the manifest write

- **WHEN** the projection cannot read the ledger's completed rows
- **THEN** no manifest is written that cycle and the previously published manifest is left in place

#### Scenario: Suppression and emptiness are distinguishable

- **WHEN** a cycle suppresses the manifest write, and another cycle publishes an empty manifest
- **THEN** the two are recorded distinctly in the diagnostic log, so "could not tell" is never read as
  "shares nothing"
