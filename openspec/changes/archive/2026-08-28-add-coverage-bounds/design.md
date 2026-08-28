## Context

Nothing measures coverage in this repository. The gates that do exist measure other things:
`:test:architecture` proves structural invariants, `detektAppShell` proves the shells hold no
decisions, and eight `detekt*Tier` tasks hold every Kotlin source under a complexity ceiling
(capability `complexity-budgets`). All of them pass for a class that has no test.

The tree was measured with Kover before any of this was designed, and the measurement drove every
decision below. Four findings shaped it. (These are the *exploratory* numbers, taken before the scope
below was settled, so they still include `:test:world`'s tests; the seeded bounds differ and D4 shows
both columns.)

| finding | measurement |
|---|---|
| Four classes in `:adapter:generic:app` have no test of their own | `HttpAttestClient` 500 instr / 0%, plus `HttpEnrollment`, `HttpDeviceFilesSource`, `SystemTime` |
| Two of five `flow/` trigger flows are executed by **no test on any tier** | `Background` 20 instr / 0%, `DownloadBackstop` 96 instr / 0%, in every scope measured |
| `:domain`'s package distribution is sharply **bimodal** | `compose/` 35%, `flow/` 57%, then a gap, then eleven packages at 91–100% |
| A module aggregate sits in the gap and describes nothing | a `:domain` bound of 84 is above no package and below no package |

The last one is why this design is not simply "one number per module".

Constraints that bound the solution space, all verified rather than assumed:

- **Kover measures JVM targets only.** `:adapter:ios:ext-safe` and `:adapter:ios:app-only` declare no
  JVM target, so 5,833 production LOC and 182 tests are outside any possible bound.
- **`KoverVerifyRule` carries no filters.** It exposes only `groupBy`, `disabled` and `bound(...)`;
  filters live on the report *set*, and there is one total set per project. Verified by decompiling
  `kover-gradle-plugin-0.9.9`.
- **`minValue` is `Property<Int>`.** Bounds are whole percentages; 91.03% cannot be expressed.
- **`instrumentation { disabledForAll }`** is per Gradle project, so the unit/integration line can
  only be drawn at module boundaries. (Note the property is `disabledForAll`, not `disableForAll` as
  Kover's prose sometimes renders it.)

## Goals / Non-Goals

**Goals:**

- Make an untested class fail `./gradlew build`, at the granularity where that is actually detectable.
- Land green with no code changes, by seeding every bound at what the tree measures.
- Produce a register whose numbers point at the next thing to fix, and whose destination is full
  coverage.
- State what the instrument cannot see, in the specification, rather than leaving it to be found.

**Non-Goals:**

- Paying the debt the first measurement exposes. The four untested adapter classes and the three
  untested flows are named, not fixed (D8).
- Any production code change. This change modifies build files and nothing else.
- Coverage of Kotlin/Native source. No tool available here can do it; later changes cover those
  modules by other means.
- Reopening `module-architecture`'s "One shared composition". This design accepts it and states what
  follows for measurement (D5).

## Decisions

### D1 — Kover, on its default engine, named and frozen

Kover is the only coverage tool that understands a Kotlin Multiplatform build's JVM targets without
hand-wiring JaCoCo into each target's test task.

Both engines were measured on `:domain`:

| | denominator | INSTRUCTION | BRANCH |
|---|---|---|---|
| IntelliJ (Kover default) | 25,869 | 56.30% | 65.36% |
| JaCoCo (`useJacoco()`) | 23,088 | 55.43% | 66.77% |

JaCoCo filters more — coroutine state machines mostly, hence `feature/download` −1,083 instructions
and `feature/upload` −821, the two most suspend-heavy packages. But **the filtered code was largely
already covered**, so the percentage moves less than one point. The swap buys nothing measurable and
costs a non-default configuration, so the default engine is used.

What matters is that the choice is *load-bearing*: the two engines disagree by up to 26% on a single
package's denominator, so every seeded number is engine-specific. The spec therefore requires the
engine be named where the bounds live, and re-seeding in any change that switches it.

*Alternative rejected:* JaCoCo, for the tidier denominator. Rejected because the measurement shows the
tidiness does not reach the number anyone reads.

### D2 — Two rules per module: an aggregate, and a package floor

Each bounded module carries a module-total bound and a `groupBy = PACKAGE` bound. The second means
*"no package in this module is worse than this"*, and it is the register's pointer:

```
:domain today          57   (flow/ is worst — 3 of 5 trigger flows untested)
fix flow/          →   91   (ports/ becomes worst)
fix ports/         →   92   …
                       ⋮
                      100   every package fully covered
```

The two are complementary and neither is redundant. The aggregate catches a broad slide that leaves
every package above the floor; the floor catches one package rotting while the aggregate stays
healthy. That second case is not hypothetical — it is exactly the shape of `HttpAttestClient` sitting
at 0% inside a module reading 68%.

*Alternative rejected:* an individual bound per package. It is the sharpest instrument and **Kover
0.9 cannot express it** — rules carry no filters, so "package A ≥ 91, package B ≥ 57" within one
module is not writable. It would require a custom task parsing Kover's XML against a recorded
baseline. That is a real option and it is deliberately deferred: it buys precision the floor already
approximates, at the cost of build code with no precedent in this repo.

*Alternative rejected:* the aggregate alone. Measured, `:domain`'s aggregate of 84 (with `compose/`
included) described no package in the module — eleven were above 91, two below 58.

