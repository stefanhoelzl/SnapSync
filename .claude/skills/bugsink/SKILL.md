---
name: bugsink
description: >-
  Triage SnapSync crash reports from the operator's Bugsink instance
  (steho.bugsink.com). List unresolved issues ranked by last-seen, drill into one
  issue for the full context (symbolicated stacktrace, breadcrumbs,
  device/OS/app context), and resolve an issue a shipped fix closes - the one
  write, and only on confirmation. Use when the user asks what's crashing, to
  look at a Bugsink issue/crash, to triage errors, names an issue like
  SNAPSYNC-3, or when a merged PR carries a `Bugsink-Resolves:` trailer.
---

# bugsink — crash triage

Read-only triage of the crashes both SnapSync iOS processes report to the operator's
**Bugsink** instance (capability `crash-reporting`). Dev/operator infrastructure:
non-gating, no spec, no shipped-code change — same posture as `ssh-mac`,
`harness-driver`, and the local backend rig. **Triage is read-only**: every step below
issues `GET`s. There is exactly **one** write — resolving an issue a shipped fix closes
(§4), through `resolve/` or `resolve-next/`, and never without the operator confirming
first. Muting, reopening, commenting and deleting stay out of scope entirely.

- **Instance:** `https://steho.bugsink.com` — **project 1** (`snapsync`). Both the app
  and the background-upload extension report to this one project/DSN.
- **API namespace:** `/api/canonical/0/` (Bugsink-native). The Sentry-compat `/api/0/`
  is sentry-cli / debug-file upload ONLY — its `/organizations`, `/teams`, `/projects`
  read paths are unimplemented here (they 404). Do not use them.
- **Auth:** `Authorization: Bearer $BUGSINK_TOKEN`, injected by **proton-env** from
  `.proton.yaml` (`Personal: BUGSINK_TOKEN: steho.bugsink.com/token`). Every run
  carries proton's per-run sign-off — that IS the guardrail. Run the whole flow inside
  one `proton-env -- bash -s <<'SH' … SH` block so the operator signs off once.

## Invocation

- `/bugsink` (no arg) → **list unresolved issues**, newest-crash first.
- `/bugsink SNAPSYNC-3` or `/bugsink <issue-uuid>` → **drill into one issue** with full
  context and a symbolicated stacktrace.
- Loaded by **`/ship`** after a PR merges, when its commits carry a `Bugsink-Resolves:`
  trailer → **§4**, the resolve.

**If you are here to FIX an issue, not just read it: write the trailer into your FIRST fix
commit** (§4). This skill will not be in context when the branch finally ships, and the
trailer is the only thing `/ship` reads.

Issues have two ids: a UUID (`id`) and a friendly id (`friendly_id`, e.g.
`SNAPSYNC-3`). The list shows both; the friendly id is what a human will paste. The API
paths key off the **UUID**, so map friendly → UUID from the list first.

## 1. List unresolved issues (the triage board)

`sort=last_seen&order=desc` ranks server-side; filter unresolved/unmuted client-side
(there is no server-side resolved filter). Paginate via the `next` cursor URL if present.

Save curl output to a file, then read it with a python **heredoc** — do NOT inline the
python with `python3 -c '…'`; escaped quotes inside a `<<'SH'` heredoc break it.

```bash
proton-env -- bash -s <<'SH'
set -euo pipefail
H="Authorization: Bearer $BUGSINK_TOKEN"
B="https://steho.bugsink.com/api/canonical/0"
OUT="${SCRATCH:-/tmp}/bugsink"; mkdir -p "$OUT"
curl -sS -H "$H" "$B/issues/?project=1&sort=last_seen&order=desc" -o "$OUT/issues.json"
python3 - "$OUT/issues.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
rows=[i for i in d["results"] if not i["is_resolved"] and not i["is_muted"]]
if not rows:
    print("No unresolved issues."); raise SystemExit
print(f"{len(rows)} unresolved issue(s):\n")
for i in rows:
    print(f"  {i['friendly_id']:12} {i['calculated_type']}: {i['calculated_value'][:70]}")
    print(f"  {'':12} events={i['stored_event_count']}  last_seen={i['last_seen']}  id={i['id']}\n")
PY
SH
```

Useful issue fields: `friendly_id`, `id` (UUID), `calculated_type` / `calculated_value`
(what crashed), `last_seen` / `first_seen`, `stored_event_count`, `digested_event_count`,
`is_resolved`, `is_muted`, `is_resolved_by_next_release`.

## 2. Drill into one issue (full context)

Resolve friendly id → UUID (from step 1), then fetch the issue, its latest event, and the
event detail. Report: the crash type/value, **which build and which process** (release ·
build number · `process` tag), the **symbolicated stacktrace**, recent **breadcrumbs**, and
**device/OS/app context**.

