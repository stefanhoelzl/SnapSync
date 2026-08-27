## Why

`settings.gradle.kts` declares **18** modules. `module-architecture`'s module-set requirement
enumerates **11** and exempts `:test:*`, which accounts for five more. Two are accounted for by
nothing at all: **`:app:ios:forge`** and **`:tools:diagrams`**.

That is not a typo in a list — the requirement enumerates *"exactly these **production** modules, each
existing because it **withholds** a third-party or platform dependency by compile error."* It is a
list of modules justified by **one** argument, and `:app:ios:forge` is not justified by that
argument. Ask what forge withholds from other modules: nothing. It exists so that *the forge binary*
does not link `:app:ios` and therefore has no live graph — which is **containment**, and which the
same spec already governs three requirements later:

> Where the thing to be contained is reached **through** a shell's own switch … containment SHALL be
> achieved by giving it its **own binary target** over its own module, rather than by keeping an
> inert branch.

So the spec governs forge precisely; the enumeration cannot see it, because the enumeration knows
only one reason a module may exist. Appending two names would assert something false.

**And the gate that should have caught this is tethered to the wrong thing.** `ModuleSetTest`
compares `settings.gradle.kts` against a hardcoded table **inside the test file**, then reports:

> a NEW module … **is a spec delta to module-architecture**

It has demanded that twice and received a table edit both times. `3d3947a9` (forge) touched
`settings.gradle.kts` and `ModuleSetTest.kt` and shipped **zero** OpenSpec deltas; `:tools:diagrams`
entered in `f096f287`, the very commit that wrote this spec, and has been unaccounted since birth.
Only two archived changes have ever touched the module-set requirement, neither of them these.

```
   settings.gradle.kts  ←── GUARDED, loud ──→  ModuleSetTest table
                                                      ╎
                                                      ╎  UNGUARDED, silent
                                                      ╎
                                             module-architecture spec
```

The guard watches the link that cannot rot on its own — you cannot add a module by accident; it
takes a directory, a build file and an `include` line — and says nothing about the link that
demonstrably does.

## What Changes

- **`module-architecture` gains the missing category.** The module set is stated as three groups,
  each named by the law that justifies it: modules that exist to **withhold** (the 11), modules that
  exist to be **contained** at compile time (`:app:ios:forge`, `:test:rig`), and never-shipped
  **support** modules that are exempt (`:test:world`, `:test:integration`, `:test:architecture`,
  `:test:harness-driver`, `:tools:diagrams`). 11 + 2 + 5 = the 18 the build declares.
- **`:test:rig` moves out of the blanket `:test:*` exemption** into the containment group, where it
  belongs. It and forge are the same species — build-time-only, compile-time contained, absent from
  a production build — and the containment law describes exactly their two shapes. They were treated
  oppositely only because of their name prefixes.
- **`ModuleSetTest` is re-tethered.** The hardcoded table is deleted; the gate derives the expected
  set from `module-architecture`'s own text at test runtime and compares it to the build's include
  set. Adding a module then genuinely fails until the spec accounts for it — which is what the gate's
  message has claimed all along.
- **The gate's message is corrected.** It currently tells you every new module must withhold and
  needs a `module-architecture` delta; that is wrong for a support module the spec exempts.
- **`architecture-guards` distinguishes a scope list from a pin.** Its permitted-lists clause governs
  how a gate derives *what it scans*; it never names the expected-value tables that pin guards
  (`RuntimeIdentityTest`, `PlatformVocabularyPinTest`, `KotlinShellGuardTest`) are built from, so
  those sit in a wording blind spot rather than being sanctioned.
- **CLAUDE.md's module list** gains `:app:ios:forge` and `:tools:diagrams`, which it also omits.

No behaviour changes. No production code changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `module-architecture`: the module-set requirement stops enumerating one justification and states
  all three, accounting for every module the build declares.
- `architecture-guards`: the module-set gate derives its expected set from the spec instead of a
  local table; the permitted-lists clause distinguishes a derived **scope** from a pinned
  **expected value**.

## Impact

- `openspec/specs/module-architecture/spec.md`, `openspec/specs/architecture-guards/spec.md`.
- `test/architecture/…/ModuleSetTest.kt` — the table is deleted and replaced by a parse of the spec,
  with a non-vacuity twin per group (an empty group must fail, not pass).
- `CLAUDE.md` — two lines in the module block.
- **Nothing else.** No production module changes, and the build's actual module set is unchanged:
  this change makes the spec describe what is already there.

**Deliberately NOT in scope: `LawsDigestTest`.** It is the other guard that tethers a self-created
duplicate, so it looks like the same defect. It is not the same call. Its duplicate — CLAUDE.md's
laws digest — exists to put the laws in front of an agent that would otherwise never see them, and
that justification is stronger than it looked: an agent reacts to a build failure but may miss an
instruction lost in a long context, which is an argument *for* the in-context copy, not against it.
`ModuleSetTest`'s table has no such reader; it exists only so the test can compare against it. One
duplicate serves a purpose, the other serves the guard.
