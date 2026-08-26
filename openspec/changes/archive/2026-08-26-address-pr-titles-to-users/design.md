## Context

`/ship` proposes a pull-request title, the operator picks one, and for a `feature`/`bugfix` PR that
string travels untouched into the App Store listing. `release_notes.py` applies exactly three
transforms — strip `^(feat|fix|chore|…)(\(scope\))?!?:\s*`, strip a leading `Fix|Fixes|Fixed\s`,
capitalize the first letter — and emits the remainder as a `- ` bullet under **New** or **Fixed**.
There is no second field and no editorial pass: the changelog-labels contract states outright that
the PR's title "is the one statement of what it changed".

Nothing on the path tells the author that. `ship.md` step 7.2 says only "analyze the changes and
propose 3 concise PR title options", and the surrounding repository style — deliberately expressive,
mechanism-first commit subjects like *"one instant, one shape, in `device_records.updated_at`"* — is
exactly the wrong register for the one audience the title actually has. The result is measurable in
what shipped: #202 → *"Take the photo-library import out from under the lock."*, #200 → *"Hold both
background-session completion handlers in a bounded receipt."*, #182 kept a `(join-event)` scope,
#151 shipped as *"Download page."*, #133 as *"UI refresh."*

Two forces shape the fix. First, the operator is already in the loop — `AskUserQuestion` runs on
every user-facing ship — so the cheapest place to catch a leak is the moment of approval, and the
cheapest way is to show the transformed string rather than the untransformed one. Second, the rule
has to live somewhere durable, which raises the second half of this change: `/ship`'s spec.

`openspec/specs/ship-command/spec.md` restates `ship.md` and `ship-wait.ts` in SHALL form. A rescue
pass over its four requirements found **zero** claims that live only there. `branch-protection` is
the same shape against `.github/rulesets/main.json` — also zero orphans, including the two rationale
paragraphs a JSON file could not carry, which turn out to be stated by `appstore.yml:5-6`,
`check-label.yml:13-14`, and `ship-wait.ts:395-401`. Both are second copies of a contract, and a
second copy of a contract is a drift source with no gate behind it.

## Goals / Non-Goals

**Goals:**

- A `feature`/`bugfix` PR title that a SnapSync user can read and act on, written knowingly as the
  App Store bullet it becomes.
- The customer-visible rendering shown to the operator before approval, not after promote.
- A stated, reusable criterion for when a spec earns its place, so "is this dev tooling?" is
  answered once rather than per capability.
- No dangling capability reference anywhere in `openspec/specs/` after the removals.

**Non-Goals:**

- Changing commit subjects. They address the repository, which is the correct audience for them,
  and `release_notes.py` never reads them (the unit is deliberately the PR, not the commit).
- Changing `internal` PR titles. They are excluded from the changelog by construction.
- Changing `release_notes.py`, `check-label.yml`, or the label vocabulary. The derivation is
  correct; its input is what was wrong.
- Retroactive retitling. Every offender is behind a published `vX.Y` tag; `v0.3..main` renders
  three bullets that are all already correct.
- Adding a mechanical gate on title prose (see Decisions).
- Removing any further process capability. `ci-build`, `ios-ci`, `architecture-guards`, the harness
  specs and the rest are out of scope; the criterion is recorded, not applied in a sweep.

## Decisions

### D1 — The rule lives in `ship.md` as a rubric, with no CI gate

A required check that scans a title for banned words was considered and rejected. The vocabulary
that leaks (*album, sync, download, extension, event*) overlaps heavily with the vocabulary a
customer legitimately reads — `album` and `sync` are words the app itself puts on screen — so a
wordlist gate false-positives on correct titles and, worse, teaches authors to route around it.
The failure being fixed is an *audience* mistake, which is judgment; the correct instruments are a
concrete rubric and an operator who can see the consequence.

**Alternative considered:** a `title-check` required status check alongside `check-label`. Rejected
above. **Alternative considered:** a separate release-note field carried in the PR body or a commit
trailer, with `release_notes.py` preferring it. Rejected: it splits one statement into two that can
disagree, and contradicts the standing contract that the title is the statement.

### D2 — The rubric is anchored to the app's own vocabulary, not to a ban-list alone

A ban-list is a record of yesterday's leaks; tomorrow's word is not on it. So the operative rule is
positive and testable — *every noun must be one a SnapSync user has seen in the app or the App
Store listing* — with the app's actual strings as the anchor (*event, photos, album, join, leave,
share, sync, invite, QR code, phone, event settings, event dates*). The observed leaks are listed
too, but as examples under the rule rather than as the rule.

The same clause forbids vagueness, because *"Download page"* and *"take the import out from under
the lock"* are one failure wearing two faces: both describe the change to the repository rather
than the change to the user. The rubric therefore demands an **observable outcome** — what the user
can now do, or what no longer happens to them.

### D3 — `bug` titles state the symptom, gone

Under a heading that already says **Fixed**, *"Photos no longer arrive twice in an event"* reads
correctly and *"Corrected the dedup key"* does not. This also composes with the renderer, which
strips a leading `Fix`/`Fixed` precisely so a title written for the repository does not read as
*"Fix fix …"* to a customer. Choosing the symptom voice makes that strip a no-op rather than a
rescue.

