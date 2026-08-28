# coverage-bounds Specification (new)

## Purpose

A **floor on test coverage for every measured scope**, seeded at what the tree already measures, and
permitted to move in one direction only: up. Its destination is full coverage, and the bounds are the
register of how far away that is.

It exists because nothing measured coverage at all. `./gradlew build` compiles every target, runs
every JVM test, runs every architecture guard, and holds every Kotlin source under a complexity
ceiling (capability `complexity-budgets`) — and a class added with no test passes all of it in
silence. The first measurement of the tree found four classes in `:adapter:generic:app` with no test
of their own, including `HttpAttestClient` at 0% — the client behind capability
`device-attestation` — while its package-mate `HttpLeaveNotifier` has one; and it found that two of
the five `flow/` trigger flows, `Background` and `DownloadBackstop`, are executed by no test in the
repository on any tier. Nothing was going to report any of that.

This capability is the same **kind** of thing as `complexity-budgets` and inherits its honesty about
what it is: a ratchet carried by a written contract, not a proof. Nothing mechanically prevents a
bound from being lowered in the change that would have violated it. It differs in polarity — a
complexity ceiling may only fall, a coverage bound may only rise — and in destination: a complexity
budget has no target beyond "lower", while this one converges on full coverage and its bounds are
meant to be retired by reaching it.

The bound that carries the most information is the **package floor**: the coverage of the worst
package in a module. A module aggregate can hide a wholly untested package behind well-tested
neighbours, and in this tree it did. The floor cannot: it names the next thing to fix, and raising it
is the work.

Decision record: `changes/archive/<id>`.

## ADDED Requirements

### Requirement: Every bounded module sits above a coverage floor

The canonical check (`./gradlew build`) SHALL enforce coverage bounds on every bounded module, as a
verification task attached to `check`, so a scope that falls below its bound fails the build locally
and in CI.

Each bounded module SHALL carry bounds on **two coverage units**, and on both:

- **`INSTRUCTION`**, as the primary measure. `LINE` SHALL NOT be bounded: it is strictly coarser on
  the same axis, and Kotlin places several instructions behind one line — an elvis, a safe-call
  chain, a defaulted argument — so bounding both would be two ratchets moving together.
- **`BRANCH`**, as the orthogonal axis.

Each bounded module SHALL additionally carry a **package floor** — a bound applied per package
(`groupBy = PACKAGE`) rather than to the module total — on `INSTRUCTION`. The floor states that *no
package in this module is worse than this number*, and it is the register's pointer at the next
unpaid debt. The module aggregate and the package floor SHALL both be present, because they fail on
different things: the aggregate catches a broad slide that leaves every package above the floor, and
the floor catches a single package falling behind while the aggregate stays healthy.

`BRANCH` SHALL NOT carry a package floor. Branch counts per package in this tree run from 6 to 353,
so at package granularity a single uncovered `when` arm moves a small package by as much as 17
percentage points, and the floor would report noise rather than debt.

#### Scenario: A class is added with no test

- **WHEN** a class with no test is added to a bounded module
- **THEN** the module's coverage falls below its bound and the build fails, naming the scope

#### Scenario: One package rots behind healthy neighbours

- **WHEN** a single package's coverage falls while the module's aggregate stays above its bound
- **THEN** the package floor fails, because the aggregate alone would not have

#### Scenario: A branch bound is proposed per package

- **WHEN** a per-package bound on `BRANCH` is proposed
- **THEN** it is rejected; branch denominators at package granularity are too small for the number to
  mean anything

### Requirement: A bound may only rise, and the destination is full coverage

Every bound SHALL be **seeded at the value the tree measured** when the scope came under the gate, so
the gate lands green with no code changes, and SHALL thereafter be treated as a value that may only
increase.

Raising a bound is ordinary work: do it in the change that makes it true. **Lowering** one requires a
stated forcing proof in that change's description, naming what makes the loss of coverage
unavoidable. This requirement is enforced by the written contract and by review, **not** by a check,
and the specification states that limitation rather than implying a guarantee it does not deliver.

The contract SHALL be stated in prose in the file that carries the numbers it governs, so a reader
editing a bound encounters the rule without opening anything else. This mirrors `complexity-budgets`
("A ceiling may only fall") and is stated here rather than referenced, because a rule a reader must
go looking for is the failure the arrangement exists to prevent.