### D3 — `INSTRUCTION` and `BRANCH`, never `LINE`

`INSTRUCTION` is strictly finer than `LINE` on the same axis, and Kotlin puts several instructions
behind one line — an elvis, a safe-call chain, a defaulted argument — so bounding both would be two
ratchets moving together with no independent signal. `BRANCH` is the orthogonal axis and is bounded
per module only; per package its denominators collapse to between 6 and 34, where one uncovered arm
moves `feature/creation` by 17 points.

### D4 — Unit tests only, and three roles rather than two

A module is **instrumented** (its tests produce coverage), **bounded** (its classes are measured), or
neither. The split matters because two modules need one role and not the other:

- `:adapter:generic:fake` — instrumented, not bounded. Its `commonTest` holds `:domain`'s feature
  tests, placed there only because a test source set cannot be depended on across modules
  (`testing-architecture`, "Fake-driven feature tests live in the fake module"). Its fakes are test
  equipment; bounding them ratchets the harness instead of the product.
- `:test:world` — neither. Its `commonTest` is the tier `testing-architecture` calls "The world hosts
  feature tests over the real stack", which is integration by any reading.

Excluding `:test:world` was measured before it was decided, and the cost is instructive:

| | with `:test:world` | unit only |
|---|---|---|
| `:adapter:generic:app` | 76% | **68%** |
| ↳ `membership` package | 98.74% | **34.85%** |
| ↳ `join` package | 74.80% | **56.27%** |
| `:domain` `compose/` | 35.27% | **1.13%** |

The adapter drop was initially read as unit tests being hosted in the wrong module, which would have
made this change wait on relocating them. Investigation showed the opposite: `HttpLeaveNotifier` and
`HttpEventDirectory` already have properly-placed unit tests and keep their coverage, while their
package-mates `HttpDeviceFilesSource` and `HttpEnrollment` have **no test at all** and were being
covered incidentally by the world harness. So the exclusion is not impurity to work around — it is
the gate reporting real debt on its first run.

*Alternative rejected:* excluding only `:test:integration`, the narrower reading. It leaves
`:test:world`'s real-stack feature tests standing in for unit tests, which is the substitution this
capability exists to prevent.

*Alternative rejected:* relocating the mini-edge-backed tests first, as a precursor change. Rejected
on evidence — there is nothing to relocate.

### D5 — `compose/` is excluded; `flow/` is not

They measure similarly low and are opposite cases, and conflating them would have been easy.

- **`flow/` at 57%** is ordinary debt. `SilentPush` and `Foreground` have unit tests and read 100%
  and 96.6%; `Provision`, `DownloadBackstop` and `Background` have none. Flows accept every port touch
  as an injected `suspend` lambda (`module-architecture`, "A trigger flow never outlives its own
  run"), so they are among the easiest things in the tree to unit-test. It converges by writing three
  tests. It stays in the register, and it is the number the floor currently names.
- **`compose/` at 1.13%** cannot converge. A composition root is reachable only by composing it, and
  any test that composes the whole graph is not a unit test. `module-architecture`'s "One shared
  composition" says the wiring graph shall not be unit-tested, and that law is the written form of
  the same fact rather than an independent obstacle. A register entry asserts a debt that can be
  paid; this one could not be.

Excluded by **zone path**, not by module, so the remaining twelve packages of `:domain` stay bounded.
The exclusion changes `:domain`'s headline from 77.56% to 91.35% and its floor from 1 to 57 — which
is the difference between a gate that names a law and one that names three unwritten tests.

*Alternative rejected:* a permanent register entry for `compose/` carrying a forcing proof. It would
sit at 1% forever and train readers to ignore the floor.

*Alternative rejected:* letting integration tests count for `compose/` only. Kover attributes coverage
per project, not per package, so "these tests count for these classes" is not expressible.

### D5a — Credit follows placement rules, not incidental execution

A module's bound counts the unit tests written for its code, wherever a placement rule forced them to
live. Two crediting edges exist, and both trace to a rule rather than to convenience:

- `:domain` ← `:adapter:generic:fake`. `:domain` cannot reach those fakes itself — the fake module
  depends on `:domain`, so a test edge back is a project cycle — which is exactly why
  `testing-architecture` places those feature tests there under "Fake-driven feature tests live in
  the fake module". Without the edge `:domain` reads 56% instead of 91% and is punished for obeying
  that rule.
- `:ui:components` ← `:ui:screens`, and `:ui:presentation` ← `:ui:screens`. Rendering a real screen
  is what exercises the components and the container host. Without the first edge `:ui:components`
  reads 48% instead of 95%.

Coverage that a module's classes receive **incidentally**, from tests written for something else,
deliberately does not count. Measured, a root-level aggregate in which every instrumented module's
tests credited every other lifted `:domain`'s `model/` from 86.42% to 93.60%, because UI tests
construct `model/` types on their way to rendering. That is the same substitution excluding
`:test:world` exists to prevent, one level down: a number inflated by tests nobody wrote for the code
being measured. Each module's report is therefore filtered back to its own classes, and only the two
rule-driven edges above contribute coverage.

*Alternative rejected:* crediting every module that executes the code. It is simpler to configure and
produces a higher, less meaningful number.

### D6 — Seed at `floor(measured)`, and accept the slack lottery

Because bounds are whole percentages and are seeded at the floor of the measurement, each scope's real
strictness is `frac(measured) × total ÷ 100` — an accident of where its percentage lands on the
integer grid. Measured across `:domain`'s packages that ranges from **0** uncovered instructions
(`ports` at 91.03% → 91) to **37** (`feature/upload` at 97.60% → 97).

This is accepted rather than engineered around, for two reasons. Kover offers no finer expression, so
removing the lottery means leaving Kover's verification entirely (see D2's deferred alternative). And
the alternative of a uniform margin — seeding at `floor(measured) − 1` — trades an uneven small slack
for an even larger one, in a design whose destination is zero slack.

