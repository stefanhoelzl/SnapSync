## ADDED Requirements

### Requirement: A failing service is told as what it is

When SnapSync's service fails while the device has a usable network, the app SHALL tell the member which of three
conditions applies, each in its own words, wherever that screen tells a failure:

- **SnapSync cannot be reached**: no answer came back, or the answer was not one the app understands, such as a
  page a Wi-Fi network or a proxy put in its place;
- **SnapSync is having trouble**: the service answered that it failed;
- **SnapSync is being updated and is back shortly**: the service answered that it is briefly unavailable for an
  update.

Only *cannot be reached* SHALL suggest that the member check their connection. None of the three SHALL be shown as
the device having no network (capability `sync-status`), as this phone being refused (capability
`privacy-security`), or by marking anything the member typed as wrong. An answer the app does not understand SHALL
count as no answer: whatever the member or the app asked for SHALL NOT be treated as done, and SHALL be tried
again as after any other failure.

#### Scenario: No answer is told as unreachable
- **WHEN** the device has a network and the service does not answer
- **THEN** the app says SnapSync cannot be reached

#### Scenario: A failing service is told as trouble
- **WHEN** the service answers that it failed
- **THEN** the app says SnapSync is having trouble, and does not ask the member to check their connection

#### Scenario: An update is told as brief
- **WHEN** the service answers that it is being updated
- **THEN** the app says SnapSync is being updated and is back shortly, and does not ask the member to check their
  connection

#### Scenario: A Wi-Fi login page is not an answer
- **WHEN** a Wi-Fi network answers in the service's place with a login page
- **THEN** the app says SnapSync cannot be reached, and nothing the member asked for counts as done
