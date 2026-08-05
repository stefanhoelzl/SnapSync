## ADDED Requirements

### Requirement: The harness composes the live core on the shipped lane structure

The world harness SHALL compose the live core on the same dispatcher lane structure the device shell
uses — a serial, non-UI-bound scope — rather than on the UI thread it renders from.

The harness's standing claim is that its core **is** the real core from the same shared composition the
device shell calls. Composing it on the UI thread made that claim true of the object graph and false of
the threading, which is the dimension the dispatcher-lane law governs: the harness would have contradicted
that law while passing every mechanical check, since a UI-bound scope names no main-thread dispatcher for
the containment gate to see.

This is also what makes the harness the verification vehicle for the lane change: it is the only place
where presentation state produced off the UI thread is exercised against the real graph, headlessly and
without a device.

#### Scenario: The harness composes the live core
- **WHEN** the world harness builds its core
- **THEN** the scope it passes is serial and not bound to the UI thread, matching the device shell

#### Scenario: Off-UI-thread state production regresses
- **WHEN** a change makes presentation state unobservable when produced off the UI thread
- **THEN** the harness surfaces it, before any device build is involved

#### Scenario: The forge harness is unaffected
- **WHEN** the forge harness mounts forged sources
- **THEN** this requirement does not apply to it, because it composes no live core
