# Spec Delta

## ADDED Requirements

### Requirement: The service reports its own failures to the operator
When SnapSync's service fails unexpectedly while handling a request from an app or a browser, a report of
the failure SHALL reach the operator's error-tracking service. The report SHALL carry the request as the
service received it: what was asked for, including any event, device or photo identifiers and file names it
named, and the details the request arrived with, such as the browser or app making it, the approximate
location it came from and its connection's fingerprint. It SHALL NOT carry the requester's network address.
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

#### Scenario: A development copy of the service fails
- **WHEN** a copy of the service running for development or tests fails unexpectedly
- **THEN** nothing is reported anywhere

## MODIFIED Requirements

### Requirement: A web visitor leaves no trace in the event
Viewing the event page or downloading an event's photos SHALL NOT make the visitor a member, SHALL NOT take one
of the event's device places, and SHALL be invisible to the members. The service SHALL record only that a
browser read the event's photo list and when (requirement "The service records who reads an event's photo
list"); it SHALL NOT record the visitor's address, browser or anything else that identifies them. The one
exception is a failure: when the service fails unexpectedly while serving the visitor, the report it sends the
operator carries that request's details, including the visitor's browser, approximate location and connection
fingerprint, but never their address (requirement "The service reports its own failures to the operator").

#### Scenario: Many visitors download
- **WHEN** twenty people download an event's photos through its invite link
- **THEN** the event's membership and its remaining device places are unchanged, and no member sees them

#### Scenario: What a visit leaves behind
- **WHEN** a web visitor opens an event page and downloads its photos, and the service serves them without
  failing
- **THEN** the operator can see that a browser read the event's photo list at that time, and nothing that
  identifies the visitor

#### Scenario: The service fails while serving a visitor
- **WHEN** the service fails unexpectedly while serving a web visitor the event page
- **THEN** the operator's failure report carries the visitor's browser, approximate location and connection
  fingerprint, and not the visitor's address
