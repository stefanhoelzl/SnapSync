## ADDED Requirements

### Requirement: Whether staged bytes are still present is readable

The owner of staged bytes SHALL answer whether a given set of staged paths is **still present on disk**,
so the import path can tell "the photo library took these bytes" from "nothing was ever created for this
row" (capability `photo-download`).

The read SHALL report the **filesystem fact** and nothing more. It SHALL NOT report consumption,
ingestion, or any interpretation of why a file is absent: that inference belongs to the download feature,
which owns the knowledge that nothing else can remove a staged file — release runs only after a confirming
write or immediately before dropping a row, and a row carrying a created-asset marker is never dropped.
Placing the inference behind the port would put a load-bearing decision where the shared tests cannot
reach it.

It SHALL live on the same port that owns where staging lives and what may be reclaimed from it, so one
owner decides both and the two can never name different directories.

A path set in which **any** member is missing SHALL be reported as not-all-present: an asset's resources
are ingested individually, so one missing file is as much evidence of a submitted creation as all of them.
An **empty** path set SHALL be reported as all-present, carrying no evidence either way; the caller
distinguishes it from a genuine set and declines to act on it.

#### Scenario: A consumed resource is reported missing

- **WHEN** the photo library has ingested a staged resource and the adjudicator asks about its path
- **THEN** the read reports that the paths are not all present

#### Scenario: Untouched staged bytes are reported present

- **WHEN** an import never reached the library's ingest and its staged files are still on disk
- **THEN** the read reports that the paths are all present

#### Scenario: One missing resource of several is enough

- **WHEN** an asset has two staged resources and only one has been consumed
- **THEN** the read reports that the paths are not all present

#### Scenario: The read reports a fact, not a verdict

- **WHEN** any implementation of the port answers
- **THEN** it reports only whether the files exist, and no caller receives a claim about why one is absent
