# Spec Delta

## MODIFIED Requirements

### Requirement: An encrypted event's photos open only with the whole invite
An encrypted event's page opened with its whole invite SHALL offer the event's photos exactly as a plain event's page
does, opened in the visitor's browser (capability `invite-link`), each under the name it had on its sender's phone,
also opened in the browser, and the zip SHALL be named after the name the members gave the event. A photo whose name
does not open with the invite's key SHALL be left out of the zip, a Live Photo's still and video together. Opened
without the key, or with another, the page SHALL still show the event's dates, status and members, SHALL name the event
only as its invite's preview name, if it carries one, SHALL say that only the whole invite opens its photos, SHALL
still offer "Get SnapSync", and SHALL offer no download.

#### Scenario: The whole invite downloads the photos
- **WHEN** a visitor opens an encrypted event's whole invite in a browser and downloads its photos
- **THEN** the zip holds every shared photo, each one viewable as it was taken and under its original file name, and
  is named after the event

#### Scenario: A photo whose name does not open is left out
- **WHEN** a visitor downloads an encrypted event's photos with its whole invite, and one Live Photo's name does not
  open with the invite's key
- **THEN** the zip holds every other photo, and neither the still nor the video of that Live Photo

#### Scenario: An invite without its key
- **WHEN** a visitor opens an encrypted event's invite whose key was cut off
- **THEN** the page names the event as the invite's preview name, shows its dates, status and members, says the whole
  invite is needed for the photos, and offers no download

#### Scenario: An invite without its key or a preview name
- **WHEN** a visitor opens an encrypted event's invite that carries neither its key nor a preview name
- **THEN** the page shows its dates, status and members, names no event, says the whole invite is needed for the
  photos, and offers no download
