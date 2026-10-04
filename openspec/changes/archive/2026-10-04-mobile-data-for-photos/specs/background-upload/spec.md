# Spec Delta

## MODIFIED Requirements

### Requirement: Photos upload without the app being opened

A shareable photo SHALL be uploaded without the member opening the app, on every supported iOS and Android
version. On iOS 26.1 and later with full photo access, iOS itself SHALL be able to upload new photos even
while the app is not running. Below iOS 26.1, and under limited access, the app SHALL upload in the
background on its own wake-ups, iOS's background-upload completions, the silent wakes other members' photos
cause, and on every opening of the app. On Android the app SHALL upload in the background when a photo is
added to the library, on its own wake-ups while photos remain to upload, and on every opening of the app;
Android has no uploader of its own that works while the app is not running. The app SHALL NOT hold uploads
back for Wi-Fi or external power, except that a member who chose not to use mobile data for photos has
their uploads wait for an unrestricted Wi-Fi (capability `mobile-data`); the phone's system MAY still schedule the app's background work at its
discretion, for example to save battery.

#### Scenario: A photo taken with the app closed reaches the event
- **WHEN** a sharing member takes a photo during the event and never opens the app
- **THEN** the photo is uploaded in the background and becomes available to the other members

#### Scenario: Uploads continue after the app is suspended
- **WHEN** the member leaves the app while uploads are in progress
- **THEN** the uploads continue in the background and further queued photos follow

#### Scenario: Uploads work below iOS 26.1
- **WHEN** a sharing member's phone runs iOS 18 through iOS 26.0
- **THEN** their photos are still uploaded in the background, by the app

#### Scenario: Uploads work under limited access
- **WHEN** a sharing member has granted limited access and selects an in-range photo
- **THEN** it is uploaded, without any dependency on iOS's own background uploader

#### Scenario: A new photo wakes the app on Android
- **WHEN** an Android member who has not opened the app for days takes an in-range photo with the camera
  app
- **THEN** it is uploaded in the background, once Android lets the app run

#### Scenario: Uploads wait for Wi-Fi when the member chose so
- **WHEN** a sharing member with mobile data off takes an in-range photo while on mobile data and never opens the app
- **THEN** the photo is uploaded in the background once the phone is on an unrestricted Wi-Fi
