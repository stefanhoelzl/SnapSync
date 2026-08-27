## 1. The spec, first — the guard reads it

- [x] 1.1 Apply the `module-architecture` delta: the module-set requirement becomes three groups
      (withholding / contained / support), enumerated exhaustively, no wildcards, with
      `:app:ios:forge` and `:test:rig` in the contained group and `:tools:diagrams` in support.
- [x] 1.2 Apply the `architecture-guards` delta: scope-vs-pin distinction in "Gates fail closed on
      novelty"; the module-set bullet in "The migration's laws are permanent gates" now requires
      derivation from the spec and a per-group non-vacuity twin.
- [x] 1.3 Confirm the three groups union to exactly the 18 `include(...)` lines in
      `settings.gradle.kts` — by hand, before any parser exists, so a parser bug cannot mask a
      counting error.

## 2. Re-tether the gate

- [x] 2.1 Rewrite `ModuleSetTest` to parse `openspec/specs/module-architecture/spec.md` at test
      runtime: locate the module-set requirement, extract backticked `^:[a-z][a-z0-9:-]*$` tokens
      per group label, union them, compare to the `include("…")` set.
- [x] 2.2 Delete the hardcoded `targetModules` table. This is the point of the change — if the table
      survives in any form, the gate is still tethered to a copy of itself.
- [x] 2.3 Add a non-vacuity twin **per group**, plus a loose floor on the union (in the manner of
      `LawsDigestTest`'s `>= 5`). A reworded group label must fail, not silently shrink the expected
      set.
- [x] 2.4 Replace the failure message: name the three groups and what each requires, so it is a
      decision prompt rather than "must withhold a dependency", which is right for one group in
      three.
- [x] 2.5 Decide the fate of the file's **second** test (`the core declares zero project
      dependencies`). It re-asserts in text what the build already enforces by unresolvable symbol —
      a candidate for the "guards what the compiler already refuses" pattern. Keep it (it makes the
      law legible at the gate) or drop it (it is duplicate enforcement); record which and why. Do
      not decide it silently.

## 3. CLAUDE.md

- [x] 3.1 Add `:app:ios:forge` and `:tools:diagrams` to the Modules block, which omits both while
      `ModuleSetTest` and `settings.gradle.kts` name them.

## 4. Verification — prove the gate fires, do not assume it

- [x] 4.1 **Negative test, unaccounted module**: add a throwaway `include(":test:scratch")` to
      `settings.gradle.kts`, run `:test:architecture:test`, confirm it fails naming the module and
      the three groups, then revert. A gate that has never been seen red is a gate nobody has
      tested.
- [x] 4.2 **Negative test, per-group vacuity**: reword one group label in the spec, confirm that
      group's twin fails rather than the union quietly shrinking, then revert.
- [x] 4.3 **Negative test, spec drift**: remove one module from the spec's enumeration, confirm the
      gate fails, then revert. This is the link that was previously unguarded and is the whole point.
- [x] 4.4 `./gradlew build` green, and `npx --yes @fission-ai/openspec@1.5.0 validate --specs
      --strict` green.
- [x] 4.5 Confirm `:test:architecture:test` actually re-runs when the spec changes — the guard's
      test task must declare `openspec/specs/module-architecture/spec.md` as an input, or it will
      serve a cached green after a spec edit. `CLAUDE.md` and the `module-architecture` spec are
      already declared inputs for `LawsDigestTest`; verify the new read is covered by the same
      declaration and extend it if not.

## 5. Follow-ups, not to do here

- [x] 5.1 Record the `:adapter:generic:fake` question: it never links into a shipped framework —
      the support group's defining property — yet holds port contracts every integration test stands
      on, and the spec deliberately enumerates it as withholding. If a fourth group is ever wanted
      ("ships nothing but holds a production contract"), this is its first member.
- [x] 5.2 Record that `:tools:*` is enumerated rather than a prefix because there is exactly one
      build tool. A second one is the moment to choose, and the gate will ask.
