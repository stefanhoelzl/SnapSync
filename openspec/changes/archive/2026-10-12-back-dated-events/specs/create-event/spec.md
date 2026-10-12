# Spec Delta

## ADDED Requirements

### Requirement: Creating an event that is already over asks how long guests have to join
When the host taps Create and the end of the chosen range has already passed, the app SHALL NOT create the event
at once but SHALL first ask, in a sheet over the create form, how long guests have to join it. The sheet SHALL
explain that the event is already over, that guests can still join and share the photos they took, and that for
privacy SnapSync keeps the photos only for a limited time; it SHALL NOT promise that the photos are deleted on the
day chosen. It SHALL offer 1, 7, 14 and 30 days, each as its own choice showing the day it leads to and creating
the event at once with that day as the day until which guests can join (capability `event-lifetime`), and a way
to cancel. Cancelling — also by swiping the sheet away — SHALL return to the create form with everything the
host entered kept and nothing created. An event whose end has not yet passed when Create is tapped, even by a
minute, SHALL be created without the sheet. After a choice, creating proceeds exactly as for any other event,
failure included (requirements "The creator joins through the same join screen as every guest" and "A failed
create says so and changes nothing"); a retry SHALL ask again.

#### Scenario: A weekend already over
- **WHEN** on 12 October the host names an event, picks 2 October 18:00 to 4 October 20:00, and taps Create
- **THEN** nothing is created yet, and a sheet says the event is already over, that guests can still join and
  share their photos, that photos are kept only for a limited time, and offers 1, 7, 14 and 30 days with the
  days they lead to, and Cancel

#### Scenario: A choice creates the event
- **WHEN** the host chooses 7 days in that sheet on 12 October
- **THEN** the event is created at once, its join screen opens, and guests can join it at least until
  19 October

#### Scenario: Cancelling keeps the draft
- **WHEN** the host cancels the sheet, or swipes it away
- **THEN** the create form shows the name and range the host entered, and no event was created

#### Scenario: An event still running needs no choice
- **WHEN** the host taps Create for an event that started yesterday and ends in an hour
- **THEN** the event is created without the sheet

#### Scenario: A retry after a failure asks again
- **WHEN** the host chose 7 days, the create failed because the server could not be reached, and the host taps
  Create again
- **THEN** the sheet asks again how long guests have to join, and the chosen choice creates the event
