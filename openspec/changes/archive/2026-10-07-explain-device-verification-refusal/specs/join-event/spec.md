# Spec Delta

## ADDED Requirements

### Requirement: A refused phone is told why it cannot join
When the user taps Join and the service refuses this phone as not genuine, the join screen SHALL show that refusal
and its cause (capability `privacy-security`, "A refused phone is told why") — never a generic failure and never
that the connection dropped — with Retry, which first tries to verify the phone again, and Cancel. The device SHALL
be in no event.

#### Scenario: A refused guest is told why
- **WHEN** a guest taps Join on a phone the service refuses as not genuine
- **THEN** the join screen says this phone was refused and why, offers Retry and Cancel, and the device is in no event

#### Scenario: Retry after the service stops refusing
- **WHEN** a refused guest taps Retry after the service has stopped refusing their phone
- **THEN** the guest joins with the choices they made, as any guest does
