## 1. Settle the name before moving anything

- [x] 1.1 Confirm `upload-state-reconciliation`, or pick the alternative. 45 live files move either way, so
      the name is cheap now and expensive later.
- [x] 1.2 Decide the route: folded into `detect-lost-upload-records` as its own commit, or a standalone
      mechanical PR skipping OpenSpec. The proposal argues against a separate OpenSpec change.

## 2. Move the capability

- [x] 2.1 `git mv openspec/specs/event-rejoin-reconciliation openspec/specs/upload-state-reconciliation`.
- [x] 2.2 Rewrite the Purpose to lead with the operation — ask the backend which resources it holds for
      this device and make the local record agree — and list the occasions (marker mismatch: fresh
      provision, event switch, reinstall) rather than defining the capability as a join-time gate.
- [x] 2.3 Add the pointer line: the capability was `event-rejoin-reconciliation` until this change, and
      decision records under `changes/archive/` cite the former name.
- [x] 2.4 Apply the RENAMED delta for *Join reconciliation seeds already-stored photos as completed*.

## 3. Follow the live citations — and only the live ones

- [x] 3.1 The 17 other files under `openspec/specs/`.
- [x] 3.2 The 28 source and doc files: `domain/feature` (9), `adapter/ios` (6), `domain/model` (3),
      `app/ios` (3), `test/world` (2), `domain/ports` (2), `test/architecture` (1), `CLAUDE.md`, and the
      skill under `.claude/skills/`.
- [x] 3.3 Verify nothing under `openspec/changes/archive/` changed: `git diff --stat openspec/changes/archive`
      must be empty. The 151 archived citations stay as written.
- [x] 3.4 Confirm the only remaining live hits for the old name are the deliberate pointer line and any
      archive-facing citation: `grep -rl event-rejoin-reconciliation` outside `changes/archive/`.

## 4. Verify

- [x] 4.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 4.2 `./gradlew build` — `:test:architecture` includes `RunbookSkillsTest` and the laws digest, so run
      the full gate rather than assuming a rename is invisible to the guards.
- [x] 4.3 `./gradlew compileIosMainKotlinMetadata`.
- [x] 4.4 `./gradlew architectureDiagrams` and commit if the generated set moved.
- [x] 4.5 Read the diff once for hunks that are not the substitution — a rename PR must contain nothing
      else.

## 5. Ship

- [ ] 5.1 Branch → PR with the `internal` label → `/ship`, or fold the commit into
      `detect-lost-upload-records` per 1.2.
