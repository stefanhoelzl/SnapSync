## MODIFIED Requirements

### Requirement: SyncEngine enumeration summary

Each upload discover cycle SHALL log one summary line accounting for the enumeration as `seen`,
`new`, and `already-uploaded` counts, without emitting a per-asset line for assets that are already
uploaded.

The line SHALL be emitted whether or not the cycle went on to create a job for every resource it
accounted for, and a cycle that stopped creating jobs early SHALL say so in that line, reporting how
much of the enumeration it left un-enqueued. A cycle that stops early is the one whose accounting is
needed most: it is the state in which a backlog is accumulating, and without the line a device log
shows the candidates going in and a handful of jobs coming out with nothing stating the difference.

#### Scenario: Per-cycle summary
- **WHEN** a discover cycle enumerates the library and the engine decides each resource
- **THEN** one summary line reports the number seen, the number newly minted for upload, and the number already uploaded

#### Scenario: A cycle that stopped creating jobs still accounts for its enumeration
- **WHEN** a discover cycle stops creating jobs because the platform's job limit was reached
- **THEN** the summary line is still written, and it states that creation stopped early and how many
  admitted resources were left un-enqueued

#### Scenario: Skips stay silent
- **WHEN** the engine returns `AlreadyUploaded` for a resource during enumeration
- **THEN** no per-asset line is written for that resource (only the cycle summary reflects it)
