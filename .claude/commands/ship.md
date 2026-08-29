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
| MERGED | - | Already shipped. Treat the result as `MERGED`: emit the MERGED report (9.1), then step 10 |
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

⚠️ **This title is the App Store bullet.** It is not addressed to this repository. At release
time `.github/scripts/release_notes.py` takes the title of every `enhancement`/`bug` PR in the
range, applies exactly three transforms — strips `type(scope):`, strips a leading
`Fix`/`Fixes`/`Fixed`, capitalizes the first letter — and publishes the remainder **verbatim** as
a `- ` bullet under **New** or **Fixed** in the App Store listing. There is no editorial pass
after this moment, and correcting an already-promoted version's notes is a manual console upload.
Write the sentence a customer reads.

This is what shipped to customers when that was forgotten:

| PR | what the App Store showed |
| --- | --- |
| #202 | *"Take the photo-library import out from under the lock."* |
| #200 | *"Hold both background-session completion handlers in a bounded receipt."* |
| #151 | *"Download page."* |
| #133 | *"UI refresh."* |

**The four rules.**

1. **Every noun must be one a SnapSync user has seen** — in the app or the App Store listing:
   *event, photos, album, join, leave, share, sync, invite, QR code, phone, event settings, event
   dates, download*. If a word names something only this repository knows about, it may not
   appear. Observed leaks, as examples rather than as the list: *ledger, manifest, upload cycle,
   PhotoKit, URLSession, App Group, port, flow, lock, completion handler, MIME, UTI, extension,
   backend, endpoint, cursor, seam, adapter*.
2. **Name an observable outcome, never the area touched.** Vagueness and jargon are the same
   failure wearing two faces — both describe the change to the repository instead of the change to
   the user. *"Download page"* → *"Download an event's photos from the web"*. *"UI refresh"* →
   name what a user now sees or can do.
3. **A `bug` title states the symptom, gone.** It appears under a heading that already says
   **Fixed**, so *"Photos no longer arrive twice in an event"* reads correctly and *"Corrected the
   dedup key"* does not. Good shape: #196 *"photos no longer arrive twice for everyone in an
   event"*, #187 *"garbled screen after the app has been in the background"*, #217 *"config
   buttons appeared delayed"*.
4. **No scope.** The prefix is bare `feat: ` or `fix: ` — never `fix(download): `. The renderer
   strips a scope either way, so this costs the customer nothing; it exists to keep you writing to
   the customer rather than to the module. #202 and #182 broke this rule.

**Procedure.**

1. Determine the prefix: `feat: ` for feature, `fix: ` for bugfix.

2. **If a title was provided in parentheses** (e.g., `feat(Add dark mode)`): PR title =
   `feat: Add dark mode`. Check it against the four rules. If it violates one, say which rule and
   what the customer would read, and offer a compliant alternative via AskUserQuestion — with the
   user's original as the first option. **The user's answer is final**; this is a single check, not
   a negotiation, and a title that passes the rules goes through with no question at all.

3. **If no title in parentheses:** derive the **user-visible symptom or capability** from the diff
   and the commit messages — what did a user experience before, and what do they experience now?

   - **If you cannot derive it**, do NOT propose technical titles and do NOT invent a symptom you
     are not sure of: a confident wrong claim reaches customers and is worse than jargon. Ask,
     via AskUserQuestion with no preset options: *"I can't tell what a user experienced here. What
     did they see before this fix?"* (or, for a feature, *"...what can they now do?"*). Then write
     the title from the answer. "I could not tell" and "there is no user-visible symptom" are
     different answers — if it is really the second, the PR is `internal`, so go back to §7.1.

   - **Otherwise**, propose 3 title options via AskUserQuestion (the user can also pick "Other").
     Each option MUST carry, in its `preview` field, the **rendered customer-visible line** — the
     three transforms applied, under the heading it will appear beneath:

     ```
     Fixed
     - Config buttons appeared delayed
     ```

     Approve what the customer reads, not what the repository reads.

4. Prepend the prefix (`feat: ` or `fix: `) to the selected title.

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
  - **The body is where the technical detail belongs** — the mechanism, the module, the root
    cause, the internal vocabulary §7.2 forbids in the title. Nothing here is customer-visible:
    the release notes read the **title** only, never the body. So a user-facing title that lost
    detail has not lost it from the PR; put it here rather than smuggling it back into the title.
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

Do **not** wrap the command in `ch bg`. A ship is real work - the script rebases and
force-pushes this very worktree - so the workspace should read as busy while it runs, and the
`ch bg` prefix would also fall outside this command's `Bash(npx:*)` grant, prompting on every
ship.

The Bash tool returns immediately with a task id and an **output file path**. Then:

1. Emit one line of acknowledgement, e.g. `Waiting on PR #<number> (up to 20 min).`
2. **End the turn.** The harness re-invokes you with a task notification when the shell exits.
3. On re-invocation, `Read` the output file and find the final line:

   ```
   SHIP-WAIT RESULT: <MERGED|FAILED|TIMEOUT> (<reason>)
   ```

4. Report per that result (formats in 9.1) and take the workspace decision from this table.
   The decision is made HERE, so step 10 has exactly one job left:

   | result line | report format | workspace |
   | --- | --- | --- |
   | `MERGED` | MERGED | **delete** - continue to step 10 (unless `--keep-workspace`) |
   | `FAILED` | FAILED | keep. The report is the whole turn |
   | `TIMEOUT` | TIMEOUT | keep. The report is the whole turn |
   | none present | UNKNOWN | keep. The report is the whole turn |

   A missing `SHIP-WAIT RESULT:` line is its own outcome - the process died without reaching
   any of its own exit paths. Do not infer MERGED or FAILED from the absence of a line.

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

