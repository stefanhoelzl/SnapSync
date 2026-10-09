## MODIFIED Requirements

### Requirement: The member chooses for the device whether photos may use mobile data
The device SHALL carry one choice whether photos may be sent and received over mobile data, and it SHALL govern
the photo transfers of whichever event the device is in. The choice SHALL be on — photos may use mobile data —
unless the member turns it off, so a member who never touches it shares and receives exactly as without the
choice. It SHALL be made in the app menu (capability `app-experience`), with or without an event, and SHALL apply as
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
