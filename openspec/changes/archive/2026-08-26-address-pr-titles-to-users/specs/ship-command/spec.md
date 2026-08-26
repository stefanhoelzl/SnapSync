## REMOVED Requirements

### Requirement: Ship preconditions

**Reason**: `/ship` is dev tooling whose contract is stated in full by a single committed,
self-documenting artifact. Under the criterion recorded in `openspec/config.yaml` — a spec exists
where a contract is spread across artifacts and drift is invisible; where one committed artifact IS
the contract and carries its own rationale, the spec is a second copy — this capability is a second
copy. A rescue pass over all four requirements found zero claims stated only here.

**Migration**: `.claude/commands/ship.md` §1 states all four preconditions (uncommitted changes,
default branch, un-archived openspec changes, `allow_auto_merge`) and opens with "All precondition
checks are READ-ONLY — never mutate repository settings to satisfy them."

### Requirement: Create PR with auto-merge

**Reason**: As above — a second copy of `.claude/commands/ship.md`.

**Migration**: `ship.md` §3–§8 state the rebase, `./gradlew build`, `git push --force-with-lease`,
PR creation with a title and exactly one category label (`enhancement`/`bug`/`internal`, optionally
`resolves #N`), and `gh pr merge --auto --rebase --delete-branch`. The title rule itself is
strengthened in the same change (§7.2), which is why it has no spec home to move to.

### Requirement: Client-side merge queue

**Reason**: As above — a second copy of `.claude/commands/ship-wait.ts`, which carries the measured
rationale the spec only asserted.

**Migration**: `ship-wait.ts` states the FIFO ordering by `autoMergeRequest.enabledAt`, the
skip rules for dead ahead-PRs, the run-time derivation of the required-check set from the branch
rules (`fetchRequiredChecks`, lines 217–230) and its strict fallback when that fetch fails
("Empty set = the required-checks fetch failed → strict fallback: every check treated as required",
lines 112–117), the ruleset apply (lines 395–401), the CI watch on `--required` checks, and the
measured 20-minute budget with its 15-minute CI sub-budget.

### Requirement: Workspace cleanup

**Reason**: As above — a second copy of `.claude/commands/ship.md`.

**Migration**: `ship.md` §9's result table and §10 state the delete-on-MERGED rule, the
`--keep-workspace` exemption, and the measured requirement that the delete call ride in the same
message as the report.
