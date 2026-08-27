## Context

`StatusIndicator` (`:ui:components`) is a sealed interface with seven payload-free `data object` cases.
Measured across the whole tree, excluding the renderer's own `when`:

| case | construction sites |
|---|---|
| `Loading` | 1 — `CreateEventScreen.kt:157`, the "Creating your event …" hero |
| `Error` | 1 — `AppErrorBanner.kt:51` |
| `Success`, `Waiting`, `Photos`, `InProgress`, `Complete` | **0** |

Zero test references, zero harness references, zero forge presets. `IndicatorIcon`'s `when` carries no
`else`, so the five dead branches are the only thing keeping those cases compiling.

Three things shaped this design, and the first two were found by looking rather than assuming.

1. **The cases were abandoned, not reserved.** `git log -S` on each construction site shows every one
   removed by a redesign — `8840c4f3 feat(ui): redesign event UX`, `c0c8a816 chore(ui): dead-component
   sweep`. The sweep commit is the telling one: a deliberate pass at exactly this problem went past them.
2. **The type's own justification is false.** Its KDoc reads *"the variant is runtime data arriving from
   UI state — [Progress] even carries a payload"*. There is no `Progress` case, so the link is broken,
   and no case carries a payload. The claim describes `AppSyncStatus` next door, whose
   `Syncing(upload, download)`, `NotStarted(startsAt)` and `NeedsAccess(prompt)` really do.
3. **The `design-system` spec had not followed the redesign.** It requires a screen showing an
   in-progress pass to pass `StatusIndicator.InProgress` to `StatusHero`. Nothing does — progress is
   rendered by `AppStatusLine` from `JoinedLayer`.

### What the audit found, and what it did not

Point 3 was reached by auditing the whole capability first rather than editing the line that blocked the
deletion. That audit is worth recording because most of its result is *negative*, and a negative result
is the kind that gets re-derived later if nobody writes it down.

`design-system` is 696 lines, 20 requirements, 86 scenarios, over 44 components. A first pass grepping
component identifiers suggested **34 of 44 were unmentioned**. That was wrong, and the error is
instructive: the spec describes components by **role and contract**, not by Kotlin symbol — "status line"
appears 6 times, "arrow" 29, "error banner" 6, pickers 19 — and its inventory sentence states outright
that it *"grows demand-driven with the screens that need it"*. Grepping identifiers in a document that
avoids identifiers measures the grep, not the spec. `AppStatusLine` is the sharpest example: absent as a
string, and the subject of a requirement of its own.

Checking instead the **17 places the spec does descend to symbol level**:

| verdict | count | notes |
|---|---|---|
| correct | 15 | including every `AppSyncStatus` payload form |
| deliberate counter-example | 1 | `AppButton(role = …)` — named so the spec can forbid it |
| **false** | **1** | the `StatusIndicator.InProgress` scenario |

So the capability is healthy, and its one drift is the one this change was already going to touch.

## Goals / Non-Goals

**Goals:**

- A vocabulary whose cases all have callers.
- A type whose form matches what it holds, and a doc-comment whose justification is true.
- A `design-system` spec with no false symbol-level claim.
- Everything verified by the compiler rather than by review: the `when` has no `else`, so a case that is
  still needed cannot be silently dropped.

**Non-Goals:**

- **Removing the variant axis.** `StatusHero` has one call site today, and it would be easy to argue the
  indicator parameter has stopped earning its place. Two live cases with two distinct call sites is a
  real axis, and the spec's rule that runtime variants are sealed VALUES rather than separate components
  is deliberate.
- **Restoring any of the five.** If an in-progress hero is wanted back, that is a feature change with a
  screen behind it, not a lint-driven revival of a case nobody calls.
- **Any other `design-system` correction.** The audit found none to make.
- **Touching `AppSyncStatus`.** It is correct, live, and already specified.

## Decisions

### D1 — `enum class`, not a two-case sealed interface

Every case is a payload-free `data object`, which is precisely what `enum class` exists for. Keeping the
sealed form would preserve room for a future case that carries data — a real benefit, and the reason to
decline it is that the type already tried this argument and it was false: the KDoc claimed a payload
that no case has.

