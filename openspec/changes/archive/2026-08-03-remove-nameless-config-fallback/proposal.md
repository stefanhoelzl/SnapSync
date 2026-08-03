## Why

`EventConfig.name` carries a serialization default of `""` so that a membership persisted before the
name was reliably set decodes non-null rather than crashing. That state has not been reachable for a
long time — the join gate only provisions from a loaded phase that carries a name, and the backend has
rejected a blank name on create since event markers existed — but three pieces of code still compensate
for it: a decode default, a "does this membership still need its title fetched?" rule with its own flow
branch, and a guard clause in the event-album coordinator. Each one invites the next reader to ask what
an empty name means and to add a fourth.

Removing the default buys something the compensations cannot: `name` becomes a **required constructor
parameter**, so every present and future `EventConfig(...)` site must supply one, enforced by the
compiler rather than by convention. That holds regardless of what the install base looks like.

## What Changes

- **BREAKING (decode contract):** `EventConfig.name` loses its `= ""` default. A persisted membership
  whose payload lacks the `name` key no longer decodes. On the App-Group file — the store of record —
  that reads as **unreadable**, not as absent: the UI shows the setup gate for the session, the file is
  left intact, no backend leave is issued, and the upload/reconcile paths defer rather than act. On the
  read-only legacy-Keychain fallback it reads as *no config*, which is already that seat's documented
  behavior for an undecodable item.
- `MembershipRefresh.fetchNeed` and the `TitleNeed` enum are deleted, together with the `Provision`
  flow's step-6 branch and the `membershipRefresh` / `fetchEventDetails` constructor parameters it
  needed. `Foreground` becomes the sole caller of `MembershipRefresh.refresh`, keeping the name
  convergence, the window/retention backfill, and the absence verdict exactly as they are.
- `MembershipRefresh`'s name refresh is **kept**, re-documented as convergence on the served name rather
  than as the scan-path nameless fill. It differs in kind from the deletions above: it converges toward
  the backend's truth and stays correct under any future rename, rather than branching on an impossible
  local state.
- `AlbumCoordinator.ensureAlbum` drops its `name.isEmpty()` clause; the granted/opt-in gate remains.
- `HttpEventDirectory` maps a **blank** name to `EventDetails.Failed`, not only a missing one. This is
  promoted from a nicety to the **sole** guard: removing the constructor default requires the *key*, not
  a non-blank *value*, so `{"name":""}` still decodes, and the album guard that used to catch the
  consequence is gone.
- `SnapSyncRoot`'s config log line stops printing `named=<bool>` (now a constant) and prints the name.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-link`: the config source/store seam requirement. `name` becomes required with no decode
  default, and a payload lacking it reads as **unreadable** — the same category as `minPhotoDate`,
  not the lenient-default category it currently shares with `direction`/`saveToAlbum`. The scenario
  asserting a nameless config decodes to a non-null empty string is removed. The same requirement's
  restatement of the `EventConfig` shape is corrected while it is being edited: it currently declares
  `minPhotoDate` nullable ("absent = whole-library scope") and omits `startsAt`, `endsAt`,
  `maxPhotoDate`, and `deletesAt` entirely, contradicting `photo-selection-policy` and the shipped type.
- `join-event`: the details-fetch coordination clause narrows to `Foreground` alone, and the scenario
  pinning that both triggers reach the same consequence is removed as vacuous with one caller. The
  requirement that the rule performs the teardown itself is unchanged — it rests independently on the
  flow transcriber's closed grammar. A blank `name` on a 200 joins a missing one as a retryable failure.
- `event-album`: `ensureAlbum`'s contract drops the nameless no-op; it is a no-op for an ungranted or
  opted-out membership only.

## Impact

**Code**

- `:domain` `model/EventConfig.kt` — the default, and the KDoc paragraph justifying it.
- `:domain` `feature/membership/MembershipRefresh.kt` — `fetchNeed`, `TitleNeed`, and the name-refresh
  comment.
- `:domain` `flow/Provision.kt` — the step-6 branch and two constructor parameters. Regenerates
  `architecture/flows/Provision.md` (the `alt` block goes) and `architecture/features.md` (`TitleNeed`).
- `:domain` `compose/SnapSyncApp.kt` — the two arguments dropped from the `Provision(...)` construction.
  `membershipRefresh` and `fetchEventDetails` stay; `Foreground` still uses them.
- `:domain` `feature/album/AlbumCoordinator.kt` — the guard clause and its doc.
- `:adapter:generic:app` `HttpEventDirectory.kt` — blank-name rejection.
- `:app:ios` `SnapSyncRoot.kt` — one log line.

**Tests**

- `EventConfigTest` — the legacy-decode test inverts; a new pin asserts `{"name":""}` still decodes, so
  the next reader is pointed at the boundary rather than inferring the invariant from the type.
- `MembershipRefreshTest` — the `fetchNeed` test is deleted; two `name = ""` fixtures become a stale
  real name so the name-refresh assertion keeps its meaning.
- `AlbumCoordinatorTest` — the empty-name no-op test is deleted.
- `HttpEventDirectoryTest` — a blank-name case joins the missing-name one.

**Not affected**

- The backend. No route, validator, or marker shape changes. `validateEventName` already rejects blank
  and whitespace-only names and is already pinned by `api/test/`.
- The upload, download, ledger, and attestation paths: none reads `name`.
