## 1. The changelog contract

- [x] 1.1 Add `.github/release.yml`: exclude the `internal` label; declare category `New` for
      `enhancement` and `Fixed` for `bug`; no catch-all category.
- [x] 1.2 Add `.github/workflows/check-label.yml` — `on: pull_request`
      (`opened`/`labeled`/`unlabeled`/`synchronize`), job display name `check-label`, reading the PR's
      **live** labels via `gh pr view` rather than the event payload, failing with the three label
      names and commenting the hint on failure.
- [x] 1.3 Add the `check-label` context to `.github/rulesets/main.json`
      (`integration_id` 15368, like its siblings).

## 2. The derivation

- [x] 2.1 Add `.github/scripts/release_notes.py`: given the repo, target sha and optional previous
      tag, call `POST /repos/{repo}/releases/generate-notes`, then render plain text — headings
      verbatim, one `- ` bullet per PR, `by @user in <url>` dropped, `type(scope):` prefix and a
      leading `Fix`/`Fixes`/`Fixed` stripped, first letter capitalized.
- [x] 2.2 Make it read `.github/release.yml` for the declared category titles and **fail** on any
      heading **carrying items** outside that set (the un-configured `What's Changed` fallback must
      never render).
- [x] 2.3 Emit the committed fallback sentence when no heading has any item, and fail with a clear
      message when the rendered text exceeds 4000 characters.
- [x] 2.4 Verify empirically which ref `generate-notes` reads `.github/release.yml` from, and record
      the answer in `design.md` under the risk that names it. **Answer: the `target_commitish`** — the
      generation states it in a leading HTML comment, and the same range renders from this branch's
      head while being refused at build 542's origin commit. Also measured, and load-bearing for the
      guard: a configured generation nests each category as `### <title>` under an item-less
      `## What's Changed` wrapper, while an unconfigured one lists every PR directly under it.

## 3. Applying the notes at release time

- [x] 3.1 Add `.github/scripts/asc_release_notes_apply.sh` — resolve the version id by
      `versionString` exactly as `asc_review_details_apply.sh` does, find-or-create the `en-US`
      localization, then
      `asc localizations update --version <id> --locale en-US --whats-new "$notes"`.
- [x] 3.2 Wire a generate step into `ios-appstore-promote.yml` after the origin-commit resolve and
      **before** the attach: derive the previous tag with
      `git describe --tags --abbrev=0 --match 'v[0-9]*' "$ORIGIN_SHA"`, write the notes to a file,
      and echo them to `$GITHUB_STEP_SUMMARY`.
- [x] 3.3 Wire the apply step in after the attach and before the submit gate, unconditional on the
      `submit` input, alongside the review-details step.

## 4. Specs and docs

- [x] 4.1 Sync the deltas into `openspec/specs/` (new `changelog-labels`; modified
      `ios-appstore-release`, `ios-appstore-metadata`, `branch-protection`) and correct
      `ios-appstore-metadata`'s Purpose, which still promised "and later 'what's new'" as committed
      text.
- [x] 4.2 Update `CLAUDE.md`'s App Store release section: the promote now writes the release notes
      itself, derived from the labelled PRs since the previous tag; a PR must carry a changelog label;
      and `asc review doctor` is not the whole submit preflight.
- [x] 4.3 Validate: `openspec validate --specs --strict`, `asc metadata validate --dir metadata` (the
      committed listing must still carry no `whatsNew`), and the renderer against the live API for
      both the configured and unconfigured range.

Releasing 0.2 itself is the Migration Plan in `design.md`, not implementation work: promote a build
whose origin commit carries `.github/release.yml` (build 542 cannot be promoted — see D5), then
re-dispatch with `submit=true`.
