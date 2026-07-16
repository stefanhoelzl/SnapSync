## Context

`asc validate --app 6781692480 --version 1.0` gates submission. At the start of this change it reported
4 blocking + 2 warnings; the copyright blocker was then **set by hand in the console**, taking it to 3
blocking + 2 warnings:

| | check | in scope |
| --- | --- | --- |
| blocking | `review_details.missing` · `build.required.missing` · `availability.missing` | no — a follow-up |
| warning | `metadata.required.subtitle` · `metadata.recommended.privacy_policy_url` | **yes** |
| info | App Privacy publish state (console-only, not API-verifiable) | no |

This change closes the two warnings and prevents the copyright blocker from recurring on a future
version. Everything else stays with the follow-up.

### What this change is *not*, and why that took measuring

This work was briefed on a premise that is **false**: that `metadata/app-info/en-US.json` is committed
but never applied, because `asc metadata push --include` accepts only `localizations` and app-info needs
the separate `--app-info <APP_INFO_ID>` flag. That premise implied a script change and an appInfo id to
either hardcode or resolve. **None of it is real.** Each finding below was measured against the live API
or the pinned binary, and each one removed work:

- **`--include localizations` already covers app-info.** A `--dry-run` of the *exact* command CI runs
  today — no `--app-info` flag — self-resolved `appInfoId 161d0599-0891-4dca-9579-27a3d6957918` and
  planned both writes with `"scope": "app-info"` and
  `"apiCalls": [{"operation": "update_localization", "scope": "app-info", "count": 1}]`. `--app-info` is
  documented as an *"optional override for apps with multiple app-infos"* — a disambiguator, not a
  requirement. `metadata/app-info/en-US.json` has been applied on every `main` push since it was
  committed (`4f02fb6`); it no-ops only because it holds one key, and an omitted field is a no-op by
  contract. **Consequence: the appInfo id appears nowhere in the repo, hardcoded or resolved, and the
  existing "resolved at run time" requirement is satisfied by the tool itself.**
- **`--include` really does accept only `localizations`** (every other value errors
  `--include supports only "localizations"`). True, but *not* the reason app-info was unapplied — the
  scope name covers both app-info and version localizations. This is the trap: the flag's name suggests
  a narrower meaning than it has, and the docs for `metadata pull` are where it is spelled out
  ("Phase 1 supports localization metadata for app-info and app-store versions").
- **The absent scenario is what let the wrong premise stand.** `ios-appstore-metadata` already declares
  the repo owns "the app-info fields, one JSON file per locale", but no scenario asserted them reaching
  App Store Connect. A contract with no scenario reads as aspirational, so this change adds one.

## Goals / Non-Goals

**Goals:**

- Clear `metadata.required.subtitle` and `metadata.recommended.privacy_policy_url` on the editable
  version, through the existing declarative pipeline, with no new machinery.
- Make a copyright blocker impossible to re-open on a version record this repo creates.
- Pin the app-info behavior with a scenario, so the next reader does not re-derive the above.

**Non-Goals:**

- The 3 remaining blocking items, category, pricing/availability, review details, the App Privacy
  questionnaire, and Submit — all owned by a follow-up.
- Repairing version `1.0`'s copyright (already correct by hand) or any pre-existing version record.
- Changing when `appstore-metadata-apply` runs, or what it writes for the version localizations.
- Additional locales. `en-US` is the only one.

## Decisions

### 1. Subtitle and privacyPolicyUrl ship as a **data-only** commit

No script, workflow, or spec machinery changes — the pipeline already does this. Both values are gated
by the existing required check, verified offline against the pinned binary:

- `subtitle: "Group photos, shared instantly"` — **30/30 chars**. Verified that 31 chars fails the gate
  with `subtitle exceeds 30 characters` (severity `error`, `valid: false`), and that this copy passes.
  Chosen because the subtitle is **search-indexed** and `keywords` already carries
  group/shared/event/trip/wedding/party — a subtitle restating those buys no index coverage. This copy
  adds `instantly`. It sits exactly at the limit, so any future edit must re-count (the gate enforces it).
- `privacyPolicyUrl: "https://snapsync.stho.net/#privacy"` — the page `marketing-site` already serves at
  `GET /`. Verified the fragment URL passes validation.

*Alternative rejected:* adding `--app-info "161d0599-…"` to `asc_metadata_apply.sh`. It is unnecessary
(the tool resolves it), and it would hardcode a stored id in the one script whose spec forbids exactly
that pattern for version/localization ids.

### 2. Copyright is set at **version creation**, not by the metadata apply

`appstore_release.py`'s `_find_or_create_version` gains `"copyright": COPYRIGHT` in its create payload.
It already owns version records, already speaks raw REST (`pyjwt`, `requests`), and already reuses an
existing record by early return — so `1.0` is untouched and the change only affects records born after
it. The value is a module constant: `COPYRIGHT = "2026 Stefan Hoelzl"`. Apple's format is `YYYY Name`
where the year is that of **first publication**, so it must **not** roll with the calendar year.

*Alternatives rejected, in the order they were eliminated:*

