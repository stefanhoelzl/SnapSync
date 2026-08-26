## Why

A user-facing pull-request title **is** the App Store bullet. `release_notes.py` strips the
`type(scope):` prefix and a leading `Fix`, capitalizes, and publishes the rest verbatim under
**New** / **Fixed** — so `/ship`'s proposed titles reach customers unedited. They are written for
the repository instead: PR #202 shipped to the App Store as *"Take the photo-library import out
from under the lock."*, #200 as *"Hold both background-session completion handlers in a bounded
receipt."*, #151 as *"Download page."* Nothing in `/ship` states that the title has a customer
audience, so each ship re-decides it and most decide wrong.

Fixing that means changing what `/ship` produces — and `/ship` is dev tooling whose contract is
already stated, in full, by the two committed artifacts that implement it. Its spec is a second
copy. So is `branch-protection`'s, of `.github/rulesets/main.json`. Both are removed here rather
than amended, under a criterion recorded so the question is not re-decided per capability.

## What Changes

**The title rule (`.claude/commands/ship.md`).** For pull requests categorized `feature` or
`bugfix` only — `internal` titles reach no customer and keep today's repo-addressed style:

- The title is written **as the App Store bullet**: every noun one a SnapSync user has seen in the
  app or the listing (*event, photos, album, join, leave, share, sync, invite, QR code, phone,
  event settings, event dates*), and never internal vocabulary (*ledger, manifest, upload cycle,
  PhotoKit, URLSession, App Group, port, flow, lock, completion handler, MIME/UTI, extension,
  backend, endpoint*).
- It names an **observable outcome**, never the area touched — vagueness and jargon are the same
  failure. *"Download page"* → *"Download an event's photos from the web"*.
- A `bug` title states **the symptom, gone**, because that is what reads correctly under the
  **Fixed** heading.
- **No scope**: bare `feat: ` / `fix: `. Already the rule; #202 and #182 broke it.
- Each proposed option carries the **rendered customer-visible line** in its `AskUserQuestion`
  preview, under a mock `New`/`Fixed` heading — so the leak is visible at approval, not at promote.
- When no user-visible symptom can be derived from the diff and commits, `/ship` **asks** what the
  user experienced rather than proposing technical titles. A confident wrong claim to customers is
  worse than jargon.
- An explicit `feat(<title>)` / `fix(<title>)` is challenged when it violates the rule; the
  operator's word still wins.
- The **PR body** is named as the home for mechanism, module, and root cause — never
  customer-visible — so the displaced detail has somewhere to go.

**Two process capabilities leave `openspec/specs/`.**

- **REMOVED `ship-command`.** `ship.md` and `ship-wait.ts` are the contract and carry their own
  rationale. Verified: every claim in the spec — the four preconditions and their read-only rule,
  the create/auto-merge sequence, the FIFO queue, the run-time required-set derivation and its
  strict fallback, `--keep-workspace` — is already stated in one of them.
- **REMOVED `branch-protection`.** `.github/rulesets/main.json` states the six required contexts,
  rebase-only, linear history, PR-gated, and the deletion/force-push bans mechanically. Its two
  pieces of prose rationale are already in the artifacts: `appstore.yml:5-6` and
  `check-label.yml:13-14` each explain why their own check is safe to require, and
  `ship-wait.ts:395-401` documents the create-or-update apply, the no-op without the directory,
  and the operator's admin `gh`.
- **KEPT `changelog-labels`**, under the same criterion: its contract spans three artifacts
  (`release_notes.py`, `check-label.yml`, a ruleset entry), a drift between them is invisible until
  a release, and four release specs depend on it.

**The criterion is recorded** in `openspec/config.yaml`'s `context:` block — the hand-authored
surface injected into every agent in this root, which `openspec update` cannot rewrite: *a spec
exists where the contract is spread across artifacts and drift is invisible; where a single
committed artifact IS the contract and carries its own rationale, a spec is a second copy.*

**Five citations are re-pointed** from the removed capabilities to the artifacts that state the
fact: four specs that cite `capability branch-protection`, and `changelog-labels`' citation of
`capability ship-command`.

Not breaking: no product behavior changes, and no released bits are affected. The unreleased range
`v0.3..main` publishes three bullets, all already correct — there is no retroactive work.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ship-command`: **removed in full.** The capability ceases to exist; `ship.md` and
  `ship-wait.ts` become its only statement.
- `branch-protection`: **removed in full.** The capability ceases to exist;
  `.github/rulesets/main.json` and the workflows that explain their own required checks become its
  only statement.
- `changelog-labels`: the label-gate requirement's citation of `capability branch-protection`
  becomes a citation of `.github/rulesets/main.json`, and the Purpose stops naming
  `capability ship-command`.
- `ios-ci`: the merge-gate requirement stops citing `capability branch-protection`.
- `ios-testflight-delivery`: the delivery-decoupling requirement stops citing
  `capability branch-protection`.
- `ios-appstore-metadata`: the validation-gate requirement stops citing
  `capability branch-protection`.

## Impact

- `.claude/commands/ship.md` — steps 7.2 and 7.3 rewritten; no change to `ship-wait.ts`.
- `openspec/specs/ship-command/`, `openspec/specs/branch-protection/` — deleted.
- `openspec/specs/changelog-labels/spec.md`, `ios-ci/spec.md`, `ios-testflight-delivery/spec.md`,
  `ios-appstore-metadata/spec.md` — citation edits only.
- `openspec/config.yaml` — one paragraph added to `context:`.
- `.github/scripts/release_notes.py:63` — comment stops naming `capability ship-command`.
- No Kotlin, no Swift, no workflow logic. No module in the CLAUDE.md map is touched, so no
  architecture guard, diagram, or test is affected.
- `CLAUDE.md:244` names `changelog-labels`, which is kept — no edit needed.
