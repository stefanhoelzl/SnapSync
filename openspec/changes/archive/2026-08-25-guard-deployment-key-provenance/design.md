## Context

`scripts/resolve-deployment.py` renders `iosApp/Configuration/Deployment.xcconfig`, which
`Config.xcconfig` hard-`#include`s. The include is deliberately not `#include?`: a missing fragment must
be a build error, not a silent fall-through to stale values. Within the build that containment holds —
xcodebuild's Gradle build phase runs the resolver, `RuntimeIdentityTest` and `EventLinkDomainTest` were
both migrated to read the fragment and fail loudly when it is absent, and `EventLinkDomainTest` goes
further by asserting `Config.xcconfig` must not re-declare the moved keys. The old home is pinned shut.

The gap is outside the build. `.claude/skills/ssh-mac-build/SKILL.md` performs a **manual re-sign** —
the only path that produces an installable dev IPA, because `-exportArchive` does not reuse installed
profiles without an ASC key. That step reads the two keys with `awk` and substitutes them into the repo
`.entitlements` templates, which carry `$(AppIdentifierPrefix)`, `$(ASSOCIATED_DOMAIN)` and
`$(APS_ENVIRONMENT)` as placeholders. It still points at `Config.xcconfig`.

Three properties make the resulting failure invisible rather than loud:

1. **`awk` matching nothing is indistinguishable from matching an empty value.** `Config.xcconfig` still
   exists and is still readable; it simply no longer assigns the keys. The variable is empty, and `sed`
   substitutes an empty string without complaint.
2. **The wrong identity is a well-formed identity.** `.app.snapsync.shared` is a syntactically valid
   access group. `codesign -v` validates the signature, not the claim.
3. **The consequence is logged, not raised.** `device-identity` names the group explicitly, so the read
   throws `errSecMissingEntitlement` (-34018) into the app-scope error boundary, which logs. The app
   launches and looks normal with no device id — and the id is written once and never rewritten.

This is the "absence is never silent" law failing at a seam the law's existing gates do not reach: the
seam between a generated rendering and a reader that lives outside the build.

## Goals / Non-Goals

**Goals:**

- The re-sign step reads the rendering that owns the keys, and **refuses to sign** on an empty value.
- The signed artifact is checked **positively** — it carries the real prefix — not only negatively.
- A future key move cannot leave a reader behind silently: the reference is gated in the repo.
- The gate fires at the moment a key moves, which is the only moment the mistake is cheap.

**Non-Goals:**

- Asserting the signed binary from `./gradlew build`. The Linux guards scan the source tree; the signed
  `.app` exists only on a Mac at sign time. Attempting this would produce a guard that passes by
  inspecting nothing — the one thing this capability's guards may not do.
- Removing the existing wildcard guard, or folding it into the new positive one. They ask opposite
  questions (§D2).
- Generalising to every build setting the fragment owns. The gate covers the fragment's key set as the
  resolver declares it (§D3), which is broader than the two keys that broke, and narrower than "any
  string in any file".
- Changing how the resolver, the fragment, or the `#include` work. The generation design is sound; only
  its readers drifted.

## Decisions

### D1 — Fail closed on an empty value, rather than only fixing the path

Pointing `CFG` at `Deployment.xcconfig` fixes today's bug and leaves tomorrow's open: the same silent
substitution returns the moment a key is renamed, moved again, or the fragment is absent because the
resolver has not run. The explicit `[ -n "$TEAM" ] || exit 1` turns "I could not tell" into a refusal.

This is the local application of the capability's standing rule that a consumer without a rendering fails
loudly, naming the missing input. The reason the rule did not already cover this case is worth stating:
it is written for a **missing rendering**, and this reader was not reading the rendering at all. It was
reading a different file that exists. Hence the new requirement in `deployment-configuration`.

**Alternative considered — teach the reader to resolve the `#include` chain** (read `Config.xcconfig`,
follow its include, then look up the key). Rejected: it makes the reader tolerant of exactly the drift
worth detecting, and it re-creates a small xcconfig parser in shell in the one place where being wrong
is unrecoverable.

### D2 — Two guards on the signed artifact, asking opposite questions

The existing guard greps the signed entitlements for `*` and is key-agnostic **on purpose** — it catches
whichever wildcard key Apple adds next, which per-key narrowing by construction cannot. Keep it exactly
as is.

Add a second, positive guard: the entitlements contain `<TEAM>.app.snapsync.shared`. The first cannot
subsume the second — an empty `$TEAM` yields a string with no wildcard in it — and the second cannot
subsume the first, since it says nothing about keys it does not name. Absence of a leaked grant and
presence of the right claim are different propositions, and the failure that motivated this change lives
precisely in the space between them.

Run it over **both** binaries. The app and the extension must land in the same keychain group or they
hold different device ids — the 2026-07-20 split the skill already documents — so checking only the app
would leave half the invariant unstated.

Use `grep -qF`: the `.` in `<TEAM>.app.snapsync.shared` is a regex metacharacter, and a guard that
matches slightly more than it means is a guard that can pass wrongly.

### D3 — Gate the reference in the repo, against the committed file's own contents

The skill is prose. An agent may follow it, may skim it, or may — as here — follow a version of it that
was true six weeks ago. The repo gate is what makes the invariant mechanical.

