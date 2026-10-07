# Spec Delta

## ADDED Requirements

### Requirement: An encrypted event's photos open only with the whole invite
An encrypted event's page opened with its whole invite SHALL offer the event's photos exactly as a plain event's page
does, opened in the visitor's browser (capability `privacy-security`). Opened without the key, or with another, the
page SHALL still name the event and show its dates, status and members, SHALL say that only the whole invite opens
its photos, SHALL still offer "Get SnapSync", and SHALL offer no download.

#### Scenario: The whole invite downloads the photos
- **WHEN** a visitor opens an encrypted event's whole invite in a browser and downloads its photos
- **THEN** the zip holds every shared photo, each one viewable as it was taken

#### Scenario: An invite without its key
- **WHEN** a visitor opens an encrypted event's invite whose key was cut off
- **THEN** the page names the event and shows its dates, status and members, says the whole invite is needed for
  the photos, and offers no download