The bounds SHALL be read as a **debt register with a destination**: full coverage of every bounded
scope. A scope's bound is the distance still to travel, and the register is discharged by raising
bounds until they can be raised no further — not by deleting the gate.

A mechanical exact-match ratchet — failing whenever measured coverage exceeds its bound — SHALL NOT
be adopted. Its failure mode is inverted: improving the code would fail the build until someone
edited a number, which teaches people to route around the gate. `complexity-budgets` rejected the
same mechanism for the same reason, and the objection is stronger here, because coverage moves on
every commit that adds a test while a complexity ceiling sits still until someone restructures a
function.

#### Scenario: A change improves coverage

- **WHEN** a change raises a scope's measured coverage above its bound
- **THEN** the build passes, and raising the bound to the new value is ordinary work for that change
  or a later one — it is never required by a check

#### Scenario: A change lowers coverage

- **WHEN** a change lowers a scope's measured coverage below its bound
- **THEN** the build fails, and the change either restores coverage or lowers the bound with a stated
  forcing proof

#### Scenario: A reader asks what the gate proves over time

- **WHEN** a reader asks whether these bounds prove coverage improves
- **THEN** the capability answers that they do not: it is a ratchet carried by a written contract, and
  the guarantee is regression detection against the bound as it currently stands

### Requirement: Coverage is measured over unit tests only

Coverage SHALL be measured from **unit tests only**. A module whose tests exercise a broad composed
stack SHALL NOT contribute coverage, so that a thick harness or integration suite cannot stand in for
a thin unit suite.

Three roles SHALL be distinguished, and a module SHALL be placed in each deliberately:

- **Instrumented** — its test tasks produce coverage data. `:domain`, `:adapter:generic:app`,
  `:adapter:generic:fake`, `:ui:presentation`, `:ui:screens`, `:ui:components`.
- **Bounded** — its own classes are measured against a bound. Every instrumented module except
  `:adapter:generic:fake`.
- **Neither** — not instrumented, so its tests contribute nothing and its classes are not measured:
  `:test:integration`, `:test:world`, `:test:architecture`, `:tools:diagrams`, and the modules with
  no test source set at all (`:app:desktop`, `:test:harness-driver`).

