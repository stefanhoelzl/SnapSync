## MODIFIED Requirements

### Requirement: An invalid or expired link says so
The page SHALL say the link is invalid or expired when the link is malformed or its event no longer
exists — including an event whose photos were deleted because it finished or reached the end of its
lifetime (capability `event-lifetime`). It SHALL then suggest asking the host for a fresh link, still
offer "Get SnapSync", and offer no download.

#### Scenario: A truncated link
- **WHEN** a visitor opens an invite link whose event part is cut off or corrupted
- **THEN** the page says the link is invalid or expired and offers no download

#### Scenario: An event that has been deleted
- **WHEN** a visitor opens the invite link of an event that has been deleted
- **THEN** the page says the link is invalid or expired and offers no download

#### Scenario: A finished event
- **WHEN** a visitor opens the invite link of an event whose members all received its photos and whose
  photos were then deleted, before its 30 days are up
- **THEN** the page says the link is invalid or expired and offers no download
