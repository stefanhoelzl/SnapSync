## Why

The joined screen shows a checkmarked **"In sync"** on every cold launch — before the gallery has been
enumerated, before the ledger has been read, before anything is known — and flips to "Synchronization
ongoing…" seconds or minutes later when the real counts arrive. Members read the first frame as truth and
the second as a regression, and report it as one:

| Bugsink | what the member wrote |
|---|---|
| `SNAPSYNC-14` | "State is in sync but then switches to ongoing" |
| `SNAPSYNC-16` | "last time I opened the app it was on sync now it is ongoing … did not have taken any pictures" |

Nothing goes backwards. The first reading was never true. Three status sources seed a placeholder zero
(`OwnDeviceGalleryStatusSource._size = MutableStateFlow(0)`, `LedgerCounts.ZERO`,
`DownloadProgress(0, 0)`), `LedgerBackedSyncStatusSource` `combine`s them and publishes
`Ready(pending = 0, completed = 0, total = 0)` on its first dispatch — `SyncStatus.Loading` lasts one
frame, not until a read — and the health rule then computes `shown = synced < total` → `0 < 0` → `false`
on **both** arms, which is the definition of `SyncHealth.InSync`.

Two specs already forbid exactly this, and the code does not keep them. `sync-status`: *"`Loading` is a
genuine value … never a placeholder, guess, or default."* `gallery-status`: *"SHALL always be a real,
source-derived count (never a placeholder or negative sentinel)"* — and then, one clause later, licenses
the very placeholder that causes the bug: *"`N` remains at its seeded `0`."* The same requirement even
names this failure in the opposite polarity (*"a fail-closed one (`None`) makes a contributing member's
screen read 'In sync' over a count of nothing"*), so the guard went on the default **parameter** while
the **seed** stayed open.

`SNAPSYNC-16`'s dump also shows why the honest counts never arrived at all during the session that
displayed the checkmark: `Foreground.run()` **awaits** `pumpForeground()` before it starts the status
poll and launches `refreshStatus()`, and the upload pump awaits a discovery walk that stays outstanding
across app suspension. The member's nine-second visit ran entirely on the placeholder zeros.

```
17:53:57.980  → pump.onForeground              app opened
17:54:06.968  === app entering background ===  app closed — a 9-second visit
              (no `gallery: N=` line: refreshStatus never ran)
18:06:44.283  → pump.onForeground              app reopened
18:06:52.387  ← pump.onForeground (774395ms)   the 17:53 call returns, 12.9 min later
18:06:52.440  gallery: N=71                    the first honest total in a 2-hour log
```

The status surface is the one place a member judges whether their photos are safe. It must not claim
"everything shared" over a count of nothing.

## What Changes

- The own-device upload total `N` gains an **un-counted** value distinct from a counted zero.
  `GalleryStatusSource.size` becomes `StateFlow<Int?>`; `null` means "never enumerated". A
  `SelectionPolicy.None` membership still publishes a **counted** `0`, so a download-only member with
  their downloads complete still reads "In sync". **BREAKING** for every consumer of the seam (all
  in-repo).
- `LedgerCounts` and `DownloadProgress` gain the same distinction, so an un-read count is never mistaken
  for a settled one. The health rule ANDs the two direction arrows, so one arm able to seed a false
  "hidden" reproduces the defect through the other half.
- `SyncStatus` stays `Loading` until **every** input the snapshot needs has been read, instead of
  publishing `Ready` over placeholder seeds. The joined screen therefore renders its neutral
  "Syncing…" line on a cold launch rather than a checkmarked "In sync".
- The foreground trigger flow stops sequencing the status refresh behind the upload pump: the pump joins
  the existing concurrent fan-out and the status poll starts before it. `run()` still returns only when
  every child has finished, so the flow's completion report to the OS stays truthful.
- A failed gallery enumeration is caught and logged at `Error` severity instead of propagating: it must
  not cancel the sibling refreshes, and its consequence — `N` stays unknown and the screen stays at
  "Syncing…" — is named rather than silently converted back into "In sync".
- The in-memory gallery fake carries the un-counted value, so a test can finally forge the state the
  device actually has on launch. Today `LedgerBackedSyncStatusSourceTest` constructs its fake with a
  count already in the cell, which is why no test caught this.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `gallery-status`: the `GalleryStatusSource` seam's `size` becomes nullable — an un-enumerated source
  reports "not counted", and the clause licensing a seeded `0` is removed. A non-contributing membership
  still reports a counted `0`.
- `sync-status`: `SyncStatus.Loading` holds until the total **and** the ledger counts **and** the
  download projection have each been read at least once; `LedgerCounts` and `DownloadProgress` carry
  their read-ness; and the foreground liveness guarantee states that the status refresh is not sequenced
  behind the upload pump.
- `sync-status-screen`: a joined cold launch renders the neutral "Syncing…" line, never the settled
  "In sync" line, before the first read completes.
- `harness-world-model`: the world's enumerated failure levers gain a **gallery-enumeration failure**,
  which is the only way a test can reach "the walk could not run" and assert the total stays *not
  counted* rather than collapsing to a `0` that reads as settled.

## Impact

**Code**

- `:domain` `ports/` — `GalleryStatusSource`
- `:domain` `feature/status/` — `OwnDeviceGalleryStatusSource`, `LedgerCountsSource`,
  `LedgerBackedSyncStatusSource`
- `:domain` `feature/download/` — `DownloadStatusSource` / `DownloadProgress`
- `:domain` `flow/` — `Foreground`
- `:domain` `compose/` — `SnapSyncApp.refreshStatusSources`
- `:ui:presentation` — `StatusContainerHost.syncHealth`
- `:adapter:generic:fake` — `InMemoryGalleryStatusSource`, `InMemoryDownloadStatusSource`
- `:app:desktop` — the forge presets and the world inspector pass a real count where they mean one
- `:test:world`, `:test:integration` — the composed-core assertions

**No impact**

- The backend, the edge, the upload tiers, the ledger schema, and the selection policy are untouched.
  This change alters what the app **reports**, never what it uploads or downloads.

**Adjacent, deliberately out of scope** — the reason the honest total takes 8–10 s to arrive on a
backlogged device (a discovery walk outstanding across suspension, a cap-truncated upload cycle that
never advances its cursor) is owned by separate investigations. This change makes the screen honest; it
does not make it fast.
