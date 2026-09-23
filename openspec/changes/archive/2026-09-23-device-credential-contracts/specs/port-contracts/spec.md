## MODIFIED Requirements

### Requirement: Replay matches exactly, in order, over deterministic clauses

Recorded requests SHALL be matched exactly and in order within a clause. Answers SHALL be recorded in
full, with only a named list of volatile keys masked to fixed placeholders. A value the operating system
mints and the adapter sends back in a later call (an identifier such as an App Attest `keyId`) SHALL be
masked to the same placeholder in the requests that carry it, so a replay feeds the placeholder back and
the later request still matches. Credential material the operating system mints (an attestation, an
assertion, a token) SHALL always be masked: recordings are committed to a public repository, and a
device's credential is never part of one. Clause inputs SHALL be deterministic — fixed values, a fixed
identifier generator, and addresses derived from the clause id. A missing recording, or a recording lacking
a block for a clause its host's binding declares reachable, SHALL be `Failed`.

#### Scenario: The adapter reorders two calls
- **WHEN** the adapter issues the same calls as recorded in a different order
- **THEN** the clause reads `Diverged`

#### Scenario: The adapter starts reading an answer attribute
- **WHEN** the adapter begins reading an attribute of an answer that it did not read when recorded
- **THEN** the attribute is present on replay, because answers are recorded in full

#### Scenario: A recording is deleted
- **WHEN** a replay binding's recording file is absent
- **THEN** its clauses are `Failed`, not `NotRunHere`

#### Scenario: A minted identifier is sent back
- **WHEN** the operating system answers a call with a fresh identifier and the adapter passes it to a later
  call
- **THEN** both the answer and the later request carry the same placeholder in the recording, and replay
  matches the later request

#### Scenario: A recorded answer is a credential
- **WHEN** the operating system answers with attestation or assertion bytes
- **THEN** the committed recording carries a placeholder in their place, and the clause's assertions hold
  against it on replay
