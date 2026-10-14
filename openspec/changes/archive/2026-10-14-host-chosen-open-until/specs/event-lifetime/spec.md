## RENAMED Requirements

- FROM: `### Requirement: An event created after its end stays open as long as the host chose`
- TO: `### Requirement: An event stays open until the date its host chose`

## MODIFIED Requirements

### Requirement: The date range closes nothing

The end of an event's date range SHALL bound only which photos may be shared (capability
`photo-sharing`); reaching it SHALL close nothing. Until the event's photos are deleted (see "An event's
photos are deleted once it is finished, and after 30 days at the latest"), joining, rejoining, sharing
in-range photos, withdrawing them, receiving, renaming and changing settings SHALL all keep working after the
range has ended, including after the event's open-until date (see "An event stays open until the date its host
chose").

#### Scenario: A late guest still joins
- **WHEN** a guest scans the invite a week after the event's range ended, while its photos have not been
  deleted
- **THEN** they can join and their photos taken within the range are shared

#### Scenario: Sync continues after the end
- **WHEN** a member still has in-range photos to upload after the range ended
- **THEN** they upload and reach the other members as before

#### Scenario: A rename after the end
- **WHEN** a member renames an event days after its range ended, while its photos have not been deleted
- **THEN** the new name shows for every member

### Requirement: An event stays open until the date its host chose

Every event SHALL have an **open-until date**, fixed at creation: the day its host chose when creating it, or the
end of its range when the host chose none. A chosen day SHALL NOT be before the end of the range, and SHALL NOT be
more than about 30 days after the later of that end and the moment the event was created; an event asked for with
a day outside that bound SHALL NOT be created. When the app asks the host for a day is capability `create-event`.
The event SHALL NOT be finished — and its photos SHALL NOT be deleted for being finished or idle — before its
open-until date. The open-until date SHALL NOT change which photos are shared; that remains the range's alone.

#### Scenario: Guests join a party that is over
- **WHEN** a host creates on Monday an event for Saturday's party, choosing 7 days, and shares the invite
- **THEN** guests who scan it during the following week join and share their photos taken on Saturday, and
  receive everyone's

#### Scenario: The host alone does not finish it
- **WHEN** the host of a back-dated event has shared and received everything an hour after creating it
- **THEN** the event's photos are kept, and the invite still works, until at least the chosen date

#### Scenario: The range still bounds the photos
- **WHEN** a guest of a back-dated event took photos the day after the party, before the chosen date
- **THEN** those photos are not shared

#### Scenario: No choice keeps the end
- **WHEN** a host creates an event that ends on Sunday evening without choosing a day
- **THEN** the event may be finished, and its photos deleted, from Sunday evening on

#### Scenario: A chosen day is bounded
- **WHEN** a host creating an event that ends on 10 June, on 1 June, asks to keep it open until 15 July
- **THEN** the event is not created, and no event of theirs is kept open past 10 July
