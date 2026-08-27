## Context

Three artifacts describe the module set, and only two of them are tied together:

```
   settings.gradle.kts   18 modules   ═══ guarded ═══  ModuleSetTest table   18 entries
                                                              ╎
                                                              ╎  nothing
                                                              ╎
                    module-architecture spec   11 enumerated + `:test:*` exempt   = 16 accounted
```

`:app:ios:forge` and `:tools:diagrams` fall through the gap. Measured, not assumed:

- `3d3947a9 internal(forge): give forge its own binary…` changed `settings.gradle.kts` (+3) and
  `ModuleSetTest.kt` (2 lines) and shipped **no** OpenSpec delta.
- `:tools:diagrams` entered in `f096f287`, the commit that established the target architecture and
  wrote this spec. Unaccounted from the first day.
- `grep` over the archive: exactly two changes ever touched the module-set requirement
  (`2026-07-17-establish-target-architecture`, `2026-07-19-uniform-adapter-tree`). Neither is these.

The guard is not weak; it is aimed at the wrong link. Adding a module cannot happen by accident — it
takes a directory, a `build.gradle.kts` and an `include` line, and it is the most visible change a
PR can contain. What *can* rot silently is whether the spec still describes the set, and that is
precisely what nothing watches.

## Goals / Non-Goals

**Goals:**

- Every module the build declares is accounted for by the spec, under a stated justification.
- The module-set gate becomes true: adding a module fails until `module-architecture` accounts for
  it.
- One home for the module set. Delete the copy rather than guarding it harder.

**Non-Goals:**

- Changing the build's module set. Nothing is added, removed, split, or renamed. This change makes
  the spec describe what is already there.
- Any production code, behaviour, or CI change.
- Touching `LawsDigestTest` (D8).
- Re-litigating `:adapter:generic:fake`'s place in the withholding group. It never ships, which makes
  it look like a support module, but the spec deliberately enumerates it and discusses it by name
  ("the inverse of `:adapter:generic:fake`, which never links into a shipped framework at all"). It
  stays where it is; moving it is a separate argument.

## Decisions

### D1 — Three groups, each named by the law that justifies it

The requirement stops being a list of modules-that-withhold and becomes an accounting of every
module, grouped by *why it may exist*:

| group | justified by | members |
|---|---|---|
| **withholding** | "The module set withholds" — withholds a third-party/platform dep by compile error | the existing 11 |
| **containment** | "A build-time-only module is contained by compilation" | `:app:ios:forge`, `:test:rig` |
| **support** | never linked into any shipped-format binary; exempt from production-module laws | `:test:world`, `:test:integration`, `:test:architecture`, `:test:harness-driver`, `:tools:diagrams` |

11 + 2 + 5 = 18, which is the include set exactly.

This is not a new law. Both justifications already exist in the spec as separate requirements; only
the enumeration was single-minded. Appending forge to the withholding group would have asserted that
it withholds a platform dependency, which is false.

### D2 — `:test:rig` moves from the prefix exemption to the containment group

Today `:test:rig` is exempt because it is called `:test:*`, and `:app:ios:forge` is unaccounted
because it is called `:app:*`. They are the same species: build-time-only, linked only under a build
property, absent from a production build. The containment law even describes their two shapes and
this codebase has exactly one instance of each:

```
  "…it SHALL contribute that source itself"        →  :test:rig  (test/rig/src/hook, -Psnapsync.rig)
  "…its own binary target over its own module"     →  :app:ios:forge  (-Psnapsync.forge)
```

Grouping by the governing law instead of by name prefix is what makes that visible. The alternative —
renaming `:app:ios:forge` into `:test:*` so the existing exemption swallows it — was rejected: it
produces an app binary with its own Xcode target, which is what `:app:*` means here, and a rename
would hide the distinction rather than state it.

### D3 — The gate derives its expected set from the spec; the table is deleted

`ModuleSetTest` parses `openspec/specs/module-architecture/spec.md` at test runtime and compares the
union of the three groups to `settings.gradle.kts`'s include set. This is the `LawsDigestTest` shape
— two artifacts that both exist independently, derived on both sides, no third copy — applied to the
set that currently has one.

Consequences, all intended:

- Adding a module fails until the spec accounts for it **and says which group it joins**.
- The gate stops being one of the two hand-maintained lists `architecture-guards` permits, which
  makes that clause's budget go from two to one.
- Adding a `:test:*` module now requires a spec edit, where the wildcard previously absorbed it
  silently. That is the point: the gate already claimed to require this.

### D4 — Parse by group label, fail loudly on any reword

The requirement names each group on its own labelled line, with members in backticks. The guard
locates `### Requirement: The module set withholds; packages organize`, then extracts backticked
tokens matching `^:[a-z][a-z0-9:-]*$` from each group's line.

The group labels become three magic strings the guard depends on. That is a real cost, and it is
much smaller than the one it replaces: labels are structural and change approximately never, whereas
the 18 names change every time a module is added. And the failure mode is loud, not silent — see D5.

The alternative, a fenced block holding the set, is more robust to prose edits but makes the
requirement carry a machine format. Rejected on readability: the spec is read by people far more
often than by the guard, and a tolerant backtick scan is already this repo's idiom.

Wildcards are removed from the enumeration. `:test:*` cannot be compared against an include set, and
enumerating the five support modules is what makes the comparison total.

