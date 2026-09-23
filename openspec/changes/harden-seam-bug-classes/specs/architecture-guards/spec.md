## MODIFIED Requirements

### Requirement: Every user command declares its dispatcher lane

A gate SHALL fail the build when any field of the user-command bundle **or of the user-query bundle** is
built without a lane-declaring decorator. Two decorators SHALL exist — one for commands that present
platform UI and must run on the main lane, one for everything else — and neither SHALL be a default, so a
command or query that declares no lane does not compile. A query SHALL use a decorator that awaits its
result on the core lane.

This gate exists because the composition scope cannot cover this door: the presentation container launches
an intent on an unconfined dispatcher, and a composable's effect runs on the main thread, so a seam's
synchronous prefix runs on the thread that fired it. It also keeps the manually-verified surface small —
the lane choice for every command and query is visible in one file, which matters because the UI-lane
commands cannot be exercised by any automated test available to this project.

#### Scenario: A command is added without a lane
- **WHEN** a field is added to the user-command bundle and built without either decorator
- **THEN** the build fails

#### Scenario: A query is added without a lane
- **WHEN** a field is added to the user-query bundle and built without the query decorator
- **THEN** the build fails

#### Scenario: A command's lane is reviewed
- **WHEN** a reviewer checks whether platform-UI commands stay on the main lane
- **THEN** every command's and query's lane is readable in the single file where the bundles are built

### Requirement: The composition seam gate

The build SHALL fail when the function-typed inventory of **any scanned declaration** differs from a
pinned list held in `:test:architecture`, exact in **both** directions. A new function-typed field or
parameter fails until it is pinned with a stated reason it is not a port; a removed one fails until the pin
is deleted, so the inventory cannot outlive what it describes.

The scanned set SHALL be every `*Ports` bundle declared in `compose/` **and every constructor of a class
declared in `feature/` or `compose/`**, and the gate SHALL fail while a declared bundle is unlisted — a
bundle it does not know about is another place the composition can hand the core a lambda unseen. A bundle
whose fields are **all** ports is listed with an **empty** pinned inventory rather than omitted: an
omission and "this bundle hands the core no lambda" are different facts, and only the entry distinguishes
them. Constructor parameters are discovered, not listed: every function-typed constructor parameter in
those zones is pinned or the gate fails.

A pinned reason SHALL state what the seam's bindings do in **each** composition that binds it, and SHALL
be one the law admits: a callback into the core that cannot throw and does not leave the process. A reason
that claims a seam "returns a value the composition already holds" SHALL NOT be accepted for a value the
composition obtains by a platform read, however it is cached.

The gate pins an inventory rather than inspecting call targets, deliberately: whether a lambda reaches out
of the process is not decidable from its declared type — two `() -> String` seams are type-identical while
one resolves a platform container and the other returns a constant. The pin records a human judgement once,
which is the same mechanism the shell gates use for complexity suppressions.

The scanner SHALL recognise a function type wherever the declared type is one, including a nullable
function type written `(… -> …)?` and a `suspend` function type.

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

#### Scenario: A feature gains a function-typed constructor parameter
- **WHEN** a class in `feature/` gains a function-typed constructor parameter that is not pinned
- **THEN** the gate fails, naming the class and parameter

#### Scenario: A nullable function type is declared
- **WHEN** a scanned declaration types a parameter `(() -> Unit)?`
- **THEN** the scanner counts it as function-typed

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

## ADDED Requirements

### Requirement: The callback-slot and lambda-default gates

A gate SHALL fail the build when production Kotlin source declares a function-typed `var` property, and
when production Kotlin source declares a default value on a function-typed parameter or property, except
where that type is `@Composable`. Each failure SHALL name the file and declaration. Both gates SHALL fail
closed when their scan resolves no production source.

#### Scenario: A nullable callback slot is added
- **WHEN** a production class declares `var onStaged: (suspend (…) -> Unit)? = null`
- **THEN** the callback-slot gate fails

#### Scenario: A defaulted effect lambda is added
- **WHEN** a production constructor declares `registerPush: suspend () -> Unit = {}`
- **THEN** the lambda-default gate fails

#### Scenario: A content slot keeps its default
- **WHEN** a composable declares `trailing: @Composable () -> Unit = {}`
- **THEN** the lambda-default gate passes

### Requirement: The catch gate

A gate SHALL fail the build when production Kotlin source calls `runCatching` or declares
`catch (… : Throwable)` or `catch (… : Exception)`, except inside the cancellation-keeping helpers and the
ObjC-boundary helpers, which are named in the gate. A catch of `TimeoutCancellationException` is legal.

#### Scenario: runCatching around a suspend call
- **WHEN** an HTTP adapter wraps its request in `runCatching { … }`
- **THEN** the catch gate fails, naming the call site

### Requirement: The flow fan-out gate

The flow-zone gate SHALL fail when a `flow/` source file calls `launch` or `async` other than through the
isolating fan-out helper.

#### Scenario: A flow launches a child directly
- **WHEN** a flow's entry point calls `launch { … }` inside `coroutineScope`
- **THEN** the gate fails, naming the file

### Requirement: The ObjC-boundary gate

A gate SHALL fail the build when `iosMain` source in an adapter or shell module passes a Kotlin lambda
as an argument named for an ObjC block (`completionHandler`, `completion`, a `performChanges` change
block, a notification-observer block) or overrides an ObjC delegate method, without running the body
through the boundary helper; and when it calls a selector ending in `error:` without the checked-call
helper. The gate is a text scan and SHALL state in its source that it is heuristic: what it cannot see is a
block passed positionally under an unrecognised name.

#### Scenario: A delegate callback writes the store unguarded
- **WHEN** `URLSession:task:didCompleteWithError:` calls the ledger directly
- **THEN** the gate fails until the body runs through the boundary helper

#### Scenario: An error-reporting selector's result is dropped
- **WHEN** `submitTaskRequest(request, null)` is called outside the checked-call helper
- **THEN** the gate fails

### Requirement: The confinement gate

A gate SHALL fail the build when a production class that receives an OS callback (it implements an ObjC
delegate protocol, or is named in the gate as receiving one through a port) declares a mutable collection
or a `var` field without a confinement annotation naming its lane or a thread-safe primitive type. The gate
is heuristic and SHALL say so in its source.

#### Scenario: A plain list touched from a delegate queue
- **WHEN** such a class declares `private val outstanding = mutableListOf<Job>()`
- **THEN** the gate fails until the field is confined or made thread-safe

### Requirement: The screens take no suspend seam

The presentation gate SHALL fail when a `:ui:screens` or `:ui:components` declaration takes a `suspend`
function-typed parameter or field. Screens reach the core only through presentation, whose queries are
lane-decorated.

#### Scenario: A screen takes a suspend count query
- **WHEN** a screen's action bundle declares `shareableCount: suspend (…) -> Int?`
- **THEN** the gate fails

### Requirement: Every OS entry point has a parity test

A gate SHALL fail the build when an OS entry point in the iOS root (the set the OS-handler containment
gate already enumerates) has no integration test tagged with that entry point's name. The inventory is
derived from the shell source, never hand-listed, and the gate SHALL fail closed when it resolves no entry
point.

#### Scenario: A new OS entry point is added
- **WHEN** the root gains a new handler for an OS callback
- **THEN** the gate fails until an integration test exercises it from a cold core
