## ADDED Requirements

### Requirement: Selection snapshots are emitted in change order

The selection-change source SHALL emit its snapshots from one serial lane, in the order of the changes
they reflect, so the last snapshot a consumer holds always reflects the latest change. It SHALL emit
nothing after observation has ended, and in particular SHALL NOT emit a snapshot built under a limited grant
once the grant has become full. A change that arrives before the baseline read completes SHALL be applied
after the baseline, not dropped.

#### Scenario: Two changes in quick succession

- **WHEN** the member picks more photos twice, quickly, and the second snapshot's enumeration would finish
  first on a parallel dispatcher
- **THEN** the second change's snapshot is still the last one emitted

#### Scenario: The grant becomes full while the baseline is being read

- **WHEN** the grant changes from limited to full while the baseline read is in flight
- **THEN** no snapshot is emitted from that baseline

#### Scenario: A change during the baseline read

- **WHEN** a selection change arrives before the baseline read has completed
- **THEN** a snapshot reflecting that change is emitted after the baseline one
