---
name: local-backend
description: >-
  Test a backend change without deploying it — run the real api/ app against a
  filesystem store (touching no bunny zone), optionally behind a cloudflared tunnel
  so a physical device can reach it, and point a build at it. Use for "test the
  backend locally", "run the API without deploying", "point the device at my local
  backend", "try a backend change on device", or anything involving deno task
  dev:local / dev:tunnel / api/.localstore.
---

# local-backend — testing a backend change on a real device

This is an **index**, not a duplicate. The rig itself is documented in **`api/README.md`
§*Develop & test*** — read that for `dev:local` / `dev:tunnel`, the `.localstore` layout, the reset,
and why the attestation gate stays fully on. What lives here is the part **no single document owns**:
the three-hop chain from "I changed the backend" to "the device really uploaded to it", and the one
step whose omission fails **silently**.

Dev infrastructure: non-gating, no spec, same posture as `ssh-mac.yml` and `:test:harness-driver`.
`main.ts` never imports `src/dev/`, and `deno bundle` roots the deployed bundle at `main.ts`, so none
of it can ship.

## The chain

1. **Start the rig** — `cd api && deno task dev:local` (curl loop, `127.0.0.1:8080`) or
   `deno task dev:tunnel` (adds a cloudflared quick tunnel so a real device can reach it). Both print
   the origin and the store path, and write the origin to `api/.localdev/host`. (Any
   `BACKGROUND_UPLOAD_URL_BASE=…` line it still prints is **dead** — see step 2.) It is a long-lived server rather than the work itself, so wrap it
   in **`ch-bg`** (CLAUDE.md, *Agent harness limits*) to keep the workspace able to go idle:
   `ch-bg deno task dev:tunnel`. Contract and detail: `api/README.md`.
2. **Rebuild the IPA against it** — the upload host is **compile-time** (PhotoKit forces it), so this
   needs a rebuild. 🚫 **Not via `BACKGROUND_UPLOAD_URL_BASE=` on the xcodebuild line**: that value now
   lives in a generated bundle resource no build setting can reach, so the override is accepted, ignored,
   and the build silently targets PRODUCTION. Point the build by writing the host into
   `deployments/local.json` and re-running the resolver. Load **`ssh-mac-build`** → *Pointing a build at
   a local backend* for the exact commands. A quick tunnel's hostname is random per session, so the IPA
   is rebuilt per session (~1 min incremental Debug).
3. **Install, launch, then RESET over the channel** — load **`ios-device`** (which owns the device lease
   every phone command requires) to install and launch, then **`rig-channel`** for
   `POST /device/reset`. The reset is not optional; see below.

Reset is `rm -rf api/.localstore`. This is the deliberate **inverse** of the production rule: no
whole-zone reset tool exists for bunny because that one zone holds real users' photos; the local store
holds nothing.

## ⚠️ Crossing backends REQUIRES a device reset — or nothing uploads, silently

Reset the swapped build **every time you change which backend is baked in — in both directions**,
including going back to production. `SNAPSYNC_RESET_STATE` is **gone**: production Kotlin declares no
`SNAPSYNC_*` launch trigger any more, and a build guard fails if one returns. It is the control channel's
job now, on a build made with `-Psnapsync.rig=true` (load **`rig-channel`**):

```bash
curl -X POST --max-time 180 localhost:18099/device/reset
```

It answers with the ledger counts after the fact — `{"reset":true,"ledgerCompleted":0,"ledgerPending":0}`
— so "it cleared" is verifiable rather than assumed. Needs `usbmux forward 18099`; see `rig-channel`.

⚠️ Order matters and nothing enforces it: reset **before** leaving. After a reset the device is unjoined,
so a leave becomes a no-op rather than a `DELETE` aimed at the backend you are departing.

**Why it is not optional:** the upload ledger's key is the **bare filename**, event-independent, and a
*leave* deliberately keeps it (a `COMPLETED` row stays true across a leave — `sync-ledger`). Point the
build at a different backend and the bytes are on the one you left while the ledger still says
`COMPLETED`, so the device uploads **nothing** — no error, no failed request, no log line. Clearing the
ledger alone is **not enough** either: the discovery cursor is a `PHPersistentChangeToken`, and with it
retained the next cycle sees no changes and enumerates nothing. `/device/reset` clears both, plus the
membership config (**locally**, notifying no backend) and prunable download rows; it **keeps**
imported download rows, whose `createdLocalId` suppresses re-uploading photos this device downloaded.

**The oracle when you forget:** each process logs `[boot] upload base = …` in `debug.log`. A tunnel
host there beside a cycle reporting `enumeration: 0 seen` (or `N seen, 0 new, N already-uploaded`)
means the reset did not run. ⚠️ **Order matters and nothing enforces it any more** — each command is
its own request now, so reset **before** leaving: after a reset the device is unjoined, so a leave is
a no-op rather than a `DELETE` aimed at the backend you are leaving behind.

⚠️ **`api/.localstore` survives across sessions.** If it still holds objects from an earlier run, the
re-join reconcile (`event-rejoin-reconciliation`) seeds them as `COMPLETED` from the device's
stored-file listing and they never re-upload. `rm -rf api/.localstore` when you want a clean slate —
measured 2026-08-25: a rejoin seeded 167 rows this way, which is correct behaviour and looks exactly
like "nothing uploaded".

Going **back to production** is the direction with no automatic protection and it needs the same reset;
`event-rejoin-reconciliation` then re-seeds already-stored photos as `COMPLETED`, so the cost is one
reconcile, not a re-upload of the library.

## Two rig behaviours worth knowing before you debug them

- **curl needs no `authorization` header.** The gate stays fully on and a request carrying a *bad*
  token still `401`s; the rig only fills in a token when one is **absent** (the same trick
  `test/app.test.ts` uses). `/attest/*` is untouched, so a device's real attestation runs for real
  against the rig.
- **`dev:local` mints download URLs as `https://127.0.0.1:8080/…`** because the production presigned
  URL shape is fixed. Swap the scheme to follow one by hand: `… | sed 's|^https://|http://|'`.
- **No APNs**, so `/events/<id>/notify` returns `202` with every token skipped — faithful to the
  route's best-effort contract. A receiving device therefore reconciles on foreground/relaunch rather
  than on a silent push.

## The oracle

`find api/.localstore -type f` — objects land as `api/.localstore/objects/<key>`, keys mapping 1:1 onto
the production keys. Against the **deployed** backend the oracle is the bunny storage zone instead;
see `ios-device` → *Verifying real uploads*.
