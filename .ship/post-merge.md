# Post-merge: resolve the Bugsink issues this PR closes

A branch that fixes a crash carries a `Bugsink-Resolves: SNAPSYNC-<n>` trailer, written into the
first commit of the fix. Closing the issue is bookkeeping that follows the merge, so it happens
here — after the PR is MERGED, before the report.

1. **Detect.** Read the trailers of the commits this PR shipped:

   ```bash
   git log origin/<default-branch>..HEAD --pretty=format:%b \
     | { grep -oP '^Bugsink-Resolves:\s*\K[A-Z]+-[0-9]+' || true; } | sort -u
   ```

   The `|| true` is load-bearing: `grep` exits **1** when it matches nothing, which is the
   normal case, and under `set -e` that would abort the run over a ship that simply fixed no
   Bugsink issue.

   **No matches - do nothing at all.** No lookup, no question, no `**Post-merge**` field in the
   report. Most ships fix no Bugsink issue and must stay silent about it.

   If that range is empty because the PR was already merged on an earlier run (the
   already-MERGED route on the route table), read the merged commits from the default
   branch instead: `git log <default-branch>@{u}~1..<default-branch>@{u} --pretty=format:%b`.

2. **Look up, then confirm.** Load the `bugsink` skill and follow its **§4**: it maps each
   friendly id to a UUID, reports whether the issue is still open and whether it is a
   diagnostic dump or a real crash, and owns the choice between `resolve/` and
   `resolve-next/`. Then **ask the operator to confirm** - every id, every time. A declined
   confirmation resolves nothing and is not an error.

3. **Resolve** what was confirmed, per §4.

4. **Report** each id on its own line in the MERGED report, saying what actually happened.

⚠️ **This step NEVER fails the ship.** The PR is already merged; the resolve is bookkeeping
after the fact. A declined proton sign-off, an API error, an id that does not exist, an
unattended run with nobody there to confirm - each is one honest line in the report and
nothing more. Do not retry, do not diagnose, and do not let any of it change the exit path or
the workspace-delete decision.
