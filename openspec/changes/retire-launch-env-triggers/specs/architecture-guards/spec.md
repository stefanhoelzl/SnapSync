## REMOVED Requirements

### Requirement: The launch-trigger index agrees with production source

**Reason**: The index and the triggers it indexed are both gone. The requirement held a duplicate
loud-when-stale — the `ios-device` skill's operator table against the `SNAPSYNC_*` literals in production
Kotlin — and with production Kotlin declaring none, there is nothing on one side to hold the other to. Its
non-vacuity floor (`>= 5` literals) is the exact negation of the invariant this change establishes, so it
cannot be retuned; it has to be replaced.

The stated gap it recorded — that `SNAPSYNC_SEED_PHOTOS`, `SNAPSYNC_SEED_POLICY`, `SNAPSYNC_WIPE_GALLERY`
and `SNAPSYNC_POLICY_PROBE` shipped in production Kotlin and appeared in no spec — closes with them: those
four are now channel commands, and the two that hold behavior are documented in the `rig-channel` runbook.

**Migration**: Replaced by "Production Kotlin declares no launch triggers" below, which asserts absence
rather than agreement. Operator documentation for the channel's commands lives in the `rig-channel` skill
and is not held to source by name, because the channel's own coverage guard already derives its
`/os` and `/user` populations from source and the `/device` set is not derivable from any population.

## ADDED Requirements

### Requirement: Production Kotlin declares no launch triggers

A test-only JVM guard SHALL assert that production Kotlin source declares **no** `"SNAPSYNC_*"` string
literal at all.

Dev/test control of a device is the control channel's surface (`:test:rig`), contained at compile time and
absent from every production build. A `SNAPSYNC_*` literal in production Kotlin is therefore a regression to
a surface this repo removed deliberately: a remote-control affordance present in every shipped binary, inert
only because a SpringBoard launch supplies no process environment — which is a property of how the app is
*started*, not of what it *contains*.

The guard SHALL be an **exact inventory** whose permitted set is empty, and the failure SHALL name every
literal found together with its file. It SHALL NOT be expressed as a maximum count: a count invites being
raised, and the previous guard's floor is what this requirement replaces.

Two readers are deliberately **out of scope**, both for the same reason — the file reading them does not
exist in a production build, so their inertness is a property of the module graph rather than a runtime
check:

- `SNAPSYNC_RIG_PORT`, read in the source `:test:rig` contributes into the shell under its build property;
- the forge target's state selector, read in the forge module's own source.

The scan SHALL therefore cover the production main source sets under `domain/`, `app/`, `adapter/` and
`ui/`, excluding test sources, `build/`, and the build-property-gated trees.

The guard SHALL fail loudly rather than vacuously: an empty *result* over a non-empty *scan* is the passing
condition, and a scan that resolves zero Kotlin files SHALL fail rather than pass while inspecting nothing.

#### Scenario: A launch trigger is re-added to production Kotlin

- **WHEN** a production main source set gains a `"SNAPSYNC_*"` literal
- **THEN** the guard fails, naming the literal and its file, so the trigger must be argued rather than
  landing unnoticed

#### Scenario: A gated tree may read one

- **WHEN** the source `:test:rig` contributes into the shell reads `SNAPSYNC_RIG_PORT`, or the forge module
  reads its state selector
- **THEN** the guard passes, because neither file is on a production build's compile path

#### Scenario: The guard is not vacuous

- **WHEN** the scanned roots are absent, renamed, or resolve to zero Kotlin files
- **THEN** the guard fails rather than passing while inspecting nothing
