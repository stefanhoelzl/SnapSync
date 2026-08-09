---
description: Create PR with auto-merge, wait for merge via client-side queue
allowed-tools: Bash(git:*), Bash(gh:*), Bash(npx:*), Bash(./gradlew:*), mcp__codehydra__workspace_delete
---

# /ship Command

Ship the current branch by creating a PR with auto-merge and waiting for it to merge.

## Arguments

$ARGUMENTS

- Empty: Auto-generate PR title and summary from commits
- `feat` or `fix`: User-facing change. Agent proposes a PR title, user reviews.
- `feat(<title>)` or `fix(<title>)`: User-facing change with explicit PR title.
- `internal`: Internal change. Skips user-facing detection.
- `--keep-workspace`: Keep workspace after successful merge (default: delete)
- `--resolves <issue>`: Link PR to a GitHub issue
  - `--resolves #123` or `--resolves 123`: Links to issue #123
  - `--resolves ?`: List all open issues and prompt for selection

## Execution

You are a BUILD AUTOMATION agent. Execute the workflow below. On FAILED or TIMEOUT,
return immediately with a report - do NOT attempt to diagnose or fix issues.

**This command spans two turns.** Step 9 launches the merge wait as a background shell and
ends the turn; the harness re-invokes you when that shell exits, and you finish at step 10.
The pause between them is the design, not a failure - do not restart the workflow.

### 0. Derive repo and default branch

Parse the GitHub repo from the git remote:

```bash
git remote get-url origin
```

Extract `<owner>/<repo>` from the URL (handles both HTTPS and SSH formats).
Use this as `<repo>` in all subsequent `gh` commands.

Detect the default branch:

```bash
gh repo view <repo> --json defaultBranchRef --jq '.defaultBranchRef.name'
```

Use this as `<default-branch>` in all subsequent commands.

### 1. Validate preconditions

All precondition checks are READ-ONLY — never mutate repository settings to satisfy them.

**1.1. Check for uncommitted changes:**

```bash
git status --porcelain
```

If output is non-empty: ABORT with:

```
Cannot ship with uncommitted changes.

**Uncommitted files:**
<list of files>

Commit your changes first, then run `/ship` again.
```

**1.2. Check we're not on the default branch:**

```bash
git branch --show-current
```

If on `<default-branch>`: ABORT with "Cannot ship from <default-branch> branch"

**1.3. Check for un-archived openspec changes:**

```bash
npx --yes @fission-ai/openspec@1.5.0 list --json
```

There is no global `openspec` binary in this repo and there should not be one - it runs via
`npx`, pinned to the version CI uses (`.github/workflows/build.yml`). A bare `openspec ...`
fails with "command not found".

If the command fails: ABORT with "openspec list failed: <command output>."

If the JSON array is non-empty: ABORT with:

```
Cannot ship with un-archived openspec changes:
  - <change-name>
  - <change-name>

Archive them before shipping.
```

**1.4. Check auto-merge is allowed on the repo:**

```bash
gh api repos/<repo> --jq '.allow_auto_merge'
```