`:adapter:generic:fake` is instrumented but **not** bounded, and the split is the point: the tests in
its `commonTest` are `:domain`'s feature tests, hosted there only because a test source set cannot be
depended on across modules (`testing-architecture`, "Fake-driven feature tests live in the fake
module"), while the fakes themselves are test equipment and bounding them would ratchet the harness
rather than the product. The same reasoning excludes `:test:world`'s classes.

`:test:world` is additionally **not instrumented**, because its `commonTest` is the tier
`testing-architecture` names in "The world hosts feature tests over the real stack" — real features
driven against a composed world. Excluding it is what makes the numbers say what they appear to say,
and the specification records what that cost when it was applied: `:adapter:generic:app` fell from
76% to 68%, because `HttpEnrollment` and `HttpDeviceFilesSource` have no unit tests and the world
harness had been covering them incidentally. That is the gate reporting real debt, not an artifact of
scoping.

A module's bound SHALL be computed over every in-scope unit test that exercises it, including tests
a placement rule forced to live in another module. Where such a rule applies, a **crediting edge**
SHALL make those tests contribute, and the receiving module's report SHALL be filtered back to its
own classes so the edge contributes coverage without contributing classes to be measured. Two such
rules apply today: `testing-architecture`'s "Fake-driven feature tests live in the fake module"
(without the edge `:domain` measures 56% rather than 91%), and Compose screens exercising the design
system they render (without it `:ui:components` measures 48% rather than 95%).

Coverage a module's classes receive **incidentally** — from tests written for something else — SHALL
NOT be credited. Measured, an aggregate in which every instrumented module credited every other
lifted `:domain`'s `model/` from 86% to 94%, because UI tests construct `model/` types on their way
to rendering. That is the same substitution this requirement exists to prevent, one level down: a
number raised by tests nobody wrote for the code being measured.

#### Scenario: A placement rule forced a module's tests elsewhere

- **WHEN** a module's unit tests live in another module because a dependency cycle forbids placing
  them with their subject
- **THEN** a crediting edge makes them count, and the report is filtered to the measured module's own
  classes

#### Scenario: Another module's tests happen to execute the code

- **WHEN** a module's classes are executed incidentally by tests written for a different module, with
  no placement rule involved
- **THEN** that coverage is not credited, and the bound reports only tests written for this code

#### Scenario: An integration suite would lift a module's number

- **WHEN** a module's classes are exercised by `:test:integration` or `:test:world` but by no unit
  test
- **THEN** they count as uncovered, and the bound reports the module's unit-test depth

#### Scenario: A module hosts another module's unit tests

- **WHEN** a module exists to host unit tests that a dependency cycle forbids placing with their
  subject
- **THEN** it is instrumented so those tests contribute, and not bounded, because its own classes are
  test equipment

### Requirement: A composition root is outside the measurable set

`:domain`'s `compose/` zone SHALL NOT be bounded, and its exclusion SHALL be recorded as a permanent
scoping decision rather than as debt.

`module-architecture`'s **"One shared composition"** states that the wiring graph SHALL NOT be
unit-tested. That is not merely a policy this capability defers to: a composition root is reachable
only by composing it, and any test that composes the whole graph is by nature not a unit test. Under
a unit-tests-only measurement `compose/` measures 1.13%, and no amount of permitted work would move
it. Listing it in the register would assert a debt that cannot be paid without repealing a law.

The exclusion SHALL name the zone, not the module, so the rest of `:domain` stays bounded, and SHALL
cite the law by requirement name.

This exclusion removes coverage as a signal over that zone. Whether the wiring graph is exercised at
all is `module-architecture`'s question and is answered there; this capability states only that its
own instrument does not reach it.

#### Scenario: The composition root's coverage is proposed as a bound

- **WHEN** a bound over `compose/` is proposed, or its low measurement is recorded as debt
- **THEN** it is rejected, citing "One shared composition": no unit test can reach a composition root,
  so the number is not a statement about test quality

### Requirement: What the gate cannot see is stated

The specification SHALL state what a green coverage gate does **not** cover, so that no reader has to
discover it.

- **Kover measures JVM targets only.** Source outside common and JVM source sets is not instrumented.
  `:adapter:ios:ext-safe` and `:adapter:ios:app-only` declare no JVM target at all: 5,833 production
  LOC and 182 tests, invisible to every bound — and they hold PhotoKit, the Keychain, background
  `URLSession` and every other platform seam. A green gate SHALL NOT be described as a statement
  about the iOS adapters. Expiry trigger: Kover gaining Kotlin/Native instrumentation, or those
  modules gaining a JVM target.
- **The Compose compiler depresses `BRANCH` structurally.** `startRestartGroup`, `$changed` bitmasks
  and default-argument masks emit branch arms on the declaration line of every `@Composable`, and
  many cannot take both paths under test. Measured, the Compose modules show an
  instruction-minus-branch gap near 30 points against roughly 5 for non-Compose comparators. A
  Compose module's `BRANCH` bound is therefore a regression detector for that module against itself
  and SHALL NOT be compared across the Compose boundary. How much of that gap is structurally
  unreachable is **not yet measured**, so full `BRANCH` coverage SHALL NOT be asserted as reachable
  for those modules until it is.
- **Bounds are expressed as integer percentages.** Kover's `minValue` takes an `Int`, so a bound
  concedes up to one percent of its scope permanently — 60 instructions in the largest package
  measured. A bound is therefore a floor with slack, not an exact ratchet.
- **Bounds are specific to the coverage engine.** The two engines available disagree by up to 26% on
  a single package's denominator. The engine SHALL be chosen deliberately and named where the bounds
  live; changing it invalidates every seeded number and requires re-seeding in the same change.

#### Scenario: A coverage number is cited as the repository's coverage

- **WHEN** a bound or report is described as the coverage of the repository
- **THEN** the description is corrected to name the JVM-visible, unit-tested subset, because no
  instrumentation of the iOS adapters exists

#### Scenario: The coverage engine is changed

- **WHEN** a change switches the coverage engine
- **THEN** every bound is re-seeded in that same change, because the previous numbers measured a
  different denominator
