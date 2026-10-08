# Spec Delta

## MODIFIED Requirements

### Requirement: The joined screen shows how long the event lasts

The joined screen SHALL show the event's date range in the device's own timezone — as its first and last
calendar day, or, for an event that starts and ends on the same day, as that day with its start and end
times — together with how far the event is in its life: how long until it starts, how long until it ends,
or that it has ended. The last day SHALL be the calendar day the end falls on, an end at midnight included,
so the range reads as the host chose it. The time until a start or an end SHALL be given in whole days while a day or more
remains, then in hours, then in minutes. While the app is open, what the dates say SHALL stay correct to
within a minute without any other trigger.

#### Scenario: A running event counts down in days
- **WHEN** a member opens the joined screen of an event running from 12 to 14 July, with two days and five
  hours left
- **THEN** the screen shows 12 to 14 July and that the event ends in 2 days

#### Scenario: The last day counts in hours, then minutes
- **WHEN** five hours of the event remain, and later forty minutes
- **THEN** the screen says it ends in 5 hours, and later that it ends in 40 minutes

#### Scenario: A same-day event shows its times
- **WHEN** a member is joined to an event running today from 18:00 to 23:00
- **THEN** the screen shows today, 18:00 to 23:00, and how long until it ends

#### Scenario: Dates in the device's timezone
- **WHEN** an event starts at 22:00 UTC on 13 July and the device is in a UTC+2 timezone
- **THEN** the range shown begins on 14 July

#### Scenario: An end at midnight shows the day it falls on
- **WHEN** a member opens the joined screen of an event running from 12 July, 14:00 to 14 July, 00:00
- **THEN** the screen shows 12 to 14 July

#### Scenario: An event ending at the next midnight spans two days
- **WHEN** a member is joined to an event running from 13 July, 18:00 to 14 July, 00:00
- **THEN** the screen shows 13 to 14 July, not a same-day event with its times
