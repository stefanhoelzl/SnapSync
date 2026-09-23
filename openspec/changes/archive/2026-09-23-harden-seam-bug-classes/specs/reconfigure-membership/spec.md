## ADDED Requirements

### Requirement: A reconfigure whose save fails stops and says so

The reconfigure's config save SHALL be a **required** step (capability `module-architecture`, "A multi-step
use case declares which steps are required"). When it fails, the reconfigure SHALL run none of its later
steps — no status refresh against the unsaved settings, no upload kick, no album ensure or gather, no
download start or cancel — and the command SHALL return a failure outcome that presentation reduces into
state, so the surface stays open with the member's unsaved choices and says the save did not land. The
later steps remain best-effort once the save has landed.

#### Scenario: The save throws

- **WHEN** a member saves settings that enable the event album and turn download off, and the config
  save throws
- **THEN** no album is created or filled, in-flight downloads are not cancelled, the persisted settings
  are unchanged, and the surface reports that the save failed

#### Scenario: A later step fails after the save landed

- **WHEN** the save succeeds and the album gather then fails
- **THEN** the failure is logged, the download arm is still re-driven, and the reconfigure reports
  success