### D4 — The operator approves the rendered line, not the raw title

`AskUserQuestion`'s `preview` field renders each option as it will appear — heading, bullet,
transforms applied. This is display only: no script, no artifact, no gate. It is the single
highest-value element of the change, because it moves the discovery of a leak from *promote time*
(where a single-shot promote makes it a manual console upload to correct) to *approval time*
(where it costs one keystroke).

An explicit `feat(<title>)` is challenged when it violates the rubric rather than passed through
silently. The round-trip is the point: the explicit form exists to skip *generation*, not review,
and the operator's answer still wins.

### D5 — When the symptom cannot be derived, ask

The honest failure mode of D2/D3 is a change whose user-visible symptom takes real thought — #200's
stalled uploads were a genuine user symptom hidden behind "completion handlers". Three responses
were possible: propose a technical title (the status quo, which is the bug), infer a symptom
confidently (which risks publishing a false claim to customers — strictly worse than jargon), or
ask. `/ship` asks: *"What did the user experience before this fix?"* This is the same discipline as
the repository's standing law that absence is never silent — "I could not tell" and "there is no
symptom" are different answers with different consequences, and collapsing them is what produced
#200's title.

### D6 — Remove `ship-command` and `branch-protection`; keep `changelog-labels`

The criterion: **a spec exists where the contract is spread across artifacts and drift is
invisible. Where a single committed artifact IS the contract and carries its own rationale, the
spec is a second copy.**

Applied:

| capability | artifacts holding the contract | verdict |
| --- | --- | --- |
| `ship-command` | `ship.md` + `ship-wait.ts`, both self-documenting | remove |
| `branch-protection` | `.github/rulesets/main.json`, applied mechanically | remove |
| `changelog-labels` | `release_notes.py` + `check-label.yml` + a ruleset entry | **keep** |
| `openspec-archive-command` | `.claude/` is *regenerated* — the rule cannot live there | **keep** |

`changelog-labels` is the criterion's own counter-example and the reason it is worth stating: rename
a label in `check-label.yml` without touching `release_notes.py`'s `CATEGORIES` and the changelog
silently loses a heading, discovered at a release that already shipped. That is drift across
artifacts, which is what a spec is for. Four release specs depend on it and `CLAUDE.md:244` sends
operators to it.

`openspec-archive-command` is the sharper case: it *is* a `.claude/` command spec, yet it stays,
because `openspec update` regenerates `.claude/` and has already silently deleted a rule
hand-patched into it. Its contract cannot live in its own artifact. That is the criterion
discriminating, not an exception to it.

**Alternative considered:** keep `ship-command` and add a title requirement to it. Rejected —
it doubles the statement of a rule that has exactly one implementation and one reader. **Alternative
considered:** the broader line *"specs cover the product; build-and-release is tooling"*. Rejected:
it is cleaner to state but sweeps ~14 of 59 capabilities including `changelog-labels`, whose output
customers read, and `architecture-guards`, whose contract spans a spec, a digest in CLAUDE.md, and
a test that keeps the two in sync — a textbook cross-artifact contract.

### D7 — The criterion is recorded in `openspec/config.yaml`, not in `.claude/`

`.claude/opsx` is generated; a rule patched there is deleted by the next `openspec update`, with a
green run and no notice — measured, and the reason the archive gates already live in
`config.yaml`'s `context:` block. A rule about what earns a spec belongs beside the rule about what
an archive owes one.

### D8 — Citations are re-pointed at artifacts, not absorbed as requirements

Five live citations name the removed capabilities. Each is *attributive rationale* — the reason a
check is or is not required — not a dependency, and the enforcement in every case is a required
check the citing spec already names. So each becomes a reference to the artifact that states the
fact (`.github/rulesets/main.json`, the `/ship` command). The alternative — absorbing "the
PR-opening tool SHALL apply exactly one label" into `changelog-labels` as its own requirement —
was rejected: it writes a requirement about an agent command into a product spec, which is the
thing this removal exists to stop.

## Risks / Trade-offs

- **A rubric is not a gate; a rushed operator can still approve a leaking title.** → The rendered
  preview makes the leak the most visible thing in the question, and the correction is one
  `gh pr edit` at any time before the release derives notes. Accepted deliberately over a
  false-positive-prone wordlist gate (D1).
- **The rubric could over-correct into marketing vagueness** — trading jargon for "Various
  improvements". → D2 forbids vagueness in the same clause that forbids jargon, and names #151/#133
  as the failing examples.
- **A removed spec's rationale could be lost.** → The rescue pass verified zero orphans line by
  line, and the archived decision records under `openspec/changes/archive/` are never rewritten, so
  the reasoning survives regardless.
- **The criterion invites a sweep nobody asked for.** → It is recorded as a test to apply when a
  capability is next touched, and this change explicitly applies it to two capabilities only
  (Non-Goals).
- **`openspec validate --specs --strict` will not catch a citation missed here** — it checks
  structure, not truth. → The re-pointing is enumerated as tasks against a `grep` for both
  capability names, and the same grep is the verification step.
