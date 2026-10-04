# Spec Delta

## MODIFIED Requirements

### Requirement: Photos arrive without the app being opened

Downloads and saving into the library SHALL continue while the app is in the background or not running,
and SHALL finish without a visit to the app. A photo whose download finished but whose save the system cut
short SHALL be saved on the device's next background wake of any kind — another member's new photo, a
finished transfer, or the app's own scheduled background work — or, at the latest, the next time the
member opens the app; it SHALL never be lost or saved twice. Downloads SHALL use cellular data as well as
Wi-Fi, unless the member chose not to use mobile data for photos, in which case they wait for an
unrestricted Wi-Fi (capability `mobile-data`).

#### Scenario: Downloads finish in the background
- **WHEN** the member joins, downloads start, and the member leaves the app
- **THEN** the downloads complete and the photos are saved without the app being reopened

#### Scenario: A save that ran out of time completes later
- **WHEN** photos finished downloading but could not be saved before the system suspended the app
- **THEN** they are saved on the next background wake, without the member opening the app — or, if no
  wake comes, the next time the member opens it

#### Scenario: Downloads wait for Wi-Fi when the member chose so
- **WHEN** a receiving member with mobile data off is on mobile data while another member shares a photo
- **THEN** the photo arrives in their library in the background once the phone is on an unrestricted Wi-Fi