The repo also already accepts an enum in exactly this position. `:ui:components` declares `api(:domain)`
specifically so `model/`'s `Arrow` enum can appear in `AppStatusLine`'s signature — a runtime semantic
value passed to a design-system component, which is the same shape. Exhaustiveness is unaffected:
`when` over an enum needs no `else` either, so the compiler keeps naming every unhandled case.

If a case that carries data does arrive, converting back is mechanical and the call sites are two.

### D2 — Delete the five rather than deprecate them

Nothing constructs them, nothing tests them, and nothing in the harness drives them. `@Deprecated` is for
a symbol with callers who need time; these have none. The history is the argument: they were removed one
redesign at a time and left behind each time, so leaving them again reproduces the state this change
exists to end.

What goes with them is not incidental — `LedDot` and its `LedYellow` colour existed only to render
`InProgress` and `Complete`, and three of the four icon imports served only dead branches. A deletion
that leaves those behind would trade five dead cases for four dead private declarations.

### D3 — The stale scenario is REMOVED, not rewritten

*"Progress is expressed as meaning, not styling"* asserts a real principle. The question is whether it
still needs its own scenario once its subject is gone, and it does not. The capability's **"App
status-line component"** requirement carries *"Pulsing arrow drives the ongoing label"* — the status line
is given `Syncing(upload = Pulsing, download = Hidden)` and renders it *"with no counts and no exposed
appearance parameters"* — which states the principle about the component that actually does the work,
with symbols verified to exist. That the principle now lives under the status line's requirement rather
than under "Semantic-only components" is the correct outcome, not a consolation: a rule about how
progress is expressed belongs with the component that expresses it.

Rewriting the stale scenario to point at `AppStatusLine` would leave two scenarios saying one thing, the
newer one weaker. Removing it loses no contract, and the delta says so explicitly rather than letting a
reader wonder whether a rule was quietly dropped.

Considered and rejected: **making it true**, by giving some screen an in-progress hero. That inverts the
decision an earlier redesign already made when it moved progress to the status line, and it would be a
product change driven by a spec sentence rather than by anyone wanting the surface.

### D4 — The inventory keeps naming the cases

The inventory could have stopped enumerating cases altogether and said "a sealed variant axis", which
would make it immune to this drift. It keeps the enumeration, now of two, because the enumeration is what
made the drift **findable**: the reason this was caught is that a spec sentence named seven symbols and
five of them turned out to have no callers. A contract that names fewer things goes stale more quietly.

## Risks / Trade-offs

- **[A deleted case is wanted back]** → Restoring one is a small, obvious edit, and the git history of
  this change records what each case rendered. Weighed against five cases that survived a previous
  dead-component sweep by being invisible.
- **[The enum forecloses a payload-carrying case]** → It does, and converting back is mechanical with
  two call sites. `AppSyncStatus` is the precedent for where a payload-carrying variant axis belongs, and
  it is a different type for a different surface.
- **[`StatusHero`'s single call site makes the whole component questionable]** → Real, and deliberately
  out of scope. Recorded as an open question rather than resolved by a change whose subject is the
  vocabulary.
- **[The audit's negative result is trusted too far]** → It covers symbol-level claims, which are
  mechanically checkable. It does **not** establish that every role-level contract in those 86 scenarios
  is honoured — that would need reading each against the components, which this change did not do and
  does not claim.

## Migration Plan

One PR, and the order matters only in that the compiler does the checking:

1. `StatusIndicator` → `enum class` with `Loading` and `Error`; KDoc corrected.
2. `IndicatorIcon`'s `when` down to two branches; `LedDot`, `LedYellow` and the three now-unused icon
   imports removed.
3. `./gradlew build` — the `when` has no `else`, so any surviving need for a deleted case is a compile
   error, not a runtime surprise.
4. Spec delta applied: inventory names two cases, the stale scenario removed.

Rollback is a revert; nothing persists and no other module is touched.

## Open Questions

- **`StatusHero` has one call site.** Two live cases justify the variant axis today. If a third never
  arrives, the question is whether the hero should take an indicator at all — worth revisiting then
  rather than defending indefinitely.
- **Vocabulary outliving its callers is a repeat pattern in this module.** Five cases survived a
  deliberate dead-component sweep. Whether anything mechanical should catch that — a guard, or a periodic
  audit — is a larger question than this change, and `:ui:*` is not the only place it could apply.
