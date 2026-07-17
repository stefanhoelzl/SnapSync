# Migration run — harness design (settled by interview, 2026-07-17)

Ad-hoc agent orchestration of PLAN.md's structural steps. **This doc lets a cold session resume
the run**; like PLAN.md, it dies with this module. Resume rule: `git log` + the PLAN.md status
column say where the run stands; the next unticked step is next.

## Model

- **Scope: steps 1–10 only**, on branch `arch`, commits only — **nothing ships**. Behavior steps
  (11a/11b/12) and 13a/13b are a second campaign after the operator's end review + ship.
- The plan's freeze / soak / TestFlight-per-merge protocols are **void on this branch** (they
  exist because every merge to `main` ships). Replacements: main merged in at pauses, device
  sessions at pauses, operator end review before the one real ship.
- CI never sees the branch mid-run, so every gate runs locally via gradle.

## Per-step loop

1. **Implementer subagent** (inherits session model): full OpenSpec ceremony
   (`npx --yes @fission-ai/openspec@1.5.0`, propose → apply → archive), the step's moves per its
   PLAN.md section, iterated to green: `./gradlew build`, `compileIosMainKotlinMetadata`,
   `architectureDiagrams` (regenerated output stays in tree), the step's ride-along guard flips,
   CLAUDE.md reference updates. **Never commits.**
2. **Law reviewer subagent** (fresh context): audits the dirty diff against the laws digest +
   `module-architecture` spec + the step's PLAN section. **Crucial steps 4, 5, 7, 8** get a
   second, independent behavior-preservation reviewer (semantic drift in moved code). Max
   **2 bounces**, then halt.
3. **Orchestrator** commits with the plan's commit discipline (e.g. 3a's move-commit +
   import-fix commit) and ticks the PLAN.md row in the same commit set. Step N+1 never starts
   unless N is green and committed.

**Beacon policy (soft check):** measured Δ vs the row's estimate; divergence → note in the
PLAN.md row (the plan's own rule). Any law-count **increase** → halt. Beacon runs before/after
each step; the report is `build/burn-down/report.md`.

## Functional smokes

- **World harness** (`:test:harness-driver:driveWorld`, headless) after steps **7, 8, 10**:
  invoke extension → complete a job → second invoke → "In sync".
- **`screenshots.yml` dispatch at step 9** (sole exerciser of `forgeStatusHost`): raws are
  dossier evidence only, eyeballed by the operator later; never committed.

## Pauses (commit-then-pause): after steps 4, 5, 7, 8

Operator reviews the committed step (fixups via git). Then **merge `origin/main` in** and re-run
the full verifier before the next segment. A merge conflict inside a renamed tree **halts** —
never silently hand-resolved by an agent.

- **Step-4 pause = device Session A**: ssh-mac build → sideload over the joined install →
  re-provision-triggered cycle → pull both `debug.log`s → same device id, no cursor reset, no
  re-upload, marker intact. Also settles the ①–⑥ list (inputs to the behavior campaign).
- **Step-8 pause = device Session B**: cold/warm universal-link delivery, silent push, BGTask +
  extension cycle, `SNAPSYNC_SEED_POLICY` lines intact.
- **End of run**: final device pass against the finished branch, before the operator's end review.

Segments: `[1, 2, 3a, 3b, 4] ⏸A [5] ⏸ [6, 7] ⏸ [8] ⏸B [9, 10] → end review`.

## Failure policy

Two reviewer bounces → halt with a report, dirty tree preserved. Gradle-red the implementer
cannot fix → halt. Main-merge conflict in renamed code → halt. Any beacon law-count increase →
halt. Every halt leaves the last green step committed and the failure state inspectable.
