## MODIFIED Requirements

### Requirement: A pre-commit count of the photos that would be shared

The system SHALL expose a **shareable-count** read-model that answers, for a **candidate** capture range
not yet committed, **how many of the device's own photos would be shared to the event**. The count SHALL
be the size of the set the selection policy admits (capability `photo-selection-policy`) — the photo's
`creationDate` lies within the candidate range and no origin exclusion applies — so that the number a
member sees equals the set that would upload and list.

The read-model SHALL be a **query** parameterised by the candidate range, not a passive reduction of
committed state, because the range is an uncommitted choice on the decision surface. The query SHALL be
run against the member's current uncommitted choices and its **result** carried in the display state;
carrying the result is not a reduction of committed state, and does not make the count a function of the
persisted membership.

The count SHALL NOT be parameterised by the participation direction. A direction that excludes upload is
expressed by **not offering the row at all** — the count is then **absent**, not `0`, and no library
enumeration is performed because no query is made. Absent and zero mean different things here and SHALL
stay distinguishable: `0` states that the member's chosen range admits none of their photos, which is a
fact worth telling them; absence states that the question does not apply.

The count means **photos shared to this event**, NOT bytes physically transferred. It SHALL therefore be
computed **without any network call** — it SHALL NOT consult the backend's already-stored device files —
so a member whose bytes were already stored from a previous event still sees every photo that will appear
in this event. This is deliberate: a member never cares how photos arrive (the mission), only which of
their photos are shared.

#### Scenario: The count equals the policy-admitted set for the candidate range
- **WHEN** the shareable-count query runs for a candidate range and the share choice includes upload
- **THEN** it returns the number of the device's own assets whose `creationDate` lies in that range and
  which no origin exclusion rejects — the same set the upload cycle would admit for that range

#### Scenario: A non-contributing choice offers no count at all
- **WHEN** the member turns the share choice off (or the membership is receive-only)
- **THEN** no count row is offered, no query is made, and no library enumeration is performed — the count
  is absent rather than `0`

#### Scenario: An in-scope range that admits nothing counts zero
- **WHEN** the share choice includes upload and the chosen range admits none of the member's photos
- **THEN** the count is `0` and the row says so, which is distinct from the row being absent

#### Scenario: The count ignores already-stored bytes
- **WHEN** the device has previously uploaded photos (their bytes already stored) that are also in scope
  for the candidate range
- **THEN** those photos are still counted, and no request is made to the backend's device-files listing

#### Scenario: The count reaches the screen through the state
- **WHEN** the member changes the candidate range and the query re-runs
- **THEN** the new result is carried in the display state, and the screen renders it without calling the
  query itself
