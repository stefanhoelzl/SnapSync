# Probe findings — the limited-access alert is armed by out-of-scope library growth

> **Supersedes** `changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md` **§5**
> ("⚠️ Corrected conclusion — the plist key does NOT reliably suppress the alert", and with it the
> "every `PHAsset` fetch under `.limited` appears to queue an automatic alert" inference). That
> document is deliberately left unedited: it is the record of what was believed and why, and the rule
> below explains its observations rather than contradicting them.

**Device:** iPhone SE2 `00008030-0018703A1A7A402E`, **iOS 26.5.2**. Build `0.1 (1)`, dev-signed,
`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = True` verified present in the installed bundle.
Grant confirmed `LIMITED` three independent ways: the `photokit.stop → url-session.start` producer
switch (fact ②), the subtype census dropping `5127 → 8`, and the in-app *"Choose more photos" /
"Allow full access"* affordances.

⚠️ **Provenance caveat:** the installed build carries millisecond timestamps, a marker of the sibling
change `hold-os-receipts-until-work-completes`, so it is neither `main` nor this workspace. Accepted
deliberately — every result below concerns PhotoKit + grant behaviour, and each stimulus was verified
to have actually run via `debug.log`.

## The result

| # | library changed since last look? | who made the change | fetches after | **alert** |
|---|---|---|---|---|
| P0 / P0b | no | — | ~15 `fetchAssetsWithOptions`, 4 launches 1 s apart | **none** |
| P0c | **yes** | **camera** (outside the selection) | 3 launches, ~9 fetches | **exactly 1**, queued, surfaced on the bare home screen after SIGKILL |
| P1 | **yes** | **the app itself** (`PHAssetCreationRequest` ×2 — auto-joined the selection, census `8 → 10`) | walk in the same launch, census confirms it saw them | **none** |

### The rule

> **Under `.limited`, a `PHAsset` fetch surfaces the limited-access alert iff the library has gained
> content OUTSIDE the app's selection since the app last looked.** The fetch does not *cause* the
> alert — it *surfaces* an already-armed one, **once per change**, not once per fetch. App-created
> assets join the selection at creation, so they never arm it.

This supersedes `PROBE-FINDINGS.md` §5's *"every `PHAsset` fetch under `.limited` appears to queue an
automatic alert"*, and it explains that probe's own data rather than contradicting it — both of its
storms (the initial grant picker; taking a photo while the app re-fetched) were out-of-scope changes,
and its five clean creations were fetch-free.

Note P0c's census stayed at **8** throughout: the app never saw the camera photo. The change was
invisible to it, and the very next fetch still prompted — which is what identifies **scope**, not data
freshness, as the trigger.

## Consequences for `fix-duplicate-import-on-restart`

**The alert objection is retired; the design still stands unchanged.** These are two different
objections and only the first one falls:

- *Alert safety* — **retired.** The guard resolves an asset **the app itself created**, which by the
  rule never arms the alert; and running later it can only surface an alert the app's own sanctioned
  observer-driven read would surface anyway. Marginal cost zero. The guard **could** fetch under
  `LIMITED`.
- *Answer reliability* — **stands.** Under `LIMITED` a fetch sees only the selection, and
  `PROBE-FINDINGS.md` measured that auto-add is creation-time only and **does not survive a full→limited
  downgrade**. An asset imported under `GRANTED` after a narrowing is real but invisible, so the fetch
  answers *absent* about a photo that exists — clearing the handle, re-importing, and orphaning the first
  copy. That is the bug itself.

⚠️ A case found while re-deriving this: **`DENIED` / `NOT_DETERMINED` is equally unsafe.** A row can hold
a handle written while access was granted and then have access revoked; the fetch returns empty for an
asset that exists, and once access is restored the unsuppressed asset echoes into the event.

So `Presence` stays three-valued, `ABSENT` remains producible only under `GRANTED`, and since a
`LIMITED` fetch and the snapshot lookup see the same set, the snapshot lookup is kept — same answer,
no XPC round-trip. **What the probe changed is the justification, not the shape** — plus it removed a
mischaracterised platform risk and dropped the six-site sweep (below).

**Site-by-site:** #4 `logImportedDate` fetches the asset it just created → safe. #9 `place()` and
#7/#8 (denylist) fetch on cycles → they surface an alert only when an out-of-scope change has occurred,
which the sanctioned read surfaces regardless. #5/#6 are **collection** fetches — still unmeasured;
the rule is stated for `PHAsset`.

## The larger finding, outside this change

`PHPhotoLibraryChangeObserver` fired for a change the app **cannot see** (`url-session.onSelectionChanged`
after the camera capture, census unchanged at 8). The app's response to an observer emission is the
*sanctioned* snapshot re-read — a fetch. So:

> every photo a limited-access user takes arms one alert, which the app's own blessed read then
> surfaces.

During an event — the app's entire use case — that is one alert per photo taken. **Limited access
therefore remains alert-prone on 26.5.2, and the six ungated sites are not the cause; the sanctioned
read is.** Gating them is hygiene, not a user-visible fix. This belongs to `limited-photo-access` as its
own finding, not to this change.

## What was NOT tested

- **Collection fetches** (`PHAssetCollection.fetchAssetCollections…`) — sites #5, #6, #7. The rule above
  is measured for `PHAsset` only.
- **S4** `fetchAssetsInAssetCollection` — a `WhatsApp` album was created, but under `LIMITED` no
  `denylisted album` line ever appeared, i.e. **album structure was not readable**. Consistent with
  CLAUDE.md's claim; now observed, though not isolated from "the cycle didn't reach the denylist".
- **iOS 18–26.0**, the tier the bug was actually reported on.
- Whether the alert count scales with the number of out-of-scope changes (only n=1 was tested).

## Device cleanup owed

- 2 seeded assets (2001-dated, 64×64) — written to the real library, joined the limited selection.
- 1 camera photo taken for P0c.
- The `WhatsApp` album created for S4.
- Grant left at **Limited**; restore to Full Access if that is the normal state.

## Reproduction

```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
P="uvx --python 3.14 pymobiledevice3"
$P developer dvt screenshot start.png --userspace          # verify drained
$P developer dvt launch app.snapsync --env SNAPSYNC_POLICY_PROBE=2026-01-01T00:00:00Z --userspace
#   ... optionally: take a photo with the Camera app ...
$P developer dvt signal <pid> 9 --userspace                # SIGKILL, not SIGTERM
$P developer dvt screenshot after.png --userspace          # queued alerts drain here
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log ./debug.log
#   oracle that the stimulus ran: "policy probe: subtype census — library total=N"
```

Raw logs and screenshots: `scratchpad/probe/{P0-granted-dryrun,P0-limited,P0b-provoke,P0c-libchange,P1-appcreate}/`.
