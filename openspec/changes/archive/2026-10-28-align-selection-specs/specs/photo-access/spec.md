## RENAMED Requirements

- FROM: `### Requirement: Switching between full and limited access never re-uploads`
- TO: `### Requirement: Switching between full and limited access re-uploads only what was de-selected`

## MODIFIED Requirements

### Requirement: Switching between full and limited access re-uploads only what was de-selected

Moving from full to limited access SHALL narrow what is shared to the selection from then on: selected photos already
shared SHALL stay shared without being uploaded again, and photos outside the selection SHALL be withdrawn like any
de-selected photo (capability `photo-sharing`). Moving from limited to full access SHALL keep every photo still shared
without uploading it again, and SHALL share the in-range photos not shared yet; a photo that had been withdrawn because
it was outside the selection comes back as a re-included photo does, uploaded again (capability `photo-sharing`,
requirement "Restoring or re-including a photo shares it again"). A switch SHALL also change which kinds of photo are shared: a selected
photo that only limited access shares (capability `photo-sharing`, requirement "Under limited access the member's
selection decides what a photo is") SHALL be withdrawn on moving to full access, and shared again — uploaded again,
like a photo recovered after being deleted — if access becomes limited with it still selected.

#### Scenario: Widening to full access
- **WHEN** a member who shared photos under limited access switches to full access
- **THEN** only newly in-scope photos from the event's range upload, and nothing already shared is sent
  again

#### Scenario: A photo de-selected earlier returns on widening
- **WHEN** a member who removed a shared photo from their limited selection switches to full access
- **THEN** that photo is uploaded again and offered to the other members, and no photo still shared is sent again

#### Scenario: Widening right after changing the selection
- **WHEN** a limited-access member changes their selection and switches to full access before the app has
  finished looking at the changed selection
- **THEN** no shared photo is withdrawn because of the old selection, and nothing is uploaded again

#### Scenario: Narrowing to a selection
- **WHEN** a member who shared photos under full access switches to limited access with a selection
  that excludes some of them
- **THEN** the excluded photos leave the event for the other members, the selected ones stay without
  being uploaded again, and the status reflects the selection

#### Scenario: A selected screenshot leaves on widening to full access
- **WHEN** a limited-access member shared a selected screenshot taken during the event, then switches to full access
- **THEN** the screenshot stops being offered to the other members, members who already have it keep it, and
  nothing is uploaded again

