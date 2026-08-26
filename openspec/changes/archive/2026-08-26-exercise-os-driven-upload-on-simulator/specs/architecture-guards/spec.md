## MODIFIED Requirements

### Requirement: The control channel's trigger coverage is derived, never hand-enumerated
Where a dev/test control surface exposes platform entry points, the set it exposes SHALL be **derived**
from the same entry-point population the entry-point guard derives, and every member SHALL be either
wired to a trigger or named in an exclusion list carrying its reason. A guard SHALL assert that the
derived population equals the wired set plus the excluded set, exactly, in both directions.

A hand-picked trigger list rots invisibly: a new OS callback simply cannot be driven, and the only symptom
is a test nobody wrote. Deriving it means adding an entry point fails the build until its disposition is
stated, which is the same bargain the entry-point guard already imposes.

An exclusion SHALL name the consequence that makes it safe. Re-invoking an entry point that registers
process-lifetime observers, or one that reads a process environment fixed for the life of the process, is
a defect rather than an omission, and the reason distinguishes the two.

Where the control surface reaches **more than one composition root**, the derivation SHALL be **grouped by
root**: each root's entry-point population is compared against the wired-plus-excluded set of that root's
own group, and the trigger namespace SHALL name the root it addresses. Comparing one flat set across roots
is not sufficient and SHALL NOT be used — two roots may legitimately declare an entry point of the same
name, and a set comparison silently deduplicates the pair, dropping one entry point from the inventory
while the guard still passes. Grouping makes that collision unrepresentable instead of asserted about, and
it keeps a route leaf equal to the member name it invokes without either root having to rename a member for
disambiguation.

The scope of the derivation SHALL be stated as a consequence of what the surface can reach, not as a
convenience. A scoping reason that has been falsified by the surface growing SHALL be replaced rather than
reworded: the surface previously reached only the app's root, and the exclusion of the extension root's
entry points rested on their being unreachable from it, which ceased to be true when the control channel
began invoking that root.

#### Scenario: A new entry point is added without a trigger disposition
- **WHEN** a new platform entry point is added to a composition root
- **THEN** the coverage guard fails until the entry point is either wired to a trigger or excluded with a
  stated reason

#### Scenario: An exclusion is recorded without a reason
- **WHEN** an entry point is listed as excluded with no reason
- **THEN** the guard fails, because an unreasoned exclusion is indistinguishable from an oversight

#### Scenario: A wired trigger is removed
- **WHEN** a trigger is deleted but its entry point still exists
- **THEN** the guard fails until the entry point moves to the exclusion list with its reason

#### Scenario: An entry point of a second root is unaccounted for
- **WHEN** the control surface reaches a second composition root and one of that root's entry points is
  neither wired nor excluded within that root's group
- **THEN** the guard fails, naming the root and the entry point

#### Scenario: Two roots declare the same entry-point name
- **WHEN** two composition roots each declare an entry point of the same name and both are wired
- **THEN** the guard accounts for both, because each is compared within its own root's group, and neither
  is absorbed by the other

## ADDED Requirements

### Requirement: The upload-job subsystem binding gate

`:test:architecture` SHALL pin, by source text, which implementation each iOS target binds for the **OS
upload-job subsystem** — the registration record and the job queue (`ios-photokit-upload`, "The upload-job
subsystem binding is fixed by the compilation target"). The gate SHALL assert, **exactly in both
directions**:

- the `iosArm64` actuals name the PhotoKit APIs — `setUploadJobExtensionEnabled` and
  `creationRequestForJobWithDestination`; and
- the `iosSimulatorArm64` actuals name **neither**.

A source-text gate is the mechanism for the same reason the transport-binding gate uses one: this repo's
iOS tests run on `iosSimulatorArm64` and nothing else, so the **device** actual is never executed by
anything in CI. A swap of the two actuals would ship a binary whose uploads are inert to real users and
would pass the build, `codesign`, and the whole `iosSimulatorArm64Test` suite.

The stakes are higher here than for the transport binding, and in the opposite direction. Reaching the
PhotoKit job creation on a simulator does not degrade — it raises an uncaught `NSInvalidArgumentException`
from inside PhotoKit and terminates the process. So a mis-bound simulator actual destroys the host it was
meant to serve, with a crash whose stack names Apple's frames rather than ours.

The gate SHALL fail on a missing actual as well as on a wrong one, so deleting a target's actual is not a
way past it. Adding a third iOS target SHALL require extending this pin rather than silently escaping it,
by the rule in "Gates fail closed on novelty".

The gate SHALL NOT assert anything about the *runtime behaviour* of either binding — that the PhotoKit
subsystem accepts a registration on a device, or that it refuses one on a simulator. Those are platform
facts with their own forcing proofs and expiry triggers in `ios-photokit-upload`, and a text gate that
claimed them would be asserting what it cannot observe.

#### Scenario: The device actual loses its PhotoKit call
- **WHEN** the `iosArm64` binding stops naming `setUploadJobExtensionEnabled` or
  `creationRequestForJobWithDestination`
- **THEN** the gate fails, because a shipped binary would register nothing and create no upload job

#### Scenario: The simulator actual gains a PhotoKit call
- **WHEN** the `iosSimulatorArm64` binding names either PhotoKit API
- **THEN** the gate fails, because reaching that call on a simulator terminates the process

#### Scenario: A target's actual is deleted
- **WHEN** either target's actual is removed
- **THEN** the gate fails on the missing actual rather than passing vacuously

#### Scenario: A third iOS target is added
- **WHEN** a new iOS compilation target is introduced
- **THEN** the gate fails until the pin names that target's binding explicitly
