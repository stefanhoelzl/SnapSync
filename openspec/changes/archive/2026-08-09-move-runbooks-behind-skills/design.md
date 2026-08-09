## Context

`CLAUDE.md` is loaded in full into every session in every workspace. At 97,732 characters
(~24.4k tokens) it is the single largest fixed cost of every task, and ~60% of it is operator
runbooks. The obvious move — put runbooks behind Claude Code skills, which exist to hold reference
material until it is asked for — is complicated by one measurement: **the inline warnings are
working**. Across 20,518 Bash commands scanned for the specific mistakes the file warns about there
was 1 interpolated `NSLog`, 1 `--kill-existing`, 0 `java.awt.Robot` captures of the real screen,
0 hardcoded harness-driver ports, 0 whole-zone storage resets, 1 bare `openspec` invocation. Several
of those warnings exist because a build/install/scan cycle was already burned learning them once
(the `NSLog` redaction on 2026-07-16; the wildcard keychain on 2026-07-20 and again 2026-07-21; the
non-existent `dvt ps` costing ~2 h and two wedged installers on 2026-08-09).

A skill only helps if it is loaded. So the design problem is not "what is big" but **where the line
falls between a trap (stays inline, one line, cheap) and reference material (moves)** — and how a
pointer is written so its trigger is unmissable.

Three facts about the current tree shaped the answer:

- **Directory-scoped `CLAUDE.md` already works.** `app/ios/CLAUDE.md` is 20,942 chars and is *not*
  injected into a session that has not touched `app/ios/`. It is the mechanism this change
  generalises, not a problem to solve.
- **Hand-written project skills coexist with the generated ones.** `.claude/skills/bugsink/` sits
  beside five generated `openspec-*` skills and has survived. `openspec update` rewrites its own
  generated set; it does not garbage-collect strangers. The standing rule is unchanged: anything
  that must be **enforced** goes in `openspec/config.yaml`'s `context:` block, never in `.claude/`.
- **Much of the runbook prose duplicates an existing contract.** `ios-appstore-release` (28k),
  `ios-testflight-delivery` (23k), `ios-appstore-metadata` (17k), `ios-app-shell` (69k) and
  `api/README.md` (25k) already carry most of it.

## Goals / Non-Goals

**Goals:**

- Cut `CLAUDE.md` to roughly 9k tokens without weakening the trap set that measurement shows is
  working.
- Delete duplication rather than relocate it, so the number of places a fact can rot goes down.
- Give every surviving duplicate a loud-when-stale guard, per this repo's standing rule.
- Make each pointer fire for a session that does not know the tool exists — the failure mode that
  produced the `java.awt.Robot` warning in the first place.
- Fix the orientation failures the transcripts recorded: 10 `cd: api|backend|site` misses after the
  `backend/` split, 6 stale `openspec/specs/…` paths.

**Non-Goals:**

- **Specifying the four unspecified dev triggers.** `SNAPSYNC_SEED_PHOTOS`, `SEED_POLICY`,
  `WIPE_GALLERY` and `POLICY_PROBE` ship in production Kotlin and appear in no spec. That is a real
  gap and it is named here, but closing it means adding requirements to `ios-app-shell` for four
  dev-only triggers — a larger change with a different argument. This change documents them and
  guards the documentation against source; it does not specify them.
- **Hooks.** A `PreToolUse` hook could make skill loading mechanical rather than probabilistic.
  Rejected here: it would create the project `.claude/settings.json` that a separate finding owns,
  and it fires on the command you already knew to type — not on the one you did not know existed,
  which is the failure being fixed.
- **A character budget guard on `CLAUDE.md`.** A number invites gaming and says nothing about
  whether the file is *right*.
- **Any production code change.** No module, port, feature, flow or composition is touched.

## Decisions

### D1 — Cost-of-being-wrong governs; frequency only sizes the prize

Usage share decides how much is at stake, never what moves. Each section splits: the damage
warnings stay, the procedure goes. The local backend rig is 6%-usage and still keeps its warning
inline-adjacent, because the consequence of getting it wrong (uploads silently stop, no error, no
log line) is unrelated to how often it comes up.

*Alternative rejected:* move whole sections below a usage threshold. Simplest rule and the biggest
saving, but it makes the pointer the only thing between a rare-topic session and a re-burned build
cycle, and several of these warnings cost hours to learn.

