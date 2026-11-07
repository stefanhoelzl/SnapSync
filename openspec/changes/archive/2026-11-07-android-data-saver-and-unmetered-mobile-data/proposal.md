# Proposal

## Why

On Android, a member who turned mobile data off for photos and switched on Data Saver still has their photos sent
and received over an ordinary Wi-Fi, because Android applies Data Saver to metered networks only and SnapSync followed
Android's reading (`changes/archive/2026-10-04-mobile-data-for-photos`, D4–D6). Members reasonably expect Data Saver to
mean "save my data" wherever they are. And on mobile data the carrier currently does not charge for (Android's
"temporarily unmetered"), the app's own uploads run while its downloads and background wakes wait, because Android
schedules that kind of network as metered. The two halves of the app disagree with each other.

## What Changes

- With mobile data off, photos also wait while **Android's Data Saver is on, on any network** — an ordinary Wi-Fi
  included — unless the member allowed SnapSync **unrestricted data** in Data Saver's settings, in which case SnapSync
  is exempt and photos move over an unrestricted Wi-Fi as before.
- Switching Data Saver off lets the waiting photos travel, in the background too, though not necessarily at once.
- **Mobile data the carrier does not currently charge for counts as mobile data**, for uploads as already for downloads:
  with mobile data off, no photo travels over it. This is what the spec already says; the uploads now match it.
- The joined screen's "Waiting for Wi-Fi…" line follows the same rule, as it does today for Low Data Mode (no copy
  change).
- iPhone behaviour is unchanged.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `mobile-data`: "With mobile data off, photos wait for an unrestricted Wi-Fi" — Data Saver applies on every network
  unless SnapSync is exempted from it; switching Data Saver off releases what waited; mobile data counts as mobile data
  even while the carrier does not charge for it.

## Impact

- **Android adapters only.** The network monitor reads Data Saver and its per-app exemption, and stops counting a
  temporarily unmetered network as unmetered. The in-process upload already waits on the monitor. Downloads need a
  hold of their own: DownloadManager does not apply Data Saver on an unmetered Wi-Fi. The background wake's unmetered
  constraint does not see Data Saver.
- **Contracts and tests:** the Android network binding enters Data Saver and the exemption from the shell; the download
  contract gains an Android clause for Data Saver on an unmetered Wi-Fi; the scenarios are claimed on those tests.
- **No core, iOS, UI, api or stored-format change.** No backwards-compatibility shim.
- Builds already installed keep today's behaviour until updated; nothing is lost either way.
