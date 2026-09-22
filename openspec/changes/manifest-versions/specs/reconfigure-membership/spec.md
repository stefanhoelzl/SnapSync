## ADDED Requirements

### Requirement: The reconfigure save advances the manifest version after it lands

A successful reconfigure save SHALL advance the ledger's manifest version. The save is the one writer of the
membership's policy bounds — direction, the capture cutoff, and the ceiling — and those bounds are an input to
the device manifest's projection (capability `device-manifest`). A successful save SHALL therefore advance the ledger's manifest version (capability `sync-ledger`) **after** the
config is durably saved, in the same step, and a failed save SHALL advance nothing.

The order is the requirement. The config and the counter live in two stores and cannot share a transaction. A
bump before the save would let a cycle read the newer version and then the **old** config, and publish the old
policy under a version nothing later exceeds. A bump after the save means a cycle that read the older version
is overtaken by the newer one. A crash between the save and the bump leaves the new policy unversioned. The
next cycle's snapshot still differs from the skip record, so it republishes under the older version, and only a
same-version race inside that window remains. Decision record: `changes/manifest-versions`.

No other config save advances the version: the membership refresh rewrites only the name and absent event
dates, and none of them is a policy input.

#### Scenario: A saved reconfigure advances the version after the save

- **WHEN** a reconfigure saves a new cutoff
- **THEN** the manifest version advances, and it advances only after the new config is readable

#### Scenario: A failed save does not advance the version

- **WHEN** the reconfigure's config save throws
- **THEN** the manifest version is unchanged
