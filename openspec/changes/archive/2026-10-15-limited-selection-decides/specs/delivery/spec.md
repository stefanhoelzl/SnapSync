# Spec Delta

## MODIFIED Requirements

### Requirement: Only the original is uploaded, complete

Each shared photo SHALL be uploaded as its original as captured — full resolution, original format,
original filename, and its embedded details such as the capture location intact — and a Live Photo SHALL be
uploaded with both its still and its video. Edits made in Photos (crops, filters, adjustments), their
rendered versions and RAW alternates SHALL NOT be uploaded; other members receive the original. On Android
the photo is uploaded as the file the phone stores: a motion photo SHALL be uploaded as its single
file; an edit a gallery app saves as a new photo SHALL be a photo of its own; and an
edit a gallery app saves over the photo before it was uploaded SHALL be what is uploaded. No iCloud or
Google account SHALL be required.

#### Scenario: A Live Photo travels whole
- **WHEN** a member shares a Live Photo
- **THEN** both its still and its video are uploaded

#### Scenario: An edit is not shared
- **WHEN** an iPhone member applies a filter to a photo before or after it was shared
- **THEN** the other members receive the unedited original

#### Scenario: The capture location travels with the photo
- **WHEN** a member shares a photo whose camera recorded where it was taken
- **THEN** the other members receive it with that location

#### Scenario: An Android motion photo travels as one file
- **WHEN** an Android member shares a motion photo
- **THEN** it is uploaded as the one file the camera saved

#### Scenario: No iCloud account
- **WHEN** the member's phone is signed out of iCloud, or an Android phone has no Google account
- **THEN** their photos upload normally
