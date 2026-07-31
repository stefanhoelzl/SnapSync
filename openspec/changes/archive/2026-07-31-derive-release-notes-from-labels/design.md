## Context

`ios-appstore-promote.yml` promotes an already-uploaded build: it derives the store version from the
build, finds-or-creates the version record, attaches the build, uploads the screenshots, applies the
App Review details, optionally submits, and tags the build's origin commit last. Everything the
listing shows is repo-owned and applied by CI — except `whatsNew`, which no step writes and no file
carries. Apple requires it on every update, so the first real update (0.2, over the shipped 0.1)
failed at `asc review submit` with `en-US: whatsNew` missing. Two facts make this cheap to close:

- **`/ship` already labels every PR** `enhancement` / `bug` / `internal` (capability `ship-command`),
  and the discipline has held — of the 29 PRs merged between `v0.1` and build 542's origin commit,
  6 carry `enhancement`/`bug` and 23 carry `internal`. The curation a changelog needs already exists.
- **The `vX.Y` tag is already the release receipt** and is already created on the build's origin
  commit, so consecutive releases delimit an exact commit range with no extra bookkeeping.

The constraint that shapes the rendering: this text is read by **App Store customers**, not by
developers. It is plain text in a 4000-character field, with no markdown rendering, no links, and no
issue numbers that mean anything to the reader.

## Goals / Non-Goals

**Goals:**

- A promoted version carries release notes without anyone writing them at release time.
- One declarative place decides which changes a user is told about, and under which heading.
- An omission is loud: a PR that would silently vanish from the changelog fails something.
- Notes are computed before any App Store Connect mutation, so a bad range or an over-long result
  costs nothing.

**Non-Goals:**

- Publishing a GitHub Release. The generated notes have one consumer, the App Store listing.
- Localized notes. The listing has a single `en-US` localization; translation is a separate change.
- A committed `CHANGELOG.md`. The tags plus the labeled PRs already are the history.
- Rewriting PR titles into marketing copy. The text ships as the author wrote it, minus the
  conventional-commit prefix.

## Decisions

### D1 — The unit is the labeled pull request, not the commit

The changelog is built from **PRs and their labels**, never from commit subjects. Measured on the
`v0.1..f936b9fc` range: 66 commits, of which 38 carry a `feat:`/`fix:` prefix — including
`feat(ci)`, `feat(site)`, `feat(backend)`, `feat(dev)`, `fix(api)`,
`fix(ios-appstore-promote)`, plus one subject appearing twice (a rebased fixup). Filtering by prefix
or by scope would ship CI and website work to App Store readers and would need a scope allowlist
that goes stale silently. The **label already answers the only question that matters** — is this
something a user of the app experiences? — it is applied by `/ship` at the moment the author knows
the answer, and it is one per PR rather than one per commit.

*Alternative rejected:* conventional-commit prefixes with a user-facing scope allowlist. It infers
the answer from a field that does not encode it, and a new scope defaults to the wrong side quietly.

### D2 — GitHub's release-notes generator, with `.github/release.yml` as the one mapping

The promote calls `POST /repos/{owner}/{repo}/releases/generate-notes` for the range, and
`.github/release.yml` is the single declarative source of `label → heading` and of the `internal`
exclusion. This is the same mechanism the operator's other repository uses, and it means the label
vocabulary is mapped in exactly one committed file rather than in a script that can drift from it.

It also removes work we would otherwise have to reimplement: merges here are **rebase-only**, so
there are no merge commits to read a PR number out of, and mapping 66 rebased commits back to their
PRs is GitHub's job — verified: it resolved all 29 PRs in the range.

*Alternative rejected:* enumerate the range's PRs ourselves (`commits/{sha}/pulls` per commit) and
group them in a script. ~66 API calls, a re-implementation of commit→PR association for rebased
commits, and a second place where labels map to headings.

### D3 — The range is "nearest ancestor `vX.Y` tag → the build's origin commit"

Both endpoints already exist in the run: `ORIGIN_SHA` is resolved for tagging, and the previous
release is `git describe --tags --abbrev=0 --match 'v[0-9]*' "$ORIGIN_SHA"`. Using the **nearest
ancestor** tag rather than the newest tag in the repo keeps an out-of-order promote honest — it
reports what that build actually added over the release before it. A build with no ancestor tag is
the first release, where Apple does not require notes at all; the generator is then given no
previous tag and the result still renders.

### D4 — Render plain text; strip the prefix; know nothing about labels

The renderer emits each heading from the generated body verbatim, then one `- ` bullet per PR, with
`by @user in <url>` dropped, the conventional-commit `type(scope):` prefix stripped, a leading
`Fix`/`Fixes`/`Fixed` word dropped, and the first letter capitalized. It deliberately holds **no
label knowledge and no heading allowlist for output** — headings come from `.github/release.yml` via
the generator, so `New` and `Fixed` are named in one file only.

The leading-`Fix` strip is applied to every item rather than only under a bug heading, which is what
keeps the renderer heading-agnostic; a PR titled "Fix …" and labeled `enhancement` is a mislabeled
PR, not a case to preserve.

### D5 — Guard: any heading carrying items must be one the committed config declares

