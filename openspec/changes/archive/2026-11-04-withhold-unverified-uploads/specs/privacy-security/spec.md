## MODIFIED Requirements

### Requirement: A verification problem never loses a photo
Photos waiting to be shared SHALL be held on the device and retried, never dropped, while the app cannot
currently prove it is genuine — for example because its proof expired while the app was not opened — and
SHALL be shared once the app is verified again. A photo SHALL NOT be sent while the phone holds no proof at
all: it waits on the phone, and is sent with a proof once the phone has one again. The stall SHALL be made visible
to the member (capability `sync-status`), never hidden behind a screen that reads as healthy. A proof that
is still valid SHALL keep sharing working even when an attempt to renew it fails.

#### Scenario: The proof expires while the phone sits unused
- **WHEN** a member does not open SnapSync for longer than the proof lasts, while taking event photos
- **THEN** those photos are not lost, and they are shared after the member next opens the app and it is verified

#### Scenario: A renewal fails while the proof is still valid
- **WHEN** the app tries to renew its proof while offline, and the current proof has not yet expired
- **THEN** photos keep being shared and the member is not told sharing has stopped

#### Scenario: A phone whose proof was refused sends no photo until it is verified again
- **WHEN** the service refused the proof of a member's iPhone while iOS shares their photos in the background, and
  the member takes another event photo before opening the app
- **THEN** the photo does not leave the phone, and it is shared once the member opens the app and it is verified
