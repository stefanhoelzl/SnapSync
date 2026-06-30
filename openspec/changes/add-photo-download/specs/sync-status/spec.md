## ADDED Requirements

### Requirement: Independent download-progress projection

The status surface SHALL expose download progress as an **independent** indicator, separate from the
own-device upload status: a count of foreign complete assets imported (`X`) out of the foreign
complete assets currently in the union (`Y`), asset-counted to match the upload progress convention.
This projection SHALL NOT alter the own-device upload "Completed" notion — uploads are "done" when the
device's own qualifying assets are all present in storage, regardless of download progress. `Y` MAY
grow as other contributors add assets, and the indicator SHALL reflect that honestly.

#### Scenario: Download line is independent of upload completion

- **WHEN** the device's own uploads are complete but foreign downloads are still in progress
- **THEN** the screen shows upload "Completed" **and** a separate "downloaded X of Y" line; the two do
  not gate each other

#### Scenario: Download denominator is foreign complete assets

- **WHEN** the union reports `Y` foreign complete assets and `X` of them are imported
- **THEN** the download line reads `X of Y`, asset-counted

#### Scenario: Denominator grows with new contributions

- **WHEN** other contributors add complete assets to the event
- **THEN** `Y` increases accordingly on the next union read, with no false "all downloaded" state