(`autoMergeAllowed` is not a `gh repo view --json` field; the repo-level
auto-merge flag is the REST API's `allow_auto_merge`.)

If `false`: ABORT with:

```
Cannot ship: auto-merge is disabled on <repo>.

Enable it once (repo settings, or:
  gh api -X PATCH repos/<repo> -F allow_auto_merge=true
), then run `/ship` again.
```

### 2. Check for existing PR (idempotency) - the route decision

This runs BEFORE the rebase and build, because the common reason to re-run `/ship` is to
confirm a ship whose wait timed out (see step 9) - and that confirmation must not pay for a
rebase and a full `./gradlew build` on a commit CI has already validated.

```bash
gh pr list --repo <repo> --head <current-branch> --json number,url,state,headRefOid
git rev-parse HEAD
```

Pick the route from the result. `headRefOid` matters: "a PR is open" and "a PR is open **and
already carries exactly these commits**" are different answers, and only the second one is
safe to resume without building.

| PR state | `headRefOid` vs local `HEAD` | Route |
| --- | --- | --- |
| MERGED | - | Already shipped. Treat the result as `MERGED` and skip to step 10 (report + delete workspace) |
| OPEN | same | Skip to step 9 (re-enter the wait). Nothing to rebuild |
| OPEN | different | There is unshipped local work: continue to step 3, but skip step 5 (issue selection) and step 7 (PR creation); re-confirm auto-merge at step 8 |
| CLOSED, or no PR | - | Continue to step 3 (full create path) |

### 3. Rebase onto default branch

```bash
git fetch origin <default-branch>
git rebase origin/<default-branch>
```

If rebase fails: ABORT with:

```
Rebase onto <default-branch> failed (conflicts?).

Resolve conflicts manually, then run `/ship` again.
```

### 4. Run checks

```bash
./gradlew build
```

If the build fails: ABORT with:

```
Cannot ship: build failed.

Fix the issues, then commit and run `/ship` again.
```

### 5. Resolve issue selection (if --resolves ? was passed)

If `--resolves ?` was provided:

1. Fetch open issues:

   ```bash
   gh issue list --repo <repo> --state open --json number,title --limit 100
   ```

2. If no open issues exist: ABORT with "No open issues found"

3. Display the list to the user:

   ```
   Open issues:

   #<number> <title>
   #<number> <title>
   ...
   ```

4. Ask the user explicitly:

   ```
   Which issue does this PR resolve? Enter the issue number (e.g., 123):
   ```

5. Wait for user response and store the issue number for step 7.

### 6. Push

```bash
git push --force-with-lease origin HEAD
```

### 7. Create PR

Generate title and summary from commits:

```bash
git log origin/<default-branch>..HEAD --pretty=format:"%s%n%b"
```

Also get the diff for category analysis:

```bash
git diff origin/<default-branch>..HEAD
```

#### 7.1. Determine PR category

A `category` variable tracks the result: `"feature"`, `"bugfix"`, or `null` (internal).

**If `feat` or `fix` argument was provided:**

Set `category` to `"feature"` or `"bugfix"` accordingly.

**If `internal` argument was provided:**

Set `category` to `null` (skip detection and user prompt).

**If no category argument was provided:**

Analyze the actual changes (diffs and commit messages) to determine if the changes are user-facing.
User-facing changes include: new features, bug fixes, UX improvements, new configuration options, API changes visible to users.
Internal changes include: refactors, test additions/fixes, documentation, CI/CD, dependency bumps, code style, chore tasks.

- If changes appear **user-facing**: Ask the user via AskUserQuestion: "This looks like a user-facing change. How should it be categorized?" with options:
  - `Feature` — categorize as feature
  - `Bugfix` — categorize as bugfix
  - `No` — internal change
    Set `category` based on the user's choice (`"feature"`, `"bugfix"`, or `null`).
- If changes appear **purely internal**: set `category` to `null` (no prompt).

#### 7.2. Determine PR title

**If `category` is `"feature"` or `"bugfix"`:**

1. Determine the prefix: `feat: ` for feature, `fix: ` for bugfix.
2. If a title was provided in parentheses (e.g., `feat(Add dark mode)`): PR title = `feat: Add dark mode`
3. If no title in parentheses: Analyze the changes and propose 3 concise PR title options via AskUserQuestion (the user can also pick "Other" to enter a custom title). Prepend the appropriate prefix (`feat: ` or `fix: `) to the selected title.

**If `category` is `null`:**

Determine PR title using the standard convention:

- **PR title**: `<type>(<scope>): <description>` (from primary commit or summarized)

**Commit types (for internal PRs):**

| Type    | Description                                     |
| ------- | ----------------------------------------------- |
| `feat`  | new feature                                     |
| `fix`   | bug fix                                         |
| `docs`  | documentation only                              |
| `chore` | maintenance, deps, config, refactor, formatting |
| `test`  | adding/fixing tests                             |
| `infra` | CI/CD, build system                             |

#### 7.3. Create the PR

- **PR body**: Bullet-point summary of changes
  - If `--resolves <number>` was provided (directly or via `?` selection), append an empty line followed by `resolves #<number>`

**Example PR body with resolves:**

```
- Added feature X
- Fixed bug Y

resolves #123
```

Determine the label from `category`:

- `"feature"`: `enhancement`
- `"bugfix"`: `bug`
- `null` (internal): `internal`

Create PR with the label included:

```bash
gh pr create --repo <repo> --title "<title>" --label "<label>" --body "<body>"
```

Capture the PR URL and number from output.

### 8. Enable Auto-merge

On the resume route (step 2 found an OPEN PR with a differing head), first check whether
auto-merge is already on:

```bash
gh pr view --repo <repo> <number> --json autoMergeRequest
```

If `autoMergeRequest` is non-null, SKIP this step. The client-side queue is ordered by
`autoMergeRequest.enabledAt`, so re-enabling auto-merge sends an already-queued PR to the
BACK of the queue - turning a resume into a fresh wait behind everyone else.

Otherwise:

```bash
gh pr merge --repo <repo> <number> --auto --rebase --delete-branch
```

This:

- Enables auto-merge (will merge when all checks pass and branch is up-to-date)
- Uses **rebase** to maintain linear history
- Sets branch to auto-delete after merge

### 9. Run ship-wait script (BACKGROUND - this ends the turn)

Run it with the Bash tool's `run_in_background: true`, and pass **no** `timeout` parameter:

```bash
npx tsx .claude/commands/ship-wait.ts <repo> <number> <default-branch>
```

⚠️ **Never run this in the foreground, and never "fix" it by raising `timeout`.** The Bash
tool caps `timeout` at 600_000 ms and clamps anything larger **silently** - so a foreground
call is killed at exactly `10m 0s` while the script is still mid-wait, and the merge outcome
is never learned. Raising the value changes nothing; it is the clamp, not the number. (This
happened on 26 consecutive ships. See "Agent harness limits" in CLAUDE.md.)

Do **not** wrap the command in `ch-bg`. A ship is real work - the script rebases and
force-pushes this very worktree - so the workspace should read as busy while it runs, and the
`ch-bg` prefix would also fall outside this command's `Bash(npx:*)` grant, prompting on every
ship.

The Bash tool returns immediately with a task id and an **output file path**. Then:

1. Emit one line of acknowledgement, e.g. `Waiting on PR #<number> (up to 20 min).`
2. **End the turn.** The harness re-invokes you with a task notification when the shell exits.
3. On re-invocation, `Read` the output file and find the final line:

   ```
   SHIP-WAIT RESULT: <MERGED|FAILED|TIMEOUT> (<reason>)
   ```

4. Report per that result (see Report Formats), then do step 10.

If the output file has **no** `SHIP-WAIT RESULT:` line, the outcome is UNKNOWN - the process
died without reaching any of its own exit paths. Report UNKNOWN and keep the workspace. Do
not infer MERGED or FAILED from the absence of a line, and do not delete the workspace.

The script handles:

- Waiting for PRs ahead in queue (ordered by `autoMergeRequest.enabledAt`; re-enabling auto-merge moves a PR to the back)
- Skipping failed-ahead PRs (CLOSED, merge state DIRTY, or any check with conclusion in {FAILURE, CANCELLED, TIMED_OUT, ACTION_REQUIRED}). Once skipped, always skipped within a run
- Exiting early if our own PR merges or is closed while waiting (polled every iteration)
- Rebasing onto default branch when it's our turn
- **Applying committed rulesets** (`.github/rulesets/*.json`) when first in queue, after the rebase and before CI — created if absent, updated if present, no-op without the directory
- Waiting for CI via `gh pr checks --watch`
- Waiting for auto-merge to complete
- Fetching latest default branch from origin

**Budget:** 20 minutes for the whole run, with a 15-minute sub-budget on the CI watch (so a
wedged required check is diagnosed there rather than absorbed by the overall budget). Measured
over 80 merged PRs, 20 minutes covers ~84% of ships.

**Exit codes** (redundant with the result line; the line is authoritative because it also
carries the reason):

- 0: MERGED
- 1: FAILED
- 2: TIMEOUT

### 10. Report, then delete workspace

Deleting the workspace tears down this worktree (and the agent session running in it), so
nothing can execute after the delete call. Therefore the order is strict:

1. **First**, emit the final report (see Report Formats below).
2. **Then**, if `--keep-workspace` was NOT passed and the result was `MERGED`:
   call `mcp__codehydra__workspace_delete` with `keepBranch: false` as the VERY LAST
   action — no tool calls or output after it.

`MERGED` is the ONLY result that deletes the workspace. FAILED, TIMEOUT and UNKNOWN all keep
it — a TIMEOUT in particular leaves a perfectly healthy PR that GitHub will merge on its own,
and the kept workspace is what makes the cheap re-run in step 2 possible.

If `--keep-workspace` was passed, do not delete; the report already says "kept".

## Report Formats

### MERGED (exit code 0)

```
PR merged successfully!

**PR**: <url>
**Commit**: <sha> merged to <default-branch>
**Workspace**: will be deleted now (or "kept" if --keep-workspace)
```

### FAILED (exit code 1)

```
PR failed to merge.

**PR**: <url>
**Reason**: <reason from the SHIP-WAIT RESULT line>

Action required: Fix the issue and run `/ship` again.
```

### TIMEOUT (exit code 2)

TIMEOUT means the **watcher stopped looking** - never that the merge failed. Auto-merge is
still armed on GitHub, so the PR merges by itself once its required checks pass. Say so; do
not send the user off to diagnose a healthy ship. Do NOT delete the workspace.

```
Stopped waiting after 20 minutes - the PR has not merged yet.

**PR**: <url>
**Reason**: <reason from the SHIP-WAIT RESULT line>

Auto-merge is still enabled, so GitHub will merge this PR on its own once its required
checks pass. Nothing is broken and no action is needed to complete the merge.

Re-run `/ship` when convenient to confirm the merge and delete the workspace (it takes
seconds - it skips the rebase and build when the PR already carries these commits).
```

### UNKNOWN (no result line)

```
Could not determine the merge outcome.

**PR**: <url>
**Reason**: the ship-wait process exited without reporting a result

The wait process died before reaching any of its own exit paths, so the outcome was never
learned - this is NOT a report that the merge failed. Check the PR directly, then re-run
`/ship` to resume. Workspace kept.
```
