---
name: ios-device
description: >-
  Drive the connected iPhone headlessly over USB — install a build, launch it,
  screenshot it, pull device logs, apply the SNAPSYNC_* launch triggers (join,
  create, leave, reset, seed, wipe), force the app-driven upload tier, and verify
  that uploads and event links really landed. Use whenever the task touches the
  physical device: "install on the phone", "launch the app", "take a screenshot",
  "read debug.log", "join an event on device", "seed photos", "test on the SE2",
  or any idevice*/pymobiledevice3/dvt command.
---

# ios-device — driving the connected iPhone

Everything around the app on a real device — install, **launch**, **screenshot**, event-subscribe,
logs — is **scriptable headless over USB, no root and no Mac**. What is *not* available: taps and UI
gestures need a signed **WebDriverAgent** (`developer wda`), and the PhotoKit extension's `process()`
timing is OS-owned — a re-provision reliably triggers an invocation but you cannot force *when*.

To **build** an IPA, load `ssh-mac-build`. To point a build at a local backend, load `local-backend`.
To register a device UDID or mint a profile, load `asc-portal`.

## Take the lease first

⚠️ **There is ONE phone and up to a dozen workspaces. Take the lease before any command below** — as a
**background** call, **always** under `ch-bg` (its marker is what lets this workspace still go idle
while it holds the phone):

```
ch-bg scripts/device-lease "<why you need the phone>"      # blocks; THIS process is the lease
```

You are asked to confirm once, at the acquire — then every device command in this workspace runs
without further prompting. **Release by killing that shell** (session death releases it too; a
`SIGKILL`ed lease is reclaimed automatically, since liveness is checked by pid, not by a timeout).
`scripts/device-guard` (a `PreToolUse` hook wired in `.claude/settings.json`) **denies** device
commands with no lease, and denies them while **another** workspace holds one — naming the holder,
its reason and its age, so you report and wait instead of racing it (two concurrent installers wedge
`installation_proxy` for both of you). Take it from a live holder only deliberately:
`ch-bg scripts/device-lease --steal "<why>"`.

**Outside the fence** — no lease needed, because they mutate nothing and never race: `usbmux list`,
`idevice_id`, `ideviceinfo`. The lease runs one itself before claiming anything, so a missing phone is
reported as *"no device connected"* rather than leased as if present. Everything else that speaks to
the phone — `apps install`/`pull`/`list`/`uninstall`, every `developer` subcommand, the syslog and
crash-report tools — is inside.

⚠️ **The fence matches tool names as SUBSTRINGS, deliberately** — over-matching costs one denied call,
under-matching costs a wedged installer. So a command that merely *writes* one of those names is
denied too: a heredoc editing a doc that mentions them, or a `git commit -m` describing device work.
Don't fight it — use the Write/Edit tools, or `git commit -F <file>` with the message written first.

## Reaching the device

Reach a connected iPhone through the host's usbmuxd — **this is specific to the codehydra sandbox**
(the host socket is bridged at `/run/host/run/usbmuxd`).

**`USBMUXD_SOCKET_ADDRESS` is already set for you** — to the **bare** path, in `.claude/settings.json`,
because that is the form `pymobiledevice3` wants and it carries ~10× the traffic. Do **not** re-export
it. The libimobiledevice tools want the **`UNIX:`**-prefixed form instead, so prefix those calls
inline — and only those. Getting it backwards is not a visible error: libusbmuxd parses a bare path as
`host:port`, fails to connect, and reports **"No device found"**, which reads exactly like an
unplugged phone. `scripts/device-guard` therefore refuses such a command carrying no `UNIX:`, rather
than letting it fail that way.

Lockdown-level tools (no developer tunnel needed) — note the inline prefix:

```
export USBMUXD_SOCKET_ADDRESS=UNIX:$USBMUXD_SOCKET_ADDRESS   # this shell only; libimobiledevice only
idevice_id -l          # list connected devices
ideviceinfo            # device details (UDID, model, iOS version)
```

The live device log and crash-report pullers are in the same family (and inside the fence, so they
need the lease).

