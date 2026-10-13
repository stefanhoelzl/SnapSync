## MODIFIED Requirements

### Requirement: Received photos are never shared back

A photo this device received SHALL never be uploaded back into the event as the member's own — not after
a crash, a restart, a leave or a switch, and not after a reinstall while it is still in the library,
carrying its SnapSync mark and visible to the app, whether the member rejoins sharing and receiving or
sharing only. After a member joins, nothing of theirs SHALL be shared until the app has read which photos
the event holds and recognised the received ones among the photos it can see; while that read cannot be
made, the app SHALL share nothing and SHALL try again at its next opportunity. A received photo that
becomes visible to the app later — added to a selection under limited access — SHALL be recognised the
same way before it can be shared. After a reinstall, two kinds of received photo are forgotten and a member
MAY share them as their own when they lie inside their capture range, and the other members MAY then
receive them a second time — accepted gaps: a photo received by a version of the app that did not mark it,
and a photo received in a different event than the one the member joins.

#### Scenario: A received photo stays out of the member's contribution
- **WHEN** a received photo lands in the library inside the member's capture range
- **THEN** it is not uploaded, not counted as the member's to share, and no member receives it twice

#### Scenario: A reinstall does not share received photos back
- **WHEN** a member with full photo access deletes and reinstalls the app and joins the same event again
  with a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs, and no other member receives them a second time

#### Scenario: On Android a reinstall never shares the album's photos back
- **WHEN** an Android member whose received photos are in the event's album deletes and reinstalls the app
  and joins the same event again with a capture range covering them
- **THEN** none of the album's photos that carry the SnapSync mark is shared as theirs — kept out as on iPhone,
  not by their folder

#### Scenario: A received photo from before the event is recognised after a reinstall
- **WHEN** another member shared photos from the day before the event's start, this member received them, and
  then reinstalls the app and rejoins with a range that also starts the day before
- **THEN** those received photos are not shared as theirs, and no other member receives them a second time

#### Scenario: A received photo from before the event added to a limited selection is not shared back
- **WHEN** a member whose photo access is limited to a selection reinstalls the app, rejoins, and later adds to
  their selection a received photo dated before the event's start that carries the SnapSync mark
- **THEN** it is not shared as theirs, whatever its date

#### Scenario: A rejoin that cannot reach the event shares nothing yet
- **WHEN** a member reinstalls the app and joins the same event again while the event's photos cannot be read
- **THEN** none of their photos is shared until the event's photos have been read, and once they have, the
  received photos they still hold are not shared as theirs

#### Scenario: Received photos added to a limited selection are not shared back
- **WHEN** a member whose photo access is limited to a selection reinstalls the app, joins the same event
  again, and later adds to their selection photos they had received that still carry the SnapSync mark
- **THEN** those photos are not shared as theirs, and no other member receives them a second time

#### Scenario: A share-only rejoin does not share received photos back
- **WHEN** a member with full photo access reinstalls the app and rejoins the same event sharing only, with
  a capture range covering photos they had received and still hold
- **THEN** those photos are not shared as theirs

#### Scenario: A reinstall may share received photos back
- **WHEN** a member reinstalls and rejoins with a capture range covering a photo received by a version of
  the app that did not mark it
- **THEN** that photo may be shared as theirs, and the other members may receive it a second time

#### Scenario: A reinstall may share photos received in another event
- **WHEN** a member reinstalls the app and joins a different event whose capture range covers photos they had
  received in an earlier event and still hold
- **THEN** those photos may be shared as theirs into the event they joined
