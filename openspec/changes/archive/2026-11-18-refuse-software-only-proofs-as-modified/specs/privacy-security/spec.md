# Spec Delta

## MODIFIED Requirements

### Requirement: A refused phone is told why
When the service refuses this phone as not genuine (as "Only a genuine SnapSync app can change an event" requires),
the app SHALL tell the user that this phone was refused and SHALL name which of three causes applies: the phone's
system is modified (an unlocked bootloader, a system its maker did not ship, or hardware that gives only a proof any
software could produce, as an emulator's does); the phone could not be verified (the proof its hardware gives is not
one the service recognises, nor one only software could have produced); or this copy of the app is not the official
one. It SHALL NOT present a refusal as the server being unreachable or the network being at fault. When the phone
could not be verified, the app SHALL say the user did nothing wrong and SHALL offer to report the problem. When the
app is not the official one, it SHALL point the user to the official store. A refusal SHALL be told only while the
app's latest attempt to verify this phone was refused: an attempt that got no answer SHALL NOT be told as a refusal,
and a successful verification SHALL clear it at once. Where each screen shows the refusal is that screen's capability
(`create-event`, `join-event`, `sync-status`).

#### Scenario: A phone with an unlocked bootloader is told why
- **WHEN** the service refuses a phone because its bootloader is unlocked
- **THEN** the app says this phone's system is modified or its bootloader is unlocked and that SnapSync works only
  on a phone running its maker's system, and never says the server could not be reached

#### Scenario: A phone whose hardware gives only a software proof is told its system is modified
- **WHEN** the service refuses a phone, or an emulator, because the only proof it gives is one software alone could
  have produced
- **THEN** the app says this phone's system is modified and that SnapSync works only on a phone running its maker's
  system, never that the phone could not be verified, and offers no report of the problem

#### Scenario: A phone whose proof is not recognised is not blamed
- **WHEN** the service refuses a phone because the proof its hardware gives is not one the service recognises
- **THEN** the app says this phone could not be verified, that this is not something the user did, and offers to
  report the problem

#### Scenario: An unofficial copy of the app is pointed to the store
- **WHEN** the service refuses a copy of the app that is not the official one
- **THEN** the app says this copy is not the official one and points the user to the official store

#### Scenario: No answer is not a refusal
- **WHEN** the app's attempt to verify the phone gets no answer from the service
- **THEN** no refusal is told, and a failed create or join says the server could not be reached as before

#### Scenario: A refusal the service stops making clears
- **WHEN** a phone was refused and a later attempt to verify it succeeds
- **THEN** the refusal is no longer shown anywhere, and creating and joining work as on any genuine phone
