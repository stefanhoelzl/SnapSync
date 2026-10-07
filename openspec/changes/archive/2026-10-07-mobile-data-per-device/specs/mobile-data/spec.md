# Spec Delta

## ADDED Requirements

### Requirement: The member chooses for the device whether photos may use mobile data
The device SHALL carry one choice whether photos may be sent and received over mobile data, and it SHALL govern
the photo transfers of whichever event the device is in. The choice SHALL be on — photos may use mobile data —
unless the member turns it off, so a member who never touches it shares and receives exactly as without the
choice. It SHALL be made in the app menu (capability `sync-status`), with or without an event, and SHALL apply as
soon as it is switched, without a Save; it SHALL work offline. It SHALL keep its value when the member leaves an
event, joins another and when the app is updated; a new installation of the app SHALL start with it on. It SHALL
apply the same way to sharing and to receiving. If the choice cannot be saved, the switch SHALL return to the
choice still in effect and say that the change could not be saved, and no transfer SHALL follow the change.

#### Scenario: Untouched, photos use any network
- **WHEN** a member joins without ever changing the mobile-data choice and takes a photo while on mobile data
- **THEN** the photo is shared over mobile data, as it would be without the choice

#### Scenario: The choice carries to the next event
- **WHEN** a member who turned mobile data off leaves an event and later joins another
- **THEN** the new event's photos travel only on Wi-Fi, without the member choosing again

#### Scenario: Chosen before joining
- **WHEN** someone with no event turns mobile data off in the menu, then joins an event while on mobile data
- **THEN** the join completes, and no photo of that event is transferred until the phone joins an unrestricted Wi-Fi

#### Scenario: A change that cannot be saved
- **WHEN** the member flips the mobile-data switch and the change cannot be saved
- **THEN** the switch returns to the choice in effect, the menu says the change could not be saved, and transfers keep the rule they had

## MODIFIED Requirements

### Requirement: A change applies to transfers that start afterwards
Changing the choice SHALL govern every photo transfer that starts after the change. A transfer
already under way SHALL keep the rule it started with: after turning mobile data off, a transfer already
under way MAY still finish over mobile data; after turning it on, a photo already waiting for Wi-Fi MAY
keep waiting for it, while every other photo uses mobile data at once. No transfer SHALL be cancelled or
lost because of a change.

#### Scenario: Turning mobile data on sends the rest at once
- **WHEN** a member with mobile data off and many photos waiting turns the choice on while on mobile data
- **THEN** photos start uploading and arriving over mobile data without waiting for Wi-Fi, apart from the few already handed over to wait

#### Scenario: Turning mobile data off stops new transfers
- **WHEN** a member turns the choice off while on mobile data
- **THEN** no photo transfer starts over mobile data from then on

## REMOVED Requirements

### Requirement: The member chooses per membership whether photos may use mobile data
**Reason**: The choice follows the member's data plan, not the event; it becomes one choice of the device, made in the app menu.
**Migration**: Replaced by "The member chooses for the device whether photos may use mobile data". The per-membership choice never reached a released build, so no member's choice is carried over: an internal build's membership that had it off starts on with the device's choice.