**Developer services — the `--userspace` unlock.** Launch, screenshot, and the rest of the DVT
surface need iOS 17+'s RemoteXPC tunnel + a mounted DeveloperDiskImage, which normally want root
and **hang over the usbmux bridge** (`idevicescreenshot`/`idevicedebug` fail here for this reason).
`pymobiledevice3 --userspace` builds the tunnel **in-process — no root** — and auto-mounts the DDI;
it needs **Python ≥3.14**, so pin it via `uvx --python 3.14`. It wants the **bare** socket path (no
`UNIX:` prefix) — which is the preset default, so nothing to export. Verified on the SE2 (iOS 26.5):

```
P="uvx --python 3.14 pymobiledevice3"    # define it in the SAME call that uses it — shell state does not persist
$P developer dvt launch app.snapsync --userspace                 # launch (prints the pid)
$P developer dvt screenshot shot.png --userspace                 # real screen capture (auto-mounts the DDI)
$P developer dvt process-id-for-bundle-id app.snapsync --userspace # the app's pid, or `0` when not running
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log ./debug.log  # pull the file logger
```

⚠️ **`apps pull` takes THREE arguments** — bundle id, device path, **and a local destination**. The
two-argument form fails outright with `Missing argument 'local_file'`, and a polling loop that swallows
that error reads as "the device wrote no log" — a very different diagnosis from the truth.

(`--userspace` is applied **automatically** for developer commands on iOS 17+ — without it you get one
failed lockdown attempt, a `WARNING Trying again over a no-root userspace tunnel`, then the same result.
Passing it explicitly just skips that first attempt.)

⚠️ **There is no `dvt ps`.** The process-listing commands are `proclist` (full JSON) and
`process-id-for-bundle-id <bundle>` (the pid alone). A `dvt ps` invocation is a **click usage error,
exit 2, that never reaches the device** — so with stderr discarded it prints nothing, which reads
exactly like *"the app isn't running"*. That is how a kill-before-install guard sat inert across ten
invocations and two days (2026-08-09), costing two wedged installers and ~2 h. Absence collapsed into
silence, in the one place the two causes have opposite consequences.

## Device-op timeouts — measured, ≤3× headroom

These are all 1–9 s operations. The 30–300× budgets an agent reaches for by default do not buy safety;
they convert a wedge into a ten-minute stall instead of an immediate, diagnosable error. Measured on
the SE2 (iOS 26.6, 2026-08-09; 3–5 samples each, warm `uvx` cache):

| op | measured | use |
|---|---|---|
| `usbmux list` | 0.5 s | `timeout 3` |
| `dvt process-id-for-bundle-id` | 1.1 s | `timeout 3` |
| `dvt signal <pid> <sig>` | 1.3 s | `timeout 4` |
| `dvt proclist` | 1.9 s | `timeout 5` |
| `apps pull` `debug.log` | 1.8 s at 2.3 MB (≈5 s at the 10 MB roll cap) | `timeout 15` |
| `dvt launch` | 3.5–4.7 s | `timeout 15` |
| `apps install` (25 MB IPA, app **not** running) | 8.4–9.0 s | `timeout 30` |

Pay the **cold `uvx` resolve once** — 12.9 s on an empty cache, which would blow every budget above:
`timeout 40 uvx --python 3.14 pymobiledevice3 --version >/dev/null`. Set the **Bash tool's** timeout to
the sum of the inner budgets + 5 s — which must stay under the harness cap (CLAUDE.md, *Agent harness
limits*: 600 s, silently clamped), or an inner `timeout 600` can never fire first and you are left
with a bare `Exit code 143` instead of the command's own output.

## Restarting the app (black-screen trap)

`dvt launch --kill-existing` — and `dvt kill`/`pkill` — only send **SIGTERM**, which SnapSync ignores;
a relaunch then layers a new instance on the still-alive old one and the app sticks on a **black launch
screen** (status bar visible, content black). To truly restart: `dvt signal <pid> 9` (SIGKILL) **then**
`dvt launch` (verified recovery). Get `<pid>` from the last `dvt launch` (it prints it), or — when you
don't have it, which is every fresh session — from `dvt process-id-for-bundle-id app.snapsync`,
**never** `dvt ps` (see above: it does not exist and fails silently). Take the screenshot promptly
after a single launch; avoid rapid relaunch cycles.

## Installing a build

