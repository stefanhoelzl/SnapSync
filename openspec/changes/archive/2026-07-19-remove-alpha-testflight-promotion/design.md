## Context

`ios.yml` today has four jobs. Two are the merge gates (`ios-build`, `ios-test`). `ios-deliver` (main-only, `needs: [ios-build, ios-test]`) re-signs the archive and uploads it to TestFlight. `ios-promote` (main-only, `needs: ios-deliver`) then adds that build to the **`alpha`** external group — a public open-enrollment link (`https://testflight.apple.com/join/pvqgV7Uz`) anyone may tap. Promotion runs on Ubuntu against the App Store Connect REST API via `.github/scripts/testflight_promote.py` (resolve → set release note → submit-to-testflight → silence → add-build).

The capability `ios-testflight-delivery` frames this as "`main` is the public alpha channel." The project is now going **App-Store-only**: distribution to real users is the dispatch-driven `ios-release.yml` (capability `ios-appstore-release`). The automatic public promotion is therefore unwanted.

Constraints:
- `ios-promote` is **not** a required check (`.github/rulesets/main.json` requires only `build`, `ios-build`, `ios-test`), so removing it carries **no merge-freeze risk** and needs no ruleset edit.
- Specs are the contract of record and must not lie; the whole "public alpha channel" purpose becomes false.
- No Kotlin/module code is involved — this is CI + docs + spec surgery only.

## Goals / Non-Goals

**Goals:**
- Stop all automatic distribution to the public `alpha` external group by deleting the `ios-promote` job.
- Delete the now-dead `testflight_promote.py`.
- Keep `ios-build` / `ios-test` / `ios-deliver` exactly as they are (uploads continue to the internal `development` group).
- Leave the spec, `CLAUDE.md`, and cross-referencing workflow comments truthful.

**Non-Goals:**
- Removing `ios-deliver` or the internal TestFlight upload (explicitly retained).
- Any App Store Connect portal action — the public join link stays live and existing alpha testers freeze on their last-promoted build. This change only stops CI from feeding the group.
- Preserving notification silence for the internal uploads (accepted: internal-group testers may again be notified per build).
- Any change to `ios-release.yml` behavior (only its stale comments are corrected).

## Decisions

**D1 — Delete the job, don't gate it off.** `ios-promote` is removed outright rather than kept behind an `if:` or a dispatch input. Rationale: the motivation is a durable App-Store-only direction, not a pause; a dormant job plus its script is dead weight, and git history is the revival path if alpha ever returns. Alternative considered (keep it dispatch-only) rejected — it would leave the whole promotion spec and script alive to rot.

**D2 — Keep `ios-deliver`.** Uploading to the internal `development` group continues on every `main` merge. Rationale: the user chose the literal-scope reading; internal uploads are a harmless smoke/record trail. Accepted trade-off: those builds accumulate unseen (the exact pre-promotion state the old spec called out) — acceptable because nothing external consumes them.

**D3 — Preserve the tag-exclusion contract separately.** The rule "tag refs are excluded from `build.yml`/`ios.yml` push triggers" was embedded inside the removed *Every main build is promoted, unfiltered* requirement but is **not** promotion-specific — it still keeps a `vX.Y` tag from firing a redundant `main` build/delivery. It is re-expressed as a new standalone requirement *Tag refs fire only the release workflow*. The actual triggers already encode it (`ios.yml` lists `branches: ["**"]`, which excludes tags); no workflow change is needed for it.

**D4 — Purpose rewrite lives in the main spec, not the delta.** OpenSpec deltas carry Requirement operations only; the `## Purpose` is edited directly in `openspec/specs/ios-testflight-delivery/spec.md` during apply, reframing "public alpha channel / two jobs" to "`main` uploads a signed build to internal TestFlight; distribution is App-Store-only."

**D5 — Honest touch-ups on retained requirements.** *Monotonic build numbers* and *Distribution builds use production APNs* said "`main`/alpha build"; with promotion gone those builds are not "alpha", so the delta restates each whole requirement with "`main`" only. Both are copied verbatim from the current spec and edited to change only that token, per the archive gate that a MODIFIED delta must not silently drop detail.

## Risks / Trade-offs

- [The `MARKETING_VERSION`-bump Beta-App-Review trap disappears silently] → It was triggered by `ios-promote`'s `submit-to-testflight`; `ios-deliver` only uploads. Removing the corresponding `CLAUDE.md` warning is correct, not an omission — call it out in the commit so nobody re-adds it thinking it's still live.
- [No automated external distribution remains] → Intended. The only path to real users is `gh workflow run ios-release.yml`; `CLAUDE.md` states this plainly so it is not mistaken for a regression.
- [Stale cross-references] → `ios-release.yml` guard comments and `appstore_release.py` reference `ios-promote`/`testflight_promote.py`. They are non-functional but would become false; they are corrected in the same PR so the tree stays truthful.
- [`openspec validate --specs --strict` checks structure, not truth] → A green validate does not prove the spec matches the deleted job; the diff of the delta against the current main spec is the real check.

## Migration Plan

1. Delete the `ios-promote` job block and reword the file-level comment in `ios.yml` (four jobs → three).
2. Delete `.github/scripts/testflight_promote.py`.
3. Correct stale comments in `ios-release.yml` and `appstore_release.py`.
4. Apply the spec delta and rewrite the main spec's Purpose.
5. Rewrite the `CLAUDE.md` "`main` is the public alpha channel" section.
6. `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`, then branch → PR → `/ship`.

Rollback: revert the PR — `git` restores the job and script byte-for-byte; App Store Connect state was never touched.

## Open Questions

None — all scope, notification, portal, and process decisions were settled in the interview.