The gate asserts: no file outside `scripts/resolve-deployment.py` extracts from `Config.xcconfig` a
build setting that `Config.xcconfig` does not itself assign. Phrasing it negatively — against the
committed file's own contents rather than against a list of keys that have moved — is what makes it
total. The committed file states what it carries; every key that has left it, or ever will, is covered by
construction, whichever rendering it moved to.

**This was not the first draft, and the correction is worth recording.** The first version derived the
guarded set from the resolver's *xcconfig* rendering. While this change was being verified,
`internal(config): bake the values the app reads into a plist, not the xcconfig` landed on `main` and moved
four keys — `BACKGROUND_UPLOAD_URL_BASE`, `APNS_ENV`, `SENTRY_DSN`, `SENTRY_ENVIRONMENT` — into a *second*
rendering, because `//` opens a comment in the xcconfig grammar and had truncated the DSN to `https:`,
killing crash reporting in four TestFlight builds. That draft would have silently stopped covering those
four: its key set shrank from nine to five and nothing would have said so. A guard that narrows invisibly
is the precise failure this change exists to prevent, so the derivation was inverted rather than extended
to a second file — extending would only have deferred the same fault to the third rendering.

Provenance is resolved from the extraction SITE — the filename the line names, or the variable it reads,
whose assignment is searched backwards — never from proximity. An earlier draft took the nearest xcconfig
mentioned anywhere above, and mis-attributed `grep '^MARKETING_VERSION_OUT=' "$GITHUB_ENV"` in `ios.yml` to
`Config.xcconfig` because a comment upstream happened to mention it. Proximity is not provenance, and a
guard that cannot tell the difference trains people to ignore it.

The gate scans the repository's text surfaces — including `.claude/skills/` — because the reader that
broke was a skill. `RunbookSkillsTest` already establishes that skill text is guardable source, so this
extends an existing shape rather than introducing one.

Non-vacuity is mandatory, per the capability's standing rule: the gate asserts a non-empty scanned set and
a non-empty parse of the committed file's assignments, so a parse regression cannot make it pass by
finding nothing.

**Alternative considered — assert positively that the skill contains the string
`Configuration/Deployment.xcconfig`.** Rejected: it pins today's text rather than the invariant, breaks
on any rewording, and says nothing about the next reader added elsewhere.

**Alternative considered — no repo gate; fix the skill and rely on review.** Rejected by this
capability's own Purpose: an invariant held only by convention and review is exactly what silently
shipped a crash before. It is also the argument the resolver itself already made — guards are opt-in and
cover what someone remembered, which is how `BACKGROUND_UPLOAD_URL_BASE` went unpinned. The difference
here is that the reader is outside the generated set, so generation cannot cover it and a gate must.

### D4 — Two capabilities, two halves

`deployment-configuration` gets the invariant (a key's readers follow it; an empty read fails closed);
`architecture-guards` gets its executable enforcement. This mirrors the split already stated in
`architecture-guards`' Purpose — the guards are the containment half, the behavioral half is pinned by
the owning capability — rather than inventing a placement.

**Alternative considered — put both in `architecture-guards`.** Rejected: the rule about where a key
lives and how its readers behave is a deployment-configuration fact that would still be true if the gate
were implemented some other way.

### D5 — Correct the stale comments as part of this change

Four comments still cite `Config.xcconfig` as the home of keys it no longer carries. They change no
behavior, and they are the same defect class as the bug: documentation that outlived the fact it
describes. The new gate flags them, so leaving them would ship a change whose own gate is red.

## Risks / Trade-offs

**[The gate scans `.claude/`, which `openspec update` regenerates]** → It scans `.claude/skills/`, and
`ssh-mac-build` is hand-written; the generated tree is `.claude/opsx/`. A gate firing on regenerated
output would be the failure mode CLAUDE.md warns about, so the gate must not require anything of the
generated skills — it only forbids a specific wrong read, which generated OpenSpec skills do not perform.

**[The gate could fire on prose that legitimately mentions both a key and `Config.xcconfig`]** → It must
match a *read* — the key adjacent to the filename in an extraction — not co-occurrence anywhere in a
file. `Config.xcconfig`'s own header comment names every fragment-owned key deliberately, and the file
must stay passable; the resolver and this change's own artifacts likewise discuss both. Scope the match
narrowly and exempt the authored files that document the split.

**[The artifact-side checks remain unenforced by CI]** → Accepted, and stated rather than hidden: no CI
job performs a manual re-sign, so the fail-closed checks and positive assertions live only in the skill.
The repo gate is what keeps the skill's *reader* correct; nothing keeps its *checks* present except this
change. That is the honest limit of a Linux-side guard on a Mac-side artifact.

**[The keychain group's team prefix is a literal in the guard]** → It already is one, pinned by
`RuntimeIdentityTest` against the fragment's `TEAM_ID`. The new positive assertion composes it from the
`$TEAM` the step just read, so it cannot drift independently.

## Migration Plan

No runtime migration: no shipped behavior changes, and no device state is touched. Sequence is
fix-then-gate — the skill's reader and the comments are corrected first, so the new gate is green when it
arrives rather than landing red and inviting a suppression.

Rollback is deleting the gate; the skill fix stands on its own and should not be reverted, since the
pre-change text produces a permanently mis-signed build.

## Open Questions

- Whether the gate should also cover `site/` and `api/`, which read the JSON and site renderings rather
  than the xcconfig. Deferred: no reader there was left behind, and widening the gate before there is a
  second instance risks pinning a shape that does not fit the other renderings' consumers.
