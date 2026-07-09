---
name: openspec-archive-change
description: Archive a completed change in the experimental workflow. Use when the user wants to finalize and archive a change after implementation is complete.
license: MIT
compatibility: Requires openspec CLI.
metadata:
  author: openspec
  version: "1.0"
  generatedBy: "1.5.0"
---

Archive a completed change in the experimental workflow.

**Store selection:** If the user names a store (a store is a standalone OpenSpec repo registered on this machine) or the work lives in one, run `openspec store list --json` to discover registered store ids, then pass `--store <id>` on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, `context`). Other commands do not take the flag. Hints printed by commands already carry the flag; keep it on follow-ups. Without a store, commands act on the nearest local `openspec/` root.

**Input**: Optionally specify a change name. If omitted, check if it can be inferred from conversation context. If vague or ambiguous you MUST prompt for available changes.

**Steps**

1. **If no change name provided, prompt for selection**

   Run `openspec list --json` to get available changes. Use the **AskUserQuestion tool** to let the user select.

   Show only active changes (not already archived).
   Include the schema used for each change if available.

   **IMPORTANT**: Do NOT guess or auto-select a change. Always let the user choose.

2. **Check artifact completion status**

   Run `openspec status --change "<name>" --json` to check artifact completion.

   Parse the JSON to understand:
   - `schemaName`: The workflow being used
   - `planningHome`, `changeRoot`, `artifactPaths`, and `actionContext`: path and scope context
   - `artifacts`: List of artifacts with their status (`done` or other)

   **If any artifacts are not `done`:**
   - Display warning listing incomplete artifacts
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

3. **Check task completion status**

   Read the tasks file (typically `tasks.md`) to check for incomplete tasks.

   Count tasks marked with `- [ ]` (incomplete) vs `- [x]` (complete).

   **If incomplete tasks found:**
   - Display warning showing count of incomplete tasks
   - Use **AskUserQuestion tool** to confirm user wants to proceed
   - Proceed if user confirms

   **If no tasks file exists:** Proceed without task-related warning.

4. **Assess delta spec sync state**

   Use `artifactPaths.specs.existingOutputPaths` from status JSON to check for delta specs. If none exist, proceed without sync prompt.

   **If delta specs exist:**
   - Compare each delta spec with its corresponding main spec at `openspec/specs/<capability>/spec.md`
   - Determine what changes would be applied (adds, modifications, removals, renames)
   - Show a combined summary before prompting

   **Prompt options:**
   - If changes needed: "Sync now (recommended)", "Archive without syncing"
   - If already synced: "Archive now", "Sync anyway", "Cancel"

   If user chooses sync, use Task tool (subagent_type: "general-purpose", prompt: "Use Skill tool to invoke openspec-sync-specs for change '<name>'. Delta spec analysis: <include the analyzed delta spec summary>"). Proceed to archive regardless of choice.

5. **Give every spec a real Purpose** (capability `openspec-archive-command`)

   When the CLI creates a spec file it mints the placeholder:

   ```
   TBD - created by archiving change <name>. Update Purpose after archive.
   ```

   Nothing else ever replaces it, so the archive step must. **Before performing the archive:**

   a. **Replace every placeholder this change produced.** For each spec whose `## Purpose` contains
      `TBD - created by archiving`, write a real Purpose derived from the change's `proposal.md` (the
      motivation — *why* the capability exists) **and** the delta spec's requirements (*what* it covers).

      A Purpose that only paraphrases its own `SHALL` statements is a **failed Purpose** — the
      requirements already say that. State what the capability is for and what problem it solves.

   b. **Purposes must be self-contained.** A Purpose SHALL NOT defer the capability's meaning to a
      document outside `openspec/`. Where a decision record is worth naming, cite it as
      `Decision record: changes/archive/<id>` — a pointer *into* the archive, not out of the tree.

   c. **Fail the archive on any surviving placeholder.** Check the **whole** spec tree, not just the
      specs this change touched, so a placeholder left behind by an earlier archive surfaces here.

      Scope the match to each spec's `## Purpose` section. A naive `grep -rl` over whole files gives a
      **false positive** on `openspec/specs/openspec-archive-command/spec.md`, which legitimately quotes the
      placeholder string in its requirements — and would make that spec permanently unarchivable:

      ```bash
      for f in openspec/specs/*/spec.md; do
        awk '/^## Purpose/{p=1;next} /^## /{p=0} p' "$f" \
          | grep -q "TBD - created by archiving" && echo "$f"
      done
      ```

      If this prints anything, **stop and report the offending files** instead of archiving. Fix them,
      then re-run the check.

6. **Perform the archive**

   Create an `archive` directory under `planningHome.changesDir` if it doesn't exist:
   ```bash
   mkdir -p "<planningHome.changesDir>/archive"
   ```

   Generate target name using current date: `YYYY-MM-DD-<change-name>`

   **Check if target already exists:**
   - If yes: Fail with error, suggest renaming existing archive or using different date
   - If no: Move `changeRoot` to the archive directory

   ```bash
   mv "<changeRoot>" "<planningHome.changesDir>/archive/YYYY-MM-DD-<name>"
   ```

7. **Display summary**

   Show archive completion summary including:
   - Change name
   - Schema that was used
   - Archive location
   - Whether specs were synced (if applicable)
   - Note about any warnings (incomplete artifacts/tasks)

**Output On Success**

```
## Archive Complete

**Change:** <change-name>
**Schema:** <schema-name>
**Archived to:** the archive path derived from `planningHome.changesDir`/YYYY-MM-DD-<name>/
**Specs:** ✓ Synced to main specs (or "No delta specs" or "Sync skipped")

All artifacts complete. All tasks complete.
```

**Guardrails**
- Always prompt for change selection if not provided
- Use artifact graph (openspec status --json) for completion checking
- Don't block archive on warnings - just inform and confirm
- **DO block archive on a surviving `TBD - created by archiving` Purpose** (step 5c). This is not a
  warning: a placeholder Purpose makes `openspec/specs/` lie about being the contract of record, and
  nothing downstream ever fixes it. Report the files and stop.
- Never write a Purpose that points outside `openspec/` for its meaning
- Preserve .openspec.yaml when moving to archive (it moves with the directory)
- Show clear summary of what happened
- If sync is requested, use openspec-sync-specs approach (agent-driven)
- If delta specs exist, always run the sync assessment and show the combined summary before prompting