(Release and process live on the EVENT, not the issue — the issue object carries neither, so
there is nothing to add to step 1's list.)

```bash
proton-env -- bash -s <<'SH'
set -euo pipefail
ISSUE_UUID="<uuid-from-step-1>"
OUT="${SCRATCH:-/tmp}/bugsink"; mkdir -p "$OUT"
H="Authorization: Bearer $BUGSINK_TOKEN"
B="https://steho.bugsink.com/api/canonical/0"
curl -sS -H "$H" "$B/issues/$ISSUE_UUID/" -o "$OUT/issue.json"
# latest event for the issue (order=desc → newest first)
EVID=$(curl -sS -H "$H" "$B/events/?issue=$ISSUE_UUID&order=desc" \
       | python3 -c 'import json,sys;print(json.load(sys.stdin)["results"][0]["id"])')
curl -sS -H "$H" "$B/events/$EVID/" -o "$OUT/event.json"
python3 - "$OUT/event.json" <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))["data"]
rel=d.get("release"); proc=(d.get("tags") or {}).get("process")
print(f"release={rel or '«none»'}  build(dist)={d.get('dist')}  "
      f"process={proc or '«untagged»'}  env={d.get('environment')}")
if rel is None or (rel or '').startswith("app.snapsync@"):
    print("  ^ pre-metadata build (release added in add-release-and-process-to-crash-reports) "
          "— NOT a regression")
PY
echo "event.json + issue.json written to $OUT  (EVID=$EVID)"
SH
```

The event detail (`$OUT/event.json`) has:

- `data` — the full stored Sentry envelope:
  - `data.exception.values[]` — `{type, value, mechanism, stacktrace:{frames:[…]}}`
  - `data.threads.values[]` — threads, each with a `stacktrace.frames` and `crashed`
  - `data.debug_meta.images[]` — binary images `{image_addr, image_size, debug_id, code_file}`
    (**required** for symbolication; absent on watchdog/handled/message events)
  - `data.breadcrumbs.values[]` — `{timestamp, level, category, message}` (the log trail;
    up to ~100, oldest first — show the last ~20)
  - `data.contexts.{os,device,app}` — OS version, device model/arch/memory, foreground state
  - `data.level`, `data.environment`, `data.dist` (**the build number** → `dsyms-<dist>`)
  - `data.release` — **the marketing version** the build carried. Set by the app since
    `add-release-and-process-to-crash-reports`; `null` (or shaped `app.snapsync@<v>+<build>`,
    the SDK's own fallback) on builds predating it — see the gotcha below
  - `data.tags.process` — **which process reported**: `app.snapsync` (app) or
    `app.snapsync.BackgroundUpload` (the background-upload extension). Absent on pre-change builds
- `stacktrace_md` — Bugsink's own pre-rendered markdown stacktrace. Good for a quick look;
  **native frames in it are unsymbolicated addresses** — use symbolication (step 3) for those.

Read `data.contexts` and `data.breadcrumbs.values` straight from `event.json` for the
device context and log trail. For the stacktrace, prefer step 3.

## 2b. Bug reports / diagnostic dumps (operator-triggered, not crashes)

Any issue whose message begins **`Bug Report:`** is **not a crash**. It is a device log someone asked
to send by double-tapping the "SnapSync" label in the app and writing what went wrong (capability
`diagnostic-logging`). The rest of the title is that description, verbatim.

⚠️ **One issue per description, not one issue for all reports.** The message *is* the grouping key, so
two reports worded differently arrive as two distinct issues — expect several `Bug Report: …` entries
in an unresolved list, each self-describing, rather than one issue with many occurrences. (Builds from
before this changed sent a constant `diagnostic dump` message and still group as that single issue;
both shapes coexist.)

Its payload lives in `data.contexts`, not in a stacktrace:

| context   | what it holds |
|-----------|---------------|
| `note`    | `text`: what the operator wrote — the same text as the issue title, verbatim |
| `state`   | app version + build, OS, device model, upload tier, permission, membership (real event id), baked upload base, reporter environment |
| `ledger`  | five counts: `photos_pending` / `photos_completed` (photos, not rows) and `downloads_imported` / `downloads_assets` / `downloads_in_flight` |
| `app_log` | `text`: the tail of the app process's `debug.log` |
| `ext_log` | `text`: the tail of the extension's `ext-debug.log` |

⚠️ **Never print the log contexts.** Each is up to ~350 KB — dumping one into the conversation floods
the context window and buys nothing. **Write them to files and read selectively** (grep for the
symptom, tail the end, …), with the same heredoc discipline as above:

```bash
python3 - "$OUT/event.json" "$OUT" <<'DUMP'
import json, sys, pathlib
e = json.load(open(sys.argv[1])); out = pathlib.Path(sys.argv[2])
c = e.get("data", {}).get("contexts", {})
for k in ("state", "ledger"):
    if k in c:
        print(f"== {k} ==")
        for key, value in sorted(c[k].items()):
            if key != "type":
                print(f"  {key}: {value}")
for k in ("app_log", "ext_log"):
    text = (c.get(k) or {}).get("text")
    if not text:
        print(f"{k}: absent"); continue
    p = out / f"{k}.txt"
    p.write_text(text)
    print(f"{k}: {len(text):,} bytes -> {p}")
DUMP
```

Then work the files: `grep -n "enumeration:" "$OUT/app_log.txt" | tail`, `tail -50 "$OUT/ext_log.txt"`.

- `ext_log: absent` means the extension has never run on that device — on iOS 18–26.0 there is no
  extension at all, and uploads appear in `app_log` instead.
- A dump has **no stacktrace and no `debug_meta`**, so symbolication (step 3) does not apply.
- The two logs share a ~700 KB budget, so a dump is a **tail**, not the whole file. If the answer
  rolled off, ask for a `SNAPSYNC_EXPORT_LOGS=1` launch plus a USB pull instead.
## 3. Symbolicate native crash frames (Linux, no Mac)

Native cocoa frames arrive as raw addresses (Bugsink cannot symbolicate — tracker
`bugsink/bugsink#20`). Symbolicate offline against that build's dSYMs using **`symbolic`**
(Sentry's own core; installs on Linux via `uvx`, no atos, no address math by hand).

The dSYMs ship as GitHub artifact **`dsyms-<build>`** where `<build> = data.dist`
(90-day retention). Resolve the build number from the event, download the artifact, run the
symbolicator:

```bash
BUILD=$(python3 -c 'import json;print(json.load(open("'"$OUT"'/event.json"))["data"]["dist"])')
DSYM_DIR="$OUT/dsyms-$BUILD"
gh run download -n "dsyms-$BUILD" -D "$DSYM_DIR"   # from the ios.yml run that built it
uvx --from symbolic python .claude/skills/bugsink/symbolicate.py "$OUT/event.json" "$DSYM_DIR"
```

`symbolicate.py` matches each frame's image by address range and `debug_id`, then resolves
symbols (inlined frames included). It prints a clear per-frame line and degrades loudly:
`<no image>`, `(dSYM … missing)`, or `<symbol not found>` rather than a wrong guess.

**When `dsyms-<build>` is gone (build > 90 days old → artifact expired): fail loud.**
Show the raw frames (from `stacktrace_md`), state the build number and that its
`dsyms-<build>` artifact has expired, and point at the CLAUDE.md runbook note
("park longer-lived versions' dSYMs elsewhere at promote time"). Never symbolicate against
a *different* build's dSYMs — that produces subtly-wrong frames.

## 4. Resolve an issue a shipped fix closes

**Write the trailer as soon as the fix exists** — into the FIRST commit of the fix, not the
last:

```
Bugsink-Resolves: SNAPSYNC-9
```

One line per issue; repeat the trailer when one branch closes several. Use the **friendly
id** — the thing you and the Bugsink UI both speak.

This is deliberately a trailer and not a bare mention. This repo's history cites issues as
*evidence* as well as as fixes — `bd5c113e` cites SNAPSYNC-6 for a field measurement while
fixing SNAPSYNC-9 — and a mention cannot tell those two apart. Write it early: by the time
the branch ships, this skill is long out of context, and an unmarked fix is resolved by hand.

`/ship` does the rest, after the PR **merges** (its step 9.2). Nothing here fires before a
merge: a fix that never lands never closes an issue.

### Which endpoint

| the issue | endpoint | why |
|---|---|---|
| `calculated_value` starts with `Bug Report:` | `POST issues/<uuid>/resolve/` | a dump can never recur — the description IS the grouping key, so a re-report arrives as a **new** issue. Regression protection would be a claim about nothing. |
| anything else — a real crash | `POST issues/<uuid>/resolve-next/` | resolved as of the next release; a recurrence in that release or later reopens it. |

A third endpoint, `resolve-latest/`, stamps `fixed_at` to the newest release that exists
right now. It is not used here.

⚠️ **`resolve-next/` under-delivers today, by decision — do not silently "fix" it.** It means
"resolved as of the next value of the `release` field", and this app sends the **marketing
version** (`0.4`), which changes only when a `vX.Y` tag is pushed at App Store promote — not
per merge, not per TestFlight build. So it currently reads as *"closed until the next App
Store version"*, and a crash recurring on a TestFlight build in between does **not** reopen
its issue. Sending `0.4.<build>` instead would make it exact — strict `MAJOR.MINOR.PATCH`, so
Bugsink orders by semver rather than falling back to date order, which is already scrambled
here (release `0.1` is dated 2026-07-31, *after* `0.2`'s 2026-07-29). That changes what crash
events carry — capability `crash-reporting` — so it is a separate change nobody has proposed.

### The procedure

**Confirm with the operator before any write — every id, every time.** Look the issue up
first, so the question names what is actually being closed:

```bash
proton-env -- python3 - "SNAPSYNC-9" <<'PY'
import json, os, sys, urllib.request

friendly = sys.argv[1]
hdr = {"Authorization": "Bearer " + os.environ["BUGSINK_TOKEN"]}
url = "https://steho.bugsink.com/api/canonical/0/issues/?project=1"
while url:                                    # the board paginates - walk it, do not
    req = urllib.request.Request(url, headers=hdr)   # trust the first page
    with urllib.request.urlopen(req) as r:
        page = json.load(r)
    for i in page["results"]:
        if i["friendly_id"] == friendly:
            kind = "dump" if i["calculated_value"].startswith("Bug Report:") else "crash"
            state = "RESOLVED" if i["is_resolved"] else "OPEN"
            print(i["id"], state, kind, i["calculated_value"][:60])
            raise SystemExit
    url = page.get("next")
print("MISSING")
PY
```

Then POST the endpoint that the kind selects — `resolve/` for a dump, `resolve-next/` for a
crash:

```bash
proton-env -- bash -s <<'SH'
set -euo pipefail
curl -sS -o /tmp/resolved.json -w 'HTTP %{http_code}\n' \
  -X POST -H "Authorization: Bearer $BUGSINK_TOKEN" -H 'Content-Type: application/json' \
  "https://steho.bugsink.com/api/canonical/0/issues/<uuid>/resolve-next/"
cat /tmp/resolved.json
SH
```

Read the outcome honestly:

- **`200`** — resolved. Name the friendly id and which endpoint was used.
- **`400 {"detail":"Issue is already resolved."}`** — **success, not an error.** Someone got
  there first; say so and move on.
- **`MISSING`** — the trailer names an issue this project does not have: a typo, or another
  instance's id. Report it and resolve nothing.
- anything else — report the status and the body verbatim. Do not retry, do not diagnose.

Undo is `POST issues/<uuid>/reopen/`, which this skill does **not** issue. If a resolve was
wrong, say so and let the operator reopen it in the web UI.

## Notes & gotchas

- **Privacy by construction:** every UUID-shaped token is scrubbed before an **automatically
  captured** event leaves the device (an eventId IS the upload capability). So a crash event will
  NOT contain real event/device ids — do not expect to correlate them back to a specific
  membership. **Diagnostic dumps are the deliberate exception** and DO carry real ids (see 2b):
  they are operator-triggered and confirmed, and are worthless without them.
- **Token scope:** the injected token is `org:ci` (broad — it *could* mutate). This skill's
  narrow write surface — resolve only, on confirmation (§4) — is enforced by what it issues,
  not by the token. If a narrower credential is wanted, mint a Bugsink token scoped to reads
  plus resolve and repoint the proton path.
- **First real crash validates symbolication.** At authoring time the only stored event was a
  `WatchdogTermination` (no frames), so the exact `debug_meta.images` / frame
  `instruction_addr` field names in `symbolicate.py` are written to the standard Sentry-cocoa
  shape but unverified against a real native crash. Sanity-check on the first one; the payload
  shape is the only assumption.
- **dev builds send nothing** (no baked DSN) — so practically every event here is from a
  TestFlight/App Store build, carrying `data.environment = production`. The one exception is
  deliberate: a dev build with a hand-injected DSN (the on-device verification path documented in
  `Config.xcconfig`) reports honestly as `development`. Read `data.environment` rather than assuming.
- **An event with no `data.release` is an OLD BUILD, not a regression.** Release and the `process`
  tag arrived in `add-release-and-process-to-crash-reports`; a crash captured on an earlier build and
  delivered later carries `release = null` or the SDK's own `app.snapsync@<v>+<build>` fallback (which
  Bugsink renders truncated as `app.snapsync`, since it is not valid semver). This tail extinguishes
  as the installed base updates.
- **`data.dist` is CRASH-TIME on a real crash** — it comes from the crash report's own recorded build
  number, not from whatever was installed when the report was finally delivered (a cached crash can
  arrive days later; `SNAPSYNC-1` took three). That is what makes `dsyms-<dist>` the right artifact.
  It holds *because* the app deliberately never sets the SDK's `dist` option — see the
  `crash-reporting` spec requirement "The build number is the SDK's crash-time value and is never
  overridden". Do not "fix" that omission.
