## REMOVED Requirements

### Requirement: Default branch ruleset

**Reason**: `.github/rulesets/main.json` is committed and applied mechanically, so it states the
contract exactly rather than in prose that can drift from it. Under the criterion recorded in
`openspec/config.yaml`, a capability whose contract IS one committed artifact does not earn a
second copy. The two paragraphs of rationale a JSON file cannot carry were verified to exist
already in the workflows they describe.

**Migration**: `.github/rulesets/main.json` declares the six required status-check contexts
(`build`, `ios-build`, `ios-test`, `appstore-metadata-validate`, `diagrams`, `check-label`),
`allowed_merge_methods: ["rebase"]`, `required_linear_history`, `pull_request`, and the `deletion`
and `non_fast_forward` bans. Why each check is safe to require is stated by the workflow that posts
it: `.github/workflows/appstore.yml:5-6` for `appstore-metadata-validate`, and
`.github/workflows/check-label.yml:13-14` for `check-label`. The general invariant — a required
check that never posts on a pull-request branch freezes every merge — is stated by each workflow
about its own jobs (`ios.yml:40,59,112`, `appstore.yml:33`, `ios-appstore-promote.yml:41`).

### Requirement: Ruleset applied during ship

**Reason**: As above — a second copy of the doc comment on the function that implements it.

**Migration**: `.claude/commands/ship-wait.ts:395-401` states that every committed
`.github/rulesets/*.json` is applied when first in queue, after the rebase and before CI, looked up
by name and updated if present or created otherwise, using the operator's authenticated `gh` (repo
admin), and that a repository without the directory is a no-op.