### D2 — Reachability scopes the inline set

A trap earns an inline line only if a session can hit that mistake **without** having loaded the
owning skill. This is what keeps the inline set at ~6 lines instead of ~30:

- **Ungated, therefore inline:** `os_log` swallowing an interpolated `NSLog` (reachable from any
  session debugging Swift); `java.awt.Robot` / capturing screen `:0` (reachable *because* the
  session does not know the harness driver exists); there is deliberately no whole-zone storage
  reset (reachable from any session wanting a clean backend); the bare `openspec` invocation and
  the `.claude/`-is-generated rule (reachable from any session, and the generated skills actively
  contradict the first).
- **Gated, therefore travels with its skill:** the wildcard entitlements block (25 lines,
  unreachable outside ssh-mac step 6b — you cannot make the mistake without reading the script that
  warns about it), `dvt ps`, the SIGTERM black screen, the wedged installer, the harness-driver
  port, the `B=` shell-expansion trap, `/tree` hiding popups, `WIPE_GALLERY`'s irreversibility,
  crossing backends.

*Alternative rejected:* keep every trap inline regardless. Closest to today's measured-good
behaviour and the smallest token win; rejected because 25 lines warning about a mistake you cannot
make without reading the warning is pure cost.

### D3 — One exception to D2: third-party harm

Three gated mistakes cannot be undone: joining an event you do not own, selecting "Private" in App
Store Connect Pricing, and a wildcard `keychain-access-groups` in a hand re-sign. Only the first
stays inline. The line is **whose loss it is**: the operator may accept an unfixable risk on their
own behalf (a burned app record, a frozen device id) but not on a stranger's — a `direction=download`
join imports that person's photos onto this device and registers this device on their membership,
and log-scraped ids are someone's real event.

### D4 — Four destinations, not two

| Destination | Test |
|---|---|
| Inline | ungated trap (D2/D3), or an operator command with no other home |
| Delete + point | a spec or `api/README.md` already says it |
| Skill | operator/environment procedure with no owning document |
| Existing lazy doc | `app/ios/CLAUDE.md`, `api/README.md` — already load themselves |

The third destination is the largest single win and was not in the original framing. Verified by
grep before committing to it: `doctor`-is-not-the-whole-preflight, the path-filter/frozen-merges
rationale, `MARKETING_VERSION`, "What to Test", `dSYM` parking and the Private/unlisted one-way door
are all already in `ios-testflight-delivery` / `ios-appstore-release` / `ios-appstore-metadata`;
`autoNotifyEnabled`, `generate-notes` and the "Ready for Apple Intelligence" eyeball warning are all
in cited archive decision records. Nothing is lost by deleting the restatements.

*Consequence accepted:* a spec is a contract, not a runbook. Deleting the promote section would
otherwise send a session through 28k of SHALL/WHEN/THEN to find two `gh workflow run` lines, so the
literal operator commands stay inline (~15 lines) and only the *why* is delegated.

### D5 — Destination follows trigger, not subject matter

Applied twice, against intuition both times:

- The **screenshot capture runbook** is not a skill. After deleting what
  `ios-appstore-metadata` specifies, its residue is ~1,100 chars — a dispatch block and two notes.
  A skill whose entire body is one `gh workflow run` costs a catalog entry and a load round-trip to
  save ~275 tokens.
- The **tier-forcing runbook** leaves `app/ios/CLAUDE.md` for `ios-device`. Its trigger is "I am
  driving the SE2"; that file only loads when a session touches `app/ios/`, which a device-driving
  session never does. Today it hides precisely where it is needed — and skipping its deregister step
  gives two `LedgerWriter`s over one App-Group ledger, which, in that section's own words, "silently
  does the work you think you are testing". The architecture it sits in (which producer, which
  process holds the writer) stays, because that is needed when editing `SnapSyncRoot` with no device
  in sight.

### D6 — Five skills, one trigger each

`ios-device` · `ssh-mac-build` · `ui-harness` · `asc-portal` · `local-backend`.

- `ios-release` was **dropped entirely** — its four topics (TestFlight facts, promote, screenshots,
  ASC portal chores) have unrelated triggers, and the first three are spec-covered. A skill whose
  name does not predict its contents is a skill that does not get loaded.
