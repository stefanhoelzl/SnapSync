---
name: bugsink
description: >-
  Triage SnapSync crash reports from the operator's Bugsink instance
  (steho.bugsink.com). Read-only: list unresolved issues ranked by last-seen,
  drill into one issue for the full context (symbolicated stacktrace,
  breadcrumbs, device/OS/app context). Use when the user asks what's crashing,
  to look at a Bugsink issue/crash, to triage errors, or names an issue like
  SNAPSYNC-3.
---

# bugsink — crash triage

Read-only triage of the crashes both SnapSync iOS processes report to the operator's
**Bugsink** instance (capability `crash-reporting`). Dev/operator infrastructure:
non-gating, no spec, no shipped-code change — same posture as `ssh-mac`,
`harness-driver`, and the local backend rig. This skill only ever issues `GET`s;
it never resolves, mutes, comments, or deletes.

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
event detail. Report: the crash type/value, **symbolicated stacktrace**, recent
**breadcrumbs**, and **device/OS/app context**.

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
- `stacktrace_md` — Bugsink's own pre-rendered markdown stacktrace. Good for a quick look;
  **native frames in it are unsymbolicated addresses** — use symbolication (step 3) for those.

Read `data.contexts` and `data.breadcrumbs.values` straight from `event.json` for the
device context and log trail. For the stacktrace, prefer step 3.

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

## Notes & gotchas

- **Privacy by construction:** every UUID-shaped token is scrubbed before an event leaves
  the device (an eventId IS the upload capability). So a Bugsink event will NOT contain real
  event/device ids — do not expect to correlate them back to a specific membership.
- **Token scope:** the injected token is `org:ci` (broad — it *could* mutate). This skill's
  read-only guarantee is enforced by only ever issuing `GET`s, not by the token. If a
  narrower credential is wanted, mint a read-only Bugsink token and repoint the proton path.
- **First real crash validates symbolication.** At authoring time the only stored event was a
  `WatchdogTermination` (no frames), so the exact `debug_meta.images` / frame
  `instruction_addr` field names in `symbolicate.py` are written to the standard Sentry-cocoa
  shape but unverified against a real native crash. Sanity-check on the first one; the payload
  shape is the only assumption.
- **dev builds send nothing** (no baked DSN) — every issue here is from a TestFlight/App Store
  build. `data.environment` is `production`.