- **Put `copyright` in the metadata files.** Impossible. The schema is closed and rejects it in **both**
  canonical files (`json: unknown field "copyright"`), and `asc metadata pull` scaffolds exactly two —
  `version/<v>/<locale>.json` and `app-info/<locale>.json` — both localizations, neither carrying
  copyright (`grep -ri copyright` over a fresh pull: nothing). Copyright is a version **attribute**, not
  a localization. Worse than merely not working: an unknown key there fails `appstore-metadata-validate`,
  a **required** check, and **freezes every merge** — the same trap that keeps screenshot headlines in a
  file the tool does not decode.
- **`asc versions update --copyright` in `asc_metadata_apply.sh`, diff-then-write.** Impossible.
  Copyright is **not readable** through the tool: `versions view` returns only
  `{id, versionString, platform, state}`, and `versions list` attributes are
  `{platform, versionString, appStoreState, appVersionState, createdDate, releaseType}`. Detecting drift
  would need hand-rolled ES256-signed JWT REST in bash — disproportionate for one field.
- **`asc versions update --copyright` unconditionally, every `main` push.** Possible, but it would be the
  pipeline's **only blind writer**: `metadata push` is diff-then-write and makes **zero** writes when
  nothing differs (`"apiCalls": null` on a dry-run against the committed files), so a docs-only merge
  currently sends Apple nothing. An unconditional PATCH would break that property for a field that
  changes ~never.
- **Path-filter the apply so the blind write is rare.** Rejected, and this is the decision most worth
  recording. It cannot be done in `appstore.yml`: `on: push:` filters are workflow-wide, so a `paths:`
  key there would skip `appstore-metadata-validate` — a required check, and **a skipped required check is
  never posted, freezing merges** (`appstore-screenshots.yml` documents exactly this, which is why the
  filter is safe *there*). Splitting the apply into its own filtered workflow would work mechanically but
  costs two properties: console drift in description/keywords would survive until someone touches
  `metadata/` (the spec forbids weakening the overwrite to "eventually"), and the red
  `metadata/version/<v> not found — re-scaffold` alarm on a new editable version would go quiet.
  Setting copyright at creation makes the whole trade-off moot: **zero** extra writes per merge, and the
  trigger keeps the guarantees it was designed for.

### 3. The two spec deltas

`ios-appstore-metadata` is **modified, not extended in scope** — the app-info scenario documents behavior
that already exists and always did, and the `subtitle` ≤ 30 limit is already enforced by the gate. It is
the spec catching up to the implementation, the reverse of the usual direction.

`ios-appstore-release` gains a genuinely new requirement, because setting copyright at creation is new
behavior in that capability. Copyright is version-scoped, so it belongs to the capability that owns
version records — not to the one that owns localizations.

## Verify on the first `main` run

Neither half of this change can be observed before the merge that enables it, so the acceptance check
lives here rather than in `tasks.md`:

1. **The two warnings clear.** After the merge, `appstore-metadata-apply` runs and applies the app-info
   fields. Re-run `asc validate --app 6781692480 --version 1.0` and confirm
   `metadata.required.subtitle` and `metadata.recommended.privacy_policy_url` are gone, and that
   `blocking` is **unchanged at 3** — the out-of-scope items. A pre-merge dry-run already proved the plan
   is two app-info `adds` with `updates: []` and a single app-info `update_localization`, so a surprise
   here means Apple rejected a value, not that the plan was wrong.
2. **If Apple rejects the fragment privacy URL** (`https://snapsync.stho.net/#privacy`) at push time, the
   apply concludes red and blocks nothing. Fallback: serve the policy at a fragment-free path in
   `marketing-site` and change the single value — no other part of this change is affected.
3. **The copyright edit stays unobserved even after this merge**, by design: it applies only to a version
   record *created* by a future `vX.Y` tag, and `1.0` already exists and is reused untouched. Its first
   observation is the next release tag, where `asc validate` on the new version should report no
   `legal.required.copyright`.

## Risks / Trade-offs

- **Apple may reject the fragment URL at push time.** Only offline validation was proven; the fragment is
  syntactically valid and validate accepts it, but Apple's own acceptance is unverified until the first
  real apply. → The apply is red-but-blocks-nothing by contract, so the blast radius is a visibly failed
  job on `main`. Fallback: serve the policy at a fragment-free path and change the one value.
- **Whether Apple inherits copyright on an API-created version is undocumented, and was untestable.** ASC
  allows only one editable version at a time, so `1.1` could not be created while `1.0` is editable. → The
  create-payload attribute makes the question moot rather than answering it: the field is set explicitly
  either way. If Apple *does* inherit, the attribute is redundant and harmless.
- **A version record created outside this repo** (by hand in the console, then released by tag) is reused
  untouched, so it carries whatever copyright the console gave it — possibly none. → Accepted:
  `asc validate` catches it before submit, which is exactly how the `1.0` gap surfaced, and Submit is a
  deliberate human step.
- **The subtitle sits exactly at 30 characters**, leaving no slack for an edit. → Accepted: the required
  check enforces the limit, so an overrun fails the merge rather than the storefront.
- **Copyright's value lives in a Python constant, not in `metadata/`**, so the listing's source of truth
  is split across two files. → Accepted as the least-bad option: the schema *cannot* hold it (above), and
  a constant in the script that creates the record is still version-controlled, reviewable, and adjacent
  to its only use.
