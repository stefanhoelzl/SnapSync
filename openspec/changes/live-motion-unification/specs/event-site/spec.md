## MODIFIED Requirements

### Requirement: The zip holds every photo the members have shared
The downloaded zip SHALL contain every photo and video the event's members have shared that has finished
arriving in the event — including those shared by members who have since left — each as its original file,
under its original name, with same-named files kept apart by renaming rather than one replacing another. A
Live Photo SHALL be saved as its still image and, beside it, its motion video, the two under the same name
apart from their extensions, so the pair stays together when opened again; a renamed Live Photo SHALL keep
its two files under the same new name. The zip SHALL be named after the event. Which photos are in the event,
and when a photo is withdrawn from it, is capability `photo-sharing`.

#### Scenario: A full download
- **WHEN** a visitor downloads an event whose members have shared 40 photos and videos, none of them Live
  Photos
- **THEN** the zip holds 40 files, each under its original file name, and is named after the event

#### Scenario: A Live Photo
- **WHEN** the event holds a Live Photo whose still is "IMG_4471.HEIC"
- **THEN** the zip holds "IMG_4471.HEIC" and its motion video "IMG_4471.MOV"

#### Scenario: Two photos with the same name
- **WHEN** two members' photos carry the same original file name
- **THEN** both are in the zip, one of them under a distinguishing name

#### Scenario: Two Live Photos with the same name
- **WHEN** two members' Live Photos carry the same original file name
- **THEN** both pairs are in the zip, and the renamed pair's still and video share their new name
