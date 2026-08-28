## Why

`CandidateSource.candidates(policy)` returns `List<Candidate>`, so **"the library could not be read"
and "nothing qualifies" are the same value** — an empty list. That is a violation of
`module-architecture`'s law *"Absence is never silent"*, and the seam's own KDoc admits it and pushes
the distinction onto callers, where two consumers now carry a compensating grant check.

The counts this seam feeds drive a **state machine, not a display**: the status screen renders one
health line, never numbers, and `total == 0` settles it to a check mark meaning *everything shared*.
A zero standing in for an unread library therefore renders as **"In sync" on a device that has counted
nothing** — the shipped regression `SNAPSYNC-14` / `SNAPSYNC-16` ("status going backwards across
launches"), which `OwnDeviceGalleryStatusSource` fixed at its own level by making `size` an `Int?`.
The seam beneath it never got the same treatment, and the defect is still reachable:
`PermissionAwareCandidateSource` maps a **`LIMITED` grant whose selection snapshot has not yet
arrived** to an empty list, which the status projection publishes as a counted zero — and
`sync-status` forbids the recovery that would hide it (*once `Ready`, a source MUST NOT regress to
`Loading`*), so the false frame is replaced by a worse-looking one rather than by a neutral one.

**No gate could have seen this.** The mechanical half of the law only ever covered *nullable*-returning
`ports/` members, and this collapse hides behind `emptyList()` — outside that population by construction.
That half no longer exists at all: the guard audit retired it as unearned. So the law is prose discipline
at this seam, which is why the defect had to be found by reading rather than by a red build.

## What Changes

- `CandidateSource.candidates` returns a **sealed `CandidateRead`** — `Readable(candidates)` or
  `NotReadable` — instead of `List<Candidate>`. **BREAKING** for every implementation and call site of
  the seam (three impls, fifteen call sites).
- `NotReadable` is named for its **consequence** — *the admitted set cannot be stated right now* — not
  for its cause. It absorbs three causes with one shared consequence: `DENIED`, `NOT_DETERMINED`, and
  **`LIMITED` before the selection snapshot has arrived** (or if it never does). Naming it for the
  grant would leave the third cause in `Readable(emptyList())`, which is the live defect.
- `OwnDeviceGalleryStatusSource.refresh` under `NotReadable` **logs and writes nothing**: it never
  publishes a count it did not compute, and never withdraws one it did — the same rule
  `gallery-status` already mandates for a failed enumeration.
- `ShareableCountSource.count` under `NotReadable` returns `null` (the surface renders no row), and
  **loses its `permission` parameter**.
- The two compensating grant checks are **deleted**: `AppCore.refreshStatusSources`'s
  `if (grantsPhotoAccess)` and `ShareableCountSource.count`'s `if (!permission.grantsPhotoAccess)`.
- `EventPhotoSet` gains a **private** in-hand constructor and a companion unwrap, so the `CandidateRead`
  `when` exists exactly once and no consumer can reach the eager, policy-ignoring construction. The
  `EventPhotoSetSourceTest` guard and its allowlist are **unchanged**.
- The iOS denylisted-album lookup answers **without a PhotoKit fetch** when the library is not readable
  — the denylist is a subtraction and the policy admits on doubt, which is the semantics it already has
  under a partial grant. This keeps deleting the consumer gates from buying a library read on the join
  surface, where `NOT_DETERMINED` is the normal state.
- `SelectionScopedTransfer`'s **cursor preservation under a scoped discovery becomes a requirement**
  rather than an implementation habit. It is what makes the *upload* arm's identical `?: emptyList()`
  collapse safe (nothing is skipped, nothing is pruned, the next emission retries), and today it is
  pinned by a test whose stated reason is efficiency — a reason that would not stop someone advancing
  the cursor and silently skipping every photo in the unread window.

Not changed, deliberately: `PermissionAwareAssetPresence` (its `AssetPresence.UNKNOWN` already carries
the answer), the upload path's own `?: emptyList()` (retryable where the count is not), and the
mechanism/tier resolution.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `gallery-status`: **"Library resource enumeration seam"** — the seam's return distinguishes an
  unreadable library from an empty admitted set, and every implementation states its answer.
- `limited-photo-access`: **"The limited selection is a facts-only candidate source for the admitted
  set"** — the clause assigning the *"whether a grant permits an answer at all"* question to each
  consumer is replaced: the source answers it, and an un-arrived snapshot is not an empty selection.
- `limited-photo-access`: **"The read discipline is enforced at the mechanism, not at the trigger
  fan-out"** — a scoped discovery SHALL NOT advance the walk cursor or drive ledger pruning, so an
  un-arrived snapshot costs an idle cycle rather than permanently skipped photos.

## Impact

**Code.** `:domain` — `ports/CandidateSource`, `model/EventPhotoSet`, `compose/PermissionAwareCandidateSource`,
`compose/SnapSyncApp` (`refreshStatusSources`, `loadShareableCount`), `feature/status/OwnDeviceGalleryStatusSource`,
`feature/status/ShareableCount`. `:adapter:generic:fake` — `InMemoryCandidateSource`. `:adapter:ios:ext-safe` —
`PhotoKitCandidateSource`, `IosDiscovery`. `:app:ios` — the denylisted-album lookup in `SnapSyncRoot`.
`:test:world` — `Fakes`, `UploadFakes`. `:test:rig` — `GalleryReader`. `:app:desktop` — `WorldInspectorController`.
Roughly 13 files carry real change; the remainder are unwraps.

**Not affected**, despite touching the same names: `PermissionAwareAssetPresence`,
`PhotoSelectionSnapshotSource` (uses `candidatesFrom`, not the port method), `SelectionScopedTransfer`
(wraps `BackgroundTransfer`), and the shells that merely construct `PhotoKitCandidateSource`
(`SnapSyncRoot`'s wiring, `UrlSessionUploadController`, `UploadExtensionRoot`) — the constructor and the
declared type are unchanged.

**Tests.** A new `:test:integration` case proving the `LIMITED` pre-snapshot settle is the regression
proof and lands first, red. Then `PermissionAwareCandidateSourceTest`, `ShareableCountTest`,
`OwnDeviceGalleryStatusSourceTest`, `RawAssetMappingTest`, `ShareableCountIntegrationTest`,
`SelectionScopedTransferTest`.

**Changelog label:** `bug` — a member with a partial grant sees the fix.

**Open questions**, neither blocking the design:

- Can a `PHAssetCollection` fetch under `NOT_DETERMINED` surface a system prompt? A runtime question,
  answerable headlessly on a simulator. If yes, the adapter-side album change is load-bearing rather
  than merely tidy.
- `permission-gate` ("Permission liveness across the system Settings round-trip") and
  `limited-photo-access` ("An upgrade to full access is an offered route and an ordinary transition")
  disagree about whether the process survives a Settings grant change. Noted, not resolved here; this
  change's behaviour is identical under either reading.
