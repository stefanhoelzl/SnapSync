# Spec Delta

## ADDED Requirements

### Requirement: A page the phone will not open is told

When the member follows a link to a page outside the app (the website or the Privacy Policy in the app menu, or the
store button on the update-required notice) and the phone does not open it, the app SHALL tell the member on the
screen they are on. It SHALL say that the page could not be opened, and SHALL say where the page can be found: the
website's address for a page of SnapSync's website, or the store by name for SnapSync's store page. The notice SHALL
be calm, in line with "Failures are told calmly", SHALL clear itself after a moment or when tapped, and SHALL leave
the screen otherwise as it was. When the phone does open the page, no notice SHALL be shown.

#### Scenario: The phone does not open the Privacy Policy
- **WHEN** a member taps the Privacy Policy in the menu and the phone does not open it
- **THEN** the app says the page could not be opened and shows the website's address, and the screen is otherwise
  as it was

#### Scenario: The notice clears itself
- **WHEN** the notice that a page could not be opened is shown and the member does nothing
- **THEN** after a moment the notice is gone

#### Scenario: The phone does not open the store
- **WHEN** a member taps the App Store button on the update-required notice and the phone does not open it
- **THEN** the app says the App Store could not be opened, and the update-required notice stays as it was

#### Scenario: The page opens
- **WHEN** a member taps the website in the menu and the phone opens it
- **THEN** no notice is shown when they return to SnapSync
