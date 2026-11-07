# Design

## Context

See proposal.md for why. The rule "with mobile data off, only an unrestricted network" rides on each transfer as
`TransferNetwork.UNRESTRICTED_ONLY`, and each platform path enforces it where it runs
(`changes/archive/2026-10-04-mobile-data-for-photos`, D2–D6). On Android today:

| path | how it holds an `UNRESTRICTED_ONLY` transfer | sees Data Saver on unmetered Wi-Fi? | sees a temporarily unmetered carrier network as unmetered? |
|---|---|---|---|
| `AndroidNetworkMonitor` (status line, upload gate) | `Online(restricted = metered)`, where metered = neither `NOT_METERED` nor `TEMPORARILY_NOT_METERED` | no | yes |
| `AndroidUpload` (in-process PUT) | waits on the monitor (`awaitUnrestrictedNetwork`) | no (follows the monitor) | yes (follows the monitor) |
| `AndroidDownload` (DownloadManager) | `setAllowedOverMetered(false)` | no: DownloadManager applies Data Saver to metered networks only (archived D4) | no (see D5) |
| `AndroidWake` (the heartbeat's `UNRESTRICTED` wake) | WorkManager `NetworkType.UNMETERED` | no | no (see D5) |

Android's Data Saver is one switch for the whole phone. `ConnectivityManager.getRestrictBackgroundStatus()` answers it
for the calling app: `ENABLED`, `WHITELISTED` (the member allowed this app unrestricted data) or `DISABLED`. Its
changes are broadcast as `ACTION_RESTRICT_BACKGROUND_CHANGED`, to registered receivers only. On an unmetered Wi-Fi,
switching Data Saver changes no network capability and blocks nothing (measured 2026-10-03, `AndroidNetworkMonitor`'s
KDoc).

## Goals / Non-Goals

**Goals:**
- One reading of "restricted" on Android, the network monitor's, that every path either uses or matches.
- A held download survives process death, as it does today inside DownloadManager.

**Non-Goals:**
- iOS: Low Data Mode is already per-network and enforced by the request flags, and cellular is always `expensive`.
- The status line's copy. It reads "Waiting for Wi-Fi…" while Data Saver holds photos on a Wi-Fi, as it already does
  for iPhone's Low Data Mode on a Wi-Fi.
- Moving downloads already handed to DownloadManager when Data Saver is switched on (the spec's "MAY still finish").

## Decisions

### D1. The monitor reads Data Saver beside the capabilities
`restricted = metered || restrictBackgroundStatus == ENABLED`. `WHITELISTED` and `DISABLED` add nothing. The monitor's
callback flow also registers a receiver for `ACTION_RESTRICT_BACKGROUND_CHANGED` and publishes again when the status
changes. The status is read again at every capability change too, so a missed broadcast is corrected by the next
network event.
*Alternative*: read Data Saver only at each transfer's start. Rejected: the status line and the upload's wait would
disagree with what is enforced, and a member switching Data Saver off would see nothing move while the app is open.

### D2. Uploads need nothing of their own
`AndroidUpload` waits on `awaitUnrestrictedNetwork`, the monitor's `Online(restricted = false)`, so D1 holds uploads
under Data Saver and releases them when it is switched off or the app is exempted.

### D3. Downloads: the adapter parks a transfer while Data Saver is in force
DownloadManager would run an `UNRESTRICTED_ONLY` download on an unmetered Wi-Fi under Data Saver, and it has no pause.
So `AndroidDownload.start` under `UNRESTRICTED_ONLY`, while the monitor reads Data Saver in force, does not enqueue.
Instead it **parks** the request (url, headers, tag, network rule and restart count) in the adapter's own preferences.
Parked requests are enqueued into DownloadManager, unchanged, once the monitor reads `Online(restricted = false)`:
- while the process lives, by collecting the monitor;
- after a process death, at the adapter's next start, which every wake and every opening brings.

`cancelAll` drops parked requests too. A parked request answers `start` as started: to the core it is a transfer
handed over and not yet finished, exactly what a download held inside DownloadManager is today.
*Alternatives*: enqueue and cancel on a Data Saver change (rejected: DownloadManager's progress is lost, and a process
that is not running hears no change); hold downloads in the core (rejected: the network rule is the adapter's to
enforce on every other path, and the core would grow a second reading of the network).

### D4. The heartbeat keeps `NetworkType.UNMETERED`
WorkManager has no Data Saver constraint. On an unmetered Wi-Fi under Data Saver, the busy heartbeat still fires. Its
run finds the uploads waiting (D2) and the downloads parked (D3), ends within its time, and re-arms. So the photos
travel at the first heartbeat after Data Saver is switched off, which is the spec's "not necessarily at once".
*Alternative*: an adapter-private worker that polls Data Saver. Rejected for the reason the archived D5 rejected one:
a second wake path the Wake port and its mock cannot see.

### D5. A temporarily unmetered carrier network counts as metered
The monitor stops reading `TEMPORARILY_NOT_METERED` as unmetered: metered is "not `NOT_METERED`", nothing more. This is
how Android schedules every held transfer, so all three paths now agree:
- DownloadManager holds a download not allowed on metered networks as a JobScheduler job of `NETWORK_TYPE_UNMETERED`
  (`DownloadInfo.getRequiredNetworkType`, AOSP `DownloadProvider`).
- JobScheduler satisfies a job by matching its request's capabilities (`ConnectivityController.isStrictSatisfied`);
  `TEMPORARILY_NOT_METERED` relaxes only `isMeteredAllowed`, the background and Data Saver check.
- `NetworkCapabilities.satisfiedByNetCapabilities` is a plain bitmask with no case mapping one capability to the other.
- WorkManager's `NetworkType.UNMETERED` schedules through the same JobScheduler request.

Android's own documentation of the capability says most apps should use `NOT_METERED` instead, and that once the
capability is removed "applications are responsible for stopping any data transfer that should not occur on a metered
network". (Read in AOSP `main`, 2026-11-07.)
*Alternative*: keep it unmetered and make every path follow — enqueue such a download with metered allowed and wake
on any network. Rejected with the user: the capability can disappear at any time, which would leave a download running
over mobile data the member pays for, and a wake firing on every metered network.

## Risks / Trade-offs

- [`ACTION_RESTRICT_BACKGROUND_CHANGED` not delivered to a background app] → D1 also re-reads the status at every
  capability change, at every collection start and in D3 at every adapter start; the worst case is the next heartbeat
  (D4). Measured in task 2.2.
- [Busy heartbeats on Wi-Fi under Data Saver] → each one runs, finds everything held, and re-arms: some battery spent
  while Data Saver is on with work waiting. Bounded by the busy cadence; accepted.
- [Downloads already in DownloadManager when Data Saver is switched on] → they may finish over the Wi-Fi; the spec
  allows it (a transfer already under way).
- [A member on a free carrier allowance sees photos wait] → that is the spec's promise (mobile data stays mobile
  data); the line reads "Waiting for Wi-Fi…", which is accurate.

## Migration Plan

None. Parked downloads are a new, adapter-private store, read only by builds that write it. An update installed while
Data Saver is on starts holding photos from its first run.