- `asc-portal` stays separate despite its size (~3.2k) because two other skills consume it — ssh-mac's
  profile refresh reuses its `$A` credential bridge verbatim, and device registration precedes a
  first sideload — and it carries a standalone trap (proton-env injects `ASC_*`, the codemagic CLI
  wants `APP_STORE_CONNECT_*`, so a bare invocation dies on "Missing value ISSUER_ID").
- `local-backend` survives as a ~1.5k **index, not a duplicate**. `api/README.md` already documents
  the rig, but it never warns that crossing backends fails silently — so a session that reads it does
  the rig correctly and then measures nothing, with no error. One trigger owns the three-hop chain
  (`api/README.md` → `ssh-mac-build` → `ios-device`, with `SNAPSYNC_RESET_STATE` mandatory in both
  directions) rather than three pointers each of which can miss.

### D7 — Pointers are intent-first, commands as a tail

`**Touching the connected iPhone** — install, launch, screenshot, device logs, seeding, wiping →
load `ios-device`. (`idevice*`, `pymobiledevice3`)`

Keyed on the goal a session forms on its own, so it fires for the agent that does not know the tool
exists; the command tail catches the agent that does. A command-only key is structurally blind to
the `java.awt.Robot` case, which is the failure that motivated the section.

The pointer lives in **both** `CLAUDE.md` and the skill's own `description:` frontmatter,
deliberately redundant: the frontmatter is auto-injected into every session but reads as a catalog
entry, while `CLAUDE.md` is an instruction — and the instruction voice is the one with 20,518
commands of evidence behind it.

### D8 — Both surviving duplicates are guarded, in `LawsDigestTest`'s shape

`LawsDigestTest`'s own rationale states the governing rule: a duplicate "which this repo forbids
UNLESS it is loud-when-stale". Two guards supply the loudness.

1. **Pointer integrity** — every skill named in the runbook block resolves to
   `.claude/skills/<name>/SKILL.md`. A dangling pointer is an "absence is never silent" failure: the
   agent looks for `ios-device`, does not find it, and proceeds without it.
2. **Index freshness** — the `SNAPSYNC_*` names in `ios-device`'s `SKILL.md` equal, as a set, the
   `"SNAPSYNC_*"` literals in production Kotlin. **Names only, never semantics** — two authorities
   would be worse than one, which is the same line `LawsDigestTest` draws.

Guarded against **source**, not against `ios-app-shell`'s spec: four of the eleven triggers
(`SEED_PHOTOS`, `SEED_POLICY`, `WIPE_GALLERY`, `POLICY_PROBE`) appear in no spec at all, so a
spec-keyed guard would cover 7 of 11 and would force the non-goal above.

Both guards carry the non-vacuity twin the capability requires: a scan that resolves zero pointers,
zero skills, or zero source literals fails rather than passing.

## Risks / Trade-offs

- **A pointer fails to fire and a session does the slower or wronger thing.** → Verified after
  merge by a cold smoke test: a fresh workspace given three cold prompts ("screenshot the app on the
  phone", "show me the status screen", "test a backend change against the device"), checking whether
  the right skill loads *before* any command runs. It tests skill loading, not the commands, so it
  costs minutes and no device time. If a pointer misses, the fix is rewording it, not undoing the
  split.
- **The `SNAPSYNC_*` index drifts from the source it summarises.** → Guard D8.2, which is red on
  arrival (`POLICY_PROBE`) and therefore demonstrably not vacuous.
- **A skill is renamed and its pointer silently reaches nothing.** → Guard D8.1.
- **The three-hop backend chain still needs its middle hop.** `local-backend` names it, but a
  session that ignores the index and reads only `api/README.md` gets a working rig and a device that
  uploads nothing. → Accepted; the trigger is owned by one skill rather than split across three
  pointers, which is the best available reduction.
- **`openspec update` deletes something.** → It rewrites only its own generated set; `bugsink`
  is the standing counter-example. No rule is patched into `.claude/` — enforcement stays in
  `openspec/config.yaml` and in `:test:architecture`.
- **The measurement cannot be re-run usefully soon.** The baseline is 1–6 occurrences in 20,518
  commands, so a few weeks of new sessions cannot statistically distinguish "still working" from
  "slightly worse". → Accepted; the smoke test is the feedback signal, not the scan.