### D5 — A non-vacuity twin per group, not just overall

The standard failure of this kind of gate, and the one the industry literature names first, is a
scope that silently empties: rename the thing it scans and it passes forever because it checks
nothing. `architecture-guards` already requires a non-vacuity twin; here it needs to be **per
group**, because a reworded label would empty one group while the other two keep the test looking
alive.

The twin asserts each group parses non-empty and the union clears a loose floor, in the manner of
`LawsDigestTest`'s `>= 5`.

### D6 — The message tells you which group to join

Today: *"A NEW module must withhold a third-party/platform dependency by compile error … and is a
spec delta to module-architecture."* That is wrong for a support module, which the spec exempts from
exactly that demand.

The new message names the three groups and what each requires, so the failure is a decision prompt
rather than a single instruction that is right one time in three.

### D7 — `architecture-guards` distinguishes a derived scope from a pinned value

Its permitted-lists clause says gates SHALL derive scope *"never from a hand-maintained inclusion
list"*, permitting exactly two. A scan of the suite finds literal tables in 19 of 43 guards — which
looks like a mass violation and is not. Most are **pins**: `RuntimeIdentityTest`'s OS-held literals,
`PlatformVocabularyPinTest`'s Apple enum values, `KotlinShellGuardTest`'s suppression inventory. A
pin's expected-value table *is* the guard; it is not a scope.

The clause never names that category, so pins live in a wording blind spot. This change states the
distinction: **scope** (what a gate scans) SHALL be derived; an **expected value** MAY be pinned,
and a pin SHALL carry the reason its value is fixed. The permitted-scope-list budget then drops from
two to one, because D3 removes the module list from it.

### D8 — `LawsDigestTest` is deliberately not touched

It is the other guard tethering a self-created duplicate, and it looks like the same defect. It is
not the same call.

Its duplicate has a **reader**: CLAUDE.md's digest exists so an agent writing code sees the laws at
all. That justification survived examination — an agent reacts to a build failure but can miss an
instruction lost in a long context, which argues *for* keeping an in-context copy tethered, not
against it. `ModuleSetTest`'s table has no reader; nothing consults it but the assertion three lines
below, so it exists only so the test can compare against something.

One duplicate serves a purpose and is guarded. The other serves the guard, and is deleted.

### D9 — `the core declares zero project dependencies` is KEPT, and the proposal was wrong about it

`tasks.md` 2.5 flagged `ModuleSetTest`'s second test as a candidate for the "guards what the compiler
already refuses" pattern, on the grounds that `:domain`'s platform-freedom is a compile error.
Examined during implementation, that reasoning is **backwards**.

The compile error — *"any file under `:domain` referencing a platform API fails with an unresolvable
symbol"* — holds only because of a **precondition the compiler does not check**: that `:domain`
declares no project dependencies. Adding `project(":adapter:ios:ext-safe")` to
`domain/build.gradle.kts` compiles perfectly happily and silently hands the core a platform. Nothing
would fail. This assertion is the only thing standing between that edit and a green build.

So it is not duplicate enforcement; it guards the premise that makes the compiler's enforcement
possible. Kept, with that reasoning recorded at the assertion itself so the next reader does not
re-run the same wrong argument.

### D10 — A group's prose may not name another module by its backticked path

Found by implementing the parser. The Contained group read *"contributes its own call site into
`:app:ios`"* — a prose reference the parser cannot distinguish from a membership claim, which would
have enrolled `:app:ios` in two groups.

Fixed both ways: the prose now names the shell by description, and the requirement states the rule
("within a group, a backticked `:`-prefixed token **is** a membership claim") so a future editor
knows the constraint exists. The `groups are disjoint` test is the mechanical backstop — the
duplicate would have failed it rather than silently double-counting.

This is the format straitjacket D4 accepted in the abstract, now paid for concretely: it costs one
sentence of discipline per group, and it is loud when violated.

## Risks / Trade-offs

- **Parsing prose is brittle.** → Per-group non-vacuity twins (D5) turn every reword into a red
  build naming the group that went empty. Brittle-and-loud is the intended trade; brittle-and-silent
  is the one to avoid.
- **Three magic label strings replace an 18-name table.** → Accepted: smaller, structural, and
  changes at a different rate than the content it locates.
- **Spec and guard must land in the same commit**, or the build is red in between. → Single change,
  and the tasks are ordered spec-first.
- **A future module that fits no group.** → That is the gate working. The module either earns a
  justification or is a package with a text gate, which is the original law.
- **`:test:*` module additions now cost a spec edit** where the wildcard absorbed them. → Intended,
  and rare; the gate has always claimed to require it.

## Migration Plan

Documentation and one test file. No rollout, no rollback, no runtime path. Spec first, then the
guard, in one commit — the guard reads the spec, so the order matters within the change even though
both land together.

## Open Questions

- **`:adapter:generic:fake` sits oddly** in the withholding group: it never links into a shipped
  framework, which is the support group's defining property, yet it holds port contracts the
  composition smoke test and every integration test stand on. Left as-is deliberately (Non-Goals). If
  a fourth group is ever wanted — *ships nothing but holds a production contract* — this is its first
  member.
- **Whether `:tools:*` should be a prefix or an enumeration** if more build tooling appears. Enumerated
  here because there is one; a second tool is the moment to decide, and the gate will ask.
