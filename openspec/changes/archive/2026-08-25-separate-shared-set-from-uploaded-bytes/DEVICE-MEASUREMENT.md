# Device measurement (tasks 10.1–10.3) — confirmed

SE2, iOS 26.6, 2026-08-25. Debug rig build (`snapsync.rig=true`), PhotoKit tier, deployed backend.
Library: **768 assets**, `GRANTED`.

## 10.1 — the deny-everything predicate returns zero rows

The claim was **reasoned, not measured**: `DenyAll` translates to `creationDate < NSDate.distantPast`,
and every one of the three constraints already documented above `predicateFor` is a case where a
plausible predicate did something else — one silently returned zero rows, two aborted the process.

Same library, same device, same request, only the membership's direction differing:

| policy | fetched | elapsed |
|---|---|---|
| contributing (`direction` default) | **768** | 118 ms |
| **non-contributing (`direction=download` → `DenyAll`)** | **0** | **3 ms** |
| contributing again (control) | **768** | 104 ms |

From the app's own log, through the real `PhotoKitCandidateSource`:

```
15:22:51.240  gallery: fetched 768 candidate(s)     ← contributing
15:22:51.320  gallery: fetched 0 candidate(s)       ← DenyAll
15:22:51.457  gallery: fetched 768 candidate(s)     ← control, library unchanged
```

**Confirmed on all three failure modes the file's existing traps warn about:**

- it does **not** abort the process (the arithmetic trap) — the very next request returned 768;
- it does **not** return everything (a predicate silently ignored);
- it returns **zero rows**, and does so ~35× faster, so the narrowing genuinely avoids the walk.

A supporting probe on the same key, before the rig could express a non-contributing policy: a
far-future cutoff (`creationDate >= 2098-12-31`, also unsatisfiable) likewise fetched **0 in 4 ms**
against the same 768 assets. So PhotoKit evaluates unsatisfiable `creationDate` comparisons correctly
in both operator directions.

## 10.2 — a download-only membership publishes an empty manifest, and keeps its ledger

Created an event of this device's own (`e7996dba-fc78-4ab3-b947-5d5315f84d3b` — never joined a
stranger's), joined `UploadOnly`, let the OS-driven extension upload, then reconfigured to
`DownloadOnly`.

| | ledger `completed` | direction |
|---|---|---|
| after joining and uploading | **125** | `UploadOnly` |
| after reconfigure to download-only | **125** | `DownloadOnly` |
| after a subsequent cycle | **125** | `DownloadOnly` |

**The ledger rows survive the direction-off**, which is the whole point of removing retention: under
the old code `retainAssets` was fed the policy-admitted set, so a membership admitting nothing would
have wiped the event's rows and re-enabling the direction would have re-uploaded all 125.

And the extension's own cycle log shows the new ordering, on device:

```
15:28:21.284  ← platform.fetchAckJobs = 0 job(s)                   terminal settle, AHEAD of the gate
15:28:21.865  PUT …/events/<id>/devices/<deviceId> → 201 (req=63)  THE MANIFEST WRITE
15:28:21.882  cycle skipped — this membership contributes nothing (direction excludes upload)
15:28:21.883  process: cycle finished — SKIPPED
```

`req=63` is exactly `{"deviceId":"<36-char uuid>","assets":[]}` — 13 + 36 + 14 = 63 bytes. **The empty
manifest was published.** Under the previous behaviour the cycle returned `SKIPPED` before reaching the
write and nothing was PUT, leaving whatever the membership had published while it still contributed.

The own-device status total agrees, off the same narrowed fetch:

```
15:28:04.211  gallery: fetched 0 candidate(s)
15:28:04.212  gallery: N=0 own admitted asset(s) in 12ms
```

## Two rig gaps this measurement exposed, and closed

Neither is part of the change's own scope; both were blocking the measurement, and both are the kind
of gap the rig exists to not have.

1. **`GET /device/gallery` could not express a non-contributing policy.** It hard-coded
   `includesUpload = true`, so the deny-everything narrowing — the one that keeps a download-only
   member off a whole-library walk — was unobservable on a device. Now takes `&direction=download`.
2. **`/user/reconfigure` was not wired at all.** The membership change this entire change is about
   could not be driven from the channel. Now wired, taking `eventId`, `direction`, `cutoff`, `until`,
   `saveToAlbum`.

## An unrelated transient worth noting

For roughly ten minutes the deployed backend returned **400 to everything**, including
`GET https://snapsync.stho.net/` — not just event creation. The app reports any create 400 as
"invalid name", which is its own mapping and was misleading here: the name was valid and the whole
site was down. It recovered on its own; `POST /events` then correctly returned 401 to an
unattested curl. Nothing to do with this change (it touches no `api/` file), but the client's
collapsing of every 400 into one message is worth knowing about.
