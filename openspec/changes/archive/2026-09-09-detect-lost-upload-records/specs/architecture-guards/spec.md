## MODIFIED Requirements

### Requirement: The composition seam gate

The build SHALL fail when the function-typed field inventory of **any composition bundle listed in the
gate** differs from a pinned list held in `:test:architecture`, exact in **both** directions. A new
function-typed field fails until it is pinned with a stated reason it is not a port; a removed one fails
until the pin is deleted, so the inventory cannot outlive what it describes.

The listed set SHALL be every `*Ports` bundle declared in `compose/`, and the gate SHALL fail while a
declared bundle is unlisted — a bundle it does not know about is another place the composition can hand
the core a lambda unseen. A bundle whose fields are **all** ports is listed with an **empty** pinned
inventory rather than omitted: an omission and "this bundle hands the core no lambda" are different
facts, and only the entry distinguishes them.

The gate pins an inventory rather than inspecting call targets, deliberately: whether a lambda
reaches out of the process is not decidable from its declared type — `downloadStagingRoot: () ->
String` and `deviceId: () -> String` are type-identical while one resolves a platform container and
the other returns a value the composition already holds. The pin records a human judgement once,
which is the same mechanism the shell gates use for complexity suppressions.

Every listed bundle SHALL carry a non-vacuity floor on the number of parameters its scan resolves. The
check that the scanner distinguishes function-typed fields from the rest SHALL be applied to the bundles
that carry **both** kinds, because a bundle of ports alone parsing as all-non-function is the correct
answer there rather than evidence of a broken scan.

**What it does not cover, stated so a green run is not over-read:** it constrains what the
composition hands the core. It says nothing about what the OS hands the shell — registering an
`NSNotificationCenter` observer or submitting a `BGProcessingTaskRequest` is the shell being called
by the platform, not accessing it, and is out of scope.

#### Scenario: A function-typed field is added to a composition bundle
- **WHEN** any listed composition bundle gains a function-typed field that is not in the pinned
  inventory
- **THEN** the gate fails, naming the field, until it is given a port type or pinned with its reason

#### Scenario: A pinned seam is converted to a port
- **WHEN** a pinned function-typed field is replaced by a port type
- **THEN** the gate fails until its pin is removed, so the inventory shrinks with the code

#### Scenario: The gate is pointed at a bundle that no longer exists
- **WHEN** the scanned declarations for a listed bundle resolve to fewer fields than its floor
- **THEN** the gate fails as vacuous rather than passing, per "Gates fail closed on novelty"

#### Scenario: A further composition bundle appears
- **WHEN** a new `*Ports` bundle is declared in `compose/`
- **THEN** the gate fails until that bundle is listed with its file, because a bundle it does not
  know about is another place the composition can hand the core a lambda unseen

#### Scenario: A bundle carrying only ports is listed with an empty inventory
- **WHEN** a listed bundle declares no function-typed field at all
- **THEN** its pinned inventory is empty and the gate passes, and the entry is required — an unlisted
  bundle fails, so "no seams here" is stated rather than inferred from silence