The generator falls back to a **single ungrouped `What's Changed` section containing every PR** when
it finds no configuration — which would put 23 `internal` PRs into the App Store listing. So the
renderer reads `.github/release.yml` for the set of titles it declares and **fails** when items appear
under any other heading. This turns the one dangerous failure mode (config not found, or renamed) into
a red run before any mutation, and it is the only reason the script reads the config at all.

The guard is on headings that **carry items**, not on headings, because of the shape the generator
actually emits — measured against the live API on 2026-07-31:

- **With** a configuration, the categories are wrapped: `## What's Changed` carries **no items of its
  own** and each category is rendered beneath it as `### <title>`.
- **Without** one, every PR is listed **directly** under `## What's Changed`.

So "an unrecognized heading carrying items" is exactly, and only, the unconfigured case, while an
empty unrecognized wrapper is what a purely-`internal` range looks like (D8's fallback). Both levels
are therefore read as headings, and both branches are pinned by a check against the live API: the
range at this branch's head renders the six-item changelog, and the same range targeted at build 542's
origin commit — which predates the config — is refused.

### D6 — Write with `asc localizations update --whats-new`, resolving the version id by string

`whatsNew` is inside the metadata tool's version-localization scope, so two paths existed: a scratch
`metadata/version/<v>/en-US.json` tree pushed with `asc metadata push` (the mechanism
`asc_metadata_apply.sh` already uses), or `asc localizations update --version <id> --locale en-US
--whats-new`. The direct update wins: one call, no temporary tree, and nothing that could be
mistaken for a change to the committed listing. The version id is resolved from `versionString`
exactly as `asc_review_details_apply.sh` does — one object, so the id and the version string
provably came from the same record.

`en-US` only, named explicitly rather than looped over the committed locale set: pushing generated
English into a future `de-DE` localization would be worse than the preflight error that names it
missing.

### D7 — Generate before any mutation; apply after the attach; on every run

Generation (API call, render, length check) sits with the other guards, **before** the first App
Store Connect write, so a broken range or an over-long body costs nothing. Application sits after the
attach — the record must exist — and before the submit gate, alongside the review details, and runs
**whether or not `submit` was requested**, so a promote-only run leaves the version submit-ready.
The rendered text is echoed to the job summary: the operator sees exactly what shipped.

### D8 — An all-internal release gets a committed fallback sentence

Apple rejects an empty `whatsNew`, and a release consisting only of `internal` PRs is a real, honest
outcome (a build promoted for a dependency or infrastructure change). A committed constant is used
rather than failing the release, because the alternative — blocking a legitimate release on prose —
is worse, and rather than the industry's "bug fixes and performance improvements" fiction it says
what actually happened.

### D9 — The label gate is required, not advisory

An unlabeled PR is **silently dropped** by the generator: no heading, no warning, nothing in the run
to notice. The promote cannot detect it either without re-enumerating the range (D2). So the omission
is caught where it is cheap — on the PR — by a `check-label` workflow that becomes a required status
check. It is safe to require for the same reason `appstore-metadata-validate` is: it runs on
`pull_request`, so it always posts on a PR's head commit and can never freeze merges. And it costs
nothing in practice, because `/ship` already applies a label.

*Alternative rejected:* a catch-all `labels: ["*"]` category. It converts the silent drop into
untranslated engineering titles inside the customer-facing listing, which is worse than not shipping
them.

## Risks / Trade-offs

- **The generator's markdown shape is not a documented contract** → the renderer fails loud rather
  than degrading: an unrecognized heading is refused (D5), and a body whose item lines do not parse
  is an error, not an empty field. Downstream, `asc review submit`'s own preflight refuses an empty
  `whatsNew`, so a silent regression cannot reach the store.
- **PR titles are now customer-facing copy** → only for `enhancement`/`bug` PRs, whose titles are
  already written as user-visible statements ("events now have an end date"); an `internal` PR's
  title is never read by a customer. The trade-off is accepted deliberately: the alternative is a
  second hand-written field, which is the thing that broke.
- **`generate-notes` reads `.github/release.yml` from the `target_commitish`, not from the default
  branch** — measured 2026-07-31, and stated by the generator itself in a leading HTML comment
  (`Release notes generated using configuration in .github/release.yml at <sha>`). A build whose
  origin commit predates this change therefore produces the ungrouped fallback and is **refused** by
  D5's guard. This is a real constraint, not a transient one: it applies to every future promote of a
  build built before a change to the categories. Consequence for shipping 0.2 in the migration below —
  build 542 cannot be promoted, and a build from after this merge must be used instead.
- **A hand-edited `whatsNew` in the ASC console is overwritten** → same posture as the review details
  and the screenshots: the repo wins on every run.

## Migration Plan

1. Merge this change. `check-label` starts gating PRs (every existing labeled PR is unaffected);
   `.github/release.yml` starts governing GitHub's "Generate release notes" button too.
2. Release 0.2 by promoting a build whose origin commit **contains** `.github/release.yml` — the
   version stays `0.2` until `v0.2` is tagged, so the next `main` build after this merge carries it.
   Build 542, whose origin commit predates the config, **cannot** be promoted (the risk above is
   measured, not hypothetical): its generation is the ungrouped fallback and the guard refuses it.
3. The 0.2 version record already exists from the failed run, with the build attached, screenshots
   uploaded, and review details applied; the promote is idempotent over all of it, and no `v0.2` tag
   was created, so the tag-absent guard passes.

## Open Questions

None.