Dev IPAs come from the ssh-mac build loop (`ssh-mac-build`). There is **no CI dev-IPA artifact**; CI
delivers only to TestFlight on `main`. This is an **operator runbook, not CI behavior**.

One-time setup (per device):

- Register the device UDID at developer.apple.com → Devices (SE2 is `00008030-0018703A1A7A402E`,
  obtainable via `ideviceinfo -k UniqueDeviceID`). The dev profile only includes registered UDIDs —
  see `asc-portal` to do this over the API.
- Enable Developer Mode (dev-signed apps won't launch without it). Note `pymobiledevice3 amfi
  enable-developer-mode` **hangs over the usbmux bridge** and the Settings → Privacy & Security →
  **Developer Mode menu only appears after a dev-signed app is installed** — so install a dev IPA
  first, then toggle Developer Mode on in Settings (software restart, no hardware buttons).

Install (run Python tools via `uvx`, never a global install — pymobiledevice3 wants the **bare** socket
path, no `UNIX:` prefix). Install goes over `installation_proxy`/lockdownd — no developer tunnel needed.

⚠️ **Reinstall hangs if the app is running** — `installation_proxy` stalls at "…% Complete" forever when
replacing a **running** app (the first install of a fresh session is fine because nothing is running
yet). SnapSync ignores SIGTERM (see the black-screen trap), so **SIGKILL it first**. The lookup has
**three** answers, not two — running, not running, and *couldn't tell* — and collapsing the third into
the second is precisely what let a dead guard pass for a clean device:

```
P="uvx --python 3.14 pymobiledevice3"
OUT=$(timeout 3 $P developer dvt process-id-for-bundle-id app.snapsync 2>&1)
PID=$(printf '%s\n' "$OUT" | grep -oE '^[0-9]+$' | tail -1)   # a pid, `0`, or NOTHING (= it failed)
if [ "$PID" = "0" ]; then echo "app not running"
elif [ -n "$PID" ]; then timeout 4 $P developer dvt signal "$PID" 9 >/dev/null && echo "SIGKILLed $PID"
else echo "LOOKUP FAILED — do not install:"; printf '%s\n' "$OUT" | tail -5; exit 1
fi
timeout 30 uvx pymobiledevice3 apps install <path>/SnapSync.ipa 2>&1 | tail -1
```

A clean install of the 25 MB IPA is **~9 s**. Past ~30 s it is a **wedged installer**, not a slow
transfer: iOS answers `IXErrorDomain Code=32 "Coordinator superseded"`, and retrying immediately stacks
another coordinator and makes it worse (measured 2026-08-09: five concurrent installers, ~2 h before
anything installed again). SIGKILL the app, wait a minute, retry **once** — never loop.

## The launch triggers

Every trigger below is a **dev/test launch env var** (capability `ios-app-shell`), read **once per
process**, and **inert in production** — a launch env var is only injectable via a developer launch.
`ios-app-shell`'s spec is the contract of record for ordering, idempotency and guarantees; this is the
operator index of what to type.

| variable | value | what it does |
|---|---|---|
| `SNAPSYNC_EVENT_LINK` | the full `https://snapsync.stho.net/join#v=3&d=…` URL | (re)provisions the event through the same path a scanned QR takes |
| `SNAPSYNC_CREATE_EVENT` | `base64url(JSON)` | mints a **new** backend event via the attest-gated `POST /events`; with `autoJoin` it also joins |
| `SNAPSYNC_LEAVE` | presence (`=1`) | leaves the current membership and returns to the unjoined resting state; no-op when unjoined |
| `SNAPSYNC_RESET_STATE` | presence (`=1`) | voids durable sync state — ledger, discovery cursor, membership config (**locally**), non-terminal download rows; **keeps** imported download rows |
| `SNAPSYNC_WIPE_GALLERY` | `all` \| `assets` \| `albums` | deletes this device's photo-library content. **Irreversible**, and the only value-checked trigger |
| `SNAPSYNC_SEED_PHOTOS` | `<n>` | seeds `n` tiny 2001-dated assets (walk-cost test) |
| `SNAPSYNC_SEED_POLICY` | `<n>` | seeds `n` hour-ahead assets straddling the 3 MP floor (selection-policy probe) |
| `SNAPSYNC_POLICY_PROBE` | `<cutoff>` e.g. `2026-07-01T00:00:00Z` | runs the **real** own-device status refresh against that cutoff, with **no membership**, and logs the result |
| `SNAPSYNC_FORGE_STATE` | `create` \| `joining` \| `in_sync` | mounts the real `StatusScreen` over forged sources — no backend, attestation or photo access — for a marketing screenshot |
| `SNAPSYNC_FORCE_URLSESSION_UPLOAD` | presence (`=1`) | forces the app-driven `URLSession` tier even on iOS ≥26.1 (selects the **tier and nothing else**) |
| `SNAPSYNC_EXPORT_LOGS` | presence (`=1`) | copies the **extension's** log out of the shared App Group into the app's `Documents/`, where `apps pull` can reach it |

**Ordering.** The photo-library triggers run as one chain — `wipe → SEED_PHOTOS → SEED_POLICY →
POLICY_PROBE` — and the whole chain completes **before** the membership triggers, which apply in the
fixed order `reset → leave → create → event-link`, sequentially (each awaited). So `WIPE_GALLERY` +
`SEED_POLICY` + `EVENT_LINK` in one launch wipes, re-seeds, then joins against the final library. A
wipe left unconfirmed therefore stalls the join too — that is the trade, not a hang. A
`SNAPSYNC_FORGE_STATE` launch ignores the four membership triggers (forge wins, structurally).
`SNAPSYNC_EXPORT_LOGS` mutates no membership, so it takes no part in the ordering and applies on a
forge launch too.

### Joining an event

```
$P developer dvt launch app.snapsync \
  --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=<base64url({\"eventId\":\"<uuid>\",\"autoJoin\":true})>" --userspace
```

⚠️ `"autoJoin":true` is **REQUIRED** for a headless join: without it the link opens the interactive join
gate — a confirmation dialog awaiting a tap no headless run can give.

**It bypasses AASA entirely** — it hands the URL straight to the decoder, so it exercises the
decode→gate→join path and proves **nothing** about whether the Universal Link actually resolves. To test
the *link*, tap one (see *Verifying the event link* below).

**Re-provision no longer forces a fresh whole-library upload** — it **reconciles against storage**
(`event-rejoin-reconciliation` seeds already-stored photos as `COMPLETED` before any upload job is
created), so a relaunch against an event that already has objects uploads **nothing new**. The reconcile
runs inside the shared `UploadCycle` and is a **required** constructor parameter, so it holds on **both**
tiers. To observe real uploads in the dev loop, point at a **fresh event id** (or clear the event's
objects in the bunny zone) so the reconcile finds nothing to seed.

⚠️ **A "fresh event id" must be a real event you created — not an invented UUID.** The join gate loads
the event's details first and **aborts on a miss**, leaving the *previous* membership untouched. The
abort's only headless signal is one `debug.log` line — with `autoJoin=true` (the headless path):

```
autoJoin aborted: details load did not succeed for <id> (NotFound)
```

and on a link **without** `autoJoin` (which parks on the gate's dialog instead), the same oracle reads:

```
join gate: details load did not succeed for <id> (NotFound)
```

(`(Failed)` in either shape means a transient load failure, not a missing event.) Both are emitted by
the gate itself, **after** the HTTP `GET … → 404` line — a `404` alone is the raw fetch, not the abort
decision.

The launch still succeeds and the app runs on happily — **with the old config** — so a run that assumes
its link applied is measuring the previous membership. (Observed: a `direction=download` link with an
invented id left a `Both` membership joined and uploading, which reads exactly like a broken direction
gate. Always confirm the id in `debug.log` — `reconcile(eventId=…)` and `config ok` — matches the one
you passed.)

**One event per membership shape.** `direction`, the cutoff, and the album opt-in are **fixed at join**,
and re-scanning the *already-joined* event short-circuits as `AlreadyJoined` (capability `join-event`) —
so `SNAPSYNC_EVENT_LINK` can change **none** of them for the event you are already in. Exercising a
different direction needs a **different event that already exists**: mint them with
`SNAPSYNC_CREATE_EVENT` in mint-only mode, one relaunch per event, then join each.

🚫 **Never point it at an event you do not own.** A `direction=download` join imports that event's photos
into this device's library and registers this device on its backend membership. Log-scraped ids are
someone's real event.

⚠️ **There is deliberately NO whole-zone reset tool.** The single `snap-sync-dev` zone is the *only* zone
(`api/src/config.ts` — the deployed backend uses it too), so it is **shared with real TestFlight /
App-Store users' photos**; a blind zone wipe would destroy them. Clean up **targeted only** — a fresh
event id, `SNAPSYNC_LEAVE`, or deleting the specific event's/device's objects via bunny (dashboard or
native Storage API). Do not re-introduce a `reset-storage`-style whole-zone delete.

### Creating an event

The JSON carries a **required** `name` plus optional `startsAt` (canonical `…Z`; default **now** — which
is also the cutoff floor, so a create-today event accepts `SNAPSYNC_SEED_POLICY`'s +1h assets),
`autoJoin`, `minPhotoDate`, `direction`, `saveToAlbum`. Without `autoJoin` it is **mint-only**: it joins
nothing and logs the greppable oracle `created eventId=<uuid>`, the id to reuse in a later
`SNAPSYNC_EVENT_LINK` join. With `autoJoin` it forwards a synthesized link through the **same** join gate
a QR uses, landing a live membership in one launch (cutoff clamped to the floor like every join).

```
d=$(python3 -c "import json,base64;print(base64.urlsafe_b64encode(json.dumps(
  {'name':'Test Party','autoJoin':True,'direction':'both'}).encode()).decode().rstrip('='))")
$P developer dvt launch app.snapsync --env SNAPSYNC_CREATE_EVENT="$d" --userspace
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log ./debug.log  # `created eventId=…` (mint-only)
```

⚠️ **`SNAPSYNC_CREATE_EVENT` is NON-idempotent — every cold launch mints a NEW backend event** (the
backend mints a fresh UUID per `POST`; there is no create-if-not-exists). This is the **opposite** of
`SNAPSYNC_EVENT_LINK`, which is safe to leave set for the per-build loop (a re-join reconciles). **Unset
`SNAPSYNC_CREATE_EVENT` after the mint**, or each relaunch orphans another event (and an `autoJoin`
re-launch leaves the previous one to join the new). Use mint-only to pre-seed the several distinct events
a multi-shape test needs (one relaunch per event), then join them with `SNAPSYNC_EVENT_LINK`.

### Leaving and resetting

`SNAPSYNC_LEAVE=1` leaves the current membership (cancel downloads, stop the producer, clear config,
notify the backend). It is the only headless route to the unjoined state (a *switch* to a different event
id leaves-then-joins via the join gate; standalone leave does not rejoin).

`SNAPSYNC_RESET_STATE=1` voids this device's durable sync state — the upload ledger, the discovery
cursor, the membership config (**locally**, notifying no backend), and non-terminal download rows —
while **keeping** imported download rows (their `createdLocalId` is what stops a downloaded photo being
re-uploaded). It exists because **crossing backends otherwise fails silently in both directions**; see
`local-backend`, which is the only reason to reach for it. Not needed for ordinary event-to-event work —
a *leave* keeps the ledger deliberately, and correctly, against one backend.

After a reset the device is unjoined, so a paired `SNAPSYNC_LEAVE` is a no-op rather than a `DELETE`
aimed at a backend that is no longer baked in.

### Seeding a large photo library

`SNAPSYNC_SEED_PHOTOS=<n>` (`app/ios/.../DevPhotoSeeder.kt`): on launch the app creates `<n>` synthetic
`PHAsset`s dated from 2001-01-01 forward, one minute apart, so the capture-date-bounded walk can be
exercised against a large library on device. ~85 s for 4000 assets on an SE2.

```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_PHOTOS=4000 --userspace
```

Why it matters: the walk's cost is one synchronous PhotoKit XPC round-trip **per asset**
(`assetResourcesForAsset`, ~110 ms each on an SE2), so ~90 assets exhaust the 10 s scene-update watchdog.
A one-photo dev device cannot distinguish a bounded fetch from an unbounded one.

**These seeds never upload, by design** — they are dated 2001, i.e. before any plausible cutoff. (They are
also 64×64, three orders of magnitude below the selection policy's 3 MP image floor, so they are doubly
out of scope.) They exercise the *walk*, not the upload. **It writes to the real photo library** — clear
them with `SNAPSYNC_WIPE_GALLERY`; they are parked in one year of the Photos timeline so hand-deleting
stays a two-tap job. Use it on a dev device only.

### Seeding for the selection policy

`SNAPSYNC_SEED_POLICY=<n>` seeds `n` assets dated **an hour ahead** — past any cutoff an event created
today can carry (the cutoff is clamped to `max(chosen, startsAt)`) — **alternating above and below the
3 MP floor**. It exists because a dev device may hold *no real photos at all*, and without an asset the
policy admits, a run cannot tell "the policy correctly excluded everything" from "the fetch predicate
silently returned nothing" — and the wrong predicate form returns **zero rows without raising**, so that
is precisely the confusion that matters. One launch answers everything: the walk returns assets, exactly
the below-floor half is `origin-excluded`, and only the rest uploads.

```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_POLICY=20 \
  --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=<…fresh event…>" --userspace
```

Read the outcome from the two log lines the policy emits **before any HTTP call** — so an attestation
`401` can never be mistaken for an exclusion:

- app: `gallery: enumerated N resource(s) … (M origin-excluded) → N=…`
- extension: `origin policy dropped N resource(s)`

### Probing the selection policy without an event

`SNAPSYNC_POLICY_PROBE=<cutoff>` runs the **real** own-device status refresh against that cutoff — the
real `PhotoLibraryResourceEnumerator` (and so the real `PHFetchOptions` predicate), the real origin
rules, the real denylisted-album lookup — and logs the result (capability `photo-selection-policy`).

It exists because the policy is otherwise **unobservable on a device without a joined event**: the status
total only refreshes for a membership, and event *creation* is attest-gated, so there is no headless route
to one. But the policy's entire decision happens **before any HTTP call**, so a membership is not actually
needed — only a cutoff. This gives the cutoff directly.

```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_POLICY=20 \
  --env SNAPSYNC_POLICY_PROBE=2026-07-01T00:00:00Z --userspace
```

It runs **after** the seeders in the photo-library chain, so one launch seeds and then measures. The
oracle, in `debug.log`:

```
policy probe: subtype census — library total=…, screenshots=…, …
policy probe: refreshing the own-device total against cutoff=…
policy probe: N=… (see the `gallery:` line above for the breakdown)
```

What it proves in one line: the fetch predicate returns assets at all (the wrong exclusion form returns
**zero rows without raising**, which is the failure that would silently empty the library), how many the
origin rules excluded, and the resulting `N`. Pair it with `SNAPSYNC_SEED_POLICY`, whose assets straddle
the resolution floor by construction.

### Emptying the library again

`SNAPSYNC_WIPE_GALLERY=all|assets|albums` (`app/ios/.../DevGalleryWiper.kt`) deletes this device's
photo-library content — `assets` every asset the fetch returns (photos *and* videos), `albums` every
user-created album and folder, `all` both in one change block.

```
$P developer dvt launch app.snapsync --env SNAPSYNC_WIPE_GALLERY=all --userspace
```

⚠️ **It is irreversible and it is NOT headless.** iOS raises its own *"Delete N Photos?"* confirmation —
someone must tap **Delete on the device**, and that alert (which shows the real count) is the only guard
there is. So: dev device only, and the run parks until it is answered. **One prompt per kind, not per
transaction**: measured on the SE2 (iOS 26.6, 2026-08-08), an `all` wipe of 9525 assets + 5 albums in a
single change block raised **two** alerts — so `albums` prompts too, and batching does not collapse them.
An `albums` wipe still deletes **no** photos: removing an album never removes its members.

- **A value, not presence** — alone among these triggers, because it cannot be undone. Anything that is
  not `all`/`assets`/`albums` (a typo, a leftover `=1`, the blank string a bare `--env X=` produces)
  refuses and deletes nothing, saying so: `wipe: SNAPSYNC_WIPE_GALLERY=<x> is not all|assets|albums`.
- **It requests photo access first**, so a fresh install does not silently wipe nothing. Under a
  **LIMITED** grant it deletes exactly the hand-picked selection — all PhotoKit will return — and the log
  names the grant so the line says which set was wiped.
- **iCloud Shared Albums are never touched** (deleting one removes it for every subscriber, off this
  phone), and smart albums (Recents, Screenshots, Favourites) cannot be deleted at all.
- **It touches no SnapSync state**: the ledger keeps its `COMPLETED` rows (true — the bytes are on the
  backend, so nothing re-uploads) and imported download rows keep their `createdLocalId` (so deleted
  foreign photos are not re-imported). Pair it with `SNAPSYNC_RESET_STATE` when you want a device that
  both holds no photos and remembers nothing.

### Rendering a forged UI state

`SNAPSYNC_FORGE_STATE=<state>` mounts the real `StatusScreen` over **forged sources** for a recognized
state (`create` · `joining` · `in_sync`) — no backend, attestation, or photo access — so a
marketing/App-Store screenshot can be captured of any state. The forge substitutes the container's
*inputs*, not a static `UiState`, so it can only render a frame the real reduction can reach (the
name→sources map is the tested `forgeStatusHost` factory in `:ui:presentation`). This is what the
non-gating, dispatch-only `.github/workflows/screenshots.yml` drives on a simulator — see CLAUDE.md's
*Refreshing the marketing screenshots* for that workflow.

## Forcing the app-driven upload tier

`SNAPSYNC_FORCE_URLSESSION_UPLOAD=1` is the **only way to exercise the 18–26.0 tier on the
agent-driveable SE2**, which runs iOS 26.5 and would otherwise take the PhotoKit path. It selects the
**tier and nothing else**: the transport stays a background `URLSession` (simulator-ness is read from
`SIMULATOR_DEVICE_NAME`, not inferred from this flag), and the PhotoKit extension is never registered.

**Deregister the extension first** (≥26.1 devices only). The OS's upload-job registration record lives in
the **system**, not the app, and survives app relaunch/reinstall. So once a device has run the PhotoKit
tier, the OS keeps invoking the extension even under the force flag — the flag stops the app from
*registering* it, but nothing *de*registers it — and the extension will happily upload behind the
app-driven tier's back (two `LedgerWriter`s over one App-Group ledger, and it silently does the work you
think you are testing). Turn it off headlessly with a **download-only** join on the PhotoKit tier (no
force flag), which drives `arm.onProvision → photokit.stop → setUploadJobExtensionEnabled(false)`:

```
d=$(python3 -c "import json,base64;print(base64.urlsafe_b64encode(json.dumps(
  {'eventId':'<uuid>','autoJoin':True,'minPhotoDate':'2001-01-01T00:00:00Z','direction':'download'}
).encode()).decode().rstrip('='))")
$P developer dvt launch app.snapsync --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=$d" --userspace
```

Then relaunch with the force flag **and a fresh deeplink for whatever you are actually testing** — the
download-only config above persists otherwise, and the app-driven tier will then correctly decline every
cycle (`cycle skipped — this membership contributes nothing`), which looks exactly like a broken test rig.

Verify with `grep -c 'photokit\.'` on the app log (expect 0) and by checking the **extension's**
`debug.log` stops gaining `cycle finished` lines. Irrelevant on a real 18–26.0 device, where no appex can
exist at all. The tier *architecture* — which producer, which process holds the single `LedgerWriter` —
is in `app/ios/CLAUDE.md`.

## Reading the logs

The app and extension are separate processes, each writing its **own** verbatim, un-redacted log
(capability `diagnostic-logging`). Each rolls to a `.1` sibling past 10 MB.

```
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log ./debug.log          # the APP's log
$P developer dvt launch app.snapsync --env SNAPSYNC_EXPORT_LOGS=1 --userspace
uvx pymobiledevice3 apps pull app.snapsync Documents/ext-debug.log ./ext-debug.log  # the EXTENSION's, after the export
```

The extension writes `ext-debug.log` into the shared App Group, which is **not** pullable — hence the
extra launch. ⚠️ `apps pull app.snapsync.BackgroundUpload Documents/debug.log` is **dead**: the extension
deletes that stale file on first launch of a build carrying this change, so the pull fails honestly
instead of returning months-old content.

⚠️ **`swcd` is NOT visible in `idevicesyslog`** (measured: 23,525 lines across an install, zero AASA
activity) — don't retry that.

## Verifying the event link

An invite is an HTTPS **Universal Link** — `https://snapsync.stho.net/join#v=3&d=<base64url>` (capability
`event-link`). The payload rides in the **fragment** on purpose: a browser never sends it, so the
`eventId` (which *is* the upload capability) never reaches the backend or its CDN even when someone
without the app opens the link and gets redirected to the App Store.

Two checks run from Linux with **no device**:

```
# 1. our origin, THROUGH the pull zone — must be JSON with no redirect
curl -sSI https://snapsync.stho.net/.well-known/apple-app-site-association
# 2. what Apple actually hands a device (it caches, and parse errors show up as a miss)
curl -sS https://app-site-association.cdn-apple.com/a/v1/snapsync.stho.net
```

That second endpoint is the cheap oracle: it 404s until Apple has fetched and **accepted** our AASA, and
200s once it has. It is also why we ship plain `applinks:` with no `?mode=developer` — CDN staleness is
one curl away from being diagnosed rather than an invisible wait.

⚠️ **Apple's own apps are not AASA-wired** — `apps.apple.com` serves an empty file, `maps.apple.com`
404s, `music.apple.com` serves HTML; they are special-cased inside the OS. So an `apps.apple.com` QR is a
**worthless** test target that appears to pass. Test with a real third-party universal link (verify the
domain against the CDN endpoint above first).

Verified on device: the stock **Camera app honors AASA** on a scanned QR, and iOS **delivers the fragment**
to the app. Opening a real link and landing on the event proves the entitlement, the AASA, and fragment
delivery in one observation; a stripped fragment would surface visibly as the invalid-link error, never
silently.

⚠️ **A green AASA proves nothing about delivery.** Both curls above can pass while every link is dead:
iOS matches the AASA, foregrounds the app, and the app drops the URL — indistinguishable from success,
and on an unjoined device the create screen it lands on is the correct resting state. That shipped
(2026-07-16). The link is delivered as an `NSUserActivity` to the **scene** delegate — a SwiftUI
`WindowGroup` is a scene — so `scene(_:willConnectTo:options:)` (app NOT running) and `scene(_:continue:)`
(running) are the only hooks that work. `.onOpenURL` never fires for a universal link;
`.onContinueUserActivity` is warm-only; `application(_:continue:)` is never called in a SwiftUI app. A
`:test:architecture` guard now pins this (`EventLinkDeliveryTest`).

**The authoritative on-device check is `debug.log`, not the screen** (spec `ios-app-shell`): read the
`[onOpenUrl]` lines. A **cold** delivery is an `onOpenUrl` sharing a timestamp with
`=== app process start ===`; a **warm** one has no preceding process start. A multi-second gap after a
launch means a *second* scan delivered warm — misreading that gap is how "cold works" was concluded
wrongly the first time. Both cases must appear, exactly once each. Apple's TN3155 exposes approval state
via `swcutil` inside a **sysdiagnose** (`swcutil_show.txt` → `Site/Fmwk Approval: approved`), fetchable
headlessly with `$P developer core-device sysdiagnose` — untried here, but the documented route.

⚠️ **Changing the AASA needs an app REINSTALL.** Devices download it from Apple's CDN at install and
re-check roughly weekly; there is **no invalidation** (TN3155). A changed path/appID does not reach
installed apps on its own.

## Verifying real uploads

By default on-device uploads go to the **deployed HTTPS backend** (the device-facing host baked from
`Config.xcconfig`). Confirm one landed by checking the backend's bunny **storage zone** (see
`api/README.md` / `openspec/specs/backend-deployment`), **not** the app status screen — the `dvt
screenshot` status counts are informational, not the authoritative landing check. Connections are
HTTPS-only — default ATS, no `NSAllowsLocalNetworking` exception, on any host.

To test a **backend change** without deploying it, point the device at a local rig instead — load
`local-backend`; there the oracle is `find api/.localstore -type f`.

## The headless per-build loop

`ssh-mac-build` builds the dev IPA → `apps install` → `dvt launch --env SNAPSYNC_EVENT_LINK=…` (use a
**fresh event id**, or the reconcile seeds already-stored photos and nothing uploads) → the OS invokes
the upload extension on its own cadence → confirm the objects landed in the backend's bunny storage zone.
