# Tasks

## 1. Confirm the backend already holds the new rule

- [x] 1.1 Re-read `openUntilAdmitted` (`api/src/lifecycle.ts`), the create handler (`api/src/routes/shared.ts`) and the
      mock's create (`BackendRouter`) against the delta's requirement "An event stays open until the date its host
      chose": absent → the end; present → at least the end and at most the window maximum after the later of the end
      and now (plus the hour of slack); outside → not created. Verify: `api/test/back-dated.test.ts` already covers an
      open-until date later than a future end being admitted and one past the bound being refused — if either case is
      missing, add it there (`cd api && deno task test` green). No production code changes.

## 2. Reword the prose that restates the old rule

- [x] 2.1 Reword the `event-lifetime` Purpose's description of the open-until date ("its end, or, for an event created
      after its end, the date the host chose") to "its end, or a later date its host chose at creation" in
      `openspec/specs/event-lifetime/spec.md` (a delta cannot change a Purpose). Verify: the Purpose no longer
      conditions the chosen date on the event being over.
- [x] 2.2 Reword the same rule in the mission summary of `openspec/config.yaml`'s `context:` and of `CLAUDE.md`: the
      open-until date is the end, or a later day the host chose at creation (the app offers 1/7/14/30 days for an
      event created after its end). Verify: `grep -n "created after its end" CLAUDE.md openspec/config.yaml` shows
      only the app's offer, not the rule.

## 3. Validate

- [x] 3.1 `npx --yes @fission-ai/openspec@1.13.2 validate host-chosen-open-until --strict` and
      `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` pass.