#### 9.1. Report formats

##### MERGED (exit code 0)

```
PR merged successfully!

**PR**: <url>
**Commit**: <sha> merged to <default-branch>
**Bugsink**: <one line per id - omit this field entirely when no trailer was found>
**Workspace**: will be deleted now (or "kept" if --keep-workspace)
```

The `**Bugsink**` field is present only when step 9.2 found a `Bugsink-Resolves:` trailer.
One line per id, saying what actually happened:

```
**Bugsink**: SNAPSYNC-9 resolved (resolve-next)
             SNAPSYNC-21 already resolved
             SNAPSYNC-14 NOT resolved: operator declined
```

##### FAILED (exit code 1)

```
PR failed to merge.

**PR**: <url>
**Reason**: <reason from the SHIP-WAIT RESULT line>

Action required: Fix the issue and run `/ship` again.
```

##### TIMEOUT (exit code 2)

TIMEOUT means the **watcher stopped looking** - never that the merge failed. Auto-merge is
still armed on GitHub, so the PR merges by itself once its required checks pass. Say so; do
not send the user off to diagnose a healthy ship.

```
Stopped waiting after 20 minutes - the PR has not merged yet.

**PR**: <url>
**Reason**: <reason from the SHIP-WAIT RESULT line>

Auto-merge is still enabled, so GitHub will merge this PR on its own once its required
checks pass. Nothing is broken and no action is needed to complete the merge.

Re-run `/ship` when convenient to confirm the merge and delete the workspace (it takes
seconds - it skips the rebase and build when the PR already carries these commits).
```

##### UNKNOWN (no result line)

```
Could not determine the merge outcome.

**PR**: <url>
**Reason**: the ship-wait process exited without reporting a result

The wait process died before reaching any of its own exit paths, so the outcome was never
learned - this is NOT a report that the merge failed. Check the PR directly, then re-run
`/ship` to resume. Workspace kept.
```

#### 9.2. Resolve Bugsink issues - MERGED only, and BEFORE the report

Run this on a `MERGED` result, **before** emitting the MERGED report - because step 10 deletes
the workspace in that same message, and nothing can follow it, a question least of all.

1. **Detect.** Read the trailers of the commits this PR shipped:

   ```bash
   git log origin/<default-branch>..HEAD --pretty=format:%b \
     | { grep -oP '^Bugsink-Resolves:\s*\K[A-Z]+-[0-9]+' || true; } | sort -u
   ```

   The `|| true` is load-bearing: `grep` exits **1** when it matches nothing, which is the
   normal case, and under `set -e` that would abort the run over a ship that simply fixed no
   Bugsink issue.

   **No matches - do nothing at all.** No lookup, no question, no `**Bugsink**` field in the
   report. Most ships fix no Bugsink issue and must stay silent about it.

   If that range is empty because the PR was already merged on an earlier run (the
   already-MERGED route at step 2), read the merged commits from the default branch instead:
   `git log <default-branch>@{u}~1..<default-branch>@{u} --pretty=format:%b`.

2. **Look up, then confirm.** Load the `bugsink` skill and follow its **§4**: it maps each
   friendly id to a UUID, reports whether the issue is still open and whether it is a
   diagnostic dump or a real crash, and owns the choice between `resolve/` and
   `resolve-next/`. Then **ask the operator to confirm** - every id, every time. A declined
   confirmation resolves nothing and is not an error.

3. **Resolve** what was confirmed, per §4.

4. **Report** each id on its own line in the MERGED report (9.1).

⚠️ **This step NEVER fails the ship.** The PR is already merged; the resolve is bookkeeping
after the fact. A declined proton sign-off, an API error, an id that does not exist, an
unattended run with nobody there to confirm - each is one honest line in the report and
nothing more. Do not retry, do not diagnose, and do not let any of it change the exit path or
the workspace-delete decision.

### 10. Delete the workspace - MERGED only, and in the SAME message as the report

Reached only when step 9's table says **delete**, and only once step 9.2 is done. Nothing
follows this step, in this file or in the run: deleting tears down this worktree and the
agent session inside it, so no tool call and no output can execute after it - which is
exactly why 9.2's confirmation happens before the report rather than after it. (If `--keep-workspace` was passed, there is nothing
to do here - the report already says "kept".)

`mcp__codehydra__workspace_delete` is a **deferred** tool: it has no schema loaded and cannot
be called until you fetch one with `ToolSearch({query:
"select:mcp__codehydra__workspace_delete"})`. Do that first, so the call is available the
moment the report is written.

Then: **the MERGED report and the delete call go in ONE assistant message.** Emit the report
text, and - without ending the turn - call `mcp__codehydra__workspace_delete` with
`keepBranch: false` in that same message, as its final content block.

⚠️ **A text-only message ENDS THE TURN, and the workspace survives.** This is the observed
failure mode, not a hypothetical one: measured across 50 shipped PRs, every run whose report
message stopped at text left the workspace behind, and every run that carried the call in
the same message deleted it. Writing "will be deleted now" is not deleting it. Carry-forward
notes belong inside that same report text - never in a message after it.
