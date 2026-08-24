## ADDED Requirements

### Requirement: The committed captures depict the real screen in a reachable state

Every committed raw capture under `screenshots/` SHALL be a screenshot of the **real** status screen — the
same `StatusScreen` the shipped app renders — running in a real process, over forged **sources**. A listing
image SHALL NOT be composed from a fabricated frame, a mockup, or a hand-authored `UiState`.

The forging SHALL substitute the container's **inputs**, never its output: the state a capture shows SHALL be
produced by the real presentation reduction from those inputs, so a frame that the reduction cannot reach
cannot be captured. This is what makes the listing's screenshots honest — they depict states the app can
actually be in, and a state that stops being reachable stops being capturable rather than silently persisting
as a picture of something the app no longer does.

A capture SHALL require no backend, no attestation, and no photo-library access, so the capture pipeline
cannot be made to depend on a live event or a real library — either of which would put a real member's
content one mistake away from the App Store listing.

The capture mechanism itself is **not** specified here and holds no contract of its own: it renders the real
screen over forged sources and decides nothing (compare `:test:rig` and `:test:harness-driver`, both
deliberately unspec'd). What this requirement fixes is the property the listing depends on, which no other
requirement states.

The captures have **no automated check**. Whether a capture is a faithful picture of a reachable state is
verified by a person looking at it before it is committed; a system notification landing in a frame has been
observed (1 of 2 runs). This is stated so that the absence of a gate is a recorded fact rather than an
oversight.

#### Scenario: A capture is a real render
- **WHEN** a committed raw under `screenshots/` is produced
- **THEN** it is a screenshot of the real `StatusScreen` in a running process, rendering state the real
  presentation reduction produced from forged inputs

#### Scenario: An unreachable frame cannot be captured
- **WHEN** a capture is requested for a state the presentation reduction cannot produce from any inputs
- **THEN** no capture is produced, rather than a fabricated frame depicting it

#### Scenario: A capture needs no live event or real library
- **WHEN** the capture pipeline runs
- **THEN** it contacts no backend, performs no attestation, and reads no photo library, so no real member's
  event or photos can reach a listing image

#### Scenario: The human check is the only check
- **WHEN** a refreshed capture set is committed
- **THEN** it has been reviewed by a person, because no automated check distinguishes a good capture from one
  carrying a system notification or a stale frame
