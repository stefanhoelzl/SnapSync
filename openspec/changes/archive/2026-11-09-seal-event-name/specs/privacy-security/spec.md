# Spec Delta

## MODIFIED Requirements

### Requirement: The service reports its own failures to the operator
When SnapSync's service fails unexpectedly while handling a request from an app or a browser, a report of
the failure SHALL reach the operator's error-tracking service. The report SHALL carry the request as the
service received it: what was asked for, including any event, device or photo identifiers and file names it
named, and the details the request arrived with, such as the browser or app making it, the approximate
location it came from and its connection's fingerprint. It SHALL NOT carry the requester's network address, nor the
name an encrypted event's invite carries for previews (capability `invite-link`, "The service keeps an encrypted
event's name only encrypted").
The report SHALL be visible to the operator only, and SHALL change nothing about what the app or the browser
is answered. Only the operator's deployed service SHALL report; a development or test copy of the service
SHALL report nothing.

#### Scenario: The service fails while a member's app uploads a photo
- **WHEN** the service fails unexpectedly while receiving a member's photo
- **THEN** a report reaches the operator naming the event, the device and the photo that request was for,
  and the member's app is answered exactly as it would be without the report

#### Scenario: The requester's address is never sent
- **WHEN** a failure report is sent for any request
- **THEN** it contains no network address of the phone or browser that made the request

#### Scenario: A failing preview carries no event name
- **WHEN** the service fails unexpectedly while showing the page of an encrypted event's invite that carries its name
  for previews
- **THEN** a report reaches the operator naming the event's identifier, and it does not contain the event's name

#### Scenario: A development copy of the service fails
- **WHEN** a copy of the service running for development or tests fails unexpectedly
- **THEN** nothing is reported anywhere
