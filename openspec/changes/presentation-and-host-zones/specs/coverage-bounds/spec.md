## MODIFIED Requirements

### Requirement: Coverage is measured over unit tests only

Coverage SHALL be measured from **unit tests only**. A module whose tests exercise a broad composed
stack SHALL NOT contribute coverage, so that a thick harness or integration suite cannot stand in for
a thin unit suite.

Three roles SHALL be distinguished, and a module SHALL be placed in each deliberately:

- **Instrumented** — its test tasks produce coverage data. `:domain`, `:adapter:generic:app`,
  `:adapter:generic:fake`, `:domain:presentation`, `:ui:screens`, `:ui:components`.
- **Bounded** — its own classes are measured against a bound. Every instrumented module except
  `:adapter:generic:fake`.
- **Neither** — not instrumented, so its tests contribute nothing and its classes are not measured:
  `:test:integration`, `:test:world`, `:test:contracts`, `:test:architecture`, `:tools:diagrams`, and the modules with
  no test source set at all (`:app:desktop`, `:test:harness-driver`).

`:adapter:generic:fake` is instrumented but **not** bounded, and the split is the point: the tests in
its `commonTest` are `:domain`'s feature tests, hosted there only because a test source set cannot be
depended on across modules (`testing-architecture`, "Fake-driven feature tests live in the fake
module"), while the fakes themselves are test equipment and bounding them would ratchet the harness
rather than the product. The same reasoning excludes `:test:world`'s classes, and `:test:contracts`':
the contract mechanism and the contracts are test equipment. A contract bound from an instrumented
module's own tests still credits that module, as the storage contracts always have.

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
