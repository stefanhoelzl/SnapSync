## Context

A TestFlight user (0.2 / 542, iPhone XS, iOS 18.7.9 — the app-driven `URLSession` tier) reported that
received photos carry a `primary` suffix. Their diagnostic dump (Bugsink `SNAPSYNC-6`,
`downloads_imported=9`) shows every downloaded photo landing in the library as
`03C741F2-4FFA-4792-B2E3-076266091091_L0_001-primary.heic` — the resource's **storage object key**
(`"<assetId>-<role>.<ext>"`, `sync-ledger`) presented as the photo's name. Their own photos are
unaffected; the symptom is confined to what the device downloaded.

The name was never chosen. `PHAssetCreationRequest.addResource(with:fileURL:options:)` derives the
resource's `originalFilename` from the **file URL's last path component** when `options` is `nil`, and the
staged file is named by its object key — staging is keyed that way so a relaunched process can re-derive
the destination from the transfer description alone (`QueuedPhotoDownloadJobs.stagingPath`). The importer
passed `nil`, so the staging path silently became the user-visible name.

The human filename was already available at that exact point. `2026-06-30-add-event-union-read` renamed
the manifest fields `filename→key, originalFilename→filename` with the goal *"one vocabulary across
manifest + union (`key` = fetch handle, `filename` = human name)"* — provisioned so a download client
could name what it imports. The client, shipped later, carries the name through the union into
`PlannedResource`, persists it in the download store, and hands it to the importer as
`StagedResource.originalFilename`, which then goes unread. The user's "still" is accurate: the data was
put in place for this and the last hop was never made.

Two things kept it from surfacing. `photo-download` pins the imported asset's **capture date** but says
nothing about its name, so no test could fail. And the world's `FakePhotoLibraryImporter` applied
`staged.originalFilename` to the asset it created — so `:test:integration` saw human names while devices
saw object keys. **The fake was more correct than production**, which is the specific way a harness stops
being evidence.

## Goals / Non-Goals

**Goals**

- A downloaded photo is named what the capturing device called it.
- The naming rule, including its fallback, is decided in one tested place rather than inside a PhotoKit
  adapter no Linux CI can reach.
- The harness cannot again model a behaviour production does not have.

**Non-Goals**

- Renaming the stored object. The key shape is out of scope and stays exactly as it is (see Decisions).
- Repairing already-imported photos. Forward-only (see Decisions).
- Resolving filename collisions on device.
- The other two problems in the same report (blank UI / QR, duplicated photos) — separate workspaces.

## Decisions

### The rule lives in `:domain` `model/`, not in the adapter

`importFilename(originalFilename, resourceKey)` is a pure function in `model/`, covered by `commonTest`
on JVM **and** `iosSimulatorArm64`. This follows `uploadKey`'s precedent verbatim — *"kept platform-free
so the layout is unit-tested on the simulator instead of trapped inside the PhotoKit adapter"*. The
adapter is left with the PhotoKit call and nothing to decide.

Alternative rejected: inline `?: resourceKey` at the call site. It is the same one line, but it puts the
fallback where only a physical device can exercise it — and the fallback is the branch that fires on
exactly the rows nobody tests (unenriched manifests).

### 🚫 The storage object key does NOT change — the tempting fix is silently destructive

The obvious reading of "remove the primary suffix" is to stop putting `-primary` in the object name.
That must not happen, for two independent reasons:

1. **The token is load-bearing.** It is what distinguishes a Live Photo's still from its paired video
   under one `assetId`, and `roleFromUploadKey` / `assetIdFromUploadKey` parse it back at the ledger
   record path and in the re-join reconciler.
2. **It would re-upload every installed device's library, with no error anywhere.** The ledger key *is*
   the bare filename (`sync-ledger`, "Event-independent key"), so a new key shape invalidates every
   `COMPLETED` row on every device; `event-rejoin-reconciliation` seeds from that same bare filename and
   so would not rescue it either. The failure is invisible by construction — no failed request, no log
   line, just every member re-uploading everything.

The fix therefore touches **only** how the receiving device names what it created. Nothing about storage,
keys, or the manifest moves.

### This change re-uploads nothing

Stated explicitly because it is the question the ledger's shape forces. The imported asset's filename is
display metadata on the receiving device and nothing reads it: upload keys are
`uploadKey(localIdentifier, role, originalFilename)`, where the filename contributes only its lowercased
**extension** (`IMG_4471.HEIC` and `…-primary.heic` both yield `heic`) and the identity half comes from
the new asset's own `localIdentifier`. Echo-suppression keys on `createdLocalId`. Both are byte-identical
before and after, on both tiers.

### An absent name falls back to the object key

`filename` is `""` when the uploader's manifest row was never enriched — a row predating the 5.sqm
migration, or one the re-join reconcile seeded from a filename listing, which carries no capture detail
(`sync-ledger`). There is no human name to use in that case, and the object key is the honest answer: it
is what the bytes are actually called, and it is what the device displayed before this change. The one
outcome ruled out is an **empty** name — an unnamed `PHAssetResource` is worse than an ugly one.

This is deliberately the same precedence the web download zip already applies at its own edge
(`r.filename || r.key`, `web-event-download`), so the two consumers of the same manifest field do not
disagree about what an absent name means.

### Forward-only, and not worth engineering around

Photos already imported keep their names: a created `PHAssetResource` cannot be renamed, and their
download rows are terminal `IMPORTED`, so nothing re-imports them. Repairing them would mean deleting and
re-creating assets in the user's library — destructive, requires a system confirmation per delete, and
risks the duplicates that are already the subject of a separate report. The reporter's nine photos stay
as they are.

### Collisions are not resolved on device

Two members both offering `IMG_0001.HEIC` is ordinary, and the photo library keys assets by
`localIdentifier`, not by name — duplicate names there are as harmless as in any camera roll. Only the
zip needs distinct names, and it de-duplicates at its own edge.

### The fake applies the same function

`FakePhotoLibraryImporter` now calls `importFilename` rather than using the published name directly.
Without this the world would keep modelling a behaviour the device does not have — the precise divergence
that hid this bug — so `harness-world-model` gains the requirement alongside the code change.

## Risks / Verification

The whole fix rests on one PhotoKit call that cannot run on Linux. Two checks were made rather than
assumed:

- `compileIosMainKotlinMetadata` was proven **non-vacuous** — renaming the property to
  `originalFilenameBOGUS` fails the compile with `Unresolved reference`. So
  `PHAssetResourceCreationOptions.originalFilename` genuinely resolves against the SDK cinterop (this is
  the repo's first use of that class).
- The new integration test was proven to **bite** — reverting the fake to the pre-fix behaviour fails it.

What remains unverified without a device: that a non-nil `options.originalFilename` actually wins inside
`performChanges`. That rests on PhotoKit's documented behaviour, and is what tasks 5.1/5.2 exist to
observe on an ssh-mac dev build.
