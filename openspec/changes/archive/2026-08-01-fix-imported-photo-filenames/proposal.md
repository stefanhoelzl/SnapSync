## Why

A TestFlight user on 0.2 (542) reported, among three problems, that *"photo names still has »primary«
suffix"*. Their diagnostic dump (Bugsink `SNAPSYNC-6`, iPhone11,2 / iOS 18.7.9, `downloads_imported=9`)
shows every photo they received from the event landing in their library named:

```
03C741F2-4FFA-4792-B2E3-076266091091_L0_001-primary.heic
```

That is the resource's **storage object key** — the internal `"<assetId>-<role>.<ext>"` layout
(`sync-ledger`) — shown to the user as the photo's name, role token and all. Their own photos are
unaffected; the symptom is confined to photos this device downloaded.

Nobody chose that name. `PHAssetCreationRequest.addResource(with:fileURL:options:)` derives the
resource's `originalFilename` from the **file URL's last path component** when `options` is `nil`, and
the file handed to it is staged under its object key (staging is keyed by that name so a relaunched
process can re-derive the destination from the transfer description alone). The importer passed `nil`.

"Still" is fair. The human filename has been on the wire since
`changes/archive/2026-06-30-add-event-union-read`, whose stated goal was *"one vocabulary across
manifest + union (`key` = fetch handle, `filename` = human name)"* — provisioned precisely so a download
client could name what it imports. The download client, shipped later, carries that name through the
union into `PlannedResource`, persists it in the download store, and delivers it to the importer as
`StagedResource.originalFilename` — where it is dropped on the floor. The last hop was never made.

Two things kept it invisible: `photo-download` pins the imported asset's **capture date** ("so photos
sort by when they were taken") but says nothing about its name, so no test could fail; and the world
harness's `FakePhotoLibraryImporter` already applies `staged.originalFilename` to the asset it creates,
so `:test:integration` saw human names while devices saw storage keys. The fake was more correct than
production.

## What Changes

- The iOS importer names each resource **explicitly**, from the capturing device's own filename, instead
  of letting PhotoKit infer one from the staged file's path.
- The naming rule — including its fallback — becomes a pure, `commonTest`-covered function in `:domain`
  `model/` (`importFilename`), following `uploadKey`'s precedent of keeping a name layout out of the
  PhotoKit adapter so it is tested on the simulator rather than only on a device.
- When the uploader's manifest row was never enriched, `filename` is `""` (a row predating the 5.sqm
  migration, or one the re-join reconcile seeded from a filename listing — `sync-ledger`). That case
  **keeps today's behaviour**, the object key: it is what the bytes are actually called, and an unnamed
  resource would be worse than an ugly one.
- The world's fake importer applies the **same** function, so the harness can no longer show a human
  name where a device would show a key.
- `photo-download` gains the requirement that was missing, beside the capture-date one.

**Forward-only, and deliberately so.** Photos already imported keep their current names: a created
`PHAssetResource` cannot be renamed, and their download rows are terminal `IMPORTED`, so nothing
re-imports them. The reporter's nine photos stay as they are.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-download`: the full-fidelity import gains a naming requirement — each created resource carries
  the capturing device's filename, falling back to the object key when the manifest carried none.
- `harness-world-model`: the fake importer SHALL name imported resources through the same shared rule the
  device applies, closing the divergence that hid this bug — a fake *more* correct than production.

## Impact

**Code**

- `:domain` — new `model/ImportNaming.kt` (`importFilename`), pure, no new imports.
- `:adapter:ios:app-only` — `IosPhotoLibraryImporter` creates a `PHAssetResourceCreationOptions` per
  resource (the first use of that class in the repo) and threads the name through the existing
  per-resource loop, so a Live Photo's still and paired video each keep their own name.
- `:test:world` — `FakePhotoLibraryImporter` applies the same rule.

**Tests**

New `ImportNamingTest` in `:domain` `commonTest` (JVM + `iosSimulatorArm64`). New
`FullStackIntegrationTest` case asserting an imported foreign photo carries the capturing device's
filename through the real composed core.

**Explicitly not changed — the storage key stays exactly as it is**

The `-primary` token is **load-bearing**, not decoration: it distinguishes a Live Photo's still from its
paired video under one `assetId`, and `roleFromUploadKey` / `assetIdFromUploadKey` parse it back at the
ledger record path and in the re-join reconciler. Renaming the stored object is also the one change here
that would be **silently destructive**: the ledger key *is* the bare filename (`sync-ledger`), so a new
key shape would invalidate every `COMPLETED` row on every installed device, and
`event-rejoin-reconciliation` seeds from that same bare filename and so would not rescue it — every
member would re-upload their whole in-window library, with no error and no log line.

**No ledger consequence from this change**

The imported asset's filename is display metadata on the receiving device; nothing reads it. Upload keys
are `uploadKey(localIdentifier, role, originalFilename)`, where the filename contributes only its
lowercased **extension** and the identity half comes from the new asset's `localIdentifier` — identical
before and after. Echo-suppression keys on `createdLocalId`, untouched. **Nothing re-uploads, on either
tier.**

**Also not changed**

The web download zip (`web-event-download`) already applies the same precedence — `r.filename || r.key`
— at its own edge, and de-duplicates names there. Collisions are not resolved on device: two members
both offering `IMG_0001.HEIC` is ordinary, and the photo library keys assets by `localIdentifier`, not by
name.

**Verification**

One ssh-mac dev build joined to an event with a foreign contributor: download a photo and read its name
back in Photos. The `commonTest` + integration coverage pins the rule; only the PhotoKit call itself —
that a non-nil `options.originalFilename` actually wins — needs a device to observe.
