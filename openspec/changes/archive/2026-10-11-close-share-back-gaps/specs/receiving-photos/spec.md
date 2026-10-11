## MODIFIED Requirements

### Requirement: A deleted received photo never comes back

Once a received photo has been saved, the app SHALL NOT download or save it again, even after the member
deletes it from their library, leaves and rejoins, switches events, or the app restarts. A photo shared
into two events this device joins SHALL be saved once. Deleting the app SHALL NOT make a received photo
that is still in the library, carrying its SnapSync mark and visible to the app, arrive again: joining an
event recognises such photos as already received, and nothing is received before it has. Deleting the app
does forget the rest — a member who reinstalls and joins an event again MAY receive a second time a photo
they had deleted before the reinstall, a photo received by a version of the app that did not mark it, a
photo the app cannot see because photo access is limited to a selection, or a photo shared into the event
after that join — an accepted gap.

#### Scenario: Deleting a received photo
- **WHEN** the member deletes a received photo and new photos later arrive
- **THEN** the deleted photo is not received again

#### Scenario: The same photo in two events
- **WHEN** a photo this device already received in one event is also part of another event it joins
- **THEN** it is not saved a second time

#### Scenario: A reinstall recognises received photos
- **WHEN** a member with full photo access who received an event's photos deletes and reinstalls the app,
  then joins that event again
- **THEN** the received photos still in their library do not arrive a second time

#### Scenario: A rejoin that cannot reach the event receives nothing twice
- **WHEN** a member with full photo access reinstalls the app and joins the same event again while the event's
  photos cannot be read, and they can be read later
- **THEN** the received photos still in their library do not arrive a second time

#### Scenario: A reinstall may receive again
- **WHEN** a member deletes a received photo, then deletes and reinstalls the app and joins that event again
- **THEN** that photo may arrive in their library a second time

#### Scenario: A reinstall under limited access may receive again
- **WHEN** a member whose photo access is limited to a selection reinstalls the app and joins the event again
  without selecting the photos they had received
- **THEN** those photos may arrive in their library a second time

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
