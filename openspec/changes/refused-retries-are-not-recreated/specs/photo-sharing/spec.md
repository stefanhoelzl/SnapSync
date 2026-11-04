## MODIFIED Requirements

### Requirement: Deleting or no longer sharing a photo withdraws it

Until the event's photos are deleted (capability `event-lifetime`), a photo SHALL stop being offered to other members
when the member deletes it from their library (moving it to the trash counts), removes it from their limited
selection, moves it out of DCIM on Android under full access, or stops sharing it — by narrowing the range, adding it
to a messaging-app album under full access, or turning sharing off (capability `manage-membership`). The withdrawal
SHALL take effect the next time the device looks at the library, also for a photo that was still uploading. Members who already
received it SHALL keep their copy, and a withdrawal SHALL wake no other member. Losing photo access, or the app being
unable to read the library for a moment, SHALL NOT withdraw anything. A photo the member no longer shares SHALL NOT
be uploaded again after a failed attempt: it waits on the phone, and is uploaded only if the member shares it again.

#### Scenario: A deleted photo is withdrawn
- **WHEN** a member deletes a photo that other members could receive
- **THEN** members who have not yet received it never do, and members who already have it keep it

#### Scenario: A photo deleted mid-upload is withdrawn
- **WHEN** a member deletes a photo whose upload is still in progress
- **THEN** it is withdrawn as soon as the device next looks at the library, even if its bytes reach the
  server afterwards

#### Scenario: Deselecting under limited access withdraws
- **WHEN** a limited-access member removes a shared photo from their selection
- **THEN** it stops being offered to other members

#### Scenario: Moving a photo out of DCIM withdraws it
- **WHEN** an Android member with full photo access moves a shared photo from DCIM into a folder outside it
- **THEN** it stops being offered to other members, and members who already have it keep it

#### Scenario: Turning sharing off withdraws the member's photos
- **WHEN** a member switches from sharing and receiving to receiving only
- **THEN** none of their photos is offered to members who have not received them yet

#### Scenario: Revoked access withdraws nothing
- **WHEN** photo access is revoked while photos are shared
- **THEN** every photo already shared stays available to the other members

#### Scenario: A deletion noticed late is still withdrawn
- **WHEN** a photo is deleted while the device does no work for days, and the event's photos have not been deleted
- **THEN** it is withdrawn the next time the device looks at the library

#### Scenario: A deletion after the event's end is still withdrawn
- **WHEN** a member deletes a shared photo a week after the event's range ended, before the event's photos were deleted
- **THEN** members who have not yet received it never do, and members who already have it keep it

#### Scenario: A photo no longer shared is not sent again after its upload failed
- **WHEN** a photo's upload fails, and before it is retried the member narrows their range so the photo falls outside
  it, or switches to receiving only
- **THEN** the photo does not leave the phone again, and it is uploaded once the member shares it again