A consequence is recorded rather than discovered: a scope measured at exactly 100% is bounded at 100
and can then accept no uncovered instruction at all. That is the finish line behaving correctly, not
a trap — but the first person to hit it should know it was intended.

*Alternative rejected:* an exact-match ratchet against a recorded baseline. Its failure mode is
inverted — improving the code fails the build until someone re-records — which teaches people to
route around the gate. `complexity-budgets` rejected the same mechanism for the same reason, and the
objection is stronger here: coverage moves on every commit that adds a test.

### D7 — The contract lives with the numbers

The "may only rise" contract is stated in prose in the file carrying the bounds, not in a shared
document referenced from it, so a reader editing a number encounters the rule without opening
anything else. This is `complexity-budgets`' arrangement ("A ceiling may only fall") and is adopted
deliberately, including its honesty: it is a ratchet carried by writing and review, and nothing
mechanically prevents a bound being lowered in the change that would have violated it.

### D8 — The debt the first measurement exposes is recorded, not paid

This change enables the gate at current levels. It does not write the missing tests.

Keeping them separate matters mechanically as well as for scope: every test written moves the numbers
this change is seeding, so paying debt inside it means re-measuring the seeds repeatedly and reviewing
a diff where the gate's arrival and its first payment are indistinguishable.

Two items are named in the proposal so the seeded numbers are not mistaken for health:
`:adapter:generic:app`'s package floor seeds at **0** — vacuous until `HttpAttestClient` has a test,
and that is the first ratchet step — and `:domain`'s floor of 57 names `flow/`.

## Risks / Trade-offs

- **False confidence from a green gate.** It says nothing about `:adapter:ios:ext-safe` or
  `:adapter:ios:app-only` — 182 tests, 5,833 LOC, and every platform seam in the product. →
  Mitigated by a specification requirement that states the blindness and by correcting any
  description of the number as "the repository's coverage"; covered properly only by later changes
  using a different instrument.
- **`:adapter:generic:app`'s floor of 0 gates nothing** for that module until `HttpAttestClient` is
  tested. → Accepted deliberately, named in the proposal and in D8 as the first ratchet step, rather
  than seeded at a number the module does not meet.
- **The register is never discharged if nobody raises bounds.** Seeding at the floor keeps slack small
  today, but it grows every time coverage improves without a re-seed. → No mechanical mitigation is
  adopted (D6). A non-gating advisory that reports raisable bounds was considered and is left as an
  open question.
- **Bounds are engine- and denominator-specific**, so a Kover version bump could move numbers with no
  code change. → The version is pinned in `libs.versions.toml` like every other dependency, and the
  spec requires re-seeding in the change that moves it.
- **A flaky test changes coverage**, so a scope near its bound could fail intermittently for reasons
  unrelated to the change under review. → Seeds are floors of measured values, giving 0–37
  instructions of margin; if this bites, it is evidence for the recorded-baseline alternative in D2.

## Open Questions

- **How much of the Compose `BRANCH` gap is structurally unreachable?** `:ui:components` misses 295 of
  836 branches and `:ui:screens` 214 of 601, concentrated on `@Composable` declaration lines
  (`$changed` masks, default-argument masks, restart-group skips). Some are reachable by calling with
  and without defaults; others need a recomposition test; `isTraceInProgress()` guards never fire.
  Until classified, full `BRANCH` coverage is not claimed reachable for those modules. Classifying it
  by reading the generated bytecode is cheap and deliberately deferred.
- **Should a non-gating advisory report raisable bounds?** It would counter the slow leak in D6
  without the inverted failure mode of an exact ratchet. Not adopted here; no precedent in this repo.
- **Does `compose/` get a construction smoke test?** One test that builds `AppCore` over fakes and
  touches every `by lazy` node would assert the graph *constructs* without unit-testing it. Twenty-four
  lines of `SnapSyncApp.kt` are currently executed by nothing at all, integration tests included,
  because an unpulled lazy node is never constructed. That belongs to `module-architecture`, not here.
