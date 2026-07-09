## Why

SnapSync began as a personal one-way backup of your own library, where an absent capture-date cutoff
meaning **whole-library scope** was not merely safe — it was the point. The product is now event photo
sharing. Under that identity the same default inverts: a guest who joins an event with no cutoff uploads
their **entire camera roll to a stranger's event**. Nothing in the join UI lets a user pick "no cutoff",
so this can only be reached by failure — `EventDetails.Found.createdAt` arriving `null` or unparseable
seeds an **empty date field** on the join screen, and Join stays enabled.

The same unbounded scope is the root cause of a shipped crash. `OwnDeviceGalleryStatusSource.refresh()`
walks the whole library on every foreground entry, issuing one synchronous XPC round-trip into
`photolibraryd` per asset (`PHAssetResource.assetResourcesForAsset`), and applies the cutoff filter only
**afterwards** — walking 12,000 assets to keep the 40 taken since the event began. On build 286 that
blocked the main thread past the 10 s scene-update watchdog and the OS killed the app
(`EXC_CRASH`/`SIGKILL`, `0x8BADF00D`; thread 0 parked in `mach_msg2_trap` on the
`NSXPCStoreConnection … for Photos.sqlite` queue). Commit `28226ec` moved that walk off the main thread,
which stops the kill but leaves the O(N) IPC storm on every foreground.

Both defects are the same defect: **unbounded scope inherited from a product that no longer exists.**
Make the cutoff mandatory and the unbounded walk has no caller left to serve.

## What Changes

- **BREAKING** — `EventConfig.minPhotoDate` becomes **non-null**. An absent cutoff is no longer a legal
  membership state, and `null` no longer means whole-library scope.
- **BREAKING** — A persisted Keychain config item lacking `minPhotoDate` (written by a build predating
  the cutoff) **fails to decode and reads as not-joined**. The device shows the setup gate and the user
  re-scans the invite. Nothing uploads in either process meanwhile. See design.md for why no non-null
  default is safe — in particular why `minPhotoDate = ""` silently re-creates whole-library scope.
- The join gate SHALL seed the cutoff to **now** when the event's `createdAt` is absent or unparseable,
  and SHALL NOT offer a confirm action while the cutoff row is empty. An empty-field join becomes
  unreachable.
- **BREAKING** — the raw-asset walk seam loses its whole-library walk (`walkAll()`); every walk is
  scoped by a capture-date lower bound. The resource-enumeration seam (`enumerate()`) likewise takes the
  bound.
- The iOS walk SHALL push the bound into `PHFetchOptions.predicate` (`creationDate >= bound`) so the
  fetch returns only in-scope assets and the per-asset resource round-trip is issued only for those.
  The existing pure `commonMain` cutoff filter **still runs downstream**, so the predicate is a
  semantically inert optimization: it can remove work, never change the answer.
- **BREAKING** — the **incremental** change-token walk is bounded by the same cutoff. A change feed
  reports what *changed*, not what is in *scope*: an iCloud sync surfaces thousands of out-of-scope assets,
  and fetching each one's resources only to discard it on capture date measured **166 s for ~1500 assets**
  on an iPhone SE2, inside a process with a ~3-minute hard cap. The walk now rejects on the asset's own
  `creationDate` before reading its resources (343 ms for the same feed).
- `UploadCycle` and `OwnDeviceGalleryStatusSource` lose their `if (cutoff == null) …` branches.
- A dev/test `SNAPSYNC_SEED_PHOTOS` launch-env trigger fills the photo library with synthetic assets, so
  the bounded walk can be exercised against a large library on device. Inert in production (a launch env
  var is only injectable via a developer launch), like `SNAPSYNC_DEEPLINK`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-date-cutoff`: the cutoff becomes **required**; the "absent cutoff means whole-library scope"
  requirement is removed. Adds the legacy-item-reads-as-not-joined rule and the asymmetry rule for the
  empty-string sentinel.
- `gallery-status`: the decision-free raw-asset walk seam replaces its whole-library walk with a
  **bounded** walk taking a capture-date lower bound; the enumeration seam threads the bound. Adds the
  requirement that the platform fetch's predicate is an optimization only — the pure mapping's filter
  remains authoritative.
- `join-event`: the loaded `createdAt` seeds the cutoff default as today, but an absent or unparseable
  `createdAt` seeds **now** instead of nothing; the confirm action is unavailable while no cutoff is
  chosen.
- `deeplink-config`: the persisted `EventConfig` carries a non-null `minPhotoDate`; a legacy item without
  one decodes as no config rather than as a whole-library membership. The optional wire key
  `minPhotoDate` on the deeplink payload is unchanged (still an absent-by-default dev/test override).

## Impact

**Code**

- `capability/config` — `EventConfig.minPhotoDate` type; `KeychainConfigStore` decode of a legacy item.
- `capability/upload` — `UploadCycle.photoCutoff` becomes `suspend () -> String`; the null branch goes.
- `domain/gallery` — `RawAssetSource` (`walkAll()` deleted, `walk(since:)` added),
  `GalleryResourceEnumerator.enumerate(since:)`, `ResourceEnumerator`, and both in-memory fakes;
  `PhotoLibraryRawAssetSource` gains the `PHFetchOptions` predicate.
- `domain/status` — `OwnDeviceGalleryStatusSource` threads the bound, drops the null branch.
- `domain/presentation` — `StatusContainerHost` join seeding and confirm gating; `autoConfirm`'s
  `explicitCutoff ?: load.createdAt` fallback.
- `domain/ui` — `JoiningEventScreen` cutoff seeding; Join disabled while the cutoff is empty.
- `app/ios` — `SnapSyncRoot` (`photoCutoff` supplier), `IosDiscovery.discover()` full-enumeration branch;
  `app/ios/photokit-extension` and `app/ios/url-session-upload` composition roots.
- `test/world`, `test/integration`, `app/desktop` — fakes and the harness inspector follow the seam
  signatures.

**Behavior**

- Installs joined under a pre-cutoff build silently become unjoined and must re-scan their invite. There
  is one TestFlight tester and no production users, so the migration cost is accepted rather than
  engineered around.
- A scoped "full enumeration" narrows what the engine reconciles against: `fullEnumeration = true` now
  means "every resource **in scope**". Ledger rows for pre-cutoff assets — which can only exist on an
  install joined under a `null` cutoff, i.e. exactly the installs that now read as not-joined — would be
  pruned as though deleted. Believed vacuous; design.md carries the argument and it needs confirming
  before the tasks are trusted.

**Not in scope**

- Whether an asset with **no role-bearing resource** should count toward the total (the `resourceRole`
  filter's accidental role as an uploadability predicate).
- Whether iCloud Shared Album assets — photos other people took, appearing in a guest's library dated
  during the event — should upload to the event at all. Open question; unaffected by this change, since
  the upload cycle's role filter is untouched.
